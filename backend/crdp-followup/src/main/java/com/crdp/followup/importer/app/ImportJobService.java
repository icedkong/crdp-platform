package com.crdp.followup.importer.app;

import com.crdp.followup.importer.app.ImportCommitService.BatchView;
import com.crdp.followup.importer.app.ImportCommitService.ConfirmOptions;
import com.crdp.followup.importer.app.ImportCommitService.RollbackView;
import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportStagingService.NewImportFile;
import com.crdp.followup.importer.domain.ImportIssue.Level;
import com.crdp.followup.importer.infra.mapper.ImportFeatureMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * E18-T06 / A-PAT-008、A-PAT-018：导入向导的 HTTP 用例编排（import-export.md §1；OQ-101⑤ 端点归属）—— 模板下载、multipart
 * 上传（→ 对象存储 → 建作业 → 异步派发预检）、状态与问题分页、确认提交（prepare 会话内冻结选项与范围 + execute 异步执行，E18-T05 两段口）、
 * 取消、失败批次重试、按批 / 整作业回滚、批注版校验报告下载。
 *
 * <p>向导三步可见进度（tasks.csv 验收）：上传（POST 返回 jobId 即开始）、校验（status=PRECHECKING 时 staged_rows 递增，终态
 * PRECHECKED / PRECHECK_FAILED）、提交（status=IMPORTING 时按 {@code imp_batch} 批次状态推进，终态 DONE /
 * PARTIAL_FAILED）。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>功能开关 {@code wide_import} 关闭 → 40301（AGENTS.md 规则 7；无行视为默认启用，与 compare_highlight 同口径）；
 *   <li>同一租户同一时间只允许一个导入作业（建作业 40901，tech-plan §12；取消旧作业后可重新上传）；建作业被拒时回收已写对象， 不在桶内留孤儿文件（E23-T07
 *       演练发现）；
 *   <li>有 ERROR 问题时确认被拦（40001，E18-T05 prepare 门控）；原文件按租户前缀写对象存储（OBS SSE-KMS，ADR 0006），
 *       imp_job.storage_key 存相对键；
 *   <li>上传 / 取消 / 报告下载写作业级审计（IMPORT_UPLOAD / IMPORT_CANCEL / DOWNLOAD；提交与回滚审计在 E18-T05 服务内）。
 * </ul>
 *
 * <p>注：预检 / 提交在异步线程读对象存储整文件字节（ObjectStorage 现接口为 byte[]，ADR 0006；流式解析本身内存只有一批，E18-T02 验收不变）。
 */
@Service
public class ImportJobService {

    /** 功能开关码（cfg_feature，种子 V100：A 默认开）。 */
    static final String FEATURE_WIDE_IMPORT = "wide_import";

    private static final Logger log = LoggerFactory.getLogger(ImportJobService.class);

    private static final String TYPE_STANDARD = "STANDARD";

    private static final String TYPE_WIDE = "WIDE";

    /** 问题分页默认页大小（docs/api/README §2.4 默认 20）。 */
    private static final int ISSUE_PAGE_SIZE = 20;

