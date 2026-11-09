package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.domain.ImportCell;
import com.crdp.followup.importer.domain.ImportCellValidator;
import com.crdp.followup.importer.domain.ImportCellValidator.Checked;
import com.crdp.followup.importer.domain.ImportCellValidator.Finding;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportIssue;
import com.crdp.followup.importer.domain.ImportIssue.Level;
import com.crdp.followup.importer.domain.ImportPatientRecord;
import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.ImportWardDirectory.MatchStatus;
import com.crdp.followup.importer.domain.ImportWardDirectory.WardEntry;
import com.crdp.followup.importer.domain.ImportWorkbookParser;
import com.crdp.followup.importer.domain.InMemoryImportSheet;
import com.crdp.followup.importer.domain.InMemoryImportWorkbook;
import com.crdp.followup.importer.domain.WideFrontColumns;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.form.field.domain.FieldType;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T05 / A-PAT-018 核对（单元，不依赖数据库）：医院提供的宽表样本经 <strong>scripts/deidentify_sample.py
 * 脱敏后</strong>的形态（虚构姓名「姓+示例+序号」、病案号 S 前缀、患者编号 L 前缀、电话 1390000 示例号段） 在<strong>真实医院 A
 * 字段字典</strong>（迁移 V38）与真实病区预置（V123）下预检 <strong>0 错误</strong>——tasks.csv 验收原文「医院提供的样本经脱敏后校验通过」
 * 的单元层证明（HTTP 全链路见 {@link DeidentifiedSampleIntakeIT}）。
 *
 * <p>校验口径与 {@link com.crdp.followup.importer.app.ImportValidationService} 的行级校验同一缝： 每个非空单元格过 {@link
 * ImportCellValidator}（类型 / 范围 / 选项 / Q36 / Q37）、「病区/中心」过病区字典值映射（E18-T04）、 前置列与基线列一致性 （{@link
 * WideFrontColumns}）；ERROR 任何一条都会阻断提交（confirm 40001），WARN 是人工复核清单（可导入）。 样本内容全部虚构（规则
 * 5）：标识值是脱敏工具产出的虚构形态，临床值是合法字典取值。
 */
class DeidentifiedSampleValidateTest {

    private static final String FIELD_MIGRATION =
            "db/tenant/followup/V38__hospital_a_field_dict.sql";

    private static final String WARD_MIGRATION =
            "db/tenant/seed/V123__hospital_a_ward_user_preset.sql";

    private static final Pattern ORIGINAL_HEADER = Pattern.compile("原表列名「(.+?)」");

    private static final Pattern RANGE = Pattern.compile("\"min\":([0-9.]+),\"max\":([0-9.]+)");

    /** 脱敏工具产出的标识值形态（scripts/deidentify_sample.py Fictionalizer._format）。 */
    private static final String MRN = "S000001";

    private static final String LEGACY_NO = "L000001";

    private static final String NAME = "王示例01";

    private static final String PHONE = "13900000001";

    private static List<ImportFieldRule> baselineRules;

    private static List<ImportFieldRule> followupRules;

    private static ImportWardDirectory wards;

    @BeforeAll
    static void buildRulesFromMigrations() {
        List<String[]> fieldRows = parseInsertRows(read(FIELD_MIGRATION), "('A_", 20);
        Map<String, Map<Integer, String>> optionsBySet = options(read(FIELD_MIGRATION));
        assertFalse(fieldRows.isEmpty(), "V38 应有字段行");
        baselineRules = new ArrayList<>();
        followupRules = new ArrayList<>();
        fieldRows.sort(java.util.Comparator.comparingInt(row -> Integer.parseInt(row[18])));
        for (String[] row : fieldRows) {
            Matcher anchor = ORIGINAL_HEADER.matcher(row[17] == null ? "" : row[17]);
            ImportFieldRule rule =
                    new ImportFieldRule(
                            row[1],
                            row[3],
                            anchor.find() ? anchor.group(1) : null,
                            row[4],
                            row[16],
                            "ENABLED".equals(row[19]),
                            FieldType.ofCode(row[5]).orElseThrow(),
                            row[6],
                            parseInt(row[7]),
                            range(row[8], true),
                            range(row[8], false),
                            range(row[9], true),
                            range(row[9], false),
                            "1".equals(row[11]),
                            "1".equals(row[15]),
                            optionsBySet.getOrDefault(row[10], Map.of()),
                            row[14]);
            if ("A_BASELINE".equals(row[0])) {
                baselineRules.add(rule);
            } else {
                followupRules.add(rule);
            }
        }
        wards = wardDirectory(read(WARD_MIGRATION));
    }

