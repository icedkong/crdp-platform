package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportLayout;
import com.crdp.followup.importer.domain.ImportLayoutException;
import com.crdp.followup.importer.domain.ImportParseResult;
import com.crdp.followup.importer.domain.ImportWorkbookParser;
import com.crdp.followup.importer.domain.InMemoryImportSheet;
import com.crdp.followup.importer.domain.InMemoryImportWorkbook;
import com.crdp.followup.importer.domain.StaticImportFieldCatalog;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E18-T01 核对（单元，不依赖数据库）：版面适配器对<strong>真实医院 A 字段字典</strong>（迁移 V38，随应用发布）的两种版面都能解析—— 宽表「9 前置列 + 212
 * 基线列 + N 组 × 54 随访列」按原表列名锚点逐列对位（医院样例 N = 10、共 761 列，A-PAT-018； OQ-72① 锚点匹配、OQ-72② #217
 * 停用仍是宽表列），长表「基线 213 启用字段 + 随访记录 53 启用字段」按显示名匹配，同一份虚构内容两种版面解析到同一模型（tasks.csv 验收原文）。 字典来源与 {@link
 * AFieldDictSqlTest} 同一迁移脚本；测试数据全部虚构（规则 5）。
 */
class RealDictLayoutAdapterTest {

    private static final String MIGRATION = "db/tenant/followup/V38__hospital_a_field_dict.sql";

    private static final Pattern ORIGINAL_HEADER = Pattern.compile("原表列名「(.+?)」");

    private static ImportFieldCatalog catalog;

    private static Map<String, String> anchorsByKey;

    @BeforeAll
    static void buildCatalogFromMigration() {
        List<String[]> rows = parseFieldRows(readMigration());
        List<ImportFieldCatalog.CatalogField> baseline = new ArrayList<>();
        List<ImportFieldCatalog.CatalogField> followup = new ArrayList<>();
        anchorsByKey = new HashMap<>();
        // 排序按 sort_no（字典顺序 = 宽表列顺序）；AFieldDictSqlTest 的行序同源
        rows.sort(java.util.Comparator.comparingInt(row -> Integer.parseInt(row[18])));
        for (String[] row : rows) {
            Matcher matcher = ORIGINAL_HEADER.matcher(row[17] == null ? "" : row[17]);
            String anchor = matcher.find() ? matcher.group(1) : null;
            ImportFieldCatalog.CatalogField field =
                    new ImportFieldCatalog.CatalogField(
                            row[1], row[3], anchor, "ENABLED".equals(row[19]));
            if ("A_BASELINE".equals(row[0])) {
                baseline.add(field);
            } else {
                followup.add(field);
            }
            if (anchor != null) {
                anchorsByKey.put(row[1], anchor);
            }
        }
        String mrnKey =
                baseline.stream()
                        .filter(field -> "病案号".equals(field.label()))
                        .findFirst()
                        .orElseThrow()
                        .fieldKey();
        catalog = new StaticImportFieldCatalog(baseline, followup, mrnKey);
    }

    // ---------------------------------------------------------------- 字典口径

    /** 基线 213（212 附录字段 + 出生日期 f267）、随访 54（含停用 #217）；宽表列 = 带锚点字段（212 / 54）。 */
    @Test
    void catalogMatchesAppendixCounts() {
        assertEquals(213, catalog.baselineFields().size());
        assertEquals(
                212,
                catalog.baselineFields().stream().filter(f -> f.originalHeader() != null).count());
        assertEquals(54, catalog.followupFields().size());
        assertEquals(
                54,
                catalog.followupFields().stream().filter(f -> f.originalHeader() != null).count(),
                "停用的 #217 死亡随访时间带锚点、仍是宽表列（OQ-72②）");
        assertEquals(
                53,
                catalog.followupFields().stream()
                        .filter(ImportFieldCatalog.CatalogField::enabled)
                        .count(),
                "长表模板只含启用随访字段（53）");
        assertEquals(Optional.of("f002"), catalog.mrnFieldKey());
        assertEquals("f001", catalog.baselineFields().get(0).fieldKey(), "字典顺序 = 宽表列顺序");
    }

    // ---------------------------------------------------------------- 宽表：医院样例 761 列

