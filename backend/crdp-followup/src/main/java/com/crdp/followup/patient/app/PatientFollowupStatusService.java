package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 患者级随访状态流转（E14-T06 / A-FU-007、A-FU-008、A-PAT-011）：pt_patient.followup_status 与 pt_status_history
 * 的同一事务双写。
 *
 * <p>状态口径（hospital-a-spec「关键字段 · 患者随访状态」）：随访中 ACTIVE / 已暂停 PAUSED / 已死亡 DEAD / 已终止 TERMINATED / 失访
 * LOST / 随访结束 FINISHED。E14-T06 落地三条流转：暂停（无其他在组入组时 → PAUSED）、恢复（无其他已暂停入组时 → ACTIVE）、 移出（无其他在组入组时 →
 * TERMINATED）；死亡（→ DEAD，A-FU-007）。失访与随访结束随 E15-T01 / 展示层。
 *
 * <p>guardedOn 非空时按当前值守卫更新（并发流转 0 行 = 未变化，不写历史）；时间线只追加（pt_status_history 永不 UPDATE /
 * DELETE，tech-plan §7）。定向 UPDATE 的数据范围前置校验由调用方（经 PatientContactGateway 40400）完成。
 */
@Service
public class PatientFollowupStatusService {

    private static final Logger log = LoggerFactory.getLogger(PatientFollowupStatusService.class);

    /** 业务日期口径（AGENTS §6：随访业务日期按北京时间）。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final PatientMapper patientMapper;
    private final PatientStatusHistoryService history;

    public PatientFollowupStatusService(
            PatientMapper patientMapper, PatientStatusHistoryService history) {
        this.patientMapper = patientMapper;
        this.history = history;
    }

    /**
     * 流转患者随访状态：更新 pt_patient 并追加时间线一行。
     *
     * @param guardedOn 当前值守卫（null = 无守卫直接更新；0 行更新 = 状态已不满足，不写时间线）
     * @return 是否实际发生变化（false = 守卫未命中，调用方不把它当错误）
     */
    public boolean transition(
            long patientId,
            String target,
            String reason,
            String source,
            String guardedOn,
            Long operatorId) {
        int rows = patientMapper.updateFollowupStatus(patientId, target, guardedOn, operatorId);
        if (rows == 0) {
            log.debug(
                    "患者随访状态未变化（守卫未命中或同值）patient={} target={} guardedOn={}",
                    patientId,
                    target,
                    guardedOn);
            return false;
        }
        history.record(patientId, target, LocalDate.now(BUSINESS_ZONE), reason, source, operatorId);
        return true;
    }
}
