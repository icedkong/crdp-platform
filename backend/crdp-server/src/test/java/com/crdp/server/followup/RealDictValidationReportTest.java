package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportFieldCatalogLoader;
import com.crdp.followup.importer.app.ImportFieldRuleLoader;
import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportStagingService;
import com.crdp.followup.importer.app.ImportStagingService.NewImportFile;
import com.crdp.followup.importer.app.ImportStagingService.PrecheckOutcome;
import com.crdp.followup.importer.app.ImportValidationService;
import com.crdp.followup.importer.app.ImportWardDirectoryLoader;
import com.crdp.followup.importer.domain.ImportCell;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportFieldCatalog.CatalogField;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
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
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.excel.StreamingXlsxGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E18-T03 端到端核对（单元，不依赖数据库）：流式暂存 + 校验 + 批注版报告对<strong>真实医院 A 字段字典</strong>（V38，761 列宽表）——
 * 问题钉回真实列位（列字母、批注引用同一坐标语言，tasks.csv 验收「错误单元格带批注返回」）， 报告可被框架流式网关读回。 字典目录与 {@link
 * RealDictLayoutAdapterTest} 同一夹具；校验规则按真实字段类型手搭（f001 手术日期 date、f002 病案号敏感必填、f005 手术年龄派生
 * AGE_YEARS(f267,f001)、f215 是否死亡 a_yesno、EF 数值硬 / 软范围）。测试数据全部虚构（规则 5）。
 */
class RealDictValidationReportTest {

    private static final String CIPHER_PREFIX = "enc:";

    private static final ImportFieldCatalog CATALOG = RealDictLayoutAdapterTest.realDictCatalog();

    private final ImportStagingMapper staging = mock(ImportStagingMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final ImportFieldCatalogLoader catalogLoader = mock(ImportFieldCatalogLoader.class);
    private final ImportFieldRuleLoader ruleLoader = mock(ImportFieldRuleLoader.class);
    private final ImportWardDirectoryLoader wardDirectoryLoader =
            mock(ImportWardDirectoryLoader.class);
    private final PatientBlindIndexService patientBlindIndex = mock(PatientBlindIndexService.class);
    private final ObjectMapper json = new ObjectMapper();

    /** 假 mapper 状态（Mockito 桩背后的可变存储）。 */
    private final Map<Long, String> jobStatus = new HashMap<>();

    private final List<RowInsert> rows = new ArrayList<>();
    private final List<IssueInsert> issues = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @BeforeEach
    void setUp() {
        when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(inv -> CIPHER_PREFIX + inv.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0).toString().substring(CIPHER_PREFIX.length()));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(inv -> "blind(" + inv.getArgument(1) + ")");
        when(catalogLoader.load()).thenReturn(CATALOG);
        when(ruleLoader.load()).thenReturn(realShapedRules());
        when(wardDirectoryLoader.load()).thenReturn(new ImportWardDirectory(java.util.List.of()));
        when(patientBlindIndex.existingBlinds(any())).thenReturn(java.util.Set.of());

        when(staging.insertJob(any()))
                .thenAnswer(
                        inv -> {
                            JobInsert insert = inv.getArgument(0);
                            long id = sequence.incrementAndGet();
                            insert.setId(id);
                            jobStatus.put(id, "UPLOADED");
                            return 1;
                        });
        when(staging.countActive(any())).thenReturn(0L);
        when(staging.findJob(anyLong()))
                .thenAnswer(
                        inv -> {
                            long id = inv.getArgument(0);
                            String status = jobStatus.get(id);
                            return status == null
                                    ? null
                                    : new JobRow(
                                            id,
                                            "患者导入（示例）.xlsx",
                                            10,
                                            null,
                                            null,
                                            "WIDE",
                                            status,
                                            rows.size(),
                                            null,
                                            null,
                                            1L,
                                            "导入账号（示例）",
                                            null,
                                            null);
                        });
        when(staging.casStatus(anyLong(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            long id = inv.getArgument(0);
                            if (inv.getArgument(1).equals(jobStatus.get(id))) {
                                jobStatus.put(id, inv.getArgument(2));
                                return 1;
                            }
                            return 0;
                        });
        when(staging.addStagedRows(anyLong(), anyLong())).thenReturn(1);
        when(staging.finishPrecheck(anyLong(), anyString(), anyLong()))
                .thenAnswer(
                        inv -> {
                            jobStatus.put(inv.getArgument(0), "PRECHECKED");
                            return 1;
                        });
        when(staging.insertRows(any()))
                .thenAnswer(
                        inv -> {
                            List<RowInsert> batch = inv.getArgument(0);
                            rows.addAll(batch);
                            return batch.size();
                        });
        // 一次性给全（测试只有几个患者，游标分页单批走完）；id 复用行序号保证稳定
        when(staging.findRows(anyLong(), anyLong(), anyInt()))
                .thenAnswer(
                        inv -> {
                            long afterId = inv.getArgument(1);
                            int limit = inv.getArgument(2);
                            List<StagedRow> page = new ArrayList<>();
                            long id = 0;
                            for (RowInsert row : rows) {
                                page.add(
                                        new StagedRow(
                                                ++id,
                                                row.sheetName(),
                                                row.rowNo(),
                                                row.rowType(),
                                                row.mrnBlind(),
                                                row.payloadCipher()));
                            }
                            return page.stream()
                                    .filter(row -> row.id() > afterId)
                                    .limit(limit)
                                    .toList();
                        });
        when(staging.deleteRows(anyLong()))
                .thenAnswer(
                        inv -> {
                            rows.clear();
                            return 1;
                        });
        when(staging.insertIssues(any()))
                .thenAnswer(
                        inv -> {
                            issues.addAll(inv.getArgument(0));
                            return 1;
                        });
        when(staging.deleteIssues(anyLong()))
                .thenAnswer(
                        inv -> {
                            issues.clear();
                            return 1;
                        });
    }

    @Test
    void wideImportProducesIssuesAtRealColumnsAndAnnotatedReport() throws IOException {
        when(patientBlindIndex.existingBlinds(any()))
                .thenReturn(java.util.Set.of("blind(X0001（示例）)"));
        ImportStagingService stagingService =
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
                                json,
                                wardDirectoryLoader));

