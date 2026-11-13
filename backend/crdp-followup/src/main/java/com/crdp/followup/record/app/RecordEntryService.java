package com.crdp.followup.record.app;

import com.crdp.followup.compare.app.CompareHighlightService;
import com.crdp.followup.compare.app.CompareRuleService;
import com.crdp.followup.compare.app.CompareSourceService;
import com.crdp.followup.implant.app.DeviceContextService;
import com.crdp.followup.plan.app.FollowupTerminationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskQueryMapper;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随访工作台记录入口（E15-T02 / A-FU-005、C-FU-020；docs/api/followup.md §3 {@code GET /records/{id}} 与 {@code
 * POST /records/{id}/submit}，页面 /followup/record/:recordId，pages.md §5）。
 *
 * <p>验收口径（A-FU-005「从任务进入，填写随访表单；可暂存草稿，提交后任务完成」）：
 *
 * <ul>
 *   <li>详情：记录经 {@link FollowupRecordService#record}（40400）+ 任务上下文（fu_task.record_id 反查，@DataScope
 *       出行——越范围 / 未关联任务的记录 context 为 null，此时附 patient 摘要（补录记录的表单页头幅）与 visitDate / source）；姓名 /
 *       病案号只输出脱敏值；
 *   <li>提交：完整校验与落库经 {@link FollowupRecordService#submit}（42200 / 40900 / 40901，E15-T07 起载体
 *       fu_record），同一事务内写 DRAFT→SUBMITTED 字段历史（E11-T06，record_type = FU_RECORD）并联动任务完成（{@link
 *       FollowupTaskStatusService#complete}， followup V53 的 complete_kind / completed_at /
 *       completed_by 即在此写入；已过直接录入期 40901 只能历史补录 E15-T07）；写 RECORD_SUBMIT 审计；
 *   <li>死亡联动（OQ-79②）：模板字段编码 {@code death}（是否死亡）= 1 时经 {@link
 *       FollowupTerminationService#terminateOnDeath} 终止患者全部未开始任务（幂等；「失访」只是失访候选，判定随
 *       E15-T05，本流程不置失访）；
 *   <li>修改已提交（edit-session / PUT）、删除、报告单带入随 E16 / E21；{@code previous}（上一次记录解析，A-FU-027）已随 E17-T02
 *       接入（{@link CompareSourceService#resolve}）；{@code compareRules}（启用规则下发，前端实时标红与后端保存复算共用，
 *       tech-plan §11）随 E17-T03；标红结果持久化（提交后同一事务 {@link
 *       CompareHighlightService#refreshSubmittedRecords} 重算并落 fu_record.highlight，含补录更早记录后的级联重算）已随
 *       E17-T04 落地。
 * </ul>
 */
@Service
public class RecordEntryService {

    /** A 规格字段编码：是否死亡（yesno，1 = 是；模板驱动定位稳定键，不硬编码键名）。 */
    static final String FIELD_CODE_DEATH = "death";

    private static final int DEATH_YES = 1;

    private final FollowupRecordService recordService;
    private final FormService formService;
    private final FieldHistoryService fieldHistory;
    private final FollowupTaskStatusService statusService;
    private final FollowupTerminationService terminationService;
    private final FollowupTaskQueryMapper taskQueryMapper;
    private final CompareSourceService compareSource;
    private final CompareRuleService compareRules;
    private final CompareHighlightService compareHighlights;
    private final DeviceContextService deviceContext;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final RecordEntryAssistService entryAssist;
    private final com.crdp.followup.record.infra.mapper.FollowupRecordMapper recordMapper;
    private final com.crdp.followup.record.infra.mapper.RecordFeatureMapper features;
    private final com.crdp.followup.plan.app.PlanSwitchService planSwitch;
    private final FollowupSequenceService sequences;
    private final com.crdp.followup.report.app.ReportTranscribeService reportTranscribe;

    public RecordEntryService(
            FollowupRecordService recordService,
            FormService formService,
            FieldHistoryService fieldHistory,
            FollowupTaskStatusService statusService,
            FollowupTerminationService terminationService,
            FollowupTaskQueryMapper taskQueryMapper,
            CompareSourceService compareSource,
            CompareRuleService compareRules,
            CompareHighlightService compareHighlights,
            DeviceContextService deviceContext,
            CryptoService crypto,
            AuditLogger audit,
            RecordEntryAssistService entryAssist,
            com.crdp.followup.record.infra.mapper.FollowupRecordMapper recordMapper,
            com.crdp.followup.record.infra.mapper.RecordFeatureMapper features,
            com.crdp.followup.plan.app.PlanSwitchService planSwitch,
            FollowupSequenceService sequences,
            com.crdp.followup.report.app.ReportTranscribeService reportTranscribe) {
        this.recordService = recordService;
        this.formService = formService;
        this.fieldHistory = fieldHistory;
        this.statusService = statusService;
        this.terminationService = terminationService;
        this.taskQueryMapper = taskQueryMapper;
        this.compareSource = compareSource;
        this.compareRules = compareRules;
        this.compareHighlights = compareHighlights;
        this.deviceContext = deviceContext;
        this.crypto = crypto;
        this.audit = audit;
        this.entryAssist = entryAssist;
        this.recordMapper = recordMapper;
        this.features = features;
        this.planSwitch = planSwitch;
        this.sequences = sequences;
        this.reportTranscribe = reportTranscribe;
    }

    // ---------------------------------------------------------------- DTO

    /** 任务上下文（表单页顶栏与横幅：患者脱敏、节点、计划 / 窗口、任务状态派生）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TaskContextView(
            String taskId,
            String patientId,
            String patientName,
            String mrn,
            String wardName,
            String nodeName,
            Integer occurrence,
            String taskType,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd,
            String taskStatus,
            String completeKind,
            String createType,
            String assigneeName,
            LocalDate nextPlanDate) {}

    /**
     * 记录详情（followup.md §3；E15-T07 起附 source（TASK / BACKFILL）与 visitDate（补录的实际随访日期，A-FU-014）。
     * taskless 记录（补录）context 为 null、附 patient 摘要（表单页头幅：脱敏姓名 / 病案号 / 病区）。 E17-T02 起附
     * previous（A-FU-027 上一次记录解析：上一次已提交随访，无则基线回落；功能关闭或无启用规则为 null）； E17-T03 起附
     * compareRules（该表单启用规则，前端实时标红与后端保存复算共用同一组规则，tech-plan §11）；E21-T03 起附 device （器械上下文：按 visitDate
     * 匹配当时在用的器械，任务录入回落当前器械；页面以 {@code { device: … }}} 传入 FormRenderer 的 context
     * 驱动按器械条件显示，C-FU-009）；E21-T11 起附 visitNo（患者随访编号）、sequenceNo（随访序号，C-FU-008 自动带入）与
     * entryDefaults（录入缺省：随访医生 = 当前用户；随访方式缺省取患者默认随访方式，患者表单字段随 E23-T20 字典落地后接入）；E21-T09 / C-FU-018 起
     * sequenceNo 开关开启（record_sequence_no）的已提交记录优先读重排落库值（fu_record.sequence_no）， 草稿与关闭租户（A /
     * B）维持派生预演（FollowupSequenceService）。
     */
    public record RecordDetailView(
            String id,
            String formCode,
            int formVersion,
            String status,
            int version,
            String source,
            LocalDate visitDate,
            PatientBriefView patient,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            TaskContextView context,
            DeviceContextService.DeviceView device,
            CompareSourceService.PreviousView previous,
            List<CompareRuleService.RuleView> compareRules,
            String visitNo,
            Integer sequenceNo,
            EntryDefaultsView entryDefaults,
            List<Map<String, Object>> highlightHits) {}

    /** 患者摘要（补录记录的表单页头幅；姓名 / 病案号只输出脱敏值）。 */
    public record PatientBriefView(
            String patientId, String patientName, String mrn, String wardName) {}

    /** 录入缺省（C-FU-008「随访医生默认当前用户」；无会话〔定时作业〕为 null 项）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EntryDefaultsView(String doctorId, String doctorName) {}

    /** 提交结果：任务终态与完成方式（「提交后任务完成」验收位）+ 字段历史条数与软校验警告；E21-T17 起转录态附 transcribedReportId。 */
    public record SubmitResultView(
            String recordId,
            String taskId,
            String taskStatus,
            String completeKind,
            int fieldChanges,
            List<WarningView> warnings,
            String transcribedReportId) {}

    /** 软校验警告（不拦截提交，前端 yellow 提示口径，A-FLD-003「提示但可保存」）。 */
    public record WarningView(String key, String message) {}

    /** 开始修改已提交记录（edit-session）结果：记录当前 version（PUT 的 baseVersion）。 */
    public record EditSessionView(String recordId, int version, String status) {}

    /**
     * 保存修改已提交记录（PUT）结果：新 version + 字段历史条数与软校验警告（与提交同形，去任务联动位）；E21-T17 起转录态附 transcribedReportId。
     */
    public record UpdateResultView(
            String recordId,
            int version,
            int fieldChanges,
            List<WarningView> warnings,
            String transcribedReportId) {}

    /**
     * 保存修改请求（followup.md §3：baseVersion 乐观锁、data 全量、reason 必填——OQ-14 暂定 A / C
     * 都必填；transcribeReportId 同 SubmitCommand，E21-T17）。
     */
    public record UpdateCommand(
            Integer baseVersion,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            String reason,
            Long transcribeReportId) {}

    // ---------------------------------------------------------------- 详情（表单页装配）

    /**
     * 记录详情（40400：记录不存在；context 为 null = 记录未关联任务或越范围不可见，此时附 patient 摘要）。 E17-T03：previous +
     * compareRules 成对下发（功能开关关闭或无启用规则均为 null，记录页正常返回）； E21-T03：device 随记录下发（implant 开关关闭或无匹配器械为
     * null，提交侧用同一上下文清空隐藏字段）；E21-T11：visitNo / sequenceNo / entryDefaults（C-FU-008 自动带入）；
     * E21-T09：sequenceNo 读侧改为 {@link FollowupSequenceService#sequenceNoOfDetail}（落库值优先）。
     */
    public RecordDetailView detail(long recordId) {
        FollowupRecordService.RecordView record = recordService.record(recordId);
        TaskContextView context = contextOf(recordId);
        List<CompareRuleService.RuleView> enabledRules =
                compareRules.evaluationRules(record.formCode());
        return new RecordDetailView(
                String.valueOf(record.id()),
                record.formCode(),
                record.templateVersion(),
                record.status(),
                record.version(),
                record.source(),
                record.visitDate(),
                context == null ? patientBriefOf(record.patientId()) : null,
                record.data(),
                record.dataMeta(),
                context,
                deviceContext.resolve(record.patientId(), record.visitDate()),
                compareSource.resolve(
                        record.id(), record.patientId(), record.formCode(), record.visitDate()),
                enabledRules.isEmpty() ? null : enabledRules,
                entryAssist.visitNoOf(record.patientId()),
                sequences.sequenceNoOfDetail(
                        record,
                        record.visitDate() != null
                                ? record.visitDate()
                                : (context == null ? null : context.planDate())),
                entryDefaultsOf(),
                // E21-T32 / C-PAT-018：持久化标红命中（分组条 ▲n 数据面；compare_highlight 关闭为 null）
                compareHighlights.persistedHits(record.id()));
    }

    /** 录入缺省（C-FU-008：随访医生 = 当前用户；无医院会话〔定时作业旁路〕null）。 */
    private EntryDefaultsView entryDefaultsOf() {
        return CurrentSession.current()
                .map(
                        session ->
                                new EntryDefaultsView(
                                        String.valueOf(session.userId()), session.displayName()))
                .orElse(null);
    }

    // ---------------------------------------------------------------- 提交（A-FU-005「提交后任务完成」）

    /** 提交（一次事务：校验 → SUBMITTED → 字段历史 → 任务完成 → 死亡联动 → 审计）。 */
    @Transactional
    public SubmitResultView submit(long recordId, SubmitCommand command) {
        if (command == null || command.baseVersion() == null) {
            throw new ApiException(400, 40001, "提交必须携带 baseVersion（乐观锁）");
        }
        FollowupRecordService.SubmitOutcome outcome =
                recordService.submit(
                        recordId, command.baseVersion(), command.data(), command.dataMeta());
        // 对比标红保存复算与持久化（E17-T04 / A-FU-027，tech-plan §11「后端保存时再算一次，结果存
        // fu_record.highlight」）：同一事务重算该患者该表单全部已提交记录——含本次命中落库与补录更早记录后
        // 排在后面的记录「上一次」变化；无启用规则 / 无命中置 NULL。RECORD_SUBMIT 审计已含本次提交事实，重算不单独写审计。
        compareHighlights.refreshSubmittedRecords(
                outcome.view().patientId(), outcome.view().formCode());
        // 随访序号自动重排（E21-T09 / C-FU-018「补录插入后序号自动重排」）：补录插入较早日期后排在插入点之后的
        // 记录序号整体 +1，内部记录号不变，重排逐行入变更历史（虚拟稳定键 sequence_no）；开关关闭租户跳过
        sequences.resequence(
                outcome.view().patientId(),
                FollowupSequenceService.reasonForSubmit(outcome.view().source()));
        // 字段历史（E11-T06：提交时比较 DRAFT→SUBMITTED 逐字段留痕；隐藏字段清空同样落 diff；E15-T07 起
        // 载体 fu_record，record_type = FU_RECORD）
        List<FieldHistoryService.FieldChange> changes =
                fieldHistory.logChanges(
                        FieldHistoryService.FU_RECORD,
                        recordId,
                        outcome.view().formCode(),
                        outcome.view().templateVersion(),
                        outcome.before(),
                        outcome.view().data(),
                        ChangeSource.USER,
                        "随访记录提交");
        FollowupTaskQueryMapper.TaskRowRaw context = taskRowByRecordId(recordId);
        String taskStatus = null;
        String completeKind = null;
        Long operator = CurrentSession.current().map(item -> item.userId()).orElse(null);
        Long patientIdForDeath = null;
        if (context != null) {
            FollowupTaskStatusService.CompletionView completion =
                    statusService.complete(context.id(), operator);
            taskStatus = completion.status();
            completeKind = completion.completeKind();
            patientIdForDeath = context.patientId();
        } else {
            // 补录 / 计划外记录无任务：死亡联动以记录自身的患者为准（E15-T07，A-FU-014 补录同样终止未开始任务）
            patientIdForDeath = outcome.view().patientId();
        }
        // 死亡联动（OQ-79②：随访记录「是否死亡=是」→ 死亡登记，未开始任务全部终止）
        if (isDeathRecorded(outcome.view()) && patientIdForDeath != null) {
            terminationService.terminateOnDeath(patientIdForDeath, "随访记录提交：是否死亡 = 是", operator);
        }
        // 计划切换联动（E21-T06 / C-FU-007 切换表「接近 ERI / ERI 标记解除 / 医生指定」行）：ERI 标记翻入组模式、
        // 医生指定下次随访时间登记 doctor_next_date（SW-7 / SW-8 / SW-10）；A / B 表单无 f323 / f324 / f333 键，天然不触发
        planSwitch.onRecordSubmitted(
                outcome.view().patientId(),
                recordId,
                effectiveVisitDate(outcome.view()),
                outcome.view().data());
        // 转录完成联动（E21-T17 / C-SCAN-012：转录态提交同一事务）——报告单自动加入该记录位置并置「已电子化」
        if (command.transcribeReportId() != null) {
            reportTranscribe.completeTranscription(
                    command.transcribeReportId(),
                    recordId,
                    outcome.view().patientId(),
                    outcome.view().templateVersion());
        }
        audit.append(
                AuditEntry.operation("followup", "RECORD_SUBMIT")
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(recordId))
                        .description(
                                "提交随访记录（任务 "
                                        + (context == null ? "无（未关联任务）" : context.id())
                                        + (completeKind == null ? "" : "，完成方式 " + completeKind)
                                        + "）")
                        .afterJson(
                                "{\"taskId\":"
                                        + (context == null ? "null" : String.valueOf(context.id()))
                                        + ",\"taskStatus\":\""
                                        + (taskStatus == null ? "" : taskStatus)
                                        + "\",\"fieldChanges\":"
                                        + changes.size()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new SubmitResultView(
                String.valueOf(recordId),
                context == null ? null : String.valueOf(context.id()),
                taskStatus,
                completeKind,
                changes.size(),
                outcome.warnings().stream()
                        .map(item -> new WarningView(item.key(), item.message()))
                        .toList(),
                command.transcribeReportId() == null
                        ? null
                        : String.valueOf(command.transcribeReportId()));
    }

    /**
     * 提交请求（data 全量、baseVersion 乐观锁——与草稿 / 已提交修改同口径）。{@code transcribeReportId} 非空 = 转录态提交（E21-T17 /
     * C-SCAN-012：表单页 {@code ?compare=<报告单 id>&transcribe=1} 带入）， 同一事务完成转录——报告单自动加入该记录位置并置「已电子化」。
     */
    public record SubmitCommand(
            Integer baseVersion,
            Map<String, Object> data,
            Map<String, Object> dataMeta,
            Long transcribeReportId) {}

    // ------------------------------------------------------- 修改 / 删除已提交记录（E21-T11 / C-FU-026、Q17）

    /**
     * 开始修改已提交记录（followup.md §3 {@code POST /records/{id}/edit-session}）：权限闸门（开关开启的租户必须 {@code
     * followup:record:edit-submitted}，关闭的租户〔A / B〕沿用 {@code followup:record:edit}——OQ-14「各用各的码」，
     * AGENTS §5.7 医院差异走功能开关）→ 只允许 SUBMITTED（草稿直接编辑，40901）→ 写 RECORD_EDIT_SESSION 审计（reason 随
     * ReasonModal「开始修改」带入）→ 返回当前 version（PUT 的 baseVersion）。
     */
    @Transactional
    public EditSessionView editSession(long recordId, String reason) {
        requireSubmittedEditPermission();
        FollowupRecordService.RecordView record = recordService.record(recordId);
        if (!"SUBMITTED".equals(record.status())) {
            throw new ApiException(409, 40901, "只有已提交记录走修改流程（草稿直接编辑，无需开始修改）");
        }
        audit.append(
                AuditEntry.operation("followup", "RECORD_EDIT_SESSION")
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(recordId))
                        .description(
                                "开始修改已提交随访记录（C-FU-026 / Q17）"
                                        + (reason == null || reason.isBlank() ? "" : "：" + reason))
                        .afterJson("{\"version\":" + record.version() + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new EditSessionView(String.valueOf(recordId), record.version(), record.status());
    }

    /**
     * 保存修改已提交记录（followup.md §3 {@code PUT /records/{id}}）：权限闸门同 edit-session；reason 必填（OQ-14 暂定 A /
     * C 都必填）；完整校验（42200）→ 隐藏字段清空 → 写入闸门 → SUBMITTED 原地更新 + version + 1（「保存后生成 v2」）→ 字段级历史（含原因） →
     * 对比标红复算（E17-T04）→ RECORD_UPDATE 审计。修改不动任务（任务完成状态不回退）。
     */
    @Transactional
    public UpdateResultView updateSubmitted(long recordId, UpdateCommand command) {
        if (command == null || command.baseVersion() == null) {
            throw new ApiException(400, 40001, "保存修改必须携带 baseVersion（乐观锁）");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new ApiException(400, 40001, "修改已提交记录必须填写修改原因（OQ-14）");
        }
        requireSubmittedEditPermission();
        FollowupRecordService.SubmitOutcome outcome =
                recordService.updateSubmitted(
                        recordId, command.baseVersion(), command.data(), command.dataMeta());
        // 对比标红复算（修改参数后重新计算，E17-T04 同一事务；RECORD_UPDATE 审计已含修改事实）
        compareHighlights.refreshSubmittedRecords(
                outcome.view().patientId(), outcome.view().formCode());
        // 随访序号重排（E21-T09 / C-FU-018）：表单随访日期字段可能改早 / 改晚，同一事务重排并入变更历史
        sequences.resequence(
                outcome.view().patientId(), FollowupSequenceService.REASON_UPDATE_SUBMITTED);
        // 字段级历史（C-FU-026「修改留字段级历史」：提交前值 → 修改后值，原因入留痕行）
        List<FieldHistoryService.FieldChange> changes =
                fieldHistory.logChanges(
                        FieldHistoryService.FU_RECORD,
                        recordId,
                        outcome.view().formCode(),
                        outcome.view().templateVersion(),
                        outcome.before(),
                        outcome.view().data(),
                        ChangeSource.USER,
                        "修改已提交随访记录：" + command.reason());
        audit.append(
                AuditEntry.operation("followup", "RECORD_UPDATE")
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(recordId))
                        .description("修改已提交随访记录：" + command.reason())
                        .afterJson(
                                "{\"version\":"
                                        + outcome.view().version()
                                        + ",\"fieldChanges\":"
                                        + changes.size()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        // 计划切换联动（E21-T06）：修改已提交记录更正 ERI 状态（如接近 → 正常）同样翻入组模式（SW-8 的「更正」路径）；
        // 下次随访时间修改同样覆盖 doctor_next_date（SW-10）。修改不动任务（完成状态不回退），模式切换照常终止未完成任务
        planSwitch.onRecordSubmitted(
                outcome.view().patientId(),
                recordId,
                effectiveVisitDate(outcome.view()),
                outcome.view().data());
        // 转录完成联动（E21-T17 / C-SCAN-012：位置中已有已提交记录的对照录入，保存修改同一事务完成转录）
        if (command.transcribeReportId() != null) {
            reportTranscribe.completeTranscription(
                    command.transcribeReportId(),
                    recordId,
                    outcome.view().patientId(),
                    outcome.view().templateVersion());
        }
        return new UpdateResultView(
                String.valueOf(recordId),
                outcome.view().version(),
                changes.size(),
                outcome.warnings().stream()
                        .map(item -> new WarningView(item.key(), item.message()))
                        .toList(),
                command.transcribeReportId() == null
                        ? null
                        : String.valueOf(command.transcribeReportId()));
    }

    /**
     * 删除随访记录（followup.md §3 {@code DELETE /records/{id}}，C-FU-026 / PLT-AUD-001）：reason
     * 必填；软删除（deleted = 1 + 原因 / 删除人 / 时间，数据长期保留）；断开关联任务链接（任务终态不动——删除不重开任务，重录走补录 / 计划外入口）； 写
     * RECORD_DELETE 审计。权限由控制层 {@code @RequiresPermission("followup:record:delete")}
     * 把守（敏感码，平台按租户放开后由 租户管理员分配，Q17）。
     */
    @Transactional
    public void deleteRecord(long recordId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "删除随访记录必须填写删除原因（C-FU-026）");
        }
        FollowupRecordService.RecordView record = recordService.record(recordId);
        int updated =
                recordMapper.softDelete(
                        recordId, reason, CurrentSession.requireHospital().userId());
        if (updated != 1) {
            throw new ApiException(404, 40400, "随访记录不存在（或已被删除）");
        }
        recordMapper.clearTaskRecordLink(recordId);
        // 随访序号重排（E21-T09 / C-FU-018）：软删行出册后排在后面的记录序号回退，同一事务重排并入变更历史
        sequences.resequence(record.patientId(), FollowupSequenceService.REASON_DELETE);
        audit.append(
                AuditEntry.operation("followup", "RECORD_DELETE")
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(recordId))
                        .description("删除随访记录（软删除）：" + reason)
                        .afterJson(
                                "{\"patientId\":"
                                        + record.patientId()
                                        + ",\"formCode\":\""
                                        + record.formCode()
                                        + "\",\"status\":\""
                                        + record.status()
                                        + "\",\"visitDate\":"
                                        + (record.visitDate() == null
                                                ? "null"
                                                : "\"" + record.visitDate() + "\"")
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
    }

    /**
     * 修改已提交权限闸门（C-FU-026 验收「修改已提交记录需单独权限」）：功能开关 {@code record_edit_submitted} 开启（医院 C） 时必须持 {@code
     * followup:record:edit-submitted}（仅有录入权限 record:edit 不够，40300）；关闭（A / B，无行默认）由控制层多值注解按 {@code
     * followup:record:edit} 放行（followup.md §6「A 用户有 record:edit 即可」）。
     */
    private void requireSubmittedEditPermission() {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_RECORD_EDIT_SUBMITTED))) {
            return; // A / B：控制层 @RequiresPermission({"record:edit","record:edit-submitted"})
            // 已按任一码放行
        }
        boolean granted =
                CurrentSession.current()
                        .map(session -> session.permissionCodes().contains(PERM_EDIT_SUBMITTED))
                        .orElse(false);
        if (!granted) {
            throw new ApiException(403, 40300, "修改已提交记录需要“修改已提交记录”权限（C-FU-026 / Q17）");
        }
    }

    /** 功能开关：修改已提交记录单独权限（医院差异走开关，AGENTS §5.7；无行 = 关闭 = A / B 行为）。 */
    static final String FEATURE_RECORD_EDIT_SUBMITTED = "record_edit_submitted";

    /** C 规格权限码：修改已提交记录（敏感，平台库 V72 按钮 229）。 */
    static final String PERM_EDIT_SUBMITTED = "followup:record:edit-submitted";

    // ---------------------------------------------------------------- 内部

    /** 任务上下文（越范围 / 未关联 null——不暴露任务存在）。 */
    private TaskContextView contextOf(long recordId) {
        FollowupTaskQueryMapper.TaskRowRaw row = taskRowByRecordId(recordId);
        if (row == null) {
            return null;
        }
        String name = decrypt(row.nameCipher());
        String mrn = decrypt(row.mrnCipher());
        return new TaskContextView(
                String.valueOf(row.id()),
                String.valueOf(row.patientId()),
                name == null ? "" : SensitiveValueMasker.mask("name", name),
                mrn == null ? "" : SensitiveValueMasker.mask("medicalRecordNo", mrn),
                row.wardName(),
                row.nodeName(),
                row.occurrence(),
                row.taskType(),
                row.planDate(),
                row.windowStart(),
                row.windowEnd(),
                row.status(),
                row.completeKind(),
                row.createType(),
                row.assigneeName(),
                row.nextPlanDate());
    }

    /** 补录 / 计划外记录的患者摘要（表单页头幅；越范围 null——不暴露存在）。 */
    private PatientBriefView patientBriefOf(long patientId) {
        FollowupTaskQueryMapper.PatientBriefRow row = taskQueryMapper.patientBrief(patientId);
        if (row == null) {
            return null;
        }
        String name = decrypt(row.nameCipher());
        String mrn = decrypt(row.mrnCipher());
        return new PatientBriefView(
                String.valueOf(row.id()),
                name == null ? "" : SensitiveValueMasker.mask("name", name),
                mrn == null ? "" : SensitiveValueMasker.mask("medicalRecordNo", mrn),
                row.wardName());
    }

    /** 按记录找任务行（@DataScope 出行：越范围 null；提交联动与上下文共用）。 */
    private FollowupTaskQueryMapper.TaskRowRaw taskRowByRecordId(long recordId) {
        return taskQueryMapper.taskContextByRecordId(recordId);
    }

    /** 「是否死亡 = 是」判定：按模板字段编码 death 定位稳定键（不硬编码键名），值 = 1（yesno）。 */
    private boolean isDeathRecorded(FollowupRecordService.RecordView submitted) {
        FormService.SchemaView schema =
                formService.schema(submitted.formCode(), submitted.templateVersion());
        if (schema == null) {
            return false;
        }
        String deathKey = null;
        for (FormService.CategoryView category : schema.categories()) {
            for (FormService.FieldView field : category.fields()) {
                if (FIELD_CODE_DEATH.equals(field.code())) {
                    deathKey = field.key();
                }
            }
        }
        if (deathKey == null) {
            return false;
        }
        Object value = submitted.data().get(deathKey);
        return value instanceof Number number && number.intValue() == DEATH_YES;
    }

    /**
     * 该次记录的有效随访日期（计划切换联动的 ERI 起算基准，E21-T06）：fu_record.visit_date（补录 / 计划外落列） ?? 表单随访日期字段（模板字段编码
     * followup_date 定位稳定键，与 RecordEntryAssistService 重复提示同口径）?? null（切换服务回落今天）。
     */
    private LocalDate effectiveVisitDate(FollowupRecordService.RecordView record) {
        if (record.visitDate() != null) {
            return record.visitDate();
        }
        FormService.SchemaView schema =
                formService.schema(record.formCode(), record.templateVersion());
        if (schema == null) {
            return null;
        }
        String dateKey = null;
        for (FormService.CategoryView category : schema.categories()) {
            for (FormService.FieldView field : category.fields()) {
                if (RecordEntryAssistService.FIELD_CODE_FOLLOWUP_DATE.equals(field.code())) {
                    dateKey = field.key();
                }
            }
        }
        if (dateKey == null) {
            return null;
        }
        Object value = record.data().get(dateKey);
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }
}