    /** 761 列（9 + 212 + 10 组 × 54）按锚点对位：组位置定年度、全空组跳过、#217 可读。 */
    @Test
    void parsesHospitalSampleWideLayout() {
        List<String> header = wideHeader(10);
        assertEquals(761, header.size(), "医院样例 9 + 212 + 10 × 54 = 761 列");
        // 数据：第 1、3 年有数据（第 2 组只在 #217 死亡随访时间填值），其余组全空
        Map<Integer, String> row = new HashMap<>();
        putWide(row, 0, "1"); // 序号
        putWide(row, 1, "L0001"); // 患者编号 → 原系统编号
        putWide(row, 3, "张三（示例）"); // 前置列姓名
        putBaseline(row, "f001", "20260115");
        putBaseline(row, "f002", "X0001");
        putBaseline(row, "f003", "张三（示例）");
        putBaseline(row, "f005", "65"); // 原表列名「年龄」
        putFollowup(row, 1, "f214", "20270315"); // 第 1 年随访日期
        putFollowup(row, 1, "f248", "45"); // 第 1 年 ef_射血分数
        putFollowup(row, 2, "f217", "20270320"); // 第 2 组只有 #217（停用字段）有值 → 该组不算全空
        putFollowup(row, 3, "f214", "20290315");
        putFollowup(row, 3, "f215", "0"); // 死亡

        ImportParseResult result =
                ImportWorkbookParser.parse(
                        new InMemoryImportWorkbook(
                                List.of(
                                        new InMemoryImportSheet(
                                                "患者数据",
                                                List.of(header, cells(row, header.size()))))),
                        catalog);

        assertEquals(ImportLayout.WIDE, result.layout());
        assertEquals(1, result.patients().size());
        var patient = result.patients().get(0);
        assertEquals("X0001", patient.mrn());
        assertEquals("L0001", patient.legacyNo());
        assertEquals("20260115", patient.baseline().get("f001").normalized());
        assertEquals("65", patient.baseline().get("f005").normalized(), "表头「年龄」对到手术年龄（锚点匹配）");
        assertEquals(3, patient.followups().size(), "全空的组（4–10 年）跳过");
        assertEquals(1, patient.followups().get(0).year());
        assertEquals("45", patient.followups().get(0).values().get("f248").normalized());
        assertEquals(2, patient.followups().get(1).year());
        assertEquals(
                "20270320",
                patient.followups().get(1).values().get("f217").normalized(),
                "#217 是宽表第 54 列之一");
        assertEquals(3, patient.followups().get(2).year());
    }

    /** 换掉一个锚点表头：整体报错，差异清单带列字母。 */
    @Test
    void rejectsTamperedWideHeader() {
        List<String> header = wideHeader(1);
        header.set(9, "不是手术日期");
        ImportLayoutException ex =
                assertThrows(
                        ImportLayoutException.class,
                        () ->
                                ImportWorkbookParser.parse(
                                        new InMemoryImportWorkbook(
                                                List.of(
                                                        new InMemoryImportSheet(
                                                                "患者数据", List.of(header)))),
                                        catalog));
        assertTrue(
                ex.problems().stream().anyMatch(p -> p.contains("J") && p.contains("手术日期")),
                "第 10 列（J）表头错误应指出：" + ex.problems());
    }

    // ---------------------------------------------------------------- 同一模型：宽表 ↔ 长表

    /** 同一份虚构内容，761 列宽表与两张长表解析到同一模型（tasks.csv E18-T01 验收原文）。 */
    @Test
    void wideAndLongLayoutsParseToSameModelWithRealDictionary() {
        ImportParseResult wide =
                ImportWorkbookParser.parse(
                        new InMemoryImportWorkbook(
                                List.of(
                                        new InMemoryImportSheet(
                                                "患者数据",
                                                List.of(wideHeader(3), wideRowWithTwoYears())))),
                        catalog);

        List<String> baselineHeader = new ArrayList<>();
        catalog.baselineFields().stream()
                .filter(ImportFieldCatalog.CatalogField::enabled)
                .forEach(field -> baselineHeader.add(field.label()));
        baselineHeader.add("原系统编号");
        Map<Integer, String> baselineRow = new HashMap<>();
        putByLabel(baselineHeader, baselineRow, "手术日期", "20260115");
        putByLabel(baselineHeader, baselineRow, "病案号", "X0001");
        putByLabel(baselineHeader, baselineRow, "姓名", "张三（示例）");
        putByLabel(baselineHeader, baselineRow, "手术年龄", "65");
        putByLabel(baselineHeader, baselineRow, "原系统编号", "L0001");

        List<String> followupHeader = new ArrayList<>(List.of("病案号", "随访年度"));
        catalog.followupFields().stream()
                .filter(ImportFieldCatalog.CatalogField::enabled)
                .forEach(field -> followupHeader.add(field.label()));
        Map<Integer, String> year1 = new HashMap<>();
        putByLabel(followupHeader, year1, "病案号", "X0001");
        putByLabel(followupHeader, year1, "随访年度", "1");
        putByLabel(followupHeader, year1, "随访日期", "20270315");
        putByLabel(followupHeader, year1, "EF 射血分数", "45");
        Map<Integer, String> year2 = new HashMap<>();
        putByLabel(followupHeader, year2, "病案号", "X0001");
        putByLabel(followupHeader, year2, "随访年度", "2");
        putByLabel(followupHeader, year2, "随访日期", "20280315");
        putByLabel(followupHeader, year2, "EF 射血分数", "47");
        ImportParseResult longResult =
                ImportWorkbookParser.parse(
                        new InMemoryImportWorkbook(
                                List.of(
                                        new InMemoryImportSheet(
                                                "基线",
                                                List.of(
                                                        baselineHeader,
                                                        cells(baselineRow, baselineHeader.size()))),
                                        new InMemoryImportSheet(
                                                "随访记录",
                                                List.of(
                                                        followupHeader,
                                                        cells(year1, followupHeader.size()),
                                                        cells(year2, followupHeader.size()))))),
                        catalog);

        assertEquals(ImportLayout.LONG, longResult.layout());
        assertEquals(1, wide.patients().size());
        assertEquals(1, longResult.patients().size());
        assertEquals(
                wide.patients().get(0),
                longResult.patients().get(0),
                "同一内容的宽表（锚点表头）与长表（显示名表头）解析到同一模型");
        assertEquals(2, longResult.patients().get(0).followups().size());
        assertTrue(
                wide.unmatchedFollowups().isEmpty() && longResult.unmatchedFollowups().isEmpty());
    }