    // ---------------------------------------------------------------- 脱敏样本在真实字典下 0 错误

    /**
     * 脱敏样本形态的宽表行（761 列、第 1 年有随访）：必填齐备、N/A、yyyymmdd 日期、Q36「0/1」、Q37「编码+文字」—— 医院样本的典型脏值形态。全部单元格过真实规则后
     * <strong>无 ERROR</strong>（校验通过、可提交），Q36 / Q37 按口径进人工复核清单（WARN）。
     */
    @Test
    void deidentifiedSamplePassesRealDictionaryValidationWithNoErrors() {
        ImportPatientRecord patient = parseWideSample();
        Map<String, ImportFieldRule> baseline = byKey(baselineRules);
        Map<String, ImportFieldRule> followup = byKey(followupRules);

        // ① 脱敏形态落地：标识值是工具产出的虚构值，前置列与基线列一致（同一原值 → 同一虚构值）
        assertEquals(MRN, patient.mrn());
        assertEquals(LEGACY_NO, patient.legacyNo());
        assertEquals(MRN, patient.baseline().get("f002").normalized());
        assertEquals(NAME, patient.baseline().get("f003").normalized());
        assertEquals(PHONE, patient.baseline().get("f009").normalized());

        // ② 每个非空单元格按真实规则校验：无 ERROR（有 ERROR 确认提交被拦，E18-T05）
        List<Finding> findings = new ArrayList<>();
        patient.baseline().forEach((key, cell) -> collect(baseline.get(key), cell, findings, true));
        patient.followups()
                .forEach(
                        group ->
                                group.values()
                                        .forEach(
                                                (key, cell) ->
                                                        collect(
                                                                followup.get(key),
                                                                cell,
                                                                findings,
                                                                false)));
        findings.removeIf(finding -> finding.level() != Level.ERROR);
        assertTrue(findings.isEmpty(), "脱敏样本不应有错误级问题：" + findings);

        // ③ 敏感四字段（姓名 / 病案号 / 电话1 / 电话2）正是脱敏工具替换的列族（工具 ↔ 字典对齐）
        assertEquals(
                List.of("病案号", "姓名", "电话1", "电话2"),
                baselineRules.stream()
                        .filter(ImportFieldRule::sensitive)
                        .map(ImportFieldRule::label)
                        .toList());

        // ④ 前置列与基线列一致（前置列同值替换，不产生 FRONT_MISMATCH 警告）
        WideFrontColumns.FRONT_TO_BASELINE_LABEL
                .keySet()
                .forEach(
                        front -> {
                            ImportCell frontCell = patient.frontColumns().get(front);
                            ImportFieldRule rule =
                                    baselineRules.stream()
                                            .filter(r -> r.label().equals(front))
                                            .findFirst()
                                            .orElse(null);
                            ImportCell baselineCell =
                                    rule == null ? null : patient.baseline().get(rule.fieldKey());
                            assertTrue(
                                    WideFrontColumns.consistent(frontCell, baselineCell),
                                    "前置列「" + front + "」与基线列应一致（同一原值 → 同一虚构值）");
                        });

        // ⑤ Q36 / Q37 按口径进人工复核清单（WARN，可导入）——医院样本脏值不阻断校验通过
        List<Finding> warnings = allFindings(patient);
        assertTrue(
                warnings.stream()
                        .anyMatch(
                                finding ->
                                        finding.code().equals(ImportIssue.CODE_BOOLEAN_01)
                                                && finding.message().contains("Q36")),
                "是否类填「1」应产生 Q36 复核警告：" + warnings);
        assertTrue(
                warnings.stream()
                        .anyMatch(
                                finding ->
                                        finding.code().equals(ImportIssue.CODE_OPTION_CODE_TEXT)
                                                && finding.message().contains("Q37")),
                "「2二级」编码+文字应产生 Q37 复核警告：" + warnings);
    }

    /** 病区取值走病区字典映射（E18-T04）：脱敏不动病区值（非标识），预置病区（V123）下 MATCHED。 */
    @Test
    void wardValueResolvesAgainstPresetWardDirectory() {
        assertEquals(
                MatchStatus.MATCHED,
                wards.resolve("心外一病区（示例）").status(),
                "样本病区值应命中预置病区（未映射是 ERROR，会阻断校验通过）");
        assertEquals(
                MatchStatus.UNMAPPED,
                wards.resolve("不存在的病区（示例）").status(),
                "护栏：未映射值确实是 ERROR 源（导入前须修正原表或配别名）");
    }

