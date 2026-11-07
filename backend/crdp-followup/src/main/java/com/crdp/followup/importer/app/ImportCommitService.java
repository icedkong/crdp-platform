package com.crdp.followup.importer.app;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportRowPayloads.BaselineRow;
import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupGroup;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupRow;
import com.crdp.followup.importer.app.ImportRowPayloads.WideRow;
import com.crdp.followup.importer.domain.ImportCellValidator;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.ImportWardDirectory.Resolution;
import com.crdp.followup.importer.domain.ImportYearPlanner;
import com.crdp.followup.importer.domain.ImportYearPlanner.YearPlan;
import com.crdp.followup.importer.domain.ImportYearPlanner.YearSchedule;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper.ImportedRecordInsert;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper.PatientRowRef;
import com.crdp.followup.importer.infra.mapper.ImportCommitMapper.WriteInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.StagedRow;
import com.crdp.followup.patient.app.PatientFollowupStatusService;
import com.crdp.followup.patient.app.PatientRegistrationGateway;
import com.crdp.followup.patient.app.PatientRegistrationService;
import com.crdp.followup.patient.app.PatientStatusHistoryService;
import com.crdp.followup.patient.app.PatientSummaryService;
import com.crdp.followup.patient.infra.mapper.BaselineSectionInsert;
import com.crdp.followup.patient.infra.mapper.BaselineSectionMapper;
import com.crdp.followup.patient.infra.mapper.ContactVersionInsert;
import com.crdp.followup.patient.infra.mapper.PatientContactMapper;
import com.crdp.followup.patient.infra.mapper.PatientInsert;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.plan.infra.mapper.PlanMapper;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.followup.task.domain.TaskCreationPlanner.NodeDef;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.form.rule.app.FormDerivedEngine.DerivedOutcome;
import com.crdp.form.rule.app.FormDerivedEngine.DerivedStatus;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E18-T05 / tech-plan §12⑥⑦：导入确认提交与历史随访记录生成（tasks.csv 验收「按年度节点生成历史记录，可按批回滚」）。
 *
 * <p><b>确认门控</b>（{@link #confirm}）：PRECHECKED 状态才可确认（40901）；校验存在 ERROR 问题 → 40001（import-export.md
 * §1「有 ERROR 时 40001」，错误单元格已在向导处理）；重复策略 UPDATE / SKIP 与兜底病区在此时固化（imp_job.options_json，V68 预留列），
 * 操作人数据范围同时冻结（scope_json，V73——与 job_export 同口径）：提交可被 E18-T06 派发到异步线程，「只能导入本人可见病区」（tech-plan
 * §12①）不依赖会话。
 *
 * <p><b>分批写入</b>（tech-plan §12⑥「按每批约 200 名患者一个事务写入，失败批次可单独重试」）：患者行（宽表 PATIENT / 长表 BASELINE）按
 * imp_row.id 连续切批（{@value #PATIENTS_PER_BATCH} 名患者一批），每批一个事务（{@link TransactionTemplate}）；批次失败只标
 * FAILED （fail_reason 非敏感摘要）不影响其他批；{@link #retryFailed} 单独重跑失败批；全部成功 → DONE，有失败批 →
 * PARTIAL_FAILED（状态机 tech-plan §12「导入中 → 部分失败 → 重试失败批次 → 导入中」）。
 *
 * <p><b>历史记录生成</b>（tech-plan §12⑦、A-PAT-018 流程 6）：患者建 / 更新（重复按策略；更新只改动有变化的字段并写
 * fe_field_history，来源=导入）→ 加入 auto_enroll 预置计划（{@link
 * PlanEnrollmentService#enrollForImport}，不建任务——当前窗口的当年任务由每日 定时任务创建，A-PAT-018）→ 年度规划（{@link
 * ImportYearPlanner}）：有数据的年度生成 HIST_IMPORT 记录（直接 SUBMITTED、不受 ±7 天窗口限制、按实际随访日期，A 规格
 * §4）；窗口已结束且无数据的年度生成 HIST_MISSED 记录（DRAFT 待补录，Q06：不计入逾期 / 通知 / 已随访 / 完整度）；窗口未结束的年度不生成。随访记录确认死亡 →
 * 患者状态 DEAD，死亡年度之后的已结束窗口不再标历史未随访。
 *
 * <p><b>按批回滚</b>（{@link #rollbackBatch} / {@link #rollbackJob}）：每批写数登记 imp_batch_write（实体类型 + id +
 * 患者是否本导入新建）；回滚按写入逆序删除——新建患者连主档与状态时间线 / 摘要一起删（等于「这批没导过」，E18-T07
 * 回滚演练的支撑）；更新患者保留主档与既有基线值（fe_field_history 已留痕），只删导入新建的记录 / 联系方式 / 分节 / 入组行（OQ-104）。
 *
 * <p>写入绕过各子域面向录入流程的闸门（必填空仅警告、无窗口限制是历史导入的规格口径，A-PAT-018）；敏感列照常只写密文 + 盲索引；审计按作业级记 confirm / rollback
 * 摘要（行本身即业务记录，不逐患者写，与每日调度同口径）。调用需租户上下文；HTTP 端点随 E18-T06。
 */
@Service
public class ImportCommitService {

    /** 每批患者数（tech-plan §12⑥「每批约 200 名患者一个事务」）。 */
    static final int PATIENTS_PER_BATCH = 200;

    /** 暂存行读取批（与暂存 / 校验同口径，内存只有一批）。 */
    private static final int READ_BATCH = 500;

    private static final Logger log = LoggerFactory.getLogger(ImportCommitService.class);

    /** fu_record.source：历史导入（有数据年度，A-PAT-018）。 */
    public static final String SOURCE_HIST_IMPORT = "HIST_IMPORT";

    /** fu_record.source：历史未随访（窗口已结束且无数据，Q06）。 */
    public static final String SOURCE_HIST_MISSED = "HIST_MISSED";

    /** 重复患者策略（A-PAT-018「更新 / 跳过」）。 */
    public static final String STRATEGY_UPDATE = "UPDATE";

    public static final String STRATEGY_SKIP = "SKIP";

    /** 患者级状态与来源（V37 / V39 枚举；死亡由随访记录确认，A 规格 §5）。 */
    private static final String PATIENT_DEAD = "DEAD";

    private static final String STATUS_SOURCE_SYSTEM = "SYSTEM";

    /** 联系方式加密用途（与 PatientContactService 的 pt-contact-phone 同值；该常量包级私有，此处同口径声明）。 */
    private static final String PURPOSE_PHONE = "pt-contact-phone";

    /** 实体列字段按显示名定位（与病案号 / 病区字段的既有定位约定同源，E18-T03 {@code ImportFieldRuleLoader}）。 */
    private static final String LABEL_NAME = "姓名";

    private static final String LABEL_GENDER = "性别";

    private static final String LABEL_BIRTH = "出生日期";

    private static final String LABEL_SURGERY_DATE = "手术日期";

    private static final String LABEL_ADMISSION_DATE = "入院日期";

    private static final String LABEL_DISCHARGE_DATE = "出院日期";

    private static final String LABEL_DEATH = "是否死亡";

    private static final String LABEL_FOLLOWUP_DATE = "随访日期";

    /** 联系方式槽位字段：显示名「电话N」→ PHONE_N（A 字典 f009 / f010，存联系方式版本表，E13-T03）。 */
    private static final Pattern PHONE_LABEL = Pattern.compile("电话\\s*([0-9]+)");

    /** 电话归一（与 PatientContactService 同口径：去空白 / 连字符 / 括号，5–20 位数字可带 +）。 */
    private static final Pattern PHONE = Pattern.compile("\\+?\\d{5,20}");

    /** Q37「编码 + 文字」的取值形态（判定与 OQ-102 / ImportCellValidator 同一约定）。 */
    private static final Pattern CODE_TEXT = Pattern.compile("^(\\d+)(\\D.*)$");

    /** 年度排期推进上限（防御：起算日期异常早时不无限推进；A 长期年度计划远达不到）。 */
    private static final int MAX_YEARS = 200;

    private final ImportStagingMapper staging;
    private final ImportCommitMapper commit;
    private final ImportFieldRuleLoader ruleLoader;
    private final ImportWardDirectoryLoader wardDirectoryLoader;
    private final CryptoService crypto;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final PlanMapper planMapper;
    private final PlanEnrollmentService enrollments;
    private final PatientRegistrationGateway patients;
    private final PatientMapper patientMapper;
    private final BaselineSectionMapper sectionMapper;
    private final PatientContactMapper contactMapper;
    private final PatientStatusHistoryService statusHistory;
    private final PatientSummaryService summary;
    private final PatientFollowupStatusService patientStatus;
    private final FormService formService;
    private final FormDerivedEngine derivedEngine;
    private final FieldHistoryService fieldHistory;
    private final AuditLogger audit;

    public ImportCommitService(
            ImportStagingMapper staging,
            ImportCommitMapper commit,
            ImportFieldRuleLoader ruleLoader,
            ImportWardDirectoryLoader wardDirectoryLoader,
            CryptoService crypto,
            ObjectMapper json,
            TransactionTemplate transactions,
            PlanMapper planMapper,
            PlanEnrollmentService enrollments,
            PatientRegistrationGateway patients,
            PatientMapper patientMapper,
            BaselineSectionMapper sectionMapper,
            PatientContactMapper contactMapper,
            PatientStatusHistoryService statusHistory,
            PatientSummaryService summary,
            PatientFollowupStatusService patientStatus,
            FormService formService,
            FormDerivedEngine derivedEngine,
            FieldHistoryService fieldHistory,
            AuditLogger audit) {
        this.staging = staging;
        this.commit = commit;
        this.ruleLoader = ruleLoader;
        this.wardDirectoryLoader = wardDirectoryLoader;
        this.crypto = crypto;
        this.json = json;
        this.transactions = transactions;
        this.planMapper = planMapper;
        this.enrollments = enrollments;
        this.patients = patients;
        this.patientMapper = patientMapper;
        this.sectionMapper = sectionMapper;
        this.contactMapper = contactMapper;
        this.statusHistory = statusHistory;
        this.summary = summary;
        this.patientStatus = patientStatus;
        this.formService = formService;
        this.derivedEngine = derivedEngine;
        this.fieldHistory = fieldHistory;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 确认选项（import-export.md §1 confirm：重复策略 + 兜底病区——病区空单元格行的落区，E18-T04 遗留接线点）。 */
    public record ConfirmOptions(String duplicateStrategy, Long defaultWardId) {}

    /** 提交摘要（向导结果与进度展示；failedBatches 供「失败批次可单独重试」入口）。 */
    public record CommitSummary(
            long jobId,
            String status,
            int batches,
            int createdPatients,
            int updatedPatients,
            int skippedPatients,
            int recordsCreated,
            List<Integer> failedBatches) {}

    /** 批次视图（向导按批展示进度与回滚入口，E18-T06）。 */
    public record BatchView(
            int batchNo,
            String status,
            int patientCount,
            int createdPatients,
            int updatedPatients,
            int skippedPatients,
            int recordsCreated,
            String failReason) {}

    /** 回滚结果（按写入类型计数；deletedPatients 只含本导入新建的患者，OQ-104）。 */
    public record RollbackView(
            long jobId,
            int batchNo,
            int deletedRecords,
            int deletedContacts,
            int deletedEnrollments,
            int deletedSections,
            int deletedPatients) {}

    // ---------------------------------------------------------------- 确认提交

    /**
     * 确认并执行导入（prepare + execute 两段：E18-T06 异步派发时可分别调用——prepare 在会话内冻结选项与范围，execute 无会话依赖）。
     *
     * <p>门控：非 PRECHECKED → 40901；存在 ERROR 问题 → 40001（A-PAT-018「错误行不可导入」，处理错误单元格后重新预检）。
     */
    public CommitSummary confirm(long jobId, ConfirmOptions options) {
        prepare(jobId, options);
        return execute(jobId);
    }

    /** 固化选项与范围快照、切批、置 IMPORTING（会话内调用：范围快照取当前登录人；无会话按空范围，见 {@link #frozenScope}）。 */
    void prepare(long jobId, ConfirmOptions options) {
        JobRow job = requireJob(jobId);
        if (!"PRECHECKED".equals(job.status())) {
            throw new ApiException(409, 40901, "导入任务不在预检完成状态（当前 " + job.status() + "），不能确认导入");
        }
        String strategy = options == null ? null : options.duplicateStrategy();
        if (!STRATEGY_UPDATE.equals(strategy) && !STRATEGY_SKIP.equals(strategy)) {
            throw new ApiException(400, 40001, "重复患者处理方式只支持 UPDATE（更新）或 SKIP（跳过）");
        }
        ImportWardDirectory wardDirectory = wardDirectoryLoader.load();
        Long defaultWard = options == null ? null : options.defaultWardId();
        if (defaultWard != null && enabledWard(wardDirectory, defaultWard) == null) {
            throw new ApiException(400, 40001, "兜底病区不存在或已停用，请重新选择");
        }
        long errors = staging.countIssues(jobId, "ERROR");
        if (errors > 0) {
            throw new ApiException(
                    400, 40001, "校验报告存在 " + errors + " 个错误问题，处理后再确认导入（A-PAT-018：错误行不可导入）");
        }
        commit.saveCommitOptions(
                jobId,
                writeJson(
                        Map.of(
                                "duplicateStrategy",
                                strategy,
                                "defaultWardId",
                                defaultWard == null ? "" : String.valueOf(defaultWard))),
                writeJson(frozenScope()));
        if (staging.casStatus(jobId, "PRECHECKED", "IMPORTING") == 0) {
            throw new ApiException(409, 40901, "导入任务已被其他提交占用");
        }
        List<PatientRowRef> refs = commit.findPatientRowRefs(jobId);
        for (int start = 0; start < refs.size(); start += PATIENTS_PER_BATCH) {
            List<PatientRowRef> chunk =
                    refs.subList(start, Math.min(start + PATIENTS_PER_BATCH, refs.size()));
            commit.insertBatch(
                    new ImportCommitMapper.BatchInsert(
                            jobId,
                            start / PATIENTS_PER_BATCH + 1,
                            chunk.get(0).id(),
                            chunk.get(chunk.size() - 1).id(),
                            chunk.size()));
        }
    }

    /** 执行全部待跑批次（PENDING 首跑 / FAILED 重试同入口；无会话依赖，范围用冻结快照）。 */
    CommitSummary execute(long jobId) {
        JobRow job = requireJob(jobId);
        if (!"IMPORTING".equals(job.status())) {
            throw new ApiException(409, 40901, "导入任务不在导入中状态（当前 " + job.status() + "）");
        }
        for (ImportCommitMapper.BatchRow batch : commit.findBatches(jobId)) {
            if ("PENDING".equals(batch.status()) || "FAILED".equals(batch.status())) {
                runBatch(job, batch);
            }
        }
        return finish(jobId);
    }

    /** 重试失败批次（PARTIAL_FAILED → IMPORTING → 只重跑 FAILED 批，tech-plan §12 状态机）。 */
    public CommitSummary retryFailed(long jobId) {
        JobRow job = requireJob(jobId);
        if (!"PARTIAL_FAILED".equals(job.status())) {
            throw new ApiException(409, 40901, "导入任务没有可重试的失败批次（当前 " + job.status() + "）");
        }
        if (staging.casStatus(jobId, "PARTIAL_FAILED", "IMPORTING") == 0) {
            throw new ApiException(409, 40901, "导入任务状态已变化，请刷新后重试");
        }
        return execute(jobId);
    }

    /** 批次视图（向导进度，E18-T06）。 */
    public List<BatchView> batches(long jobId) {
        requireJob(jobId);
        return commit.findBatches(jobId).stream()
                .map(
                        batch ->
                                new BatchView(
                                        batch.batchNo(),
                                        batch.status(),
                                        batch.patientCount(),
                                        batch.createdPatients(),
                                        batch.updatedPatients(),
                                        batch.skippedPatients(),
                                        batch.recordsCreated(),
                                        batch.failReason()))
                .toList();
    }

    /** 汇总批次计数并落终态（全 DONE → DONE，否则 PARTIAL_FAILED），写作业级审计。 */
    private CommitSummary finish(long jobId) {
        List<ImportCommitMapper.BatchRow> batches = commit.findBatches(jobId);
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int records = 0;
        List<Integer> failed = new ArrayList<>();
        for (ImportCommitMapper.BatchRow batch : batches) {
            created += batch.createdPatients();
            updated += batch.updatedPatients();
            skipped += batch.skippedPatients();
            records += batch.recordsCreated();
            if ("FAILED".equals(batch.status())) {
                failed.add(batch.batchNo());
            }
        }
        String finalStatus = failed.isEmpty() ? "DONE" : "PARTIAL_FAILED";
        staging.casStatus(jobId, "IMPORTING", finalStatus);
        audit.append(
                AuditEntry.operation("followup", "IMPORT_COMMIT")
                        .objectType("IMPORT_JOB")
                        .objectId(String.valueOf(jobId))
                        .description(
                                "导入提交完成：批次 "
                                        + batches.size()
                                        + "，新建患者 "
                                        + created
                                        + "，更新 "
                                        + updated
                                        + "，跳过 "
                                        + skipped
                                        + "，生成随访记录 "
                                        + records
                                        + (failed.isEmpty() ? "" : "，失败批次 " + failed))
                        .afterJson(
                                "{\"status\":\""
                                        + finalStatus
                                        + "\",\"batches\":"
                                        + batches.size()
                                        + ",\"created\":"
                                        + created
                                        + ",\"updated\":"
                                        + updated
                                        + ",\"skipped\":"
                                        + skipped
                                        + ",\"records\":"
                                        + records
                                        + "}")
                        .result(failed.isEmpty() ? AuditResult.SUCCESS : AuditResult.FAIL)
                        .build());
        return new CommitSummary(
                jobId, finalStatus, batches.size(), created, updated, skipped, records, failed);
    }

    // ---------------------------------------------------------------- 单批执行（一个事务）

    /** 跑一批：RUNNING 标记在批事务外（批事务回滚后仍能落 FAILED），数据写入与 DONE 计数同事务原子 ——「每批一个事务，失败批次可单独重试」。 */
    private void runBatch(JobRow job, ImportCommitMapper.BatchRow batch) {
        if (commit.markBatchRunning(batch.id()) == 0) {
            log.warn("批次状态已变化，跳过 jobId={} batchNo={}", job.id(), batch.batchNo());
            return;
        }
        try {
            BatchCounts counts = transactions.execute(context -> processBatch(job, batch));
            if (counts == null) {
                throw new IllegalStateException("批次事务未执行（jobId=" + job.id() + "）");
            }
        } catch (RuntimeException failure) {
            // 带栈输出（E18-T07 压测演练发现：无消息异常只落「原因=」空串，向导与排障都看不到根因）
            log.warn(
                    "导入批次失败，可单独重试 jobId={} batchNo={} 原因={}",
                    job.id(),
                    batch.batchNo(),
                    failure.getMessage(),
                    failure);
            String reason =
                    failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage();
            commit.markBatchFailed(batch.id(), truncate(reason));
        }
    }

    /** 批内处理：逐患者落库 + 写数登记 + 批次 DONE（同事务；行级跳过不失败整批——历史导入「错误行跳过」口径）。 */
    private BatchCounts processBatch(JobRow job, ImportCommitMapper.BatchRow batch) {
        ImportContext context = new ImportContext(job);
        BatchCounts counts = new BatchCounts();
        long afterId = batch.firstPatientRowId() - 1;
        while (afterId < batch.lastPatientRowId()) {
            List<StagedRow> rows =
                    commit.findPatientRows(job.id(), afterId, batch.lastPatientRowId(), READ_BATCH);
            if (rows.isEmpty()) {
                break;
            }
            Map<String, List<FollowupRow>> followupsByBlind = followupsOf(job.id(), rows);
            for (StagedRow row : rows) {
                afterId = row.id();
                processPatient(
                        job, batch, context, row, followupsByBlind.get(row.mrnBlind()), counts);
            }
            if (rows.size() < READ_BATCH) {
                break;
            }
        }
        if (!counts.writes.isEmpty()) {
            commit.insertWrites(counts.writes); // 空清单不落库（foreach 空集合无 SQL）
        }
        commit.markBatchDone(
                batch.id(), counts.created, counts.updated, counts.skipped, counts.records);
        return counts;
    }

    /** 长表随访行按本批患者行的盲索引取（keyset 分页，内存只有一批）。 */
    private Map<String, List<FollowupRow>> followupsOf(long jobId, List<StagedRow> patientRows) {
        List<String> blinds = patientRows.stream().map(StagedRow::mrnBlind).distinct().toList();
        Map<String, List<FollowupRow>> result = new HashMap<>();
        if (blinds.isEmpty()) {
            return result;
        }
        long afterId = 0;
        while (true) {
            List<StagedRow> batch = commit.findFollowupRows(jobId, blinds, afterId, READ_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (StagedRow row : batch) {
                FollowupRow payload = (FollowupRow) decrypt(row, FollowupRow.class);
                result.computeIfAbsent(row.mrnBlind(), key -> new ArrayList<>()).add(payload);
                afterId = row.id();
            }
            if (batch.size() < READ_BATCH) {
                break;
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 单患者

    /** 一个患者的提交：跳过 / 新建 / 更新 + 基线 + 联系方式 + 入组 + 年度记录。 */
    private void processPatient(
            JobRow job,
            ImportCommitMapper.BatchRow batch,
            ImportContext context,
            StagedRow row,
            List<FollowupRow> longFollowups,
            BatchCounts counts) {
        Object payload =
                decrypt(row, "PATIENT".equals(row.rowType()) ? WideRow.class : BaselineRow.class);
        Map<String, CellValue> baselineCells =
                payload instanceof WideRow wide
                        ? wide.baseline()
                        : ((BaselineRow) payload).baseline();
        String mrn = mrnOf(payload);
        if (mrn == null || row.mrnBlind() == null) {
            counts.skipped("病案号为空（预检门控遗漏，防御跳过）");
            return;
        }
        TypedValues baseline = typedValues(context, baselineCells, context.rules.baselineByKey());
        String name = nameOf(context, baseline);
        if (name == null) {
            counts.skipped("姓名为空（主档必填，历史导入无法建档）");
            return;
        }
        String gender = genderOf(context, baseline);
        if (gender == null) {
            counts.skipped("性别为空或无法识别（主档必填，历史导入无法建档）");
            return;
        }
        WardResolution ward = resolveWard(context, baselineCells);
        if (ward.wardId() == null) {
            counts.skipped(ward.reason());
            return;
        }
        if (!context.wardInScope(ward.wardId())) {
            counts.skipped("病区不在操作人数据范围内（只能导入本人可见病区）");
            return;
        }
        Map<Integer, FollowupValues> followups =
                followupValues(
                        context,
                        payload instanceof WideRow wide ? wide.followups() : List.of(),
                        longFollowups);
        boolean dead = deadOf(context, followups);
        EntityColumns entity =
                new EntityColumns(
                        gender,
                        dateOf(context, baseline, LABEL_BIRTH),
                        ward.wardId(),
                        dateOf(context, baseline, LABEL_SURGERY_DATE),
                        dateOf(context, baseline, LABEL_ADMISSION_DATE),
                        dateOf(context, baseline, LABEL_DISCHARGE_DATE),
                        payload instanceof WideRow wide
                                ? wide.legacyNo()
                                : ((BaselineRow) payload).legacyNo());

        Long existing = patients.liveIdByMrnBlind(row.mrnBlind());
        if (existing != null && STRATEGY_SKIP.equals(context.strategy)) {
            counts.skipped("病案号已存在，按「跳过」策略处理（A-PAT-018）");
            return;
        }
        long patientId;
        boolean created = existing == null;
        if (created) {
            patientId = insertPatient(job, batch, entity, name, row.mrnBlind(), mrn, counts);
            statusHistory.recordRegistration(patientId, entity.dischargeDate(), job.operatorId());
            summary.recompute(patientId, job.operatorId());
            writeContacts(job, batch, context, patientId, baseline, true, counts);
            writeBaseline(job, batch, context, patientId, baseline, true, counts);
        } else {
            patientId = existing;
            ImportCommitMapper.PatientEntityRow current = commit.findPatientEntity(patientId);
            if (current == null) {
                counts.skipped("患者不存在或已删除（防御跳过）");
                return;
            }
            if (!context.wardInScope(current.wardId() == null ? -1 : current.wardId())) {
                counts.skipped("患者当前病区不在操作人数据范围内（越范围不更新）");
                return;
            }
            updatePatient(job, patientId, entity, current);
            writeContacts(job, batch, context, patientId, baseline, false, counts);
            writeBaseline(job, batch, context, patientId, baseline, false, counts);
            if (PATIENT_DEAD.equals(current.followupStatus())) {
                // 已死亡患者不重新入组（A 规格 §5）；年度记录照常按导入数据更新，死亡后的已结束窗口不标历史未随访
                counts.patientCreated = false;
                writeRecords(
                        job, batch.id(), context, patientId, null, entity, followups, counts, true);
                refreshBaselineCompleteness(job, context, patientId);
                counts.updated++;
                return;
            }
        }
        LocalDate startDate = context.startDateOf(entity);
        Long enrollmentId =
                context.planId == null
                        ? null
                        : enrollments.enrollForImport(
                                patientId, context.planId, startDate, job.operatorId());
        if (enrollmentId != null) {
            counts.writes.add(
                    new WriteInsert(batch.id(), job.id(), "ENROLLMENT", enrollmentId, created));
        }
        counts.patientCreated = created;
        writeRecords(
                job, batch.id(), context, patientId, enrollmentId, entity, followups, counts, dead);
        refreshBaselineCompleteness(job, context, patientId);
        if (dead) {
            patientStatus.transition(
                    patientId,
                    PATIENT_DEAD,
                    "历史导入：随访记录确认死亡",
                    STATUS_SOURCE_SYSTEM,
                    "ACTIVE",
                    job.operatorId());
        }
        if (created) {
            counts.created++;
        } else {
            counts.updated++;
        }
    }

    /** 新建患者主档（敏感列只写密文 + 盲索引；V37 实体列，日期缺失留空——历史导入必填空仅警告口径）。 */
    private long insertPatient(
            JobRow job,
            ImportCommitMapper.BatchRow batch,
            EntityColumns entity,
            String name,
            String mrnBlind,
            String mrn,
            BatchCounts counts) {
        PatientInsert insert = new PatientInsert();
        insert.setMrnCipher(crypto.encrypt(PatientRegistrationService.PURPOSE_MRN, mrn));
        insert.setMrnBlind(mrnBlind);
        insert.setNameCipher(crypto.encrypt(PatientRegistrationService.PURPOSE_NAME, name));
        insert.setNameBlind(crypto.blindIndex(PatientRegistrationService.PURPOSE_NAME, name));
        insert.setGender(entity.gender());
        insert.setBirthDate(entity.birthDate());
        insert.setWardId(entity.wardId());
        insert.setSurgeryDate(entity.surgeryDate());
        insert.setAdmissionDate(entity.admissionDate());
        insert.setDischargeDate(entity.dischargeDate());
        insert.setLegacyNo(entity.legacyNo());
        insert.setCreatedBy(job.operatorId());
        patientMapper.insertPatient(insert);
        counts.writes.add(new WriteInsert(batch.id(), job.id(), "PATIENT", insert.getId(), true));
        return insert.getId();
    }

    /** 重复患者实体列更新：只改有值且不同的列（OQ-104：空单元格不清既有值；无变化不空转 version）。 */
    private void updatePatient(
            JobRow job,
            long patientId,
            EntityColumns entity,
            ImportCommitMapper.PatientEntityRow current) {
        String gender =
                entity.gender() != null && !entity.gender().equals(current.gender())
                        ? entity.gender()
                        : null;
        Long wardId =
                entity.wardId() != null && entity.wardId() != current.wardId()
                        ? entity.wardId()
                        : null;
        LocalDate birthDate = changedDate(entity.birthDate(), current.birthDate());
        LocalDate surgeryDate = changedDate(entity.surgeryDate(), current.surgeryDate());
        LocalDate admissionDate = changedDate(entity.admissionDate(), current.admissionDate());
        LocalDate dischargeDate = changedDate(entity.dischargeDate(), current.dischargeDate());
        String legacyNo =
                entity.legacyNo() != null && !entity.legacyNo().equals(current.legacyNo())
                        ? entity.legacyNo()
                        : null;
        if (gender == null
                && wardId == null
                && birthDate == null
                && surgeryDate == null
                && admissionDate == null
                && dischargeDate == null
                && legacyNo == null) {
            return;
        }
        commit.updatePatientFromImport(
                patientId,
                gender,
                birthDate,
                wardId,
                surgeryDate,
                admissionDate,
                dischargeDate,
                legacyNo,
                job.operatorId());
    }

    // ---------------------------------------------------------------- 年度记录

    /** 年度记录生成（tech-plan §12⑦；dead 患者不再为死亡年度之后的已结束窗口标历史未随访）。 */
    private void writeRecords(
            JobRow job,
            long batchId,
            ImportContext context,
            long patientId,
            Long enrollmentId,
            EntityColumns entity,
            Map<Integer, FollowupValues> followups,
            BatchCounts counts,
            boolean dead) {
        Set<Integer> dataYears = new HashSet<>(followups.keySet());
        List<YearSchedule> schedules = schedulesOf(context, entity, dataYears);
        List<YearPlan> plans = ImportYearPlanner.plan(schedules, dataYears, context.today);
        if (dead) {
            int lastDataYear = dataYears.stream().max(Integer::compareTo).orElse(0);
            plans.removeIf(plan -> !plan.hasData() && plan.occurrence() > lastDataYear);
        }
        for (YearPlan plan : plans) {
            writeYearRecord(
                    job,
                    batchId,
                    context,
                    patientId,
                    enrollmentId,
                    plan,
                    followups.get(plan.occurrence()),
                    counts);
        }
        for (Integer year : ImportYearPlanner.unscheduledDataYears(schedules, dataYears)) {
            writeYearRecord(
                    job,
                    batchId,
                    context,
                    patientId,
                    enrollmentId,
                    new YearPlan(year, true, null, null, null),
                    followups.get(year),
                    counts);
        }
    }

    /** 单年度记录：新建或更新（重复导入；历史未随访年度补了数据时升级为 HIST_IMPORT + SUBMITTED，Q06 可补录）。 */
    private void writeYearRecord(
            JobRow job,
            long batchId,
            ImportContext context,
            long patientId,
            Long enrollmentId,
            YearPlan plan,
            FollowupValues values,
            BatchCounts counts) {
        boolean hasData = plan.hasData() && values != null && !values.values().isEmpty();
        String source = hasData ? SOURCE_HIST_IMPORT : SOURCE_HIST_MISSED;
        String status = hasData ? "SUBMITTED" : "DRAFT";
        LocalDate visitDate = hasData ? values.visitDate() : null;
        String data = hasData ? writeJson(values.values()) : "{}";
        String dataMeta = hasData ? writeJson(values.meta()) : "{}";
        ImportCommitMapper.ImportedRecordRow existing =
                commit.findImportedYearRecord(patientId, plan.occurrence());
        if (existing == null) {
            ImportedRecordInsert insert =
                    new ImportedRecordInsert(
                            patientId,
                            enrollmentId,
                            context.anchorNodeId,
                            plan.occurrence(),
                            context.yearlyNode ? plan.occurrence() : null,
                            plan.planDate(),
                            plan.windowStart(),
                            plan.windowEnd(),
                            source,
                            visitDate,
                            context.followupFormCode,
                            context.followupFormVersion,
                            status,
                            data,
                            dataMeta,
                            job.operatorId());
            commit.insertImportedRecord(insert);
            counts.writes.add(
                    new WriteInsert(
                            batchId, job.id(), "FU_RECORD", insert.getId(), counts.patientCreated));
            counts.records++;
            return;
        }
        // 重复导入更新：只改有变化的字段并留字段历史（来源=导入，A-PAT-018 / tech-plan §12⑥）
        Map<String, Object> before = readJson(existing.data());
        commit.updateImportedRecord(
                existing.id(), source, status, visitDate, data, dataMeta, job.operatorId());
        if (hasData && !before.equals(readJson(data))) {
            fieldHistory.logChanges(
                    FieldHistoryService.FU_RECORD,
                    existing.id(),
                    context.followupFormCode,
                    context.followupFormVersion,
                    before,
                    readJson(data),
                    ChangeSource.IMPORT,
                    "历史导入更新（A-PAT-018：更新只改动有变化的字段并留痕）");
        }
        counts.records++;
    }

    // ---------------------------------------------------------------- 基线分节与联系方式

    /** 基线 JSON 字段按分类写 pt_baseline_section（新建：正式保存口径 version 1；更新：合并留字段历史）。 */
    private void writeBaseline(
            JobRow job,
            ImportCommitMapper.BatchRow batch,
            ImportContext context,
            long patientId,
            TypedValues baseline,
            boolean created,
            BatchCounts counts) {
        Map<String, Map<String, Object>> byCategory = new LinkedHashMap<>();
        baseline.values.forEach(
                (key, value) -> {
                    ImportFieldRule rule = context.rules.baselineByKey().get(key);
                    if (rule == null || !"json".equals(rule.storage()) || value == null) {
                        return;
                    }
                    byCategory
                            .computeIfAbsent(
                                    rule.category() == null ? "default" : rule.category(),
                                    category -> new LinkedHashMap<>())
                            .put(key, value);
                });
        Map<String, ImportCommitMapper.SectionRow> existingSections = new LinkedHashMap<>();
        if (!created) {
            commit.findSections(patientId)
                    .forEach(section -> existingSections.put(section.categoryKey(), section));
        }
        for (Map.Entry<String, Map<String, Object>> section : byCategory.entrySet()) {
            String data = writeJson(section.getValue());
            String meta = writeJson(metaOf(baseline.meta, section.getValue()));
            ImportCommitMapper.SectionRow row = existingSections.get(section.getKey());
            if (row == null) {
                BaselineSectionInsert insert =
                        new BaselineSectionInsert(
                                patientId,
                                context.rules.baselineFormCode(),
                                section.getKey(),
                                context.baselineFormVersion,
                                data,
                                meta,
                                1,
                                false,
                                job.operatorId());
                sectionMapper.insertSection(insert);
                counts.writes.add(
                        new WriteInsert(
                                batch.id(), job.id(), "BASELINE_SECTION", insert.getId(), created));
                continue;
            }
            Map<String, Object> before = readJson(row.data());
            Map<String, Object> after = new LinkedHashMap<>(before);
            after.putAll(section.getValue());
            if (before.equals(after)) {
                continue; // 无变化不空转（A-PAT-018「只改动有变化的字段」）
            }
            commit.updateSectionData(row.id(), writeJson(after), meta, job.operatorId());
            fieldHistory.logChanges(
                    FieldHistoryService.PT_BASELINE_SECTION,
                    row.id(),
                    context.rules.baselineFormCode(),
                    row.templateVersion(),
                    before,
                    after,
                    ChangeSource.IMPORT,
                    "历史导入更新（A-PAT-018：更新只改动有变化的字段并留痕）");
        }
    }

    /** 字段元数据裁剪：只保留本节字段的 note / manual（components.md §10.2 data_meta 形态）。 */
    private static Map<String, Object> metaOf(
            Map<String, Object> meta, Map<String, Object> sectionValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        sectionValues
                .keySet()
                .forEach(
                        key -> {
                            Object value = meta.get(key);
                            if (value != null) {
                                result.put(key, value);
                            }
                        });
        return result;
    }

    /** 联系方式：基线「电话N」加密字段 → 版本表（source=IMPORT；与当前版本相同不新增版本，格式非法跳过不阻断）。 */
    private void writeContacts(
            JobRow job,
            ImportCommitMapper.BatchRow batch,
            ImportContext context,
            long patientId,
            TypedValues baseline,
            boolean created,
            BatchCounts counts) {
        baseline.values.forEach(
                (key, value) -> {
                    ImportFieldRule rule = context.rules.baselineByKey().get(key);
                    if (rule == null
                            || !"encrypted".equals(rule.storage())
                            || !(value instanceof String phone)) {
                        return;
                    }
                    Matcher label = PHONE_LABEL.matcher(rule.label());
                    if (!label.find()) {
                        return;
                    }
                    String normalized = phone.trim().replaceAll("[\\s\\-()]", "");
                    if (!PHONE.matcher(normalized).matches()) {
                        return;
                    }
                    ImportCommitMapper.ContactRow current =
                            commit.findCurrentContact(patientId, "PHONE_" + label.group(1));
                    if (current != null
                            && normalized.equals(crypto.decrypt(current.phoneCipher()))) {
                        return;
                    }
                    ContactVersionInsert insert = new ContactVersionInsert();
                    insert.setPatientId(patientId);
                    insert.setSlot("PHONE_" + label.group(1));
                    insert.setPhoneCipher(crypto.encrypt(PURPOSE_PHONE, normalized));
                    insert.setPhoneBlind(crypto.blindIndex(PURPOSE_PHONE, normalized));
                    // 生效日期默认导入当天（真库 NOT NULL，E18-T07 压测演练发现；与
                    // PatientContactService.addVersion 的缺省同口径，且不早于任何既有版本——服务侧禁止未来日期）
                    insert.setEffectiveDate(context.today);
                    insert.setSource("IMPORT");
                    insert.setVersion(current == null ? 1 : current.version() + 1);
                    insert.setCreatedBy(job.operatorId());
                    contactMapper.insertVersion(insert);
                    counts.writes.add(
                            new WriteInsert(
                                    batch.id(), job.id(), "CONTACT", insert.getId(), created));
                });
    }

    /** 基线资料完整度重算（A-PAT-019「由系统重新计算」，登记 / 保存同口径：已填必填 ÷ 应填必填）。 */
    private void refreshBaselineCompleteness(JobRow job, ImportContext context, long patientId) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (ImportCommitMapper.SectionRow row : commit.findSections(patientId)) {
            merged.putAll(readJson(row.data()));
        }
        int filled = 0;
        int total = 0;
        for (com.crdp.form.rule.app.FormProgressEngine.CategoryProgress category :
                formService
                        .progress(
                                context.rules.baselineFormCode(),
                                context.baselineFormVersion,
                                merged,
                                Map.of())
                        .categories()) {
            filled += category.filled();
            total += category.total();
        }
        summary.updateBaselineCompleteness(
                patientId,
                total == 0 ? 100 : (int) Math.round(filled * 100.0 / total),
                job.operatorId());
    }

    // ---------------------------------------------------------------- 回滚（按批）

    /** 回滚一批（只允许已完成批次；失败批次无写数，直接重试即可）。 */
    public RollbackView rollbackBatch(long jobId, int batchNo) {
        requireJob(jobId);
        ImportCommitMapper.BatchRow batch = commit.findBatch(jobId, batchNo);
        if (batch == null) {
            throw new ApiException(404, 40400, "导入批次不存在");
        }
        if (!"DONE".equals(batch.status())) {
            throw new ApiException(
                    409, 40901, "只有已完成批次可以回滚（当前 " + batch.status() + "；失败批次无写数可直接重试）");
        }
        RollbackView view = transactions.execute(context -> rollbackWrites(jobId, batch));
        audit.append(
                AuditEntry.operation("followup", "IMPORT_ROLLBACK")
                        .objectType("IMPORT_JOB")
                        .objectId(String.valueOf(jobId))
                        .description(
                                "导入批次回滚：批次 "
                                        + batchNo
                                        + "，删除记录 "
                                        + view.deletedRecords()
                                        + "、联系方式 "
                                        + view.deletedContacts()
                                        + "、入组 "
                                        + view.deletedEnrollments()
                                        + "、基线分节 "
                                        + view.deletedSections()
                                        + "、新建患者 "
                                        + view.deletedPatients())
                        .result(AuditResult.SUCCESS)
                        .build());
        return view;
    }

    /** 回滚整个作业（全部已完成批次逐批回滚；E18-T07 回滚演练入口；全部批次回滚后作业置 ROLLED_BACK）。 */
    public List<RollbackView> rollbackJob(long jobId) {
        requireJob(jobId);
        List<RollbackView> views = new ArrayList<>();
        for (ImportCommitMapper.BatchRow batch : commit.findBatches(jobId)) {
            if ("DONE".equals(batch.status())) {
                views.add(rollbackBatch(jobId, batch.batchNo()));
            }
        }
        if (commit.findBatches(jobId).stream()
                .allMatch(batch -> "ROLLED_BACK".equals(batch.status()))) {
            staging.casStatus(jobId, "DONE", "ROLLED_BACK");
            staging.casStatus(jobId, "PARTIAL_FAILED", "ROLLED_BACK");
        }
        return views;
    }

    /** 按写入逆序删除（事务内）：记录 → 联系方式 → 入组 → 分节 → 新建患者（连状态时间线 / 摘要一起删）。 */
    private RollbackView rollbackWrites(long jobId, ImportCommitMapper.BatchRow batch) {
        List<ImportCommitMapper.WriteRow> writes = commit.findWrites(batch.id());
        int records = 0;
        int contacts = 0;
        int enrollments = 0;
        int sections = 0;
        int patients = 0;
        for (int i = writes.size() - 1; i >= 0; i--) {
            ImportCommitMapper.WriteRow write = writes.get(i);
            switch (write.entityType()) {
                case "FU_RECORD" -> {
                    commit.deleteImportedRecord(write.entityId());
                    records++;
                }
                case "CONTACT" -> {
                    commit.deleteImportedContact(write.entityId());
                    contacts++;
                }
                case "ENROLLMENT" -> {
                    commit.deleteEnrollment(write.entityId());
                    enrollments++;
                }
                case "BASELINE_SECTION" -> {
                    commit.deleteBaselineSection(write.entityId());
                    sections++;
                }
                case "PATIENT" -> {
                    if (!write.createdFlag()) {
                        continue; // 更新患者：主档保留（OQ-104）
                    }
                    commit.deleteStatusHistoryOf(write.entityId());
                    commit.deleteSummaryOf(write.entityId());
                    commit.deleteCreatedPatient(write.entityId());
                    patients++;
                }
                default -> log.warn("未知导入写数类型，跳过 jobId={} type={}", jobId, write.entityType());
            }
        }
        commit.deleteWrites(batch.id());
        commit.markBatchRolledBack(batch.id());
        return new RollbackView(
                jobId, batch.batchNo(), records, contacts, enrollments, sections, patients);
    }

    // ---------------------------------------------------------------- 执行上下文

    /** 一次批处理的常量上下文（规则 / 病区字典 / 冻结范围 / 计划锚点 / 表单版本；每批加载一次，批内复用）。 */
    private final class ImportContext {

        private final ImportFieldRules rules;
        private final ImportWardDirectory wardDirectory;
        private final boolean scopeAll;
        private final Set<Long> scopeWardIds;
        private final String strategy;
        private final Long defaultWardId;
        private final Long planId;
        private final List<NodeDef> nodes;
        private final Long anchorNodeId;
        private final boolean yearlyNode;
        private final String followupFormCode;
        private final int followupFormVersion;
        private final int baselineFormVersion;
        private final LocalDate today;
        private final String planStartDateField;

        private ImportContext(JobRow job) {
            this.rules = ruleLoader.load();
            this.wardDirectory = wardDirectoryLoader.load();
            Map<String, Object> options = readJson(job.optionsJson());
            this.strategy =
                    String.valueOf(options.getOrDefault("duplicateStrategy", STRATEGY_SKIP));
            Object ward = options.get("defaultWardId");
            this.defaultWardId =
                    ward == null || String.valueOf(ward).isBlank()
                            ? null
                            : Long.valueOf(String.valueOf(ward));
            Map<String, Object> scope = readJson(job.scopeJson());
            this.scopeAll = Boolean.TRUE.equals(scope.get("all"));
            @SuppressWarnings("unchecked")
            List<Number> wardIds = (List<Number>) scope.getOrDefault("wardIds", List.of());
            this.scopeWardIds = new HashSet<>(wardIds.stream().map(Number::longValue).toList());
            PlanMapper.PlanRow plan = firstAutoEnrollPlan();
            this.planId = plan == null ? null : plan.id();
            this.planStartDateField =
                    plan == null || plan.startDateField() == null ? "" : plan.startDateField();
            List<PlanMapper.NodeRow> rows =
                    plan == null ? List.of() : planMapper.findNodesByPlanId(plan.id());
            this.nodes = FollowupTaskCreationService.nodeDefs(rows);
            long[] anchor = anchorNodeOf(rows);
            this.anchorNodeId = anchor == null ? null : anchor[0];
            this.yearlyNode = anchor != null && anchor[1] == 1;
            this.followupFormCode = rules.followupFormCode();
            this.followupFormVersion = formService.publishedVersion(followupFormCode);
            this.baselineFormVersion = formService.publishedVersion(rules.baselineFormCode());
            this.today = PlanDateCalculator.businessToday(Instant.now());
        }

        private boolean wardInScope(long wardId) {
            return scopeAll || scopeWardIds.contains(wardId);
        }

        /** 起算日期（A 计划出院日期起算；SURGERY_DATE 同理；其余起算字段随对应医院接入解析，历史导入先不排期只按年度记）。 */
        private LocalDate startDateOf(EntityColumns entity) {
            return switch (planStartDateField) {
                case "DISCHARGE_DATE" -> entity.dischargeDate();
                case "SURGERY_DATE" -> entity.surgeryDate();
                default -> null;
            };
        }

        private PlanMapper.PlanRow firstAutoEnrollPlan() {
            List<PlanMapper.PlanRow> plans = planMapper.findAutoEnrollPlans();
            return plans.isEmpty() ? null : plans.get(0);
        }

        /** 年度锚点节点：年度循环（CYCLE、周期单位 YEAR）子计划下的首个 FORM 节点；无年度子计划返回 null（记录只按年度、不带排期锚点）。 */
        private static long[] anchorNodeOf(List<PlanMapper.NodeRow> rows) {
            Long yearlySubplan = null;
            for (PlanMapper.NodeRow row : rows) {
                if ("SUBPLAN".equals(row.nodeType())
                        && "CYCLE"
                                .equalsIgnoreCase(row.repeatMode() == null ? "" : row.repeatMode())
                        && "YEAR"
                                .equalsIgnoreCase(row.cycleUnit() == null ? "" : row.cycleUnit())) {
                    yearlySubplan = row.id();
                    break;
                }
            }
            if (yearlySubplan == null) {
                return null;
            }
            for (PlanMapper.NodeRow row : rows) {
                if ("NODE".equals(row.nodeType())
                        && yearlySubplan.equals(row.parentId())
                        && "FORM".equals(row.taskType() == null ? "" : row.taskType())) {
                    return new long[] {row.id(), 1};
                }
            }
            return null;
        }
    }

    /** 一批的计数与写数（写数随批事务原子落库；patientCreated 标记本批当前患者是否本导入新建）。 */
    private static final class BatchCounts {

        private final List<WriteInsert> writes = new ArrayList<>();
        private int created;
        private int updated;
        private int skipped;
        private int records;
        private boolean patientCreated;

        private void skipped(String reason) {
            skipped++;
            log.debug("导入行跳过：{}", reason);
        }
    }

    // ---------------------------------------------------------------- 值转换与字段定位

    private JobRow requireJob(long jobId) {
        JobRow job = staging.findJob(jobId);
        if (job == null) {
            throw new ApiException(404, 40400, "导入任务不存在");
        }
        return job;
    }

    private Object decrypt(StagedRow row, Class<?> type) {
        String plaintext = crypto.decrypt(row.payloadCipher());
        try {
            return json.readValue(plaintext, type);
        } catch (Exception failure) {
            throw new IllegalStateException("暂存行载荷解析失败（imp_row.id=" + row.id() + "）", failure);
        }
    }

    private static String mrnOf(Object payload) {
        if (payload instanceof WideRow wide) {
            return wide.mrn();
        }
        if (payload instanceof BaselineRow baseline) {
            return baseline.mrn();
        }
        return null;
    }

    /** 单元格 → 类型化值 + 字段元数据（Q37 文字进备注、派生以重算为准；病区字段不进表单值，走 {@link #resolveWard}）。 */
    private TypedValues typedValues(
            ImportContext context,
            Map<String, CellValue> cells,
            Map<String, ImportFieldRule> ruleIndex) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        cells.forEach(
                (key, cell) -> {
                    ImportFieldRule rule = ruleIndex.get(key);
                    if (rule == null || cell == null || cell.normalized() == null) {
                        return;
                    }
                    if (key.equals(context.rules.wardFieldKey())) {
                        return;
                    }
                    var checked = ImportCellValidator.check(rule, cell.toCell());
                    if (checked.value() != null) {
                        values.put(key, checked.value());
                    }
                    String note = codeTextNote(rule, cell);
                    if (note != null) {
                        meta.put(key, Map.of("note", note));
                    }
                });
        applyDerived(context, ruleIndex, values, meta);
        return new TypedValues(values, meta);
    }

    /** 派生字段重算（A-FLD-005「批量导入与历史导入也重算」）：重算 OK 以重算值为准；来源缺失保留导入值并标记手工录入。 */
    private void applyDerived(
            ImportContext context,
            Map<String, ImportFieldRule> ruleIndex,
            Map<String, Object> values,
            Map<String, Object> meta) {
        boolean hasDerived =
                ruleIndex.values().stream().anyMatch(rule -> rule.derivedDefJson() != null);
        if (!hasDerived) {
            return;
        }
        Map<String, DerivedOutcome> outcomes =
                derivedEngine.recalculate(versionFields(ruleIndex), values);
        ruleIndex.forEach(
                (key, rule) -> {
                    if (rule.derivedDefJson() == null) {
                        return;
                    }
                    DerivedOutcome outcome = outcomes.get(key);
                    if (outcome != null && outcome.status() == DerivedStatus.OK) {
                        values.put(key, outcome.value());
                        return;
                    }
                    if (values.containsKey(key)) {
                        meta.put(key, Map.of("manual", true));
                    }
                });
    }

    /** Q37「编码 + 文字」的文字部分（存该字段备注；判定与 ImportCellValidator / OQ-102 同一约定）。 */
    private static String codeTextNote(ImportFieldRule rule, CellValue cell) {
        if (rule.type() != FieldType.SELECT && rule.type() != FieldType.RADIO) {
            return null;
        }
        Matcher matcher = CODE_TEXT.matcher(cell.normalized());
        if (!matcher.matches() || !rule.options().containsKey(Integer.parseInt(matcher.group(1)))) {
            return null;
        }
        return matcher.group(2).strip();
    }

    private record TypedValues(Map<String, Object> values, Map<String, Object> meta) {}

    /** 一个年度的随访值（visitDate 取「随访日期」字段，按显示名定位——A 字典 f214）。 */
    private record FollowupValues(
            LocalDate visitDate, Map<String, Object> values, Map<String, Object> meta) {}

    /** 病区解析结果（wardId 为空时 reason 为跳过原因，非敏感）。 */
    private record WardResolution(Long wardId, String reason) {}

    /** 患者实体列（类型化；起算日期 = 出院日期优先、回落手术日期——A 计划 DISCHARGE_DATE，B 计划由 startDateOf 按 NHS 字段解析）。 */
    private record EntityColumns(
            String gender,
            LocalDate birthDate,
            Long wardId,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            String legacyNo) {

        private LocalDate startDate() {
            return dischargeDate != null ? dischargeDate : surgeryDate;
        }
    }

    private static String nameOf(ImportContext context, TypedValues baseline) {
        ImportFieldRule rule = byLabel(context.rules.baselineRules(), LABEL_NAME);
        Object value = rule == null ? null : baseline.values.get(rule.fieldKey());
        return value instanceof String name && !name.isBlank() ? name.strip() : null;
    }

    /** 性别：选项显示名 男 / 女 → 主档 M / F（patient.md §1 口径；A 字典 a_gender 编码只是建议值）。 */
    private static String genderOf(ImportContext context, TypedValues baseline) {
        ImportFieldRule rule = byLabel(context.rules.baselineRules(), LABEL_GENDER);
        if (rule == null) {
            return null;
        }
        Object value = baseline.values.get(rule.fieldKey());
        if (!(value instanceof Integer code)) {
            return null;
        }
        String label = rule.options().get(code);
        if ("男".equals(label)) {
            return "M";
        }
        if ("女".equals(label)) {
            return "F";
        }
        return null;
    }

    /** 病区解析（E18-T04 映射）：单元格空 → 兜底病区（确认选项）；预检后字典又变动导致未映射 / 停用 / 一值多区 → 跳过该行。 */
    private WardResolution resolveWard(
            ImportContext context, Map<String, CellValue> baselineCells) {
        if (context.rules.wardFieldKey() == null) {
            return context.defaultWardId == null
                    ? new WardResolution(null, "字典无病区字段且未选择兜底病区")
                    : new WardResolution(context.defaultWardId, null);
        }
        CellValue cell = baselineCells.get(context.rules.wardFieldKey());
        String raw = cell == null ? null : cell.normalized();
        if (raw == null) {
            return context.defaultWardId == null
                    ? new WardResolution(null, "病区为空且未选择兜底病区（历史导入无法建档）")
                    : new WardResolution(context.defaultWardId, null);
        }
        Resolution resolution = context.wardDirectory.resolve(raw);
        if (resolution.status() == ImportWardDirectory.MatchStatus.MATCHED) {
            return new WardResolution(resolution.ward().id(), null);
        }
        return new WardResolution(null, "病区取值无法映射到唯一启用病区（预检后病区字典已变动，请重新预检）");
    }

    private static ImportFieldRule byLabel(List<ImportFieldRule> rules, String label) {
        return rules.stream().filter(rule -> label.equals(rule.label())).findFirst().orElse(null);
    }

    /** 年度随访值（宽表组序号即年度；长表按年度列），全空组跳过（A-PAT-018「全空的组跳过」）。 */
    private Map<Integer, FollowupValues> followupValues(
            ImportContext context, List<FollowupGroup> wideGroups, List<FollowupRow> longRows) {
        Map<Integer, FollowupValues> result = new LinkedHashMap<>();
        if (wideGroups != null) {
            for (FollowupGroup group : wideGroups) {
                Integer year = group.year() != null ? group.year() : group.group();
                if (year == null || !hasValue(group.values())) {
                    continue;
                }
                result.put(year, followupValues(context, group.values()));
            }
        }
        if (longRows != null) {
            for (FollowupRow row : longRows) {
                if (row.year() == null || !hasValue(row.values())) {
                    continue;
                }
                result.put(row.year(), followupValues(context, row.values()));
            }
        }
        return result;
    }

    private FollowupValues followupValues(ImportContext context, Map<String, CellValue> cells) {
        TypedValues typed = typedValues(context, cells, context.rules.followupByKey());
        ImportFieldRule dateRule = byLabel(context.rules.followupRules(), LABEL_FOLLOWUP_DATE);
        LocalDate visitDate = null;
        if (dateRule != null && typed.values.get(dateRule.fieldKey()) instanceof String date) {
            visitDate = LocalDate.parse(date);
        }
        return new FollowupValues(visitDate, typed.values, typed.meta);
    }

    private static boolean hasValue(Map<String, CellValue> cells) {
        return cells != null
                && cells.values().stream()
                        .anyMatch(cell -> cell != null && cell.normalized() != null);
    }

    /** 死亡判定：任一年度「是否死亡」=是（A 规格 §5「随访记录『是否死亡=是』，后续任务全部终止」）。 */
    private static boolean deadOf(ImportContext context, Map<Integer, FollowupValues> followups) {
        ImportFieldRule rule = byLabel(context.rules.followupRules(), LABEL_DEATH);
        if (rule == null) {
            return false;
        }
        for (FollowupValues values : followups.values()) {
            if (Integer.valueOf(1).equals(values.values.get(rule.fieldKey()))) {
                return true;
            }
        }
        return false;
    }

    private static LocalDate dateOf(ImportContext context, TypedValues baseline, String label) {
        ImportFieldRule rule = byLabel(context.rules.baselineRules(), label);
        Object value = rule == null ? null : baseline.values.get(rule.fieldKey());
        return value instanceof String date ? LocalDate.parse(date) : null;
    }

    private static LocalDate changedDate(LocalDate imported, LocalDate current) {
        return imported != null && !imported.equals(current) ? imported : null;
    }

    /** 年度排期（第 1..N 次直到窗口未结束且超出有数据年度；无计划 / 无锚点 / 起算日期缺失返回空，记录只按年度）。 */
    private List<YearSchedule> schedulesOf(
            ImportContext context, EntityColumns entity, Set<Integer> dataYears) {
        List<YearSchedule> schedules = new ArrayList<>();
        if (context.anchorNodeId == null || context.planId == null) {
            return schedules;
        }
        LocalDate startDate = context.startDateOf(entity);
        if (startDate == null) {
            return schedules;
        }
        int maxDataYear = dataYears.stream().max(Integer::compareTo).orElse(0);
        for (int occurrence = 1; occurrence <= MAX_YEARS; occurrence++) {
            var schedule =
                    TaskCreationPlanner.occurrenceScheduleOf(
                            context.nodes, context.anchorNodeId, occurrence, startDate);
            if (schedule == null) {
                break;
            }
            schedules.add(
                    new YearSchedule(
                            occurrence,
                            schedule.planDate(),
                            schedule.windowStart(),
                            schedule.windowEnd()));
            if (!schedule.windowEnd().isBefore(context.today) && occurrence > maxDataYear) {
                break;
            }
        }
        return schedules;
    }

    // ---------------------------------------------------------------- JSON 与冻结范围

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("导入提交 JSON 序列化失败", failure);
        }
    }

    private Map<String, Object> readJson(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("导入提交 JSON 解析失败", failure);
        }
    }

    /** 规则 → 版本字段快照（FormDerivedEngine 入参，与 E18-T03 校验同构）。 */
    private static List<VersionFieldRow> versionFields(Map<String, ImportFieldRule> ruleIndex) {
        List<VersionFieldRow> fields = new ArrayList<>();
        ruleIndex.forEach(
                (fieldKey, rule) ->
                        fields.add(
                                new VersionFieldRow(
                                        null,
                                        null,
                                        null,
                                        0,
                                        fieldKey,
                                        null,
                                        rule.label(),
                                        null,
                                        rule.type().code(),
                                        rule.unit(),
                                        rule.decimals(),
                                        null,
                                        null,
                                        null,
                                        rule.required(),
                                        null,
                                        null,
                                        rule.derivedDefJson(),
                                        rule.sensitive(),
                                        null,
                                        null,
                                        0)));
        return fields;
    }

    /** 冻结操作人数据范围（all=true 或病区 id 清单；无会话按空范围——提交侧逐行校验会全部跳过，由确认入口保证有会话，E18-T06）。 */
    private static Map<String, Object> frozenScope() {
        return CurrentSession.current()
                .map(
                        session -> {
                            ScopeSnapshot scope = session.scope();
                            if (scope == null || scope.all()) {
                                return Map.<String, Object>of("all", true);
                            }
                            return Map.<String, Object>of("wardIds", scope.wardIds());
                        })
                .orElseGet(() -> Map.of("wardIds", List.of()));
    }

    /** 兜底病区校验：id 存在且启用（停用病区不能进新患者，A-WARD-008）。 */
    private static ImportWardDirectory.WardEntry enabledWard(
            ImportWardDirectory directory, long wardId) {
        return directory.wards().stream()
                .filter(ward -> ward.id() == wardId && "ENABLED".equals(ward.status()))
                .findFirst()
                .orElse(null);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "未知错误";
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