    // ---------------------------------------------------------------- 夹具

    /** 宽表表头：前置 9 + 基线锚点 + groups 组随访锚点。 */
    private static List<String> wideHeader(int groups) {
        List<String> header = new ArrayList<>(WideLayoutAdapter.FRONT_COLUMNS);
        catalog.baselineFields().stream()
                .filter(field -> field.originalHeader() != null)
                .forEach(field -> header.add(field.originalHeader()));
        List<String> followupAnchors =
                catalog.followupFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(ImportFieldCatalog.CatalogField::originalHeader)
                        .toList();
        for (int g = 0; g < groups; g++) {
            header.addAll(followupAnchors);
        }
        return header;
    }

    /** 宽表数据行：第 1、2 年各两条值（与长表同一份内容）。 */
    private static List<String> wideRowWithTwoYears() {
        Map<Integer, String> row = new HashMap<>();
        putWide(row, 1, "L0001");
        putBaseline(row, "f001", "20260115");
        putBaseline(row, "f002", "X0001");
        putBaseline(row, "f003", "张三（示例）");
        putBaseline(row, "f005", "65");
        putFollowup(row, 1, "f214", "20270315");
        putFollowup(row, 1, "f248", "45");
        putFollowup(row, 2, "f214", "20280315");
        putFollowup(row, 2, "f248", "47");
        return cells(row, 761);
    }

    private static void putWide(Map<Integer, String> row, int frontIndex, String value) {
        row.put(frontIndex, value);
    }

    private static void putBaseline(Map<Integer, String> row, String key, String value) {
        List<String> anchoredKeys =
                catalog.baselineFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(ImportFieldCatalog.CatalogField::fieldKey)
                        .toList();
        row.put(WideLayoutAdapter.FRONT_COLUMNS.size() + anchoredKeys.indexOf(key), value);
    }

    private static void putFollowup(Map<Integer, String> row, int year, String key, String value) {
        List<String> anchoredKeys =
                catalog.followupFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(ImportFieldCatalog.CatalogField::fieldKey)
                        .toList();
        int baselineCount =
                (int)
                        catalog.baselineFields().stream()
                                .filter(f -> f.originalHeader() != null)
                                .count();
        int start =
                WideLayoutAdapter.FRONT_COLUMNS.size()
                        + baselineCount
                        + (year - 1) * anchoredKeys.size();
        row.put(start + anchoredKeys.indexOf(key), value);
    }

    /** 按表头名定位列写值（长表）。 */
    private static void putByLabel(
            List<String> header, Map<Integer, String> row, String label, String value) {
        int index = header.indexOf(label);
        org.junit.jupiter.api.Assertions.assertTrue(index >= 0, "表头应有「" + label + "」");
        row.put(index, value);
    }

    private static List<String> cells(Map<Integer, String> sparse, int width) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            row.add(sparse.getOrDefault(i, ""));
        }
        return row;
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析（与
    // AFieldDictSqlTest 同款）

    private static String readMigration() {
        try (InputStream in =
                RealDictLayoutAdapterTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + MIGRATION);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读迁移脚本失败：" + MIGRATION, e);
        }
    }

    /** fe_field 的 VALUES 行（生成器约定：一行一字段、列序固定 20 列；help_text 第 18 列、sort_no 第 19、status 第 20）。 */
    private static List<String[]> parseFieldRows(String sql) {
        List<String[]> rows = new ArrayList<>();
        for (String line : sql.lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("('A_")
                    || !(trimmed.endsWith("),") || trimmed.endsWith(");"))) {
                continue;
            }
            List<String> values = splitValues(bodyOf(trimmed));
            if (values.size() != 20) {
                continue;
            }
            rows.add(values.toArray(new String[0]));
        }
        return rows;
    }

    private static String bodyOf(String trimmed) {
        int open = trimmed.indexOf('(');
        int close = trimmed.lastIndexOf(')');
        return trimmed.substring(open + 1, close);
    }

    private static List<String> splitValues(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                inString = true;
            } else if (c == ',') {
                values.add(token(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(token(current.toString()));
        return values;
    }

    private static String token(String raw) {
        String trimmed = raw.strip();
        return "NULL".equals(trimmed) ? null : trimmed;
    }
}