    private static final int ISSUE_PAGE_SIZE_MAX = 100;

    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final DateTimeFormatter KEY_MONTH =
            DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT);

    /** 取消允许的来源状态（确认前整作业可放弃；预检 / 导入进行中不可打断——等终态）。 */
    private static final List<String> CANCELLABLE =
            List.of("UPLOADED", "PRECHECK_FAILED", "PRECHECKED");

    private final ImportStagingMapper staging;
    private final ImportFeatureMapper features;
    private final ImportFieldRuleLoader ruleLoader;
    private final ImportStagingService stagingService;
    private final ImportValidationService validation;
    private final ImportCommitService commit;
    private final ObjectStorage storage;
    private final AuditLogger audit;
    private final ObjectMapper json;
    private final ImportDispatchRunner runner;

    public ImportJobService(
            ImportStagingMapper staging,
            ImportFeatureMapper features,
            ImportFieldRuleLoader ruleLoader,
            ImportStagingService stagingService,
            ImportValidationService validation,
            ImportCommitService commit,
            ObjectStorage storage,
            AuditLogger audit,
            ObjectMapper json,
            ImportDispatchRunner runner) {
        this.staging = staging;
        this.features = features;
        this.ruleLoader = ruleLoader;
        this.stagingService = stagingService;
        this.validation = validation;
        this.commit = commit;
        this.storage = storage;
        this.audit = audit;
        this.json = json;
        this.runner = runner;
    }

    // ---------------------------------------------------------------- DTO

    /** 上传受理结果：预检已异步开始，向导轮询 GET /imports/{jobId}。 */
    public record UploadedJob(long jobId) {}

    /** 模板 / 报告文件（字节 + 下载文件名）。 */
    public record FileDownload(byte[] body, String fileName) {}

    /** 校验问题行（向导分页展示；不含单元格原值——敏感列不回显，OQ-101③）。 */
    public record IssueItem(
            long id,
            String level,
            String sheetName,
            int rowNo,
            String columnLetters,
            String header,
            String code,
            String message) {}

    /** 问题分页（docs/api/README §2.4）。 */
    public record IssuePage(List<IssueItem> items, long total, int pageNo, int pageSize) {}

    /** 提交统计（向导进度与结果；batchesDone 供 IMPORTING 进度条）。 */
    public record CommitStats(
            int batches,
            int batchesDone,
            int createdPatients,
            int updatedPatients,
            int skippedPatients,
            int recordsCreated,
            List<Integer> failedBatches) {}

    /** 作业视图（向导单次轮询拿全部状态与进度）。 */
    public record JobView(
            long jobId,
            String fileName,
            long fileSize,
            String layout,
            String status,
            long stagedRows,
            long errorCount,
            long warnCount,
            List<String> layoutProblems,
            String failReason,
            String duplicateStrategy,
            Long defaultWardId,
            String operatorName,
            String createdAt,
            String updatedAt,
            CommitStats stats,
            List<BatchView> batches) {}

    // ---------------------------------------------------------------- 模板

    /** 下载模板（type=STANDARD 长表 / WIDE 宽表；列来自字段字典，示例行全虚构）。 */
    public FileDownload template(String type) {
        requireFeatureEnabled();
        String resolved = type == null || type.isBlank() ? TYPE_STANDARD : type.strip();
        ImportFieldRules rules = ruleLoader.load();
        return switch (resolved) {
            case TYPE_STANDARD ->
                    new FileDownload(
                            ImportTemplateComposer.standard(rules), "患者导入模板（基线+随访记录）.xlsx");
            case TYPE_WIDE ->
                    new FileDownload(ImportTemplateComposer.wide(rules), "患者导入模板（宽表）.xlsx");
            default -> throw new ApiException(400, 40001, "模板类型只支持 STANDARD（基线+随访记录）或 WIDE（宽表）");
        };
    }

    // ---------------------------------------------------------------- 上传与预检派发

    /**
     * 上传导入文件（multipart 单文件）：校验 → 按租户前缀写对象存储 → 建作业（UPLOADED，同租户已有活跃作业 40901）→ 异步派发预检。 写
     * IMPORT_UPLOAD 审计。
     */
    public UploadedJob upload(String fileName, byte[] body) {
        requireFeatureEnabled();
        if (fileName == null || fileName.isBlank()) {
            throw new ApiException(400, 40001, "上传文件名不能为空");
        }
        if (body == null || body.length == 0) {
            throw new ApiException(400, 40001, "上传文件为空，请选择 Excel 文件");
        }
        var principal = CurrentSession.require();
        String sha256 = sha256Hex(body);
        String relativeKey =
                "imports/"
                        + LocalDateTime.now().format(KEY_MONTH)
                        + "/imp-"
                        + UUID.randomUUID()
                        + ".xlsx";
        storage.put(
                relativeKey,
                body,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        long jobId;
        try {
            jobId =
                    stagingService.createJob(
                            new NewImportFile(fileName.strip(), body.length, sha256),
                            principal.userId(),
                            principal.username());
        } catch (RuntimeException rejection) {
            // E23-T07 演练发现：建作业被拒（40901 已有运行中作业等）时先写的对象无人清理，桶内留孤儿文件——拒绝路径回收已写对象
            deleteQuietly(relativeKey);
            throw rejection;
        }
        staging.updateStorageKey(jobId, relativeKey);
        audit.append(
                AuditEntry.operation("followup", "IMPORT_UPLOAD")
                        .objectType("IMPORT_JOB")
                        .objectId(String.valueOf(jobId))
                        .description(
                                "上传导入文件："
                                        + fileName.strip()
                                        + "（"
                                        + body.length
                                        + " 字节，sha256 前 12 位 "
                                        + sha256.substring(0, 12)
                                        + "），预检已开始")
                        .result(AuditResult.SUCCESS)
                        .build());
        dispatchPrecheck(jobId, relativeKey);
        return new UploadedJob(jobId);
    }

    /** 异步预检：从对象存储按租户前缀读回字节流式解析（runPrecheck 内部把任何异常落 PRECHECK_FAILED）。 */
    private void dispatchPrecheck(long jobId, String relativeKey) {
        runner.dispatch(
                jobId,
                id -> {
                    byte[] body = storage.get(relativeKey);
                    stagingService.runPrecheck(
                            id, new ByteArrayInputStream(body == null ? new byte[0] : body));
                });
    }

    /** 尽力删除对象存储键（清理被拒上传的孤儿文件；删除失败只记日志，不掩盖原始异常）。 */
    private void deleteQuietly(String relativeKey) {
        try {
            storage.delete(relativeKey);
        } catch (RuntimeException failure) {
            log.warn("拒绝上传后的对象清理失败（键不回显）：{}", failure.getMessage());
        }
    }

    // ---------------------------------------------------------------- 状态与问题

    /** 作业视图（状态机与进度：staged_rows、错误 / 警告计数、批次进度与统计）。 */
    public JobView view(long jobId) {
        requireFeatureEnabled();
        JobRow job = requireJob(jobId);
        long errorCount = staging.countIssues(jobId, Level.ERROR.name());
        long warnCount = staging.countIssues(jobId, Level.WARN.name());
        List<BatchView> batches = commit.batches(jobId);
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int records = 0;
        int done = 0;
        List<Integer> failed = new ArrayList<>();
        for (BatchView batch : batches) {
            created += batch.createdPatients();
            updated += batch.updatedPatients();
            skipped += batch.skippedPatients();
            records += batch.recordsCreated();
            if ("DONE".equals(batch.status()) || "ROLLED_BACK".equals(batch.status())) {
                done++;
            }
            if ("FAILED".equals(batch.status())) {
                failed.add(batch.batchNo());
            }
        }
        OptionsView options = parseOptions(job.optionsJson());
        return new JobView(
                jobId,
                job.fileName(),
                job.fileSize(),
                job.layout(),
                job.status(),
                job.stagedRows(),
                errorCount,
                warnCount,
                parseStringList(job.layoutProblems()),
                job.failReason(),
                options.duplicateStrategy(),
                options.defaultWardId(),
                job.operatorName(),
                format(job.createdAt()),
                format(job.updatedAt()),
                new CommitStats(batches.size(), done, created, updated, skipped, records, failed),
                batches);
    }

    /** 校验问题分页（level=ERROR|WARN 可空；按工作表 → 行 → 列顺序）。 */
    public IssuePage issues(long jobId, String level, Integer pageNo, Integer pageSize) {
        requireFeatureEnabled();
        requireJob(jobId);
        if (level != null
                && !Level.ERROR.name().equals(level)
                && !Level.WARN.name().equals(level)) {
            throw new ApiException(400, 40001, "问题级别只支持 ERROR 或 WARN");
        }
        int page = pageNo == null ? 1 : pageNo;
        if (page < 1) {
            throw new ApiException(400, 40001, "pageNo 从 1 开始");
        }
        int size = pageSize == null ? ISSUE_PAGE_SIZE : Math.min(pageSize, ISSUE_PAGE_SIZE_MAX);
        long offset = (long) (page - 1) * size;
        long total = staging.countIssues(jobId, level);
        List<IssueItem> items =
                staging.findIssues(jobId, level, offset, size).stream()
                        .map(ImportJobService::toItem)
                        .toList();
        return new IssuePage(items, total, page, size);
    }

    private static IssueItem toItem(IssueRow row) {
        return new IssueItem(
                row.id(),
                row.level(),
                row.sheetName(),
                row.rowNo(),
                row.colLetters(),
                row.header(),
                row.code(),
                row.message());
    }

    // ---------------------------------------------------------------- 确认 / 取消 / 重试 / 回滚

    /** 确认提交：prepare 在会话内（校验策略 / 兜底病区、有 ERROR 40001、冻结选项与操作人范围快照）， execute 异步执行（向导轮询批次进度）。 */
    public UploadedJob confirm(long jobId, ConfirmOptions options) {
        requireFeatureEnabled();
        commit.prepare(jobId, options);
        runner.dispatch(jobId, commit::execute);
        return new UploadedJob(jobId);
    }

    /** 取消作业（只允许确认前：UPLOADED / PRECHECK_FAILED / PRECHECKED → CANCELLED；写 IMPORT_CANCEL 审计）。 */
    public JobView cancel(long jobId) {
        requireFeatureEnabled();
        requireJob(jobId);
        boolean cancelled = false;
        for (String from : CANCELLABLE) {
            cancelled = staging.casStatus(jobId, from, "CANCELLED") > 0 || cancelled;
        }
        if (!cancelled) {
            throw new ApiException(409, 40901, "预检或导入进行中 / 已是终态的作业不能取消（进行中请等终态，已提交的作业用回滚）");
        }
        audit.append(
                AuditEntry.operation("followup", "IMPORT_CANCEL")
                        .objectType("IMPORT_JOB")
                        .objectId(String.valueOf(jobId))
                        .description("取消导入作业（确认前放弃）")
                        .result(AuditResult.SUCCESS)
                        .build());
        return view(jobId);
    }

    /** 重试失败批次（PARTIAL_FAILED → IMPORTING，只重跑 FAILED 批；E18-T05 状态机）。 */
    public UploadedJob retry(long jobId) {
        requireFeatureEnabled();
        JobRow job = requireJob(jobId);
        if (!"PARTIAL_FAILED".equals(job.status())) {
            throw new ApiException(409, 40901, "导入任务没有可重试的失败批次（当前 " + job.status() + "）");
        }
        runner.dispatch(jobId, commit::retryFailed);
        return new UploadedJob(jobId);
    }

    /** 回滚整个作业（全部已完成批次；审计在 E18-T05 服务内 IMPORT_ROLLBACK）。 */
    public List<RollbackView> rollback(long jobId) {
        requireFeatureEnabled();
        return commit.rollbackJob(jobId);
    }

    /** 回滚一批（只允许已完成批次；向导按批展示的入口）。 */
    public RollbackView rollbackBatch(long jobId, int batchNo) {
        requireFeatureEnabled();
        return commit.rollbackBatch(jobId, batchNo);
    }

    // ---------------------------------------------------------------- 报告下载

    /**
     * 批注版校验报告下载（E18-T03 annotatedReport：暂存行重建 + 问题标色批注；报告按需生成不落对象存储， OQ-102① 载体为「按暂存行重建」）。 每次下载写
     * DATA_ACCESS/DOWNLOAD 审计（带文件哈希）。
     */
    public FileDownload report(long jobId) {
        requireFeatureEnabled();
        byte[] body = validation.annotatedReport(jobId);
        audit.append(
                AuditEntry.dataAccess("followup", "DOWNLOAD")
                        .eventType("IMPORT_REPORT_DOWNLOAD")
                        .objectType("IMPORT_JOB")
                        .objectId(String.valueOf(jobId))
                        .fileHash(sha256Hex(body))
                        .description("下载批注版校验报告（导入作业 " + jobId + "）")
                        .success()
                        .build());
        return new FileDownload(body, "导入校验报告-作业" + jobId + ".xlsx");
    }

    // ---------------------------------------------------------------- 共用

    private void requireFeatureEnabled() {
        Boolean enabled = features.findEnabled(FEATURE_WIDE_IMPORT);
        if (Boolean.FALSE.equals(enabled)) {
            throw new ApiException(403, 40301, "历史数据导入功能未启用");
        }
    }

    private JobRow requireJob(long jobId) {
        JobRow job = staging.findJob(jobId);
        if (job == null) {
            throw new ApiException(404, 40400, "导入任务不存在");
        }
        return job;
    }

    private record OptionsView(String duplicateStrategy, Long defaultWardId) {}

    private OptionsView parseOptions(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return new OptionsView(null, null);
        }
        try {
            JsonNode node = json.readTree(optionsJson);
            String strategy = node.path("duplicateStrategy").asText(null);
            String ward = node.path("defaultWardId").asText(null);
            return new OptionsView(
                    strategy == null || strategy.isBlank() ? null : strategy,
                    ward == null || ward.isBlank() ? null : Long.valueOf(ward));
        } catch (JsonProcessingException | RuntimeException failure) {
            log.warn("导入选项解析失败（原文不回显）：{}", failure.getMessage());
            return new OptionsView(null, null);
        }
    }

    private List<String> parseStringList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException failure) {
            return List.of();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : DISPLAY_TIME.format(time);
    }

    /** 文件字节 SHA-256（与 FileUploadService 同款本地助手；加解密才走 CryptoService，哈希见 NFR-CRY-002 口径）。 */
    private static String sha256Hex(byte[] body) {
        try {
            byte[] hashed = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 不可用", failure);
        }
    }
}