    /** 必填为空按历史导入口径仅警告（A 规格 §4「必填字段为空」行）——样本缺项不阻断「校验通过」。 */
    @Test
    void missingRequiredOnlyWarnsForHistoricalImport() {
        ImportFieldRule mrn = rule("f002");
        Checked checked = ImportCellValidator.check(mrn, null);
        assertEquals(1, checked.findings().size());
        assertEquals(Level.WARN, checked.findings().get(0).level());
    }

    // ---------------------------------------------------------------- 样本构造与解析

    /** 宽表行（761 列、10 组随访、第 1 年有数据）：脱敏工具输出形态 + 医院典型脏值（N/A、0/1、编码+文字）。 */
    private static ImportPatientRecord parseWideSample() {
        Map<String, Integer> baselineColumns = new LinkedHashMap<>();
        List<String> header = new ArrayList<>(WideLayoutAdapter.FRONT_COLUMNS);
        baselineRules.stream()
                .filter(rule -> rule.originalHeader() != null)
                .forEach(
                        rule -> {
                            baselineColumns.put(rule.fieldKey(), header.size());
                            header.add(rule.originalHeader());
                        });
        List<String> followupAnchors =
                followupRules.stream()
                        .filter(rule -> rule.originalHeader() != null)
                        .map(ImportFieldRule::originalHeader)
                        .toList();
        for (int group = 0; group < 10; group++) {
            header.addAll(followupAnchors);
        }
        assertEquals(761, header.size(), "医院样例 9 + 212 + 10 × 54 = 761 列");

        Map<Integer, String> row = new HashMap<>();
        // 前置列（脱敏后形态：患者编号 L、病案号 S、姓名 示例；其余为医院导出原样）
        row.put(0, "1");
        row.put(1, LEGACY_NO);
        row.put(2, MRN);
        row.put(3, NAME);
        row.put(4, "男");
        row.put(5, "66");
        row.put(6, "20230102");
        row.put(7, "冠心病");
        row.put(8, "98%");
        put(row, baselineColumns, "f001", "20230110"); // 手术日期
        put(row, baselineColumns, "f002", MRN); // 病案号（脱敏形态）
        put(row, baselineColumns, "f003", NAME); // 姓名（脱敏形态）
        put(row, baselineColumns, "f004", "男"); // 性别（编码 1 / 显示名都可）
        put(row, baselineColumns, "f005", "66"); // 年龄（手术年龄，锚点「年龄」）
        put(row, baselineColumns, "f006", "冠心病"); // 诊断信息
        put(row, baselineColumns, "f007", "心外一病区（示例）"); // 病区（病区字典值映射）
        put(row, baselineColumns, "f009", PHONE); // 电话1（脱敏形态）
        row.put(baselineColumns.get("f010"), "N/A"); // 电话2：医院原表 N/A → 导入侧归一为空
        put(row, baselineColumns, "f013", "170"); // 身高cm（软范围 130–210 内）
        put(row, baselineColumns, "f037", "2二级"); // CCS 分级：编码+文字（Q37 警告）
        put(row, baselineColumns, "f016", "20230102"); // 入院日期
        put(row, baselineColumns, "f017", "20230115"); // 出院日期
        put(row, baselineColumns, "f191", "存活"); // 出院状态
        // 第 1 年随访（后续组全空——宽表全空组跳过）
        int followupStart = WideLayoutAdapter.FRONT_COLUMNS.size() + baselineColumns.size();
        int fuDate = followupStart + followupAnchors.indexOf("随访日期");
        int death = followupStart + followupAnchors.indexOf("死亡");
        int wechat = followupStart + followupAnchors.indexOf("是否已加微信");
        int ef = followupStart + followupAnchors.indexOf("ef_射血分数");
        row.put(fuDate, "20240110");
        row.put(death, "否");
        row.put(wechat, "1"); // Q36：是否类填「0/1」→ 空值导入 + 复核警告
        row.put(ef, "45"); // 软范围 5–85 内

        List<String> cells = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            cells.add(row.getOrDefault(i, ""));
        }
        List<ImportPatientRecord> patients =
                ImportWorkbookParser.parse(
                                new InMemoryImportWorkbook(
                                        List.of(
                                                new InMemoryImportSheet(
                                                        "患者数据", List.of(header, cells)))),
                                RealDictLayoutAdapterTest.realDictCatalog())
                        .patients();
        assertEquals(1, patients.size());
        ImportPatientRecord patient = patients.get(0);
        assertEquals(1, patient.followups().size(), "只有第 1 年有数据（其余组全空跳过）");
        assertEquals(
                "1", patient.followups().get(0).values().get("f213").normalized(), "Q36 值在模型里");
        return patient;
    }

    private static void collect(
            ImportFieldRule rule, ImportCell cell, List<Finding> sink, boolean baseline) {
        if (rule == null) {
            return; // 字典里已没有的字段按原样导入（服务层同一口径）
        }
        if (baseline && "f007".equals(rule.fieldKey())) {
            assertEquals(
                    MatchStatus.MATCHED,
                    wards.resolve(cell.normalized()).status(),
                    "病区取值应命中预置病区字典（否则 ERROR 阻断校验）");
            return; // 病区不走固定选项集（E18-T04）
        }
        sink.addAll(ImportCellValidator.check(rule, cell).findings());
    }

    /** 全部单元格的发现（基线 + 随访组，病区走字典映射）。 */
    private static List<Finding> allFindings(ImportPatientRecord patient) {
        List<Finding> findings = new ArrayList<>();
        Map<String, ImportFieldRule> baseline = byKey(baselineRules);
        Map<String, ImportFieldRule> followup = byKey(followupRules);
        patient.baseline().forEach((key, cell) -> collect(baseline.get(key), cell, findings, true));
        patient.followups()
                .forEach(
                        group ->
                                group.values()
                                        .forEach(
                                                (key, cell) ->
                                                        collect(
                                                                followup.get(key),
                                                                cell,
                                                                findings,
                                                                false)));
        return findings;
    }

    private static ImportFieldRule rule(String fieldKey) {
        return baselineRules.stream()
                .filter(r -> r.fieldKey().equals(fieldKey))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, ImportFieldRule> byKey(List<ImportFieldRule> rules) {
        Map<String, ImportFieldRule> index = new LinkedHashMap<>();
        rules.forEach(rule -> index.put(rule.fieldKey(), rule));
        return index;
    }

    private static void put(
            Map<Integer, String> row, Map<String, Integer> columns, String key, String value) {
        Integer column = columns.get(key);
        assertNotNull(column, "字段目录缺少宽表锚点列：" + key);
        row.put(column, value);
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析（与
    // RealDictLayoutAdapterTest / AFieldDictSqlTest 同款）

    private static String read(String migration) {
        try (InputStream in =
                DeidentifiedSampleValidateTest.class
                        .getClassLoader()
                        .getResourceAsStream(migration)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + migration);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读迁移脚本失败：" + migration, e);
        }
    }

    /** INSERT VALUES 行（marker 开头、arity 列）解析为字符串数组（NULL → null）。 */
    private static List<String[]> parseInsertRows(String sql, String marker, int arity) {
        List<String[]> rows = new ArrayList<>();
        for (String line : sql.lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(marker)
                    || !(trimmed.endsWith("),") || trimmed.endsWith(");"))) {
                continue;
            }
            String body = trimmed.substring(trimmed.indexOf('(') + 1, trimmed.lastIndexOf(')'));
            List<String> values = splitValues(body);
            if (values.size() == arity) {
                rows.add(values.toArray(new String[0]));
            }
        }
        return rows;
    }

    /** fe_option（set_code, option_code, label, ordinal, sort_no）→ 选项集编码 → 编码 / 显示名。 */
    private static Map<String, Map<Integer, String>> options(String sql) {
        Map<String, Map<Integer, String>> bySet = new HashMap<>();
        for (String[] row : parseInsertRows(sql, "('a_", 5)) {
            bySet.computeIfAbsent(row[0], key -> new LinkedHashMap<>())
                    .put(Integer.parseInt(row[1]), row[2]);
        }
        return bySet;
    }

    /** V123 病区预置 → 病区字典（导入病区值映射的口径，E18-T04）。 */
    private static ImportWardDirectory wardDirectory(String sql) {
        List<WardEntry> entries = new ArrayList<>();
        for (String[] row : parseInsertRows(sql, "('W-", 6)) {
            List<String> aliases = new ArrayList<>();
            if (row[4] != null && !row[4].isBlank()) {
                for (String alias : row[4].split(";")) {
                    aliases.add(alias.strip());
                }
            }
            entries.add(new WardEntry(row[0].hashCode(), row[0], row[1], row[2], aliases));
        }
        assertFalse(entries.isEmpty(), "V123 应有病区预置");
        return new ImportWardDirectory(entries);
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

    private static Integer parseInt(String raw) {
        return raw == null ? null : Integer.valueOf(raw);
    }

    /** '{"min":X,"max":Y}' 的一端（min / max）。 */
    private static Double range(String json, boolean min) {
        if (json == null) {
            return null;
        }
        Matcher matcher = RANGE.matcher(json);
        return matcher.find() ? Double.parseDouble(matcher.group(min ? 1 : 2)) : null;
    }
}
