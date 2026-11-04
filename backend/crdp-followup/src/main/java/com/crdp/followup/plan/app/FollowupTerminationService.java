package com.crdp.followup.plan.app;

import com.crdp.followup.patient.app.PatientFollowupStatusService;
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
 * 死亡自动终止（E14-T06 / A-FU-007、C-FU-021；tasks.csv 验收「告知已故后未开始任务全部终止」）。
 *
 * <p>触发口径（tech-plan §9「告知已故 | 进入死亡登记」、A 规格第 5 章）：沟通结果「告知已故」或随访记录提交「是否死亡=是」后进入死亡登记—— 本服务提供领域入口
 * {@link #terminateOnDeath}，HTTP 触发随 E15-T04（沟通记录，A-FU-006）与 E15 提交流程接入； 出院状态 = 死亡的登记不自动入组（E14-T05
 * Q35），不经过本入口。患者存在性按唯一性口径全院查（@IgnoreScope，与入组前置同思路）； 操作者对该患者的数据范围校验由调用方的 HTTP 入口承担（沟通记录 /
 * 随访提交接口各自按任务与患者范围校验，E15）。
 *
 * <p>一次调用的原子效果（同一事务）：
 *
 * <ul>
 *   <li>患者状态改为已死亡（pt_patient.followup_status = DEAD，无其他在组入组判定不适用——死亡是患者级终态）， 状态时间线只追加一行（source =
 *       SYSTEM）；已死亡时幂等返回（不重复写时间线与审计）；
 *   <li>该患者全部入组的未完成任务（待随访 / 进行中 / 逾期 / 暂停或停用期间未执行）置 TERMINATED，终止原因「死亡」； 已完成与已失访保留（历史事实）；
 *   <li>之后不再创建任务：每日调度按患者随访状态过滤（DEAD / TERMINATED 不建，E14-T04 起已生效），入组行状态保持不变（死亡不是「移出」）。
 * </ul>
 */
@Service
public class FollowupTerminationService {

    private static final Logger log = LoggerFactory.getLogger(FollowupTerminationService.class);

    /** 任务终止原因（A 规格任务关键字段字典：死亡 / 拒绝随访 / 外地随访 / 其他）。 */
    static final String TERMINATE_REASON_DEATH = "死亡";

    /** 患者级随访状态（components.md §2 patient 域）。 */
    static final String PATIENT_DEAD = "DEAD";

    private final PlanEnrollmentMapper enrollmentMapper;
    private final PatientFollowupStatusService patientStatus;
    private final FollowupTaskTerminator terminator;
    private final AuditLogger audit;

    public FollowupTerminationService(
            PlanEnrollmentMapper enrollmentMapper,
            PatientFollowupStatusService patientStatus,
            FollowupTaskTerminator terminator,
            AuditLogger audit) {
        this.enrollmentMapper = enrollmentMapper;
        this.patientStatus = patientStatus;
        this.terminator = terminator;
        this.audit = audit;
    }

    /** 死亡终止结果（terminatedTasks = 本次置 TERMINATED 的未完成任务数）。 */
    public record DeathTerminationView(String patientId, String status, int terminatedTasks) {}

    /**
     * 死亡自动终止（A-FU-007）：患者 → 已死亡、未开始 / 未完成任务全部终止、之后不再创建任务。
     *
     * @param reason 死亡来源说明（如「告知已故」——沟通记录 A-FU-006；「随访记录提交是否死亡=是」），落状态时间线；空时默认「死亡」
     * @param operatorId 触发操作人（沟通 / 记录提交人），审计与终止三列记录
     */
    @Transactional
    public DeathTerminationView terminateOnDeath(long patientId, String reason, Long operatorId) {
        PlanEnrollmentMapper.PatientStateRow patient = enrollmentMapper.findPatientState(patientId);
        if (patient == null) {
            throw new ApiException(404, 40400, "患者不存在或已删除");
        }
        String normalizedReason = normalize(reason);
        // 幂等：已死亡不重复写时间线与审计；仍兜底跑一次终止（正常 0 条，防御死亡后残留）
        if (patient != null && PATIENT_DEAD.equals(patient.followupStatus())) {
            int defensive =
                    terminator.terminateUnfinishedByPatient(
                            patientId, TERMINATE_REASON_DEATH, operatorId);
            log.debug("患者已死亡，死亡终止幂等返回 patient={} 兜底终止={}", patientId, defensive);
            return new DeathTerminationView(String.valueOf(patientId), PATIENT_DEAD, defensive);
        }
        patientStatus.transition(
                patientId, PATIENT_DEAD, normalizedReason, "SYSTEM", null, operatorId);
        int terminated =
                terminator.terminateUnfinishedByPatient(
                        patientId, TERMINATE_REASON_DEATH, operatorId);
        audit.append(
                AuditEntry.operation("followup", "PATIENT_DEATH_TERMINATE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "死亡自动终止（"
                                        + normalizedReason
                                        + "）：患者状态改为已死亡，终止未开始 / 未完成任务 "
                                        + terminated
                                        + " 条，之后不再创建任务（A-FU-007）")
                        .afterJson(
                                "{\"patientId\":"
                                        + patientId
                                        + ",\"status\":\"DEAD\",\"reason\":\""
                                        + normalizedReason
                                        + "\",\"terminatedTasks\":"
                                        + terminated
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new DeathTerminationView(String.valueOf(patientId), PATIENT_DEAD, terminated);
    }

    private static String normalize(String reason) {
        if (reason == null || reason.isBlank()) {
            return TERMINATE_REASON_DEATH;
        }
        String trimmed = reason.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }
}
