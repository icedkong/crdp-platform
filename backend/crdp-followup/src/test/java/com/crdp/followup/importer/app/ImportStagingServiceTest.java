package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.XlsxTestFiles;
import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.RowInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.StagedRow;
import com.crdp.followup.patient.app.PatientBlindIndexService;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.excel.StreamingXlsxGateway;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E18-T02 暂存服务测试（tasks.csv 验收「大文件不占满内存」的服务面）： 流式解析逐行写 imp_row（内存只有一批）、状态机 UPLOADED → PRECHECKING →
 * PRECHECKED / PRECHECK_FAILED、 版面 / 表头整体错误进 layout_problems 不写行、同一租户已有活跃作业 40901、按批 flush 与进度更新、
 * 暂存行病案号盲索引与整行密文（明文不落列）。 数据库交互用假 mapper；加解密与字段目录用 Mockito 桩。全部虚构数据。
 */
class ImportStagingServiceTest {

    private static final String CIPHER_PREFIX = "enc:";

    private final FakeStagingMapper staging = new FakeStagingMapper();
    private final CryptoService crypto = mock(CryptoService.class);
    private final ImportFieldCatalogLoader catalogLoader = mock(ImportFieldCatalogLoader.class);
    private final ImportFieldRuleLoader ruleLoader = mock(ImportFieldRuleLoader.class);
    private final PatientBlindIndexService patientBlindIndex = mock(PatientBlindIndexService.class);
    private final ObjectMapper json = new ObjectMapper();

    private ImportStagingService service;

