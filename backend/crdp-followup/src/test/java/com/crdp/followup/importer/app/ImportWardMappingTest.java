package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportRowPayloads.BaselineRow;
import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.app.ImportValidationService.ValidationSummary;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.ImportWardDirectory.WardEntry;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.RowInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.StagedRow;
import com.crdp.followup.patient.app.PatientBlindIndexService;
import com.crdp.form.field.domain.FieldType;
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.framework.crypto.CryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E18-T04 病区值与字典值映射的校验接入测试（A-WARD-006；tasks.csv 验收「未映射值导入前必须处理」）： 「病区/中心」列取值按病区字典（编码 / 名称 / 别名）解析——
 * 匹配（含历史写法别名）不产问题且类型化值为病区 id（E18-T05 提交用）， 未映射 / 对应病区已停用 / 一值多区都报 <strong>ERROR</strong>（有 ERROR
 * 确认提交被拦，import-export.md §1）； 空值仍按「必填字段为空」警告口径（历史导入仅提示）； 字典里没有「病区/中心」字段时不做病区检查（其他租户形态）。问题钉在病区单元格（行
 * / 列）。全部虚构数据。
 */
class ImportWardMappingTest {

    private static final String CIPHER_PREFIX = "enc:";

    private final FakeStagingMapper staging = new FakeStagingMapper();
    private final CryptoService crypto = mock(CryptoService.class);
    private final ImportFieldRuleLoader ruleLoader = mock(ImportFieldRuleLoader.class);
    private final ImportWardDirectoryLoader wardDirectoryLoader =
            mock(ImportWardDirectoryLoader.class);
    private final PatientBlindIndexService patientBlindIndex = mock(PatientBlindIndexService.class);
    private final ObjectMapper json = new ObjectMapper();

    private ImportValidationService service;

    /** 心外一病区（别名「心外」）+ 已停用老病区 + 一值多区夹具（别名「心病区」两区共用）。 */
    private static ImportWardDirectory directory() {
        return new ImportWardDirectory(
                List.of(
                        new WardEntry(11, "CVSW1", "心外一病区", "ENABLED", List.of("心外")),
                        new WardEntry(12, "OLD1", "老病区（示例）", "DISABLED", List.of()),
                        new WardEntry(21, "W1", "心病区一（示例）", "ENABLED", List.of("心病区")),
                        new WardEntry(22, "W2", "心病区二（示例）", "ENABLED", List.of("心病区"))));
    }

    @BeforeEach
    void setUp() {
        when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(inv -> CIPHER_PREFIX + inv.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0).toString().substring(CIPHER_PREFIX.length()));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(inv -> "blind(" + inv.getArgument(1) + ")");
        when(ruleLoader.load()).thenReturn(rules("f007"));
        when(wardDirectoryLoader.load()).thenReturn(directory());
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

    // ---------------------------------------------------------------- 长表基线行

    @Test
    void wardValueMatchingAliasProducesNoIssue() {
        long jobId = longJobWithWard("心外", 5);
        ValidationSummary summary = service.validate(jobId);

        assertEquals(0, summary.errorCount(), "历史写法别名匹配 → 不产问题");
        assertEquals(0, summary.warnCount());
    }

    @Test
    void unmappedWardValueIsErrorPinnedToWardCell() {
        long jobId = longJobWithWard("心内科三病区（示例）", 5);
        ValidationSummary summary = service.validate(jobId);

        assertEquals(1, summary.errorCount(), "未映射值导入前必须处理（ERROR 阻断确认提交）");
        IssueInsert issue = issue("WARD_UNMAPPED");
        assertEquals(2, issue.rowNo());
        assertEquals(5, issue.colNo(), "问题钉在「病区/中心」单元格（长表基线第 6 列，0 起 5）");
        assertTrue(issue.message().contains("心内科三病区（示例）"), "病区值非敏感，回显供核对");
        assertTrue(issue.message().contains("心外一病区"), "提示可映射的病区候选");
        assertTrue(issue.message().contains("别名"), "提示处理方式：修表或维护别名");
    }

    @Test
    void disabledWardValueIsErrorWithWardName() {
        long jobId = longJobWithWard("老病区（示例）", 5);
        ValidationSummary summary = service.validate(jobId);

        assertEquals(1, summary.errorCount());
        IssueInsert issue = issue("WARD_DISABLED");
        assertTrue(issue.message().contains("已停用"), issue.message());
        assertTrue(issue.message().contains("老病区（示例）"));
    }

    @Test
    void ambiguousWardValueIsErrorListingCandidates() {
        long jobId = longJobWithWard("心病区", 5);
        ValidationSummary summary = service.validate(jobId);

        assertEquals(1, summary.errorCount());
        IssueInsert issue = issue("WARD_AMBIGUOUS");
        assertTrue(
                issue.message().contains("心病区一（示例）") && issue.message().contains("心病区二（示例）"),
                "列出冲突候选：" + issue.message());
    }

    @Test
    void blankWardValueStaysRequiredEmptyWarning() {
        long jobId = longJobWithWard("  ", 5);
        ValidationSummary summary = service.validate(jobId);

        assertEquals(0, summary.errorCount(), "空值不是未映射值：按「必填字段为空」警告口径（历史导入仅提示）");
        assertEquals(1, summary.warnCount());
        assertEquals("REQUIRED_EMPTY", issue("REQUIRED_EMPTY").code());
    }

