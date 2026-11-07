package com.crdp.followup.importer.app;

import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.domain.ImportCell;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportFollowup;
import com.crdp.followup.importer.domain.ImportLayout;
import com.crdp.followup.importer.domain.ImportLayoutException;
import com.crdp.followup.importer.domain.ImportPatientRecord;
import com.crdp.followup.importer.domain.ImportWorkbookParser;
import com.crdp.followup.importer.domain.LongLayoutAdapter;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.importer.infra.ImporterEncryptedColumns;
import com.crdp.followup.importer.infra.StreamingImportWorkbook;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.RowInsert;
import com.crdp.followup.patient.app.PatientRegistrationService;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.excel.ExcelGateway;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * E18-T02 / tech-plan §12③：导入作业与流式暂存（imp_job / imp_row，tasks.csv 验收「大文件不占满内存」）。
 *
 * <p>流程：{@link #createJob} 建 imp_job（UPLOADED；同一租户已有活跃作业 40901，tech-plan §12「同一租户同一时间只允许一个导入作业」）；
 * {@link #runPrecheck} 逐行流式解析并按批写 imp_row——框架 {@link ExcelGateway} 落盘随机访问 + 适配器逐行回调（ADR 0007 /
 * OQ-100④）， 每批 {@value #INSERT_BATCH} 行落库并累加进度，任何时刻内存只有一批； 版面 / 表头整体错误（{@link
 * ImportLayoutException}）标 PRECHECK_FAILED、差异清单落 layout_problems，不写任何行。
 *
 * <p>暂存形态（宽表一行 = 一位患者 PATIENT；长表基线行 BASELINE 与随访行 FOLLOWUP 分存）：行内含姓名 / 病案号等敏感明文， 整行 JSON（形态 {@link
 * ImportRowPayloads}）经 {@link CryptoService} 加密落 payload_cipher（purpose={@link
 * ImporterEncryptedColumns#PURPOSE_ROW_PAYLOAD}）； 病案号另落盲索引 mrn_blind——与 {@code
 * pt_patient.mrn_blind} 同 purpose，校验（E18-T03 {@link ImportValidationService}）按它查重与关联基线行（tech-plan
 * §12④「按盲索引查病案号重复」）。 解析阶段已知问题 （随访年度解析不了、病案号为空）落行级 problems；暂存完成后随即跑校验并把问题落 imp_issue（E18-T03）。
 *
 * <p>调用需租户上下文（imp_job / imp_row 在租户库）。上传端点（multipart → 对象存储 → 建作业 → 异步派发预检）与审计随 E18-T06 接入； 本服务的
 * runPrecheck 设计为可在异步线程直接调用（租户上下文经 TenantContextTaskDecorator 复制）。
 */
@Service
public class ImportStagingService {

    /** 写库批次（tech-plan §12「每批约 200 名患者一个事务」是提交批次（E18-T05）；暂存批次只受内存与写放大约束）。 */
    static final int INSERT_BATCH = 500;

    private static final String STATUS_UPLOADED = "UPLOADED";

    private static final String STATUS_PRECHECKING = "PRECHECKING";

    private static final String STATUS_PRECHECKED = "PRECHECKED";

    private static final String STATUS_PRECHECK_FAILED = "PRECHECK_FAILED";

    private final ImportStagingMapper staging;
    private final ImportFieldCatalogLoader catalogLoader;
    private final CryptoService crypto;
    private final ExcelGateway excel;
    private final ObjectMapper json;
    private final ImportValidationService validation;

    public ImportStagingService(
            ImportStagingMapper staging,
            ImportFieldCatalogLoader catalogLoader,
            CryptoService crypto,
            ExcelGateway excel,
            ObjectMapper json,
            ImportValidationService validation) {
        this.staging = staging;
        this.catalogLoader = catalogLoader;
        this.crypto = crypto;
        this.excel = excel;
        this.json = json;
        this.validation = validation;
    }

    // ---------------------------------------------------------------- DTO

    /** 上传文件元数据（文件内容由对象存储承载，storage_key 由上传端点随 E18-T06 回填）。 */
    public record NewImportFile(String fileName, long fileSize, String sha256) {}

    /**
     * 预检结果（状态与行数供向导页展示进度；layoutProblems 为版面 / 表头整体错误清单； errorCount / warnCount 为 E18-T03 校验问题计数——有
     * ERROR 时确认提交被拦（E18-T05 / import-export.md §1）。
     */
    public record PrecheckOutcome(
            long jobId,
            String status,
            String layout,
            long stagedRows,
            List<String> layoutProblems,
            long errorCount,
            long warnCount) {}

    // ---------------------------------------------------------------- 建作业

    /** 建导入作业（UPLOADED）；同一租户已有活跃作业 → 40901。 */
    public long createJob(NewImportFile file, long operatorId, String operatorName) {
        NewImportFile safe = file == null ? new NewImportFile(null, 0, null) : file;
        if (safe.fileName() == null || safe.fileName().isBlank()) {
            throw new ApiException(400, 40001, "上传文件名不能为空");
        }
        if (staging.countActive(ImportStagingMapper.ACTIVE_STATUSES) > 0) {
            throw new ApiException(409, 40901, "同一租户同一时间只允许一个导入作业，已有运行中的作业");
        }
        JobInsert row =
                new JobInsert(
                        safe.fileName().strip(),
                        Math.max(safe.fileSize(), 0),
                        safe.sha256(),
                        operatorId,
                        operatorName);
        staging.insertJob(row);
        return row.getId();
    }

    // ---------------------------------------------------------------- 预检（流式暂存）

    /**
     * 流式预检：UPLOADED → PRECHECKING →（PRECHECKED | PRECHECK_FAILED）。结构性问题（版面 / 表头）与解析异常都落
     * PRECHECK_FAILED（含差异清单或原因），不向上抛——作业状态是唯一事实； 数据级问题逐行落 problems。
     */
    public PrecheckOutcome runPrecheck(long jobId, InputStream content) {
        ImportStagingMapper.JobRow job = requireJob(jobId);
        if (!STATUS_UPLOADED.equals(job.status())) {
            throw new ApiException(409, 40901, "导入任务不在已上传状态，不能开始预检");
        }
        if (staging.casStatus(jobId, STATUS_UPLOADED, STATUS_PRECHECKING) == 0) {
            throw new ApiException(409, 40901, "导入任务已被其他预检占用");
        }
        ImportFieldCatalog catalog;
        try {
            catalog = catalogLoader.load();
        } catch (RuntimeException failure) {
            return fail(jobId, null, null, "字段目录不可用：" + rootMessage(failure));
        }
        ImportLayout[] detected = new ImportLayout[1];
        try (StreamingImportWorkbook workbook = new StreamingImportWorkbook(excel.open(content))) {
            detected[0] = ImportWorkbookParser.detect(workbook);
            Stager stager = new Stager(jobId, detected[0], wideSheetName(workbook));
            switch (detected[0]) {
                case WIDE -> new WideLayoutAdapter().stream(workbook, catalog, stager::patient);
                case LONG -> {
                    LongLayoutAdapter adapter = new LongLayoutAdapter();
                    adapter.streamBaseline(workbook, catalog, stager::baseline);
                    adapter.streamFollowups(workbook, catalog, stager::followup);
                }
            }
            stager.flush();
            // tech-plan §12 状态机「预检完成 = 生成校验报告」：校验（E18-T03）在状态仍为 PRECHECKING 内完成，
            // 任何异常（含校验基础设施故障）统一落 PRECHECK_FAILED，不留「已预检完成但没报告」的中间态。
            ImportValidationService.ValidationSummary summary = validation.validate(jobId);
            return stager.finish(summary);
        } catch (ImportLayoutException failure) {
            return fail(
                    jobId,
                    detected[0] == null ? null : detected[0].name(),
                    failure.problems(),
                    failure.getMessage());
        } catch (IOException | RuntimeException failure) {
            return fail(
                    jobId,
                    detected[0] == null ? null : detected[0].name(),
                    null,
                    "预检失败：" + rootMessage(failure));
        }
    }

    private ImportStagingMapper.JobRow requireJob(long jobId) {
        ImportStagingMapper.JobRow job = staging.findJob(jobId);
        if (job == null) {
            throw new ApiException(404, 40400, "导入任务不存在");
        }
        return job;
    }

    /** 宽表唯一工作表的表名（暂存行溯源；长表固定「基线」/「随访记录」取适配器常量）。 */
    private static String wideSheetName(StreamingImportWorkbook workbook) {
        List<String> names =
                workbook.sheets().stream()
                        .map(com.crdp.followup.importer.domain.ImportSheetData::name)
                        .toList();
        return names.isEmpty() ? "" : names.get(0);
    }

    private PrecheckOutcome fail(long jobId, String layout, List<String> problems, String reason) {
        // 预检失败不留半成品行与问题；状态终态 PRECHECK_FAILED（重新上传开新作业）
        staging.deleteRows(jobId);
        staging.deleteIssues(jobId);
        String problemsJson = writeJson(problems);
        staging.failPrecheck(jobId, layout, problemsJson, truncate(reason));
        return new PrecheckOutcome(
                jobId,
                STATUS_PRECHECK_FAILED,
                layout,
                0,
                problems == null ? List.of() : problems,
                0,
                0);
    }

    // ---------------------------------------------------------------- 暂存器

    /** 逐行收集 → 每 {@link #INSERT_BATCH} 行批量落库并累加进度（内存只有一批）。 */
    private final class Stager {

        private final long jobId;
        private final ImportLayout layout;
        private final String wideSheetName;
        private final List<RowInsert> batch = new ArrayList<>(INSERT_BATCH);
        private long staged;

        Stager(long jobId, ImportLayout layout, String wideSheetName) {
            this.jobId = jobId;
            this.layout = layout;
            this.wideSheetName = wideSheetName;
        }

        void patient(ImportPatientRecord record) {
            List<String> problems = mrnProblem(record.mrn());
            add(
                    wideSheetName,
                    record.rowNo(),
                    "PATIENT",
                    record.mrn(),
                    writeJson(
                            new ImportRowPayloads.WideRow(
                                    record.mrn(),
                                    CellValue.of(record.mrnCell()),
                                    record.legacyNo(),
                                    cells(record.frontColumns()),
                                    cells(record.baseline()),
                                    followups(record.followups()))),
                    problems);
        }

        void baseline(ImportPatientRecord record) {
            List<String> problems = mrnProblem(record.mrn());
            add(
                    LongLayoutAdapter.BASELINE_SHEET,
                    record.rowNo(),
                    "BASELINE",
                    record.mrn(),
                    writeJson(
                            new ImportRowPayloads.BaselineRow(
                                    record.mrn(),
                                    CellValue.of(record.mrnCell()),
                                    record.legacyNo(),
                                    cells(record.baseline()))),
                    problems);
        }

        void followup(ImportFollowup followup) {
            List<String> problems =
                    mrnProblem(followup.mrnCell() == null ? null : followup.mrnCell().normalized());
            if (followup.yearCell() != null
                    && !followup.yearCell().isBlank()
                    && followup.year() == null) {
                problems.add("随访年度「" + followup.yearCell().raw().strip() + "」不是有效年度（应为整数年度，如 3）");
            }
            add(
                    LongLayoutAdapter.FOLLOWUP_SHEET,
                    followup.rowNo(),
                    "FOLLOWUP",
                    followup.mrnCell() == null ? null : followup.mrnCell().normalized(),
                    writeJson(
                            new ImportRowPayloads.FollowupRow(
                                    followup.mrnCell() == null
                                            ? null
                                            : followup.mrnCell().normalized(),
                                    CellValue.of(followup.mrnCell()),
                                    followup.year(),
                                    followup.yearCell() == null ? null : followup.yearCell().raw(),
                                    CellValue.of(followup.yearCell()),
                                    cells(followup.values()))),
                    problems);
        }

        /** 落库当前批（校验前先全部落库——跨行校验要读全量暂存行）。 */
        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            staging.insertRows(List.copyOf(batch));
            staging.addStagedRows(jobId, batch.size());
            batch.clear();
        }

        PrecheckOutcome finish(ImportValidationService.ValidationSummary summary) {
            staging.finishPrecheck(jobId, layout.name(), staged);
            return new PrecheckOutcome(
                    jobId,
                    STATUS_PRECHECKED,
                    layout.name(),
                    staged,
                    List.of(),
                    summary.errorCount(),
                    summary.warnCount());
        }

        private void add(
                String sheetName,
                int rowNo,
                String rowType,
                String mrn,
                String payloadJson,
                List<String> problems) {
            batch.add(
                    new RowInsert(
                            jobId,
                            sheetName,
                            rowNo,
                            rowType,
                            mrn == null
                                    ? null
                                    : crypto.blindIndex(
                                            PatientRegistrationService.PURPOSE_MRN, mrn),
                            crypto.encrypt(
                                    ImporterEncryptedColumns.PURPOSE_ROW_PAYLOAD, payloadJson),
                            problems.isEmpty() ? null : writeJson(problems)));
            staged++;
            if (batch.size() >= INSERT_BATCH) {
                flush();
            }
        }
    }

    // ---------------------------------------------------------------- 载荷 JSON（形态见
    // ImportRowPayloads）

    private static Map<String, CellValue> cells(Map<String, ImportCell> values) {
        Map<String, CellValue> payload = new LinkedHashMap<>();
        values.forEach((key, cell) -> payload.put(key, CellValue.of(cell)));
        return payload;
    }

    private static List<ImportRowPayloads.FollowupGroup> followups(List<ImportFollowup> followups) {
        List<ImportRowPayloads.FollowupGroup> payload = new ArrayList<>(followups.size());
        for (int i = 0; i < followups.size(); i++) {
            ImportFollowup followup = followups.get(i);
            payload.add(
                    new ImportRowPayloads.FollowupGroup(
                            i + 1,
                            followup.year(),
                            followup.yearCell() == null ? null : followup.yearCell().raw(),
                            cells(followup.values())));
        }
        return payload;
    }

    /** 解析阶段已知问题之一：病案号为空（无法盲索引关联 / 查重；必填报错由 E18-T03 校验报告统一列出）。 */
    private static List<String> mrnProblem(String mrn) {
        List<String> problems = new ArrayList<>(1);
        if (mrn == null) {
            problems.add("病案号为空");
        }
        return problems;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("暂存载荷序列化失败", failure);
        }
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }
}