    @BeforeEach
    void setUp() {
        when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(inv -> CIPHER_PREFIX + inv.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0).toString().substring(CIPHER_PREFIX.length()));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(inv -> "blind(" + inv.getArgument(1) + ")");
        when(catalogLoader.load()).thenReturn(catalog());
        when(ruleLoader.load()).thenReturn(rules());
        when(patientBlindIndex.existingBlinds(any())).thenReturn(java.util.Set.of());
        service =
                new ImportStagingService(
                        staging,
                        catalogLoader,
                        crypto,
                        new StreamingXlsxGateway(),
                        json,
                        new ImportValidationService(
                                staging,
                                ruleLoader,
                                crypto,
                                patientBlindIndex,
                                new FormDerivedEngine(),
                                json));
    }

    // ---------------------------------------------------------------- 建作业

    @Test
    void createJobInsertsUploadedAndRejectsSecondActiveJob() {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile(
                                "患者导入（示例）.xlsx", 1024, "sha256-（示例）"),
                        7L,
                        "导入账号（示例）");
        JobRow job = staging.findJob(jobId);
        assertEquals("UPLOADED", job.status());
        assertEquals("患者导入（示例）.xlsx", job.fileName());

        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createJob(
                                        new ImportStagingService.NewImportFile("第二个.xlsx", 1, null),
                                        7L,
                                        "导入账号（示例）"));
        assertEquals(40901, conflict.code());
        assertEquals("同一租户同一时间只允许一个导入作业，已有运行中的作业", conflict.getMessage());
    }

    @Test
    void runPrecheckRejectsJobNotInUploadedStatus() {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile("a.xlsx", 1, null), 1L, "u");
        staging.casStatus(jobId, "UPLOADED", "PRECHECKING");
        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.runPrecheck(
                                        jobId,
                                        new ByteArrayInputStream(
                                                XlsxTestFiles.workbook(
                                                        new XlsxTestFiles.Sheet(
                                                                "患者数据",
                                                                List.of(List.of("病案号")))))));
        assertEquals(40901, conflict.code());
    }

    @Test
    void runPrecheckRejectsUnknownJob() {
        ApiException notFound =
                assertThrows(
                        ApiException.class,
                        () -> service.runPrecheck(404L, new ByteArrayInputStream(new byte[] {1})));
        assertEquals(40400, notFound.code());
    }

    // ---------------------------------------------------------------- 宽表暂存

    @Test
    void wideWorkbookStagesPatientRowsWithBlindIndexAndCipher() throws Exception {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile("wide.xlsx", 10, null), 9L, "op");
        ImportStagingService.PrecheckOutcome outcome =
                service.runPrecheck(
                        jobId,
                        new ByteArrayInputStream(
                                XlsxTestFiles.workbook(
                                        new XlsxTestFiles.Sheet(
                                                "患者数据",
                                                List.of(
                                                        wideHeader(1),
                                                        wideRow(
                                                                "1",
                                                                "P001（示例）",
                                                                "MRN001（示例）",
                                                                "张三（示例）",
                                                                "男",
                                                                "60",
                                                                "2020-01-10",
                                                                "冠心病",
                                                                "90%",
                                                                "2020-01-10",
                                                                "MRN001（示例）",
                                                                "张三（示例）",
                                                                "男",
                                                                "60",
                                                                "2021-02-11",
                                                                "0",
                                                                "",
                                                                "55"),
                                                        wideRow())))));
        assertEquals("PRECHECKED", outcome.status());
        assertEquals("WIDE", outcome.layout());
        assertEquals(1, outcome.stagedRows());

        assertEquals("PRECHECKED", staging.findJob(jobId).status());
        assertEquals(1, staging.rows.size());
        StagedRow row = staging.rows.get(0);
        assertEquals("患者数据", row.sheetName());
        assertEquals(2, row.rowNo());
        assertEquals("PATIENT", row.rowType());
        assertEquals("blind(MRN001（示例）)", row.mrnBlind());

        // 密文里是完整解析结果（明文不落列；假加密加前缀）：单元格原文与坐标保留给 E18-T03 批注
        JsonNode payload = json.readTree(row.payloadCipher().substring(CIPHER_PREFIX.length()));
        assertEquals("MRN001（示例）", payload.get("mrn").asText());
        assertEquals("P001（示例）", payload.get("legacyNo").asText());
        assertEquals("MRN001（示例）", payload.get("mrnCell").get("v").asText());
        assertEquals(2, payload.get("mrnCell").get("r").asInt());
        assertEquals(10, payload.get("mrnCell").get("c").asInt(), "基线病案号列 = 前置 9 + 1");
        assertEquals("2020-01-10", payload.get("baseline").get("f001").get("v").asText());
        assertEquals(9, payload.get("baseline").get("f001").get("c").asInt());
        assertEquals(1, payload.get("followups").size());
        assertEquals(1, payload.get("followups").get(0).get("group").asInt());
        assertEquals(
                "55", payload.get("followups").get(0).get("values").get("f257").get("v").asText());
        assertNull(staging.inserts.get(0).problems());

        // 暂存完成随即校验（E18-T03）：是否死亡「0」按 Q36 警告、EF 55 在范围内、无错误
        assertEquals(0, outcome.errorCount());
        assertEquals(1, outcome.warnCount());
        assertEquals(1, staging.issues.size());
        assertEquals("BOOLEAN_01", staging.issues.get(0).code());
        assertEquals(15, staging.issues.get(0).colNo(), "f215 = 前置 9 + 锚点基线 5 + 1（0 起）");
    }

    // ---------------------------------------------------------------- 长表暂存

    @Test
    void longWorkbookStagesBaselineAndFollowupRowsSeparately() throws Exception {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile("long.xlsx", 10, null), 9L, "op");
        ImportStagingService.PrecheckOutcome outcome =
                service.runPrecheck(
                        jobId,
                        new ByteArrayInputStream(
                                XlsxTestFiles.workbook(
                                        new XlsxTestFiles.Sheet(
                                                "基线",
                                                List.of(
                                                        LONG_BASELINE_HEADERS,
                                                        padded(
                                                                LONG_BASELINE_HEADERS.size(),
                                                                "2020-01-10",
                                                                "MRN001（示例）",
                                                                "张三（示例）",
                                                                "男",
                                                                "1970-01-01",
                                                                "50",
                                                                "P001（示例）"))),
                                        new XlsxTestFiles.Sheet(
                                                "随访记录",
                                                List.of(
                                                        LONG_FOLLOWUP_HEADERS,
                                                        padded(
                                                                LONG_FOLLOWUP_HEADERS.size(),
                                                                "MRN001（示例）",
                                                                "1",
                                                                "2021-02-11",
                                                                "0",
                                                                "55"),
                                                        // 年度解析不了 + 病案号为空：行保留、问题列表记录
                                                        padded(
                                                                LONG_FOLLOWUP_HEADERS.size(),
                                                                "",
                                                                "第三年",
                                                                "",
                                                                "",
                                                                ""))))));
        assertEquals("PRECHECKED", outcome.status());
        assertEquals("LONG", outcome.layout());
        assertEquals(3, outcome.stagedRows());

        assertEquals("BASELINE", staging.rows.get(0).rowType());
        assertEquals("基线", staging.rows.get(0).sheetName());
        assertEquals("blind(MRN001（示例）)", staging.rows.get(0).mrnBlind());
        JsonNode baseline =
                json.readTree(
                        staging.rows.get(0).payloadCipher().substring(CIPHER_PREFIX.length()));
        assertEquals("P001（示例）", baseline.get("legacyNo").asText());

        assertEquals("FOLLOWUP", staging.rows.get(1).rowType());
        assertEquals(2, staging.rows.get(1).rowNo());
        JsonNode followup =
                json.readTree(
                        staging.rows.get(1).payloadCipher().substring(CIPHER_PREFIX.length()));
        assertEquals(1, followup.get("year").asInt());
        assertNull(staging.inserts.get(1).problems());

        // 病案号为空：mrn_blind 为 NULL、问题列出「病案号为空」；年度解析不了也进问题列表
        assertNull(staging.rows.get(2).mrnBlind());
        JsonNode problems = json.readTree(staging.inserts.get(2).problems());
        assertEquals(2, problems.size());
        assertTrue(problems.get(0).asText().contains("病案号为空"));
        assertTrue(problems.get(1).asText().contains("随访年度"));

        // 校验（E18-T03）：空病案号与坏年度报错、必填「是否死亡」为空与 Q36「0/1」警告
        assertEquals(2, outcome.errorCount());
        assertEquals(2, outcome.warnCount());
        assertTrue(
                staging.issues.stream().anyMatch(issue -> "MRN_BLANK".equals(issue.code())),
                "病案号为空报错");
        assertTrue(
                staging.issues.stream().anyMatch(issue -> "YEAR_INVALID".equals(issue.code())),
                "随访年度解析不了报错");
        assertTrue(
                staging.issues.stream().anyMatch(issue -> "REQUIRED_EMPTY".equals(issue.code())),
                "必填为空只警告");
    }

    // ---------------------------------------------------------------- 预检失败

    @Test
    void badHeaderMarksPrecheckFailedWithLayoutProblemsAndNoRows() throws Exception {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile("bad.xlsx", 10, null), 9L, "op");
        List<String> broken = new ArrayList<>(wideHeader(1));
        broken.set(0, "对不上的前置列");
        ImportStagingService.PrecheckOutcome outcome =
                service.runPrecheck(
                        jobId,
                        new ByteArrayInputStream(
                                XlsxTestFiles.workbook(
                                        new XlsxTestFiles.Sheet(
                                                "患者数据", List.of(broken, wideRow())))));
        assertEquals("PRECHECK_FAILED", outcome.status());
        assertTrue(outcome.layoutProblems().toString().contains("前置列"));
        assertEquals(0, outcome.stagedRows());

        JobRow job = staging.findJob(jobId);
        assertEquals("PRECHECK_FAILED", job.status());
        JsonNode problems = json.readTree(job.layoutProblems());
        assertTrue(problems.size() >= 1);
        assertTrue(staging.rows.isEmpty());
        assertNotNull(job.failReason());
    }

    @Test
    void garbageFileMarksPrecheckFailed() {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile("bad.xlsx", 10, null), 9L, "op");
        ImportStagingService.PrecheckOutcome outcome =
                service.runPrecheck(jobId, new ByteArrayInputStream("不是 xlsx".getBytes()));
        assertEquals("PRECHECK_FAILED", outcome.status());
        assertEquals(0, outcome.stagedRows());
        assertEquals("PRECHECK_FAILED", staging.findJob(jobId).status());
    }

    // ---------------------------------------------------------------- 分批与进度

    @Test
    void flushesInBatchesOfFiveHundredAndUpdatesProgress() {
        long jobId =
                service.createJob(
                        new ImportStagingService.NewImportFile("many.xlsx", 10, null), 9L, "op");
        List<List<String>> rows = new ArrayList<>();
        rows.add(wideHeader(1));
        for (int i = 0; i < 1201; i++) {
            rows.add(
                    wideRow(
                            String.valueOf(i + 1),
                            "P" + i + "（示例）",
                            "MRN" + i + "（示例）",
                            "患者" + i + "（示例）",
                            "男",
                            "60",
                            "2020-01-10",
                            "冠心病",
                            "90%",
                            "",
                            "MRN" + i + "（示例）",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            ""));
        }
        ImportStagingService.PrecheckOutcome outcome =
                service.runPrecheck(
                        jobId,
                        new ByteArrayInputStream(
                                XlsxTestFiles.workbook(new XlsxTestFiles.Sheet("患者数据", rows))));
        assertEquals("PRECHECKED", outcome.status());
        assertEquals(1201, outcome.stagedRows());
        // 1201 行 → 3 批（500 / 500 / 201），每批一次 insertRows + 一次进度更新
        assertEquals(3, staging.insertBatches.size());
        assertEquals(List.of(500, 500, 201), staging.insertBatches);
        assertEquals(1201, staging.rows.size());
        assertEquals(1201, staging.findJob(jobId).stagedRows());
    }

    // ---------------------------------------------------------------- 夹具

    private static final List<String> FRONT_COLUMNS =
            List.of("序号", "患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类", "数据完整度");

    private static final List<String> WIDE_BASELINE_HEADERS =
            List.of("手术日期", "病案号", "姓名", "性别", "年龄");

    private static final List<String> WIDE_FOLLOWUP_HEADERS = List.of("随访日期", "死亡", "死亡随访时间", "EF");

    private static final List<String> LONG_BASELINE_HEADERS =
            List.of("手术日期", "病案号", "姓名", "性别", "出生日期", "手术年龄", "原系统编号");

    private static final List<String> LONG_FOLLOWUP_HEADERS =
            List.of("病案号", "随访年度", "随访日期", "是否死亡", "EF");

    /** 与 catalog() 同一套字段的校验规则（E18-T03 校验注入；值全部虚构）。 */
    private static ImportFieldRules rules() {
        return new ImportFieldRules(
                List.of(
                        rule("f001", "手术日期", FieldType.DATE, false, false, null),
                        rule("f002", "病案号", FieldType.TEXT, true, true, null),
                        rule("f003", "姓名", FieldType.TEXT, false, true, null),
                        rule("f004", "性别", FieldType.SELECT, false, false, Map.of(1, "男", 2, "女")),
                        rule("f005", "手术年龄", FieldType.NUMBER, false, false, null),
                        rule("f267", "出生日期", FieldType.DATE, false, false, null)),
                List.of(
                        rule("f214", "随访日期", FieldType.DATE, false, false, null),
                        rule("f215", "是否死亡", FieldType.RADIO, true, false, Map.of(0, "否", 1, "是")),
                        rule("f217", "死亡随访时间", FieldType.DATE, false, false, null),
                        rule("f257", "EF", FieldType.NUMBER, false, false, null)),
                "f002");
    }

    private static ImportFieldRule rule(
            String fieldKey,
            String label,
            FieldType type,
            boolean required,
            boolean sensitive,
            Map<Integer, String> options) {
        return new ImportFieldRule(
                fieldKey, label, label, true, type, null, null, 0.0, 75.0, 20.0, 70.0, required,
                sensitive, options, null);
    }

    private static ImportFieldCatalog catalog() {
        return new com.crdp.followup.importer.domain.StaticImportFieldCatalog(
                List.of(
                        new ImportFieldCatalog.CatalogField("f001", "手术日期", "手术日期", true),
                        new ImportFieldCatalog.CatalogField("f002", "病案号", "病案号", true),
                        new ImportFieldCatalog.CatalogField("f003", "姓名", "姓名", true),
                        new ImportFieldCatalog.CatalogField("f004", "性别", "性别", true),
                        new ImportFieldCatalog.CatalogField("f005", "手术年龄", "年龄", true),
                        new ImportFieldCatalog.CatalogField("f267", "出生日期", null, true)),
                List.of(
                        new ImportFieldCatalog.CatalogField("f214", "随访日期", "随访日期", true),
                        new ImportFieldCatalog.CatalogField("f215", "是否死亡", "死亡", true),
                        new ImportFieldCatalog.CatalogField("f217", "死亡随访时间", "死亡随访时间", false),
                        new ImportFieldCatalog.CatalogField("f257", "EF", "EF", true)),
                "f002");
    }

    private static List<String> wideHeader(int groups) {
        List<String> header = new ArrayList<>(FRONT_COLUMNS);
        header.addAll(WIDE_BASELINE_HEADERS);
        for (int g = 0; g < groups; g++) {
            header.addAll(WIDE_FOLLOWUP_HEADERS);
        }
        return header;
    }

    private static List<String> wideRow(String... cells) {
        return padded(wideHeader(1).size(), cells);
    }

    private static List<String> padded(int columns, String... cells) {
        List<String> row = new ArrayList<>(List.of(cells));
        while (row.size() < columns) {
            row.add("");
        }
        return row;
    }

    /** 假 mapper：内存态记录作业 / 暂存行与调用轨迹（insertBatches / progressDeltas）。 */
    private static final class FakeStagingMapper implements ImportStagingMapper {

        /** 可变作业态（findJob 时转成 JobRow）。 */
        private static final class MutableJob {
            long id;
            String fileName;
            long fileSize;
            String fileSha256;
            String layout;
            String status;
            long stagedRows;
            String layoutProblems;
            String failReason;
            long operatorId;
            String operatorName;
        }

        private final Map<Long, MutableJob> jobs = new LinkedHashMap<>();
        private final List<RowInsert> inserts = new ArrayList<>();
        private final List<StagedRow> rows = new ArrayList<>();
        private final List<IssueInsert> issues = new ArrayList<>();
        private final List<Integer> insertBatches = new ArrayList<>();
        private final List<Long> progressDeltas = new ArrayList<>();
        private long sequence;

        @Override
        public int insertJob(JobInsert row) {
            sequence += 1;
            row.setId(sequence);
            MutableJob job = new MutableJob();
            job.id = sequence;
            job.fileName = row.getFileName();
            job.fileSize = row.getFileSize();
            job.fileSha256 = row.getFileSha256();
            job.status = "UPLOADED";
            job.operatorId = row.getOperatorId();
            job.operatorName = row.getOperatorName();
            jobs.put(sequence, job);
            return 1;
        }

        @Override
        public long countActive(List<String> statuses) {
            return jobs.values().stream().filter(job -> statuses.contains(job.status)).count();
        }

        @Override
        public JobRow findJob(long id) {
            MutableJob job = jobs.get(id);
            return job == null
                    ? null
                    : new JobRow(
                            job.id,
                            job.fileName,
                            job.fileSize,
                            job.fileSha256,
                            null,
                            job.layout,
                            job.status,
                            job.stagedRows,
                            job.layoutProblems,
                            job.failReason,
                            job.operatorId,
                            job.operatorName,
                            null,
                            null);
        }

        @Override
        public int casStatus(long id, String from, String to) {
            MutableJob job = jobs.get(id);
            if (job == null || !from.equals(job.status)) {
                return 0;
            }
            job.status = to;
            return 1;
        }

        @Override
        public int addStagedRows(long id, long delta) {
            progressDeltas.add(delta);
            MutableJob job = jobs.get(id);
            job.stagedRows += delta;
            return 1;
        }

        @Override
        public int finishPrecheck(long id, String layout, long stagedRows) {
            MutableJob job = jobs.get(id);
            job.status = "PRECHECKED";
            job.layout = layout;
            job.stagedRows = stagedRows;
            return 1;
        }

        @Override
        public int failPrecheck(long id, String layout, String layoutProblems, String failReason) {
            MutableJob job = jobs.get(id);
            job.status = "PRECHECK_FAILED";
            job.layout = layout;
            job.layoutProblems = layoutProblems;
            job.failReason = failReason;
            return 1;
        }

        @Override
        public int insertRows(List<RowInsert> batch) {
            inserts.addAll(batch);
            for (RowInsert row : batch) {
                rows.add(
                        new StagedRow(
                                ++sequence,
                                row.sheetName(),
                                row.rowNo(),
                                row.rowType(),
                                row.mrnBlind(),
                                row.payloadCipher()));
            }
            insertBatches.add(batch.size());
            return batch.size();
        }

        @Override
        public int deleteRows(long jobId) {
            rows.clear(); // 单作业用例（fake 不跨作业混行）
            inserts.clear();
            return 1;
        }

        @Override
        public List<StagedRow> findRows(long jobId, long afterId, int limit) {
            return rows.stream()
                    .filter(row -> row.id() > afterId)
                    .sorted(java.util.Comparator.comparingLong(StagedRow::id))
                    .limit(limit)
                    .toList();
        }

        @Override
        public int insertIssues(List<IssueInsert> batch) {
            issues.addAll(batch);
            return batch.size();
        }

        @Override
        public int deleteIssues(long jobId) {
            issues.clear();
            return 1;
        }

        @Override
        public long countIssues(long jobId, String level) {
            return issues.stream()
                    .filter(issue -> level == null || issue.level().equals(level))
                    .count();
        }
    }
}
