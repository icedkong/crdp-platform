package com.crdp.followup.task.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 开始录入（E15-T02 / A-FU-005「从任务进入，可暂存草稿」；docs/api/followup.md §2 {@code POST
 * /tasks/{id}/start}，pages.md §5 随访整页表单）。
 *
 * <p>一次事务内：患者数据范围校验（越范围 40400，不暴露任务存在）→ 无记录则创建 DRAFT 草稿（fe_record，经 FormService.createRecord，OQ-77①
 * / OQ-83① 载体口径）→ 回填 fu_task.record_id（followup V54，幂等：已有记录直接返回） → 任务进入「进行中」（{@link
 * FollowupTaskStatusService#markInProgress}，只从 PENDING 进、逾期不退回，OQ-82①）。
 *
 * <p>REMINDER 站内提醒类任务无表单（A-FU-026），40901（录入入口只对 FORM 任务开放，页面隐藏入口随 E15-T06）。
 */
@Service
public class FollowupTaskStartService {

    /** fu_task.task_type：FORM 电话随访 + 表单 / REMINDER 站内提醒（A-FU-026）。 */
    static final String TASK_TYPE_FORM = "FORM";

    /** data_meta 里的任务标记（E15-T02：记录与任务的弱关联展示位；强关联在 fu_task.record_id）。 */
    static final String META_TASK_ID = "taskId";

    private final FollowupTaskMapper taskMapper;
    private final FollowupTaskStatusService statusService;
    private final PatientContactGateway patientScope;
    private final FormService formService;
    private final AuditLogger audit;

    public FollowupTaskStartService(
            FollowupTaskMapper taskMapper,
            FollowupTaskStatusService statusService,
            PatientContactGateway patientScope,
            FormService formService,
            AuditLogger audit) {
        this.taskMapper = taskMapper;
        this.statusService = statusService;
        this.patientScope = patientScope;
        this.formService = formService;
        this.audit = audit;
    }

    /** 开始录入结果：统一用 recordId 路由（pages.md §5）。 */
    public record StartView(String taskId, String recordId, boolean recordCreated) {}

    /** 开始录入（幂等：已有关联草稿直接返回，不重复创建；重复点击安全）。 */
    @Transactional
    public StartView start(long taskId) {
        FollowupTaskMapper.StartTaskRow task = taskMapper.findStartTaskById(taskId);
        if (task == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        // 数据范围前置校验（与 E14-T06 / E15-T01 写入口同口径）：越范围患者不暴露任务存在
        if (patientScope.patientIdInScope(task.patientId()) == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        if (!TASK_TYPE_FORM.equals(task.taskType()) || task.formCode() == null) {
            throw new ApiException(409, 40901, "站内提醒类任务无需填写表单（A-FU-026），不能开始录入");
        }
        if (task.recordId() != null) {
            return new StartView(String.valueOf(taskId), String.valueOf(task.recordId()), false);
        }
        FormService.RecordView created =
                formService.createRecord(
                        task.formCode(), Map.of(), Map.of(META_TASK_ID, String.valueOf(taskId)));
        if (taskMapper.linkRecord(taskId, created.id()) == 0) {
            // 并发重复开始：已有记录胜出（uk_fu_task_record 兜底），返回已关联的那条
            FollowupTaskMapper.StartTaskRow current = taskMapper.findStartTaskById(taskId);
            if (current == null || current.recordId() == null) {
                throw new ApiException(409, 40900, "任务状态已变化，请刷新后重试");
            }
            return new StartView(String.valueOf(taskId), String.valueOf(current.recordId()), false);
        }
        Long operator = CurrentSession.current().map(item -> item.userId()).orElse(null);
        statusService.markInProgress(taskId, operator, "开始录入");
        audit.append(
                AuditEntry.operation("followup", "TASK_START")
                        .objectType("TASK")
                        .objectId(String.valueOf(taskId))
                        .description("开始录入：创建草稿记录 " + created.id() + " 并进入进行中（A-FU-005）")
                        .afterJson(
                                "{\"recordId\":"
                                        + created.id()
                                        + ",\"formCode\":\""
                                        + task.formCode()
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new StartView(String.valueOf(taskId), String.valueOf(created.id()), true);
    }
}
