package com.crdp.followup.patient.app;

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
 * 患者转病区用例（E13-T08 / A-WARD-005、C-WARD-006；接口 docs/api/patient.md §4，入口 = 患者详情 / 列表
 * 行操作「转病区」，pages.md §3 / §6）。验收原文：转病区后原病区医生不可见。
 *
 * <p>隔离口径（A-WARD-005「原病区医生立即失去访问权」「任务与附件随患者迁移」）：只改 {@code pt_patient.ward_id}
 * 一行——数据范围改写以患者行为锚点（病区分支直接落 pt_patient，子表经 patient_id 的 EXISTS
 * 关联），患者转入新病区后，范围快照仍只有原病区的医生在查询层立即不可见该患者及其任务、附件； 无需同步任何子表，也无需刷新任何会话。
 *
 * <p>本任务只落 wardId（病区 / 中心归属）。patient.md §4 的 deptId（C 转科室）随 C 域任务（E21+）接入， 科室历史无表定义，不在此预建。
 */
@Service
public class PatientTransferService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatientTransferGateway gateway;
    private final PatientContactGateway patientScope;
    private final PatientMapper patientMapper;
    private final PatientWardHistoryMapper historyMapper;
    private final WardMapper wardMapper;
    private final AuditLogger audit;

    public PatientTransferService(
            PatientTransferGateway gateway,
            PatientContactGateway patientScope,
            PatientMapper patientMapper,
            PatientWardHistoryMapper historyMapper,
            WardMapper wardMapper,
            AuditLogger audit) {
        this.gateway = gateway;
        this.patientScope = patientScope;
        this.patientMapper = patientMapper;
        this.historyMapper = historyMapper;
        this.wardMapper = wardMapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 转病区请求（A-WARD-005：目标病区 + 原因必填）。 */
    public record TransferRequest(Long wardId, String reason) {}

    /** 转病区结果（患者行摘要；wardName = 新病区名称）。 */
    public record TransferredView(String patientId, String wardId, String wardName) {}

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

    /** 审计摘要（病区为字典值，非患者敏感字段）。 */
    private record TransferAudit(String fromWard, String toWard, String reason) {}

    // ---------------------------------------------------------------- 转病区（A-WARD-005）

    /**
     * 转病区：范围内校验（越范围 40400 不暴露存在）→ 目标病区启用校验 → 同病区拦截 → 更新归属 + 追加历史 + PATIENT_TRANSFER
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
                            .description("转病区被拒绝：患者不在操作者数据范围内")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        if (request == null || request.wardId() == null || request.wardId() <= 0) {
            throw new ApiException(400, 40001, "请选择目标病区");
        }
        String reason = requireReason(request.reason());
        long targetWardId = request.wardId();

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

        String fromWardName = wardNameOf(ref.wardId());
        long operatorId = CurrentSession.requireHospital().userId();
        patientMapper.updateWard(patientId, targetWardId, operatorId);

        WardHistoryInsert row = new WardHistoryInsert();
        row.setPatientId(patientId);
        row.setFromWardId(ref.wardId());
        row.setToWardId(targetWardId);
        row.setReason(reason);
        row.setTransferredBy(operatorId);
        historyMapper.insertHistory(row);

        audit.append(
                AuditEntry.operation("followup", "PATIENT_TRANSFER")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description("转病区：" + fromWardName + " → " + target.name())
                        .beforeJson(writeJson(new TransferAudit(fromWardName, null, null)))
                        .afterJson(
                                writeJson(new TransferAudit(fromWardName, target.name(), reason)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new TransferredView(
                String.valueOf(patientId), String.valueOf(targetWardId), target.name());
    }

    // ---------------------------------------------------------------- 历史（A-WARD-005「保留转病区历史」）

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

    // ---------------------------------------------------------------- 内部

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "请填写转病区原因");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, "转病区原因最多 255 个字符");
        }
        return trimmed;
    }

    private String wardNameOf(Long wardId) {
        if (wardId == null) {
            return "（未知）";
        }
        WardMapper.WardRow row = wardMapper.findById(wardId);
        return row == null ? "（已删除）" : row.name();
    }

    private Map<Long, String> wardNameIndex() {
        Map<Long, String> names = new HashMap<>();
        for (WardMapper.WardRow row : wardMapper.findAll()) {
            names.put(row.id(), row.name());
        }
        return names;
    }

    private static String nameOf(Map<Long, String> names, Long wardId) {
        return wardId == null ? null : names.getOrDefault(wardId, "（已删除）");
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }
}
