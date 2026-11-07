package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportRowPayloads.BaselineRow;
import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupRow;
import com.crdp.followup.importer.app.ImportRowPayloads.WideRow;
import com.crdp.followup.importer.app.ImportValidationService.ValidationSummary;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportWardDirectory;
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
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E18-T03 校验与批注版报告的服务面测试（tasks.csv 验收「错误单元格带批注返回」）： 字典级问题落 imp_issue、跨行查重（文件内重复 / 系统已存在 → 警告按策略处理；
 * 同年两行 / 随访行无基线 → 报错）、 派生字段与重算结果比对（以重算为准，警告）、 前置列与基线列不一致（以基线为准，警告且不回显敏感值）、 批注版 Excel 错误 /
 * 警告单元格标色加批注、清单表敏感列脱敏。全部虚构数据（规则 5）。
 */
class ImportValidationServiceTest {

    private static final String CIPHER_PREFIX = "enc:";

    private final FakeStagingMapper staging = new FakeStagingMapper();
    private final CryptoService crypto = mock(CryptoService.class);
    private final ImportFieldRuleLoader ruleLoader = mock(ImportFieldRuleLoader.class);
    private final ImportWardDirectoryLoader wardDirectoryLoader =
            mock(ImportWardDirectoryLoader.class);
    private final PatientBlindIndexService patientBlindIndex = mock(PatientBlindIndexService.class);
    private final ObjectMapper json = new ObjectMapper();

    private ImportValidationService service;

