package com.crdp.followup.patient.app;

import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.task.app.FollowupTaskTerminator;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者改为失访（S5（M3 审查）/ A-FU-012、A-PAT-011；docs/api/patient.md §1 {@code POST /patients/{id}/status} 的
 * LOST 动作）。
 *
 * <p>口径（A 规格 §390 行附近「系统提示后由医生改为失访」，不自动改）：失访候选任务由医生确认（POST /tasks/{id}/confirm-lost，E15-T05） 连续 2
 * 个窗口失访时前端提示，医生到患者详情发起本入口。模式同 {@link com.crdp.followup.plan.app.FollowupTerminationService
 * 死亡终止}，差异在：
 *
 * <ul>
 *   <li>原因必填（医生填写，落 pt_status_history 与审计；死亡来源是系统事件文案）；
 *   <li>只接受随访中 / 已暂停（失访是随访链路的中断，不是死亡 / 已终止 / 已结束后的改判；重复失访 40901）；
 *   <li>终止原因「失访」：该患者全部入组的未开始 / 未完成任务（待随访 / 进行中 / 逾期 / 失访候选 / 暂停或停用期间未执行）置
 *       TERMINATED，已完成保留（历史事实）；之后不再创建任务（调度过滤 followup_status 不在建名单，含 LOST）。
 * </ul>
 *
 * <p>入组行状态保持不变（失访不是「移出」，恢复口径随后续需求）；数据范围前置校验在本服务（经 {@link PatientContactGateway} 越范围 40400，不暴露存在）。
 */
@Service
public class PatientLostService {

    private static final Logger log = LoggerFactory.getLogger(PatientLostService.class);

    /** 任务终止原因（A 规格任务关键字段字典：死亡 / 拒绝随访 / 外地随访 / 其他；失访场景统一「失访」）。 */
    static final String TERMINATE_REASON_LOST = "失访";

    /** 患者级随访状态（components.md §2 patient 域）。 */
    static final String PATIENT_LOST = "LOST";

    static final String PATIENT_ACTIVE = "ACTIVE";

    static final String PATIENT_PAUSED = "PAUSED";

    private final PatientContactGateway patientScope;
    private final PlanEnrollmentMapper enrollmentMapper;
    private final PatientFollowupStatusService patientStatus;
    private final FollowupTaskTerminator terminator;
    private final AuditLogger audit;

    public PatientLostService(
            PatientContactGateway patientScope,
            PlanEnrollmentMapper enrollmentMapper,
            PatientFollowupStatusService patientStatus,
            FollowupTaskTerminator terminator,
            AuditLogger audit) {
        this.patientScope = patientScope;
        this.enrollmentMapper = enrollmentMapper;
        this.patientStatus = patientStatus;
        this.terminator = terminator;
        this.audit = audit;
    }

    /** 改为失访结果（terminatedTasks = 本次置 TERMINATED 的未开始 / 未完成任务数）。 */
    public record LostTransitionView(String patientId, String status, int terminatedTasks) {}

    /**
     * 患者改为失访：状态历史 + 审计 + 未开始任务终止 + 之后不再创建任务（A-FU-012 / A-PAT-011）。
     *
     * @param reason 失访原因（必填，≤ 255 字，医生填写）
     * @param operatorId 当前登录医生（状态历史、终止三列与审计的操作人）
     */
    @Transactional
    public LostTransitionView markLost(long patientId, String reason, Long operatorId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或无权访问");
        }
        String normalizedReason = requireReason(reason);
        PlanEnrollmentMapper.PatientStateRow patient = enrollmentMapper.findPatientState(patientId);
        if (patient == null) {
            throw new ApiException(404, 40400, "患者不存在或无权访问");
        }
        String current = patient.followupStatus();
        if (!PATIENT_ACTIVE.equals(current) && !PATIENT_PAUSED.equals(current)) {
            audit.append(
                    AuditEntry.operation("followup", "PATIENT_LOST_MARK")
                            .objectType("PATIENT")
                            .objectId(String.valueOf(patientId))
                            .description("患者改为失访被阻止：当前状态 " + current)
                            .result(AuditResult.DENIED)
                            .errorCode(40901)
                            .reason("PATIENT_" + current)
                            .build());
            throw new ApiException(
                    409,
                    40901,
                    PATIENT_LOST.equals(current)
                            ? "患者已是失访状态，无需重复操作"
                            : "患者已" + statusText(current) + "，不能再改为失访");
        }
        // 按当前值守卫更新（并发死亡 / 移出抢先流转时不覆盖）；0 行 = 状态已变化，不写历史
        if (!patientStatus.transition(
                patientId, PATIENT_LOST, normalizedReason, "MANUAL", current, operatorId)) {
            throw new ApiException(409, 40900, "患者状态已变化，请刷新后重试");
        }
        int terminated =
                terminator.terminateUnfinishedByPatient(
                        patientId, TERMINATE_REASON_LOST, operatorId);
        audit.append(
                AuditEntry.operation("followup", "PATIENT_LOST_MARK")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "患者改为失访（A-FU-012）：原因 "
                                        + normalizedReason
                                        + "，终止未开始 / 未完成任务 "
                                        + terminated
                                        + " 条，之后不再创建任务")
                        .beforeJson("{\"status\":\"" + current + "\"}")
                        .afterJson(
                                "{\"patientId\":"
                                        + patientId
                                        + ",\"status\":\"LOST\",\"reason\":\""
                                        + normalizedReason
                                        + "\",\"terminatedTasks\":"
                                        + terminated
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "患者改为失访 patient={} operator={} terminatedTasks={}",
                patientId,
                operatorId,
                terminated);
        return new LostTransitionView(String.valueOf(patientId), PATIENT_LOST, terminated);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "请填写失访原因（A-PAT-011）");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, "失访原因最多 255 个字符");
        }
        return trimmed;
    }

    private static String statusText(String status) {
        return switch (status) {
            case "DEAD" -> "死亡";
            case "TERMINATED" -> "终止随访";
            case "FINISHED" -> "结束随访";
            default -> status;
        };
    }
}