        long jobId =
                stagingService.createJob(
                        new NewImportFile("患者导入（示例）.xlsx", 10, null), 1L, "导入账号（示例）");
        PrecheckOutcome outcome =
                stagingService.runPrecheck(
                        jobId, new ByteArrayInputStream(hospitalSampleWithErrors()));

        assertEquals("PRECHECKED", outcome.status());
        assertEquals(3, outcome.stagedRows());
        assertEquals(2, outcome.errorCount(), "非法日期 + 超硬范围");
        assertEquals(3, outcome.warnCount(), "Q36 0/1 + 文件内重复 + 系统已存在");

        // 问题钉回真实 761 列网格的列位（f001 手术日期 = 基线首列 J；EF = 第 1 组随访列内）
        IssueInsert dateIssue = issue("TYPE");
        assertEquals(2, dateIssue.rowNo());
        assertEquals(9, dateIssue.colNo());
        assertEquals("J", dateIssue.colLetters(), "基线首列 = 第 10 列（0 起 9）= J");
        assertTrue(dateIssue.message().contains("日期"));

        IssueInsert rangeIssue = issue("HARD_RANGE");
        assertEquals(2, rangeIssue.rowNo());
        assertEquals(efColumn(), rangeIssue.colNo());
        assertEquals(ImportCell.columnLetters(efColumn()), rangeIssue.colLetters());
        assertTrue(rangeIssue.message().contains("150"));

        assertEquals(4, issue("DUP_FILE").rowNo(), "数据行从 2 起：P3 = 第 4 行");
        assertEquals(2, issue("DUP_EXISTING").rowNo());
        // 是否死亡 f215 = 前置 9 + 锚点基线 212 + 锚点随访序（f215 是第 3 个锚点 → 223，列字母 index 224 → 「HM」）
        assertEquals(followupColumn("f215"), issue("BOOLEAN_01").colNo());