    @BeforeEach
    void setUp() {
        when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(inv -> CIPHER_PREFIX + inv.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0).toString().substring(CIPHER_PREFIX.length()));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(inv -> "blind(" + inv.getArgument(1) + ")");
        when(ruleLoader.load()).thenReturn(rules());
        when(wardDirectoryLoader.load()).thenReturn(new ImportWardDirectory(List.of()));
        when(patientBlindIndex.existingBlinds(any())).thenReturn(Set.of());
        service =
                new ImportValidationService(
                        staging,
                        ruleLoader,
                        crypto,
                        patientBlindIndex,
                        new FormDerivedEngine(),
                        json,
                        wardDirectoryLoader);
    }

    // ---------------------------------------------------------------- 宽表：重复 / 已存在 / 前置列冲突

    @Test
    void wideRowsReportFileDuplicateExistingDuplicateAndFrontMismatch() {
        long jobId = wideJobWithTwoDuplicatePatients();
        when(patientBlindIndex.existingBlinds(any())).thenReturn(Set.of("blind(MRN001（示例）)"));

        ValidationSummary summary = service.validate(jobId);

        assertEquals(0, summary.errorCount());
        assertEquals(3, summary.warnCount(), "已存在重复 + 前置列不一致 + 文件内重复");
        assertEquals(2, summary.patientRows());

        IssueInsert frontMismatch = issue("FRONT_MISMATCH");
        assertEquals(2, frontMismatch.rowNo());
        assertEquals(3, frontMismatch.colNo(), "前置列「姓名」= 第 4 列（0 起 3）");
        assertFalse(frontMismatch.message().contains("张三"), "敏感字段不回显姓名原值（OQ-101③）");
        assertFalse(frontMismatch.message().contains("李四"));
        assertTrue(frontMismatch.message().contains("以基线列为准"));

        assertEquals(2, issue("DUP_EXISTING").rowNo(), "系统已存在提示在文件内首次出现行");
        assertEquals(3, issue("DUP_FILE").rowNo(), "文件内重复提示在再次出现行");
        assertTrue(issue("DUP_FILE").message().contains("第 2 行"));
    }

    // ---------------------------------------------------------------- 长表：无基线 / 同年两行 / 身份列

    @Test
    void longRowsReportNoBaselineDuplicateYearBlankMrnAndDerivedMismatch() {
        long jobId = longJobWithProblemRows();
        ValidationSummary summary = service.validate(jobId);

        assertEquals(4, summary.errorCount(), "无基线 + 同年两行 + 病案号为空 + 年度为空");
        assertEquals(1, summary.warnCount(), "派生重算不一致");
        assertEquals(2, summary.patientRows());
        assertEquals(4, summary.followupRows());

        IssueInsert noBaseline = issue("NO_BASELINE");
        assertEquals(6, noBaseline.rowNo());
        assertEquals(0, noBaseline.colNo(), "钉在「病案号」列（长表随访记录第 1 列）");
        assertEquals(5, issue("DUP_YEAR").rowNo());
        assertTrue(issue("DUP_YEAR").message().contains("第 4 行"));
        assertEquals(1, issue("DUP_YEAR").colNo(), "钉在「随访年度」列");
        IssueInsert mrnBlank = issue("MRN_BLANK");
        assertEquals(7, mrnBlank.rowNo());
        assertTrue(issue("YEAR_INVALID").message().contains("为空"));

        IssueInsert derived = issue("DERIVED_MISMATCH");
        assertEquals(2, derived.rowNo());
        assertEquals(13, derived.colNo(), "长表布局里 f005 手术年龄按行内坐标（0 起 13）");
        assertTrue(derived.message().contains("30"), "以重算结果（1970-01-01 → 2000-01-10 = 30 岁）为准");
    }

    @Test
    void validateRejectsJobOutsidePrecheckStates() {
        long jobId = wideJobWithTwoDuplicatePatients();
        staging.job.status = "UPLOADED";
        ApiException conflict = assertThrows(() -> service.validate(jobId));
        assertEquals(40901, conflict.code());
        ApiException missing = assertThrows(() -> service.validate(404L));
        assertEquals(40400, missing.code());
    }

    @Test
    void revalidationReplacesIssuesIdempotently() {
        long jobId = wideJobWithTwoDuplicatePatients();
        service.validate(jobId);
        int first = staging.issues.size();
        service.validate(jobId);
        assertEquals(first, staging.issues.size(), "重跑先清旧问题（幂等）");
    }

    // ---------------------------------------------------------------- 批注版报告

    @Test
    void annotatedReportMarksErrorCellsWithCommentsAndMasksSensitiveRaw() throws IOException {
        long jobId = longJobWithProblemRows();
        service.validate(jobId);
        byte[] report = service.annotatedReport(jobId);

        Map<String, String> parts = unzip(report);
        // 「校验报告」清单表在前，其后「基线」「随访记录」重建网格
        assertTrue(parts.get("xl/workbook.xml").contains("校验报告"));
        assertTrue(parts.get("xl/workbook.xml").contains("基线"));
        assertTrue(parts.get("xl/workbook.xml").contains("随访记录"));

        // 错误单元格标色（同年两行：第 5 行「随访年度」列 B5）+ 批注内容
        String followupSheet = parts.get("xl/worksheets/sheet3.xml");
        assertTrue(followupSheet.contains("<c r=\"B5\" s=\"1\""), "错误单元格浅红标色");
        String comments = parts.get("xl/comments3.xml");
        assertNotNull(comments);
        assertTrue(comments.contains("<comment ref=\"B5\""));
        assertTrue(comments.contains("【错误】同一病案号与随访年度"));

        // 空病案号单元格（第 7 行 A 列，原值空）也标色并带批注
        assertTrue(followupSheet.contains("<c r=\"A7\" s=\"1\""));
        assertTrue(comments.contains("<comment ref=\"A7\""));

        // 清单表：病案号列原值脱敏、非敏感原值照常展示、原因列带全文
        String summarySheet = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(summarySheet.contains("（已脱敏）"), "敏感列（病案号）原值脱敏显示");
        assertTrue(summarySheet.contains("与派生重算结果不一致"), "警告的原因进入清单表");
        assertTrue(summarySheet.contains("61"), "非敏感原值（手术年龄 61）照常展示供核对");
    }

    // ---------------------------------------------------------------- 夹具：规则与暂存行

    private static ImportFieldRule rule(
            String fieldKey,
            String label,
            FieldType type,
            boolean required,
            boolean sensitive,
            Map<Integer, String> options,
            String derivedDefJson) {
        return new ImportFieldRule(
                fieldKey,
                label,
                label,
                true,
                type,
                null,
                null,
                0.0,
                75.0,
                20.0,
                70.0,
                required,
                sensitive,
                options,
                derivedDefJson);
    }

    /** f005 手术年龄 = AGE_YEARS(f267 出生日期, f001 手术日期)（真实 V38 字典同款定义）。 */
    private static ImportFieldRules rules() {
        return new ImportFieldRules(
                List.of(
                        rule("f001", "手术日期", FieldType.DATE, false, false, null, null),
                        rule("f002", "病案号", FieldType.TEXT, true, true, null, null),
                        rule("f003", "姓名", FieldType.TEXT, false, true, null, null),
                        rule(
                                "f004",
                                "性别",
                                FieldType.SELECT,
                                false,
                                false,
                                Map.of(1, "男", 2, "女"),
                                null),
                        rule(
                                "f005",
                                "手术年龄",
                                FieldType.DERIVED,
                                false,
                                false,
                                null,
                                "{\"fn\":\"AGE_YEARS\",\"args\":[\"f267\",\"f001\"]}"),
                        rule("f267", "出生日期", FieldType.DATE, false, false, null, null)),
                List.of(
                        rule("f214", "随访日期", FieldType.DATE, false, false, null, null),
                        rule(
                                "f215",
                                "是否死亡",
                                FieldType.RADIO,
                                true,
                                false,
                                Map.of(0, "否", 1, "是"),
                                null),
                        rule("f257", "EF", FieldType.NUMBER, false, false, null, null)),
                "f002",
                null);
    }

    private long wideJobWithTwoDuplicatePatients() {
        long jobId = newJob("WIDE", "PRECHECKING");
        wideRow(jobId, 2, "MRN001（示例）", "张三（示例）", "李四（示例）", "61");
        wideRow(jobId, 3, "MRN001（示例）", "张三（示例）", "张三（示例）", "55");
        return jobId;
    }

    private long longJobWithProblemRows() {
        long jobId = newJob("LONG", "PRECHECKING");
        // 基线：MRN001（出生 1970-01-01、手术 2000-01-10 → 手术年龄应 30，原值 61 → 警告）
        Map<String, CellValue> baseline1 = new LinkedHashMap<>();
        baseline1.put("f001", cell(2, 1, "2000-01-10"));
        baseline1.put("f002", cell(2, 2, "MRN001（示例）"));
        baseline1.put("f003", cell(2, 3, "张三（示例）"));
        baseline1.put("f005", cell(2, 13, "61"));
        baseline1.put("f267", cell(2, 6, "1970-01-01"));
        baselineRow(jobId, 2, "MRN001（示例）", baseline1);
        Map<String, CellValue> baseline2 = new LinkedHashMap<>();
        baseline2.put("f001", cell(3, 1, "2010-02-01"));
        baseline2.put("f002", cell(3, 2, "MRN002（示例）"));
        baselineRow(jobId, 3, "MRN002（示例）", baseline2);
        // 随访记录：列 0 病案号、列 1 随访年度
        followupRow(jobId, 4, "MRN001（示例）", 1, "1");
        followupRow(jobId, 5, "MRN001（示例）", 1, "1"); // 同年两行
        followupRow(jobId, 6, "MRN999（示例）", 2, "2"); // 无基线
        followupRow(jobId, 7, null, null, ""); // 病案号与年度都空
        return jobId;
    }

    private void wideRow(
            long jobId, int rowNo, String mrn, String frontName, String baselineName, String age) {
        Map<String, CellValue> front = new LinkedHashMap<>();
        front.put("姓名", cell(rowNo, 3, frontName));
        Map<String, CellValue> baseline = new LinkedHashMap<>();
        baseline.put("f001", cell(rowNo, 9, "2020-01-10"));
        baseline.put("f002", cell(rowNo, 10, mrn));
        baseline.put("f003", cell(rowNo, 11, baselineName));
        baseline.put("f005", cell(rowNo, 13, age));
        stageRow(
                jobId,
                "患者数据",
                rowNo,
                "PATIENT",
                mrn,
                new WideRow(mrn, cell(rowNo, 10, mrn), null, front, baseline, List.of()));
    }

    private void baselineRow(long jobId, int rowNo, String mrn, Map<String, CellValue> baseline) {
        stageRow(
                jobId,
                "基线",
                rowNo,
                "BASELINE",
                mrn,
                new BaselineRow(mrn, cell(rowNo, 2, mrn), "L001（示例）", baseline));
    }

    private void followupRow(long jobId, int rowNo, String mrn, Integer year, String yearRaw) {
        Map<String, CellValue> values = new LinkedHashMap<>();
        values.put("f214", cell(rowNo, 2, "2021-02-11"));
        values.put("f215", cell(rowNo, 3, "是"));
        stageRow(
                jobId,
                "随访记录",
                rowNo,
                "FOLLOWUP",
                mrn,
                new FollowupRow(
                        mrn,
                        cell(rowNo, 0, mrn == null ? "" : mrn),
                        year,
                        yearRaw,
                        cell(rowNo, 1, yearRaw),
                        values));
    }

    private static CellValue cell(int rowNo, int colNo, String value) {
        return new CellValue(value, rowNo, colNo);
    }

    private void stageRow(
            long jobId, String sheetName, int rowNo, String rowType, String mrn, Object payload) {
        staging.insertRows(
                List.of(
                        new RowInsert(
                                jobId,
                                sheetName,
                                rowNo,
                                rowType,
                                mrn == null ? null : "blind(" + mrn + ")",
                                CIPHER_PREFIX + write(payload),
                                null)));
    }

    private String write(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long newJob(String layout, String status) {
        JobInsert insert = new JobInsert("导入（示例）.xlsx", 10, null, 1L, "导入账号（示例）");
        staging.insertJob(insert);
        staging.job.layout = layout;
        staging.job.status = status;
        return insert.getId();
    }

    private IssueInsert issue(String code) {
        return staging.issues.stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有产生 " + code + " 问题"));
    }

    private static ApiException assertThrows(Runnable runnable) {
        try {
            runnable.run();
        } catch (ApiException expected) {
            return expected;
        }
        throw new AssertionError("应抛 ApiException");
    }

    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip =
                new ZipInputStream(new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    /** 假 mapper：内存态作业 / 暂存行 / 问题（只实现校验路径用到的方法）。 */
    private static final class FakeStagingMapper implements ImportStagingMapper {

        private MutableJob job;
        private final List<StagedRow> rows = new ArrayList<>();
        private final List<IssueInsert> issues = new ArrayList<>();
        private long sequence;

        private static final class MutableJob {
            long id;
            String layout;
            String status = "UPLOADED";
            long stagedRows;
        }

        @Override
        public int insertJob(JobInsert row) {
            job = new MutableJob();
            job.id = ++sequence;
            row.setId(job.id);
            return 1;
        }

        @Override
        public long countActive(List<String> statuses) {
            return 0;
        }

        @Override
        public JobRow findJob(long id) {
            return job == null || job.id != id
                    ? null
                    : new JobRow(
                            job.id,
                            "导入（示例）.xlsx",
                            10,
                            null,
                            null,
                            job.layout,
                            job.status,
                            job.stagedRows,
                            null,
                            null,
                            1L,
                            "导入账号（示例）",
                            null,
                            null);
        }

        @Override
        public int casStatus(long id, String from, String to) {
            return 0;
        }

        @Override
        public int addStagedRows(long id, long delta) {
            job.stagedRows += delta;
            return 1;
        }

        @Override
        public int finishPrecheck(long id, String layout, long stagedRows) {
            return 1;
        }

        @Override
        public int failPrecheck(long id, String layout, String layoutProblems, String failReason) {
            return 1;
        }

        @Override
        public int insertRows(List<RowInsert> batch) {
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
            return batch.size();
        }

        @Override
        public int deleteRows(long jobId) {
            rows.clear();
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
