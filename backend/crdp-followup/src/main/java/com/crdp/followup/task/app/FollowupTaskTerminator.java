package com.crdp.followup.task.app;

import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 任务终止写入口（E14-T06 / A-FU-007「当期未完成的任务自动终止」、A-FU-008 移出终止）：把「未完成 = 待随访 / 进行中 / 逾期 / 暂停或停用期间未执行」的任务置
 * TERMINATED 并落终止原因（fu_task 终止三列，followup V51）； 已完成与已失访保留原状（历史事实）。
 *
 * <p>定向 UPDATE，无 SELECT 输出；操作者对患者 / 入组的数据范围校验由调用方（PlanEnrollmentService、
 * FollowupTerminationService，经 PatientContactGateway 40400）完成。终止原因按 A 规格任务关键字段存操作时文本 （死亡 / 拒绝随访 /
 * 外地随访 / 其他），字典化随 E15 展示层。
 */
@Component
public class FollowupTaskTerminator {

    private final FollowupTaskMapper taskMapper;

    public FollowupTaskTerminator(FollowupTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /** 终止一条入组的全部未完成任务（移出计划）。返回终止条数。 */
    public int terminateUnfinishedByEnrollment(long enrollmentId, String reason, Long operatorId) {
        return taskMapper.terminateUnfinishedByEnrollment(
                enrollmentId, reason, LocalDateTime.now(), operatorId);
    }

    /** 终止一位患者全部入组的未完成任务（死亡自动终止，tasks.csv 验收「告知已故后未开始任务全部终止」）。返回终止条数。 */
    public int terminateUnfinishedByPatient(long patientId, String reason, Long operatorId) {
        return taskMapper.terminateUnfinishedByPatient(
                patientId, reason, LocalDateTime.now(), operatorId);
    }
}