        // 批注版报告：错误单元格标色 + 批注（J2 日期、EF 列超范围），清单表含列字母与脱敏原值
        ImportValidationService validation =
                new ImportValidationService(
                        staging,
                        ruleLoader,
                        crypto,
                        patientBlindIndex,
                        new FormDerivedEngine(),
                        json,
                        wardDirectoryLoader);
        byte[] report = validation.annotatedReport(jobId);
        Map<String, String> parts = unzip(report);
        String sheet = parts.get("xl/worksheets/sheet2.xml");
        String efRef = ImportCell.columnLetters(efColumn()) + "2";
        assertTrue(sheet.contains("<c r=\"J2\" s=\"1\""), "非法日期单元格 J2 浅红标色（错误单元格带批注返回）");
        assertTrue(sheet.contains("<c r=\"" + efRef + "\" s=\"1\""), "超硬范围单元格 " + efRef + " 标色");
        String comments = parts.get("xl/comments2.xml");
        assertNotNull(comments);
        assertTrue(comments.contains("<comment ref=\"J2\""));
        assertTrue(comments.contains("【错误】"));
        assertTrue(comments.contains("【警告】"), "Q36 警告批注在 EF 单元格上");
        String summary = parts.get("xl/worksheets/sheet1.xml");
        assertTrue(summary.contains("J"), "清单表带 Excel 列字母");
        assertTrue(summary.contains("（已脱敏）"), "病案号（敏感列）原值脱敏显示");
        assertTrue(!summary.contains("X0001（示例）"), "清单表不出现病案号明文");
    }

    // ---------------------------------------------------------------- 夹具

    /** 真实字段形态的规则（其余字段一律文本透传——本测试关注列位与批注，不是校验矩阵）。 */
    private static ImportFieldRules realShapedRules() {
        Map<String, ImportFieldRule> shaped = new LinkedHashMap<>();
        shaped.put("f001", rule("f001", "手术日期", FieldType.DATE, false, false));
        shaped.put("f002", rule("f002", "病案号", FieldType.TEXT, true, true));
        shaped.put("f003", rule("f003", "姓名", FieldType.TEXT, false, true));
        shaped.put(
                "f005",
                new ImportFieldRule(
                        "f005",
                        "手术年龄",
                        "手术年龄",
                        true,
                        FieldType.DERIVED,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        "{\"fn\":\"AGE_YEARS\",\"args\":[\"f267\",\"f001\"]}"));
        shaped.put("f267", rule("f267", "出生日期", FieldType.DATE, false, false));
        shaped.put(
                "f215",
                new ImportFieldRule(
                        "f215",
                        "是否死亡",
                        "死亡",
                        true,
                        FieldType.RADIO,
                        null,
                        null,
                        0.0,
                        75.0,
                        20.0,
                        70.0,
                        true,
                        false,
                        Map.of(0, "否", 1, "是"),
                        null));
        // f248 EF 射血分数：真实字典硬范围 5–85%、单位 %（V38；锚点「ef_射血分数」）
        shaped.put(
                "f248",
                new ImportFieldRule(
                        "f248",
                        "EF 射血分数",
                        "ef_射血分数",
                        true,
                        FieldType.NUMBER,
                        "%",
                        null,
                        5.0,
                        85.0,
                        null,
                        null,
                        false,
                        false,
                        null,
                        null));
        List<ImportFieldRule> baseline = new ArrayList<>();
        List<ImportFieldRule> followup = new ArrayList<>();
        CATALOG.baselineFields()
                .forEach(
                        field ->
                                baseline.add(
                                        shaped.getOrDefault(
                                                field.fieldKey(),
                                                rule(
                                                        field.fieldKey(),
                                                        field.label(),
                                                        FieldType.TEXT,
                                                        false,
                                                        false))));
        CATALOG.followupFields()
                .forEach(
                        field ->
                                followup.add(
                                        shaped.getOrDefault(
                                                field.fieldKey(),
                                                rule(
                                                        field.fieldKey(),
                                                        field.label(),
                                                        FieldType.TEXT,
                                                        false,
                                                        false))));
        return new ImportFieldRules(baseline, followup, "f002", null);
    }

    private static ImportFieldRule rule(
            String fieldKey, String label, FieldType type, boolean required, boolean sensitive) {
        return new ImportFieldRule(
                fieldKey, label, label, true, type, null, null, null, null, null, null, required,
                sensitive, null, null);
    }

    /** EF 射血分数（f248，真实字典稳定键）。 */
    private static CatalogField efField() {
        return CATALOG.followupFields().stream()
                .filter(field -> "f248".equals(field.fieldKey()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("真实字典没有 EF（f248）随访字段"));
    }

    /** 一个随访字段在宽表第 1 组随访列内的列位（0 起）：前置 9 + 锚点基线 212 + 锚点随访序。 */
    private static int followupColumn(String fieldKey) {
        int anchored = 0;
        for (CatalogField field : CATALOG.followupFields()) {
            if (field.fieldKey().equals(fieldKey)) {
                break;
            }
            if (field.originalHeader() != null) {
                anchored++;
            }
        }
        return WideLayoutAdapter.FRONT_COLUMNS.size() + anchoredBaselineCount() + anchored;
    }

    private static int efColumn() {
        return followupColumn("f248");
    }

    private static int anchoredBaselineCount() {
        return (int)
                CATALOG.baselineFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .count();
    }

    /** 医院样例形状（761 列）+ 第 2 行三类问题：非法日期、EF 超硬范围、是否死亡 0/1；第 3、4 行病案号重复。 */
    private static byte[] hospitalSampleWithErrors() {
        List<String> header = wideHeader(1); // 9 + 212 + 54 = 275 列（1 组随访足够触达 EF / 是否死亡）
        Map<Integer, String> bad = new HashMap<>();
        putBaseline(bad, "f001", "20251345"); // 非法日期（A 规格 §4 示例）
        putBaseline(bad, "f002", "X0001（示例）");
        putBaseline(bad, "f003", "患者一（示例）");
        putFollowup(bad, 1, efField().fieldKey(), "150"); // 超硬范围
        putFollowup(bad, 1, "f215", "0"); // Q36：是否类 0/1
        Map<Integer, String> dup2 = new HashMap<>();
        putBaseline(dup2, "f001", "20260115");
        putBaseline(dup2, "f002", "X0002（示例）");
        Map<Integer, String> dup3 = new HashMap<>();
        putBaseline(dup3, "f001", "20260115");
        putBaseline(dup3, "f002", "X0002（示例）");
        return SimpleXlsx.write(
                header,
                List.of(
                        cells(bad, header.size()),
                        cells(dup2, header.size()),
                        cells(dup3, header.size())));
    }

    private static List<String> wideHeader(int groups) {
        List<String> header = new ArrayList<>(WideLayoutAdapter.FRONT_COLUMNS);
        CATALOG.baselineFields().stream()
                .filter(field -> field.originalHeader() != null)
                .forEach(field -> header.add(field.originalHeader()));
        List<String> followupAnchors =
                CATALOG.followupFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(CatalogField::originalHeader)
                        .toList();
        for (int g = 0; g < groups; g++) {
            header.addAll(followupAnchors);
        }
        return header;
    }

    private static void putBaseline(Map<Integer, String> row, String key, String value) {
        List<String> keys = anchoredBaselineKeys();
        row.put(WideLayoutAdapter.FRONT_COLUMNS.size() + keys.indexOf(key), value);
    }

    private static void putFollowup(Map<Integer, String> row, int year, String key, String value) {
        List<String> keys =
                CATALOG.followupFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(CatalogField::fieldKey)
                        .toList();
        int start =
                WideLayoutAdapter.FRONT_COLUMNS.size()
                        + anchoredBaselineKeys().size()
                        + (year - 1) * keys.size();
        row.put(start + keys.indexOf(key), value);
    }

    private static List<String> anchoredBaselineKeys() {
        return CATALOG.baselineFields().stream()
                .filter(field -> field.originalHeader() != null)
                .map(CatalogField::fieldKey)
                .toList();
    }

    private static List<String> cells(Map<Integer, String> sparse, int width) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            row.add(sparse.getOrDefault(i, ""));
        }
        return row;
    }

    private IssueInsert issue(String code) {
        return issues.stream()
                .filter(item -> code.equals(item.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有产生 " + code + " 问题：" + issues));
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
}
