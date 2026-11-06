package com.crdp.followup.task.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.domain.TaskStatusFlow;
import com.crdp.followup.task.infra.mapper.FollowupTaskAssignMapper;
import com.crdp.followup.task.infra.mapper.FollowupTaskInsertRow;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.outbox.OutboxPublisher;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时添加任务（E15-T06 / A-FU-013「在子计划任务列表中临时添加任务（如再住院后加访），不占用年度任务」；docs/api/followup.md §2 {@code POST
 * /tasks}，入口：任务列表「+ 临时任务」〔pages.md §7〕与患者时间轴〔A-FU-023「可临时添加任务」〕）。
 *
 * <p>任务形态二选一（A-FU-026）：FORM 填表随访（表单缺省取挂靠节点表单，回落入组首个 FORM 节点， 与历史补录同口径）/ REMINDER
 * 站内提醒（无表单，到点提醒责任人，「完成提醒」即完成——验收「站内提醒类任务无需填表」）。
 *
 * <ul>
 *   <li><b>不占用年度任务</b>：occurrence 用负数计数器（-1、-2、…，{@link
 *       FollowupTaskMapper#findTempOccurrenceFloor}），与调度常规序列 1..N 互不相撞——uk（入组 + 节点 + 第 N 次 +
 *       跟进序号）两条序列都唯一，调度照常按正数次数创建年度任务；起算日期重算只重排 NORMAL / BACKFILL（E14-T07），临时任务锚定手工日期不被覆写；
 *   <li>挂靠：在组入组 + 该计划的 NODE 行（缺省取患者在组最早入组的同类型首个节点）；显式传入时校验归属（40001）；
 *   <li>日期：窗口 = 计划日期 ± 窗口宽度（默认 7 天，0–365），开启日 = 提醒开始日 = 当天（手工添加即进待办，医生加的就是现在要做的事，OQ-87③）；
 *   <li>负责人：缺省当前用户；显式指定须启用且负责患者病区（或全院范围）——与改派 DOCTOR_NOT_IN_WARD 同口径（OQ-84①，保证通知可达）；
 *   <li>同事务写 {@code TaskOpened} 事件（与调度创建同契约，负责人收站内信）与 {@code TASK_TEMP_CREATE} 审计（含备注与原因文本）；
 *   <li>逾期刷新按各自窗口同口径参与（followup.md §2 已定口径：create_type ≠ BACKFILL）；完成走常规提交流程（直接录入期判定对 TEMP 豁免—— 负数
 *       occurrence 无「下一次」可排，E15-T06 起在 {@code FollowupTaskStatusService.complete} 跳过）。
 * </ul>
 */
@Service
public class FollowupTaskTempService {

    /** 任务开启 outbox 事件类型（与每日调度 E14-T04 同类型同负载契约，OQ-60③）。 */
    static final String EVENT_TASK_OPENED = "TaskOpened";

    /** fu_task.task_type（A-FU-026）：FORM 电话随访 + 表单 / REMINDER 站内提醒。 */
    static final String TASK_TYPE_FORM = "FORM";

    static final String TASK_TYPE_REMINDER = "REMINDER";

    /** 窗口宽度默认与上限（天）：默认取 A 医院预置窗口前后 7 天（A-FU-002），上限防误输入。 */
    static final int DEFAULT_WINDOW_DAYS = 7;

    static final int MAX_WINDOW_DAYS = 365;

    /** 备注 / 原因上限（与移出计划、改派原因等文本列同口径）。 */
    private static final int NOTE_MAX_LENGTH = 255;

    /** occurrence 并发兜底重试次数（uk 撞车后按递减次数重试；正常场景一次成功）。 */
    private static final int INSERT_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(FollowupTaskTempService.class);

    private final FollowupTaskMapper taskMapper;
    private final FollowupTaskAssignMapper assignMapper;
    private final PlanMapper planMapper;
    private final PatientContactGateway patientScope;
    private final com.crdp.form.record.app.FormService formService;
    private final OutboxPublisher outbox;
    private final AuditLogger audit;

    public FollowupTaskTempService(
            FollowupTaskMapper taskMapper,
            FollowupTaskAssignMapper assignMapper,
            PlanMapper planMapper,
            PatientContactGateway patientScope,
            com.crdp.form.record.app.FormService formService,
            OutboxPublisher outbox,
            AuditLogger audit) {
        this.taskMapper = taskMapper;
        this.assignMapper = assignMapper;
        this.planMapper = planMapper;
        this.patientScope = patientScope;
        this.formService = formService;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** 创建请求（docs/api/followup.md §2 POST /tasks）。 */
    public record TempCommand(
            Long patientId,
            Long enrollmentId,
            Long nodeId,
            String taskType,
            LocalDate planDate,
            Integer windowDays,
            Long assigneeId,
            String formCode,
            String note) {}

    /** 创建结果（id 超过 2^53 序列化为字符串）。 */
    public record TempTaskView(
            String taskId,
            String patientId,
            String enrollmentId,
            String nodeId,
            String taskType,
            String formCode,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            String createType,
            String status) {}

    /** 临时添加（一次事务：范围校验 → 挂靠解析 → 负数 occurrence → INSERT → TaskOpened 事件 → 审计）。 */
    @Transactional
    public TempTaskView create(TempCommand command) {
        Long operator =
                CurrentSession.current()
                        .orElseThrow(() -> new ApiException(401, 40100, "未登录或登录已失效"))
                        .userId();
        if (command == null || command.patientId() == null || command.patientId() <= 0) {
            throw new ApiException(400, 40001, "请选择患者");
        }
        // 数据范围前置校验（与任务写入口同口径）：越范围患者不暴露
        if (patientScope.patientIdInScope(command.patientId()) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        String taskType = command.taskType() == null ? TASK_TYPE_FORM : command.taskType();
        if (!TASK_TYPE_FORM.equals(taskType) && !TASK_TYPE_REMINDER.equals(taskType)) {
            throw new ApiException(400, 40001, "任务类型只支持 FORM（填表随访）或 REMINDER（站内提醒）");
        }
        if (command.planDate() == null) {
            throw new ApiException(400, 40001, "请填写计划日期");
        }
        int windowDays = command.windowDays() == null ? DEFAULT_WINDOW_DAYS : command.windowDays();
        if (windowDays < 0 || windowDays > MAX_WINDOW_DAYS) {
            throw new ApiException(400, 40001, "窗口宽度须在 0–" + MAX_WINDOW_DAYS + " 天之间");
        }
        String note = normalizeNote(command.note());

        FollowupTaskMapper.EnrollmentRefRow enrollment = resolveEnrollment(command);
        List<PlanMapper.NodeRow> nodes =
                planMapper.findNodesByPlanId(enrollment.planId()).stream()
                        .filter(node -> "NODE".equals(node.nodeType()))
                        .toList();
        if (nodes.isEmpty()) {
            throw new ApiException(400, 40001, "挂靠计划没有可用的节点，无法添加临时任务");
        }
        PlanMapper.NodeRow node = resolveNode(command, nodes, taskType);
        String formCode =
                TASK_TYPE_REMINDER.equals(taskType)
                        ? null
                        : resolveFormCode(command.formCode(), node, command.patientId());

        Long assignee = resolveAssignee(command.assigneeId(), operator, command.patientId());
        LocalDate today = PlanDateCalculator.businessToday(Instant.now());
        LocalDate windowStart = command.planDate().minusDays(windowDays);
        LocalDate windowEnd = command.planDate().plusDays(windowDays);

        FollowupTaskInsertRow row =
                insertWithNegativeOccurrence(
                        enrollment,
                        node,
                        taskType,
                        formCode,
                        assignee,
                        command.planDate(),
                        windowStart,
                        windowEnd,
                        today,
                        operator);
        publishTaskOpened(row, enrollment, node);
        audit.append(
                AuditEntry.operation("followup", "TASK_TEMP_CREATE")
                        .objectType("TASK")
                        .objectId(String.valueOf(row.getId()))
                        .description(
                                "临时添加任务（A-FU-013，不占用年度任务）：任务类型 "
                                        + (TASK_TYPE_REMINDER.equals(taskType) ? "站内提醒" : "填表随访")
                                        + "，计划日期 "
                                        + command.planDate()
                                        + (note == null ? "" : "，备注 " + note))
                        .afterJson(
                                "{\"patientId\":"
                                        + command.patientId()
                                        + ",\"enrollmentId\":"
                                        + enrollment.id()
                                        + ",\"nodeId\":"
                                        + node.id()
                                        + ",\"occurrence\":"
                                        + row.getOccurrence()
                                        + ",\"taskType\":\""
                                        + taskType
                                        + "\",\"planDate\":\""
                                        + command.planDate()
                                        + "\",\"assigneeId\":"
                                        + assignee
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "临时任务已创建 id={} patient={} enrollment={} node={} type={} occurrence={} operator={}",
                row.getId(),
                command.patientId(),
                enrollment.id(),
                node.id(),
                taskType,
                row.getOccurrence(),
                operator);
        return new TempTaskView(
                String.valueOf(row.getId()),
                String.valueOf(command.patientId()),
                String.valueOf(enrollment.id()),
                String.valueOf(node.id()),
                taskType,
                formCode,
                command.planDate(),
                windowStart,
                windowEnd,
                TaskCreationPlanner.CREATE_TEMP,
                TaskStatusFlow.PENDING);
    }

    // ---------------------------------------------------------------- 挂靠解析

    /** 挂靠入组：显式传入校验归属（在组且属于该患者，否则 40001）；缺省取患者在组最早入组。 */
    private FollowupTaskMapper.EnrollmentRefRow resolveEnrollment(TempCommand command) {
        FollowupTaskMapper.EnrollmentRefRow enrollment =
                command.enrollmentId() != null
                        ? taskMapper.findActiveEnrollment(
                                command.enrollmentId(), command.patientId())
                        : taskMapper.findFirstActiveEnrollment(command.patientId());
        if (enrollment == null) {
            throw new ApiException(
                    400,
                    40001,
                    command.enrollmentId() != null
                            ? "所选随访计划入组不存在、已移除或不属于该患者"
                            : "患者没有在组的随访计划，无法添加临时任务（可先入组或使用历史补录）");
        }
        return enrollment;
    }

    /**
     * 挂靠节点：显式传入须属于挂靠计划的 NODE 行（40001）；缺省取同类型首个节点（FORM → 首个 FORM 节点， REMINDER →
     * 首个节点）——时间轴「添加任务」带节点上下文，任务列表「+ 临时任务」走缺省。
     */
    private static PlanMapper.NodeRow resolveNode(
            TempCommand command, List<PlanMapper.NodeRow> nodes, String taskType) {
        if (command.nodeId() != null) {
            return nodes.stream()
                    .filter(node -> node.id() != null && node.id().equals(command.nodeId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException(400, 40001, "所选节点不存在或不属于该随访计划"));
        }
        return nodes.stream()
                .filter(node -> taskType.equals(node.taskType()))
                .findFirst()
                .orElse(nodes.get(0));
    }

    /** FORM 表单解析：显式传 formCode 时校验存在（40400，与历史补录同口径）；缺省节点表单 → 入组首个 FORM 节点表单。 */
    private String resolveFormCode(String requested, PlanMapper.NodeRow node, long patientId) {
        if (requested != null && !requested.isBlank()) {
            formService.publishedVersion(requested); // 表单不存在 / 未发布 → 40400
            return requested;
        }
        if (node.formCode() != null && !node.formCode().isBlank()) {
            return node.formCode();
        }
        String derived = taskMapper.findEnrollmentFormCode(patientId);
        if (derived == null) {
            throw new ApiException(400, 40001, "挂靠计划没有可用的随访表单，请显式指定表单或先配置计划节点");
        }
        return derived;
    }

    // ---------------------------------------------------------------- 负责人与落库

    /** 负责人：缺省当前用户（本人自取不发通知口径同认领）；显式指定须启用且负责患者病区或全院（OQ-84①）。 */
    private Long resolveAssignee(Long requested, long operator, long patientId) {
        if (requested == null) {
            return operator;
        }
        if (assignMapper.countEnabledAssignee(requested) == 0) {
            throw new ApiException(400, 40001, "所选负责人不存在或已停用");
        }
        Long wardId = taskMapper.findPatientWardId(patientId);
        boolean hospitalWide = assignMapper.countHospitalWideScope(requested) > 0;
        if (wardId != null
                && !hospitalWide
                && assignMapper.countWardMembership(requested, wardId) == 0) {
            throw new ApiException(400, 40001, "所选负责人不负责患者所在病区，请选择本病区医生（与改派同口径）");
        }
        return requested;
    }

    /** 负数 occurrence 落库：并发撞 uk（两人同时取到同一计数）按递减次数重试。 */
    private FollowupTaskInsertRow insertWithNegativeOccurrence(
            FollowupTaskMapper.EnrollmentRefRow enrollment,
            PlanMapper.NodeRow node,
            String taskType,
            String formCode,
            Long assignee,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            LocalDate today,
            Long operator) {
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < INSERT_ATTEMPTS; attempt++) {
            int floor = taskMapper.findTempOccurrenceFloor(enrollment.id(), node.id());
            int occurrence = floor == 0 ? -1 : floor - 1;
            FollowupTaskInsertRow row =
                    new FollowupTaskInsertRow(
                            enrollment.id(),
                            enrollment.patientId(),
                            enrollment.planId(),
                            node.id(),
                            occurrence,
                            taskType,
                            formCode,
                            "DOCTOR", // 手工指定的负责人快照（模板回落链不适用，V48 注释口径）
                            assignee,
                            planDate,
                            today,
                            windowStart,
                            windowEnd,
                            today,
                            TaskCreationPlanner.CREATE_TEMP,
                            TaskStatusFlow.PENDING,
                            0,
                            today,
                            operator);
            try {
                taskMapper.insertTask(row);
                return row;
            } catch (DuplicateKeyException race) {
                // 并发取到同一计数：递减重试
                last = race;
                log.debug(
                        "临时任务并发撞 uk，递减重试 enrollment={} node={} occurrence={}",
                        enrollment.id(),
                        node.id(),
                        occurrence);
            }
        }
        log.warn(
                "临时任务连续撞 uk（并发添加过密） enrollment={} node={} 原因={}",
                enrollment.id(),
                node.id(),
                last == null ? "" : last.getMessage());
        throw new ApiException(409, 40900, "临时任务创建冲突，请刷新后重试");
    }

    /** TaskOpened 事件（与调度创建同负载契约：通知规则引擎按它给负责人发站内信）。 */
    private void publishTaskOpened(
            FollowupTaskInsertRow row,
            FollowupTaskMapper.EnrollmentRefRow enrollment,
            PlanMapper.NodeRow node) {
        outbox.publish(
                EVENT_TASK_OPENED,
                "{\"taskId\":"
                        + row.getId()
                        + ",\"enrollmentId\":"
                        + enrollment.id()
                        + ",\"patientId\":"
                        + enrollment.patientId()
                        + ",\"planId\":"
                        + enrollment.planId()
                        + ",\"nodeId\":"
                        + node.id()
                        + ",\"occurrence\":"
                        + row.getOccurrence()
                        + ",\"openDate\":\""
                        + row.getOpenDate()
                        + "\",\"windowStart\":\""
                        + row.getWindowStart()
                        + "\",\"windowEnd\":\""
                        + row.getWindowEnd()
                        + "\"}");
    }

    /** 备注：可空；去空白后 ≤255。 */
    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > NOTE_MAX_LENGTH) {
            throw new ApiException(400, 40001, "备注不能超过 " + NOTE_MAX_LENGTH + " 字");
        }
        return trimmed;
    }
}
