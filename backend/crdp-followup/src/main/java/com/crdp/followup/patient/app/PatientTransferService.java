package com.crdp.followup.patient.app;

import com.crdp.followup.implant.app.ImplantService;
import com.crdp.followup.patient.infra.mapper.DeptHistoryInsert;
import com.crdp.followup.patient.infra.mapper.PatientDeptHistoryMapper;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.patient.infra.mapper.PatientWardHistoryMapper;
import com.crdp.followup.patient.infra.mapper.WardHistoryInsert;
import com.crdp.followup.ward.infra.mapper.WardMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者转病区 / 转科室用例（E13-T08 / A-WARD-005；E21-T21 / C-WARD-006 科室 + 病区双维度；接口 docs/api/patient.md §4，入口 =
 * 患者详情 / 列表行操作「转病区」，pages.md §3 / §6）。验收原文：转病区后原病区医生不可见；科室与病区条件 OR 生效。
 *
 * <p>隔离口径（A-WARD-005、C-WARD-006「任务与扫描件随患者迁移」）：只改 {@code pt_patient.ward_id} / {@code
 * pt_patient.dept_id} 一行——数据范围改写以患者行为锚点（病区 / 科室分支 OR 落 pt_patient，子表经 patient_id 的 EXISTS
 * 关联，E07-T02），患者转入新病区 / 科室后，范围快照仍只有原病区 / 原科室的用户在查询层立即不可见该患者及其任务、扫描件；
 * 无需同步任何子表，也无需刷新任何会话。转科室只改病区归属不影响科室可见性（反之亦然）——双维度 OR 各自独立生效。
 *
 * <p>E21-T21 / C-WARD-006：同一入口（POST transfer）接受 {@code wardId}、{@code deptId} 之一或同时携带（同时转科室 +
 * 病区为一次管理动作：一条 UPDATE、两条历史、一条审计）。转科室为医院 C 口径（implant 开关信号，OQ-71①）：开关关闭的 A / B 租户携带 deptId →
 * 40001（与登记页 visitNo 同口径）；科室历史 {@code pt_dept_history}（followup V97）与转病区历史同构。
 */