    // ---------------------------------------------------------------- 宽表行 + 无病区字段形态

    @Test
    void wideRowWardValueAlsoChecked() {
        long jobId = wideJobWithWard("心内科三病区（示例）");
        ValidationSummary summary = service.validate(jobId);

        assertEquals(1, summary.errorCount(), "宽表基线列同样走病区字典解析");
        IssueInsert issue = issue("WARD_UNMAPPED");
        assertEquals(2, issue.rowNo());
        assertEquals(11, issue.colNo(), "宽表前置 9 + 基线锚点序（f002 病案号、f007 病区……），按行内坐标");
    }

    @Test
    void noWardFieldInDictionarySkipsWardChecks() {
        when(ruleLoader.load()).thenReturn(rules(null));
        long jobId = longJobWithWard("随便什么写法（示例）", 5);
        ValidationSummary summary = service.validate(jobId);

        assertEquals(0, summary.errorCount(), "字典没有「病区/中心」字段（其他租户形态）时不做病区检查");
        assertEquals(0, summary.warnCount());
    }

    // ---------------------------------------------------------------- 夹具：规则与暂存行

    /** 基线规则：f002 病案号（敏感必填）+ f007 病区/中心（必填、无固定选项集——值来自病区字典）。 */
    private static ImportFieldRules rules(String wardFieldKey) {
        ImportFieldRule wardRule = null;
        if ("f007".equals(wardFieldKey)) {
            wardRule = rule("f007", "病区/中心", FieldType.SELECT, true);
        }
        List<ImportFieldRule> baseline = new ArrayList<>();
        baseline.add(rule("f002", "病案号", FieldType.TEXT, true));
        if (wardRule != null) {
            baseline.add(wardRule);
        }
        return new ImportFieldRules(
                baseline,
                List.of(rule("f214", "随访日期", FieldType.DATE, false)),
                "f002",
                wardFieldKey,
                "A_BASELINE（示例）",
                "A_ANNUAL_FU（示例）");
    }

    private static ImportFieldRule rule(
            String fieldKey, String label, FieldType type, boolean required) {
        return new ImportFieldRule(
                fieldKey,
                label,
                label,
                null,
                "json",
                true,
                type,
                null,
                null,
                null,
                null,
                null,
                null,
                required,
                "f002".equals(fieldKey),
                Map.of(),
                null);
    }

    private long longJobWithWard(String wardValue, int wardColNo) {
        long jobId = newJob("LONG");
        Map<String, CellValue> baseline = new LinkedHashMap<>();
        baseline.put("f002", cell(2, 1, "MRN001（示例）"));
        baseline.put("f007", cell(2, wardColNo, wardValue));
        stageRow(
                jobId,
                "基线",
                2,
                "BASELINE",
                "MRN001（示例）",
                new BaselineRow("MRN001（示例）", cell(2, 1, "MRN001（示例）"), "L001（示例）", baseline));
        return jobId;
    }

    private long wideJobWithWard(String wardValue) {
        long jobId = newJob("WIDE");
        Map<String, CellValue> baseline = new LinkedHashMap<>();
        baseline.put("f002", cell(2, 10, "MRN001（示例）"));
        baseline.put("f007", cell(2, 11, wardValue));
        stageRow(
                jobId,
                "患者数据",
                2,
                "PATIENT",
                "MRN001（示例）",
                new com.crdp.followup.importer.app.ImportRowPayloads.WideRow(
                        "MRN001（示例）",
                        cell(2, 10, "MRN001（示例）"),
                        null,
                        Map.of(),
                        baseline,
                        List.of()));
        return jobId;
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

    private long newJob(String layout) {
        JobInsert insert = new JobInsert("导入（示例）.xlsx", 10, null, 1L, "导入账号（示例）");
        staging.insertJob(insert);
        staging.job.layout = layout;
        staging.job.status = "PRECHECKING";
        return insert.getId();
    }

    private IssueInsert issue(String code) {
        return staging.issues.stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有产生 " + code + " 问题"));
    }

    /** 假 mapper：内存态作业 / 暂存行 / 问题（只实现校验路径用到的方法，与 ImportValidationServiceTest 同款）。 */
    private static final class FakeStagingMapper implements ImportStagingMapper {

        private MutableJob job;
        private final List<StagedRow> rows = new ArrayList<>();
        private final List<IssueInsert> issues = new ArrayList<>();
        private long sequence;

        private static final class MutableJob {
            long id;
            String layout;
            String status = "UPLOADED";
            String optionsJson;
            String scopeJson;
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
                            job.optionsJson,
                            job.scopeJson,
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

        /** E18-T06 问题分页与存储键回填（向导页）：假实现只服务既有用例，不命中断言。 */
        @Override
        public List<IssueRow> findIssues(long jobId, String level, long offset, int limit) {
            return List.of();
        }

        @Override
        public int updateStorageKey(long id, String storageKey) {
            return 0;
        }

        @Override
        public long countIssues(long jobId, String level) {
            return issues.stream()
                    .filter(issue -> level == null || issue.level().equals(level))
                    .count();
        }
    }
}
