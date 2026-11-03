package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientStatusHistoryMapper;
import com.crdp.followup.patient.infra.mapper.StatusHistoryInsert;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 随访状态时间线用例（E13-T03 / tech-plan §7「每次状态变化一行」；接口 docs/api/patient.md §3 GET status-history）。
 *
 * <p>表只追加（永不 UPDATE / DELETE）。当前唯一写入口是登记初始行：登记即「随访中」（OQ-71②， {@code pt_patient.followup_status} 默认
 * ACTIVE），初始行 status=ACTIVE、source=REGISTRATION；暂停 / 移除 / 恢复 / 停止 / 失访 / 死亡的流转（含 pt_patient
 * 同步与任务联动，A-FU-008）随 E14-T06 经 {@link #record} 接入。 统计「窗口开始日是否仍符合条件」按本表回看（tech-plan §7）。
 */
@Service
public class PatientStatusHistoryService {

    /** 状态口径：components.md §2 patient 域（登记只写 ACTIVE，其余值由 E14-T06 流转写入）。 */
    public static final Set<String> STATUSES =
            Set.of("ACTIVE", "PAUSED", "DEAD", "TERMINATED", "LOST", "FINISHED");

    private final PatientContactGateway patientScope;
    private final PatientStatusGateway gateway;
    private final PatientStatusHistoryMapper mapper;

    public PatientStatusHistoryService(
            PatientContactGateway patientScope,
            PatientStatusGateway gateway,
            PatientStatusHistoryMapper mapper) {
        this.patientScope = patientScope;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- DTO

    /** 状态时间线条目（GET status-history）。 */
    public record StatusItemView(
            String id,
            String status,
            LocalDate effectiveDate,
            String reason,
            String source,
            String createdBy,
            java.time.LocalDateTime createdAt) {}

    // ---------------------------------------------------------------- 写入（只追加）

    /**
     * 登记初始行（E13-T01 登记事务内调用）：随访中自生效日期起。不单独写审计——登记动作本身有 PATIENT_CREATE 审计（OQ-71②口径：状态流转审计随 E14-T06）。
     */
    public void recordRegistration(long patientId, LocalDate effectiveDate, Long createdBy) {
        insert(patientId, "ACTIVE", effectiveDate, null, "REGISTRATION", createdBy);
    }

    /** 追加一行状态记录（E14-T06 流转入口预留：状态值校验 + 只追加写入；pt_patient 同步、原因必填与任务联动 由调用方编排）。 */
    public void record(
            long patientId,
            String status,
            LocalDate effectiveDate,
            String reason,
            String source,
            Long createdBy) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) {
            throw new ApiException(400, 40001, "随访状态取值不正确");
        }
        insert(patientId, normalized, effectiveDate, reason, source, createdBy);
    }

    // ---------------------------------------------------------------- 查询

    /** 状态时间线（GET status-history）：越范围 / 不存在的患者 40400（不暴露对象存在）。 */
    public List<PatientStatusHistoryMapper.StatusRow> historyOf(long patientId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        return gateway.historyOf(patientId);
    }

    // ---------------------------------------------------------------- 内部

    private void insert(
            long patientId,
            String status,
            LocalDate effectiveDate,
            String reason,
            String source,
            Long createdBy) {
        if (effectiveDate == null) {
            throw new ApiException(400, 40001, "请填写生效日期");
        }
        StatusHistoryInsert row = new StatusHistoryInsert();
        row.setPatientId(patientId);
        row.setStatus(status);
        row.setEffectiveDate(effectiveDate);
        row.setReason(reason);
        row.setSource(source);
        row.setCreatedBy(createdBy);
        mapper.insertHistory(row);
    }
}