@Service
public class PatientTransferService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatientTransferGateway gateway;
    private final PatientContactGateway patientScope;
    private final PatientMapper patientMapper;
    private final PatientWardHistoryMapper historyMapper;
    private final PatientDeptHistoryMapper deptHistoryMapper;
    private final WardMapper wardMapper;
    private final ImplantService implants;
    private final AuditLogger audit;

    public PatientTransferService(
            PatientTransferGateway gateway,
            PatientContactGateway patientScope,
            PatientMapper patientMapper,
            PatientWardHistoryMapper historyMapper,
            PatientDeptHistoryMapper deptHistoryMapper,
            WardMapper wardMapper,
            ImplantService implants,
            AuditLogger audit) {
        this.gateway = gateway;
        this.patientScope = patientScope;
        this.patientMapper = patientMapper;
        this.historyMapper = historyMapper;
        this.deptHistoryMapper = deptHistoryMapper;
        this.wardMapper = wardMapper;
        this.implants = implants;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 转病区 / 转科室请求（A-WARD-005：wardId + 原因；C-WARD-006：deptId 与 wardId 之一或同时，至少一项）。 */
    public record TransferRequest(Long wardId, Long deptId, String reason) {}

    /** 转病区 / 转科室结果（患者行摘要；ward / dept 为转后当前归属，未涉及的一侧原样回显）。 */
    public record TransferredView(
            String patientId, String wardId, String wardName, String deptId, String deptName) {}

    /** 转病区历史条目（GET ward-history）。 */
    public record WardHistoryItemView(
            String id,
            String fromWardName,
            String toWardName,
            String reason,
            String operatorName,
            LocalDateTime transferredAt) {}

    /** 历史响应。 */
    public record WardHistoryView(String patientId, List<WardHistoryItemView> items) {}

    /** 转科室历史条目（GET dept-history，与转病区历史同构）。 */
    public record DeptHistoryItemView(
            String id,
            String fromDeptName,
            String toDeptName,
            String reason,
            String operatorName,
            LocalDateTime transferredAt) {}

    /** 转科室历史响应。 */
    public record DeptHistoryView(String patientId, List<DeptHistoryItemView> items) {}

    /** 审计摘要（病区 / 科室为字典值，非患者敏感字段）。 */
    private record TransferAudit(
            String fromWard, String toWard, String fromDept, String toDept, String reason) {}

    // ---------------------------------------------------------------- 转病区 /
    // 转科室（A-WARD-005、C-WARD-006）

    /**
     * 转病区 / 转科室：范围内校验（越范围 40400 不暴露存在）→ 目标启用校验 → 同归属拦截 → 更新归属 + 追加历史 + PATIENT_TRANSFER
     * 审计（同一事务）。被拒绝的越范围请求写 DENIED 审计（AGENTS.md §5.4「被拒绝的操作」）。
     */
    @Transactional
    public TransferredView transfer(long patientId, TransferRequest request) {
        PatientMapper.WardRef ref = gateway.patientWardInScope(patientId);
        if (ref == null) {
            audit.append(
                    AuditEntry.operation("followup", "PATIENT_TRANSFER")
                            .objectType("PATIENT")
                            .objectId(String.valueOf(patientId))
                            .description("转病区 / 转科室被拒绝：患者不在操作者数据范围内")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        String reason = requireReason(request == null ? null : request.reason());
        Long targetWardId = normalizeTarget(request == null ? null : request.wardId());
        Long targetDeptId = normalizeTarget(request == null ? null : request.deptId());

        if (targetWardId == null && targetDeptId == null) {
            throw new ApiException(400, 40001, "请选择目标病区或科室");
        }
        if (targetDeptId != null && !implants.implantEnabled()) {
            // E21-T21 / C-WARD-006：转科室为医院 C 口径（OQ-71① implant 开关信号），A / B 租户不接收
            throw new ApiException(400, 40001, "转科室为医院 C 口径，当前未开通，不能填写");
        }

        String fromWardName = wardNameOf(ref.wardId());
        String fromDeptName = deptNameOf(ref.deptId());
        String toWardName = fromWardName;
        String toDeptName = fromDeptName;

        if (targetWardId != null) {
            WardMapper.WardRow target = wardMapper.findById(targetWardId);
            if (target == null) {
                throw new ApiException(400, 40001, "目标病区不存在");
            }
            if (!"ENABLED".equals(target.status())) {
                throw new ApiException(400, 40001, "目标病区已停用，不能转入");
            }
            if (ref.wardId() != null && ref.wardId() == targetWardId) {
                throw new ApiException(400, 40001, "患者已在该病区，无需转病区");
            }
            toWardName = target.name();
        }
        if (targetDeptId != null) {
            PatientMapper.DeptRow target = patientMapper.findDeptById(targetDeptId);
            if (target == null) {
                throw new ApiException(400, 40001, "目标科室不存在");
            }
            if (!"ENABLED".equals(target.status())) {
                throw new ApiException(400, 40001, "目标科室已停用，不能转入");
            }
            if (ref.deptId() != null && ref.deptId() == targetDeptId) {
                throw new ApiException(400, 40001, "患者已在该科室，无需转科室");
            }
            toDeptName = target.name();
        }

        long operatorId = CurrentSession.requireHospital().userId();
        patientMapper.updateWardAndDept(patientId, targetWardId, targetDeptId, operatorId);

        if (targetWardId != null) {
            WardHistoryInsert row = new WardHistoryInsert();
            row.setPatientId(patientId);
            row.setFromWardId(ref.wardId());
            row.setToWardId(targetWardId);
            row.setReason(reason);
            row.setTransferredBy(operatorId);
            historyMapper.insertHistory(row);
        }
        if (targetDeptId != null) {
            DeptHistoryInsert row = new DeptHistoryInsert();
            row.setPatientId(patientId);
            row.setFromDeptId(ref.deptId());
            row.setToDeptId(targetDeptId);
            row.setReason(reason);
            row.setTransferredBy(operatorId);
            deptHistoryMapper.insertHistory(row);
        }

        audit.append(
                AuditEntry.operation("followup", "PATIENT_TRANSFER")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                transferDescription(
                                        fromWardName, toWardName, fromDeptName, toDeptName))
                        .beforeJson(
                                writeJson(
                                        new TransferAudit(
                                                fromWardName, null, fromDeptName, null, null)))
                        .afterJson(
                                writeJson(
                                        new TransferAudit(
                                                fromWardName,
                                                toWardName,
                                                fromDeptName,
                                                toDeptName,
                                                reason)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new TransferredView(
                String.valueOf(patientId),
                ref.wardId() == null && targetWardId == null
                        ? null
                        : String.valueOf(targetWardId == null ? ref.wardId() : targetWardId),
                toWardName,
                ref.deptId() == null && targetDeptId == null
                        ? null
                        : String.valueOf(targetDeptId == null ? ref.deptId() : targetDeptId),
                toDeptName);
    }

    // ---------------------------------------------------------------- 历史（A-WARD-005 /
    // C-WARD-006「保留转科历史」）

    /** 转病区历史（新在前）：越范围 / 不存在的患者 40400（不暴露对象存在）。 */
    public WardHistoryView historyOf(long patientId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        Map<Long, String> wardNames = wardNameIndex();
        List<WardHistoryItemView> items =
                gateway.wardHistoryOf(patientId).stream()
                        .map(
                                row ->
                                        new WardHistoryItemView(
                                                String.valueOf(row.id()),
                                                nameOf(wardNames, row.fromWardId()),
                                                nameOf(wardNames, row.toWardId()),
                                                row.reason(),
                                                row.operatorName(),
                                                row.transferredAt()))
                        .toList();
        return new WardHistoryView(String.valueOf(patientId), items);
    }

    /** 转科室历史（新在前）：越范围 / 不存在的患者 40400（不暴露对象存在）。 */
    public DeptHistoryView deptHistoryOf(long patientId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        List<PatientDeptHistoryMapper.DeptHistoryRow> rows = gateway.deptHistoryOf(patientId);
        Map<Long, String> deptNames = deptNameIndex(rows);
        List<DeptHistoryItemView> items =
                rows.stream()
                        .map(
                                row ->
                                        new DeptHistoryItemView(
                                                String.valueOf(row.id()),
                                                nameOf(deptNames, row.fromDeptId()),
                                                nameOf(deptNames, row.toDeptId()),
                                                row.reason(),
                                                row.operatorName(),
                                                row.transferredAt()))
                        .toList();
        return new DeptHistoryView(String.valueOf(patientId), items);
    }

    // ---------------------------------------------------------------- 内部

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "请填写转病区 / 转科室原因");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, "转病区 / 转科室原因最多 255 个字符");
        }
        return trimmed;
    }

    /** 目标 id 归一：空白 / 非正数视为未提供（与「请选择目标病区」40001 口径一致）。 */
    private static Long normalizeTarget(Long id) {
        return id == null || id <= 0 ? null : id;
    }

    private static String transferDescription(
            String fromWard, String toWard, String fromDept, String toDept) {
        StringBuilder text = new StringBuilder("转");
        boolean wardChanged = fromWard != null || toWard != null;
        boolean deptChanged = fromDept != null || toDept != null;
        if (deptChanged) {
            text.append("科室")
                    .append(wardChanged ? "病区：" : "：")
                    .append(fromDept)
                    .append(" → ")
                    .append(toDept);
            if (wardChanged) {
                text.append("，");
            }
        }
        if (wardChanged) {
            if (!deptChanged) {
                text.append("病区：");
            }
            text.append(fromWard).append(" → ").append(toWard);
        }
        return text.toString();
    }

    private String wardNameOf(Long wardId) {
        if (wardId == null) {
            return null;
        }
        WardMapper.WardRow row = wardMapper.findById(wardId);
        return row == null ? "（已删除）" : row.name();
    }

    private String deptNameOf(Long deptId) {
        if (deptId == null) {
            return null;
        }
        PatientMapper.DeptRow row = patientMapper.findDeptById(deptId);
        return row == null ? "（已删除）" : row.name();
    }

    private Map<Long, String> wardNameIndex() {
        Map<Long, String> names = new HashMap<>();
        for (WardMapper.WardRow row : wardMapper.findAll()) {
            names.put(row.id(), row.name());
        }
        return names;
    }

    /**
     * 科室名称索引（转科室历史回显）：启用项来自 {@code findDepts} 候选；历史行引用但已停用 / 删除的科室按 id 逐个补齐 （停用仍回名称，删除显示「（已删除）」）。
     */
    private Map<Long, String> deptNameIndex(List<PatientDeptHistoryMapper.DeptHistoryRow> rows) {
        Map<Long, String> names = new HashMap<>();
        for (PatientMapper.OptionRow row : patientMapper.findDepts()) {
            names.put(row.id(), row.name());
        }
        for (PatientDeptHistoryMapper.DeptHistoryRow row : rows) {
            for (Long deptId : List.of(row.fromDeptId(), row.toDeptId())) {
                if (deptId != null && !names.containsKey(deptId)) {
                    PatientMapper.DeptRow dept = patientMapper.findDeptById(deptId);
                    names.put(deptId, dept == null ? "（已删除）" : dept.name());
                }
            }
        }
        return names;
    }

    private static String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.getOrDefault(id, "（已删除）");
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }
}
