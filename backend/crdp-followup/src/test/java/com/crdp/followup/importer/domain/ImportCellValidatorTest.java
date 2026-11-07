package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.domain.ImportCellValidator.Checked;
import com.crdp.followup.importer.domain.ImportCellValidator.Finding;
import com.crdp.followup.importer.domain.ImportIssue.Level;
import com.crdp.form.field.domain.FieldType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E18-T03 单元格校验矩阵（A 规格 §4「校验报告中的常见问题」表 + Q36 / Q37 已确认口径； tech-plan §12 单元格转换表）。 测试数据全部虚构（规则 5）。
 */
class ImportCellValidatorTest {

    private static ImportCell cell(String raw) {
        return new ImportCell(raw, 2, 3);
    }

    private static ImportFieldRule rule(
            FieldType type,
            Double hardMin,
            Double hardMax,
            Double softMin,
            Double softMax,
            Map<Integer, String> options) {
        return new ImportFieldRule(
                "f001", "EF（示例）", "EF", true, type, null, 0, hardMin, hardMax, softMin, softMax,
                false, false, options, null);
    }

    private static List<String> codes(Checked checked, Level level) {
        return checked.findings().stream()
                .filter(finding -> finding.level() == level)
                .map(Finding::code)
                .toList();
    }

    // ---------------------------------------------------------------- 数值与范围

    @Test
    void numberInRangePassesWithTypedValue() {
        Checked checked =
                ImportCellValidator.check(
                        rule(FieldType.NUMBER, 0.0, 75.0, 20.0, 70.0, null), cell("55"));
        assertTrue(checked.findings().isEmpty());
        assertEquals(55.0, checked.value());
    }

    @Test
    void numberOverHardRangeIsError() {
        Checked checked =
                ImportCellValidator.check(
                        rule(FieldType.NUMBER, 0.0, 75.0, 20.0, 70.0, null), cell("150"));
        assertEquals(List.of(ImportIssue.CODE_HARD_RANGE), codes(checked, Level.ERROR));
        assertTrue(codes(checked, Level.WARN).isEmpty(), "已报硬范围不再叠软范围警告");
        assertTrue(checked.findings().get(0).message().contains("150"));
        assertTrue(checked.findings().get(0).message().contains("0–75"), "提示里带硬范围");
    }

    @Test
    void numberOverSoftRangeOnlyIsWarning() {
        Checked checked =
                ImportCellValidator.check(
                        rule(FieldType.NUMBER, 0.0, 75.0, 20.0, 70.0, null), cell("73"));
        assertEquals(List.of(ImportIssue.CODE_SOFT_RANGE), codes(checked, Level.WARN));
        assertTrue(codes(checked, Level.ERROR).isEmpty());
        assertEquals(73.0, checked.value(), "警告可导入，值照常转换");
    }

    @Test
    void nonNumericTextIsTypeError() {
        Checked checked =
                ImportCellValidator.check(
                        rule(FieldType.NUMBER, null, null, null, null, null), cell("abc"));
        assertEquals(List.of(ImportIssue.CODE_TYPE), codes(checked, Level.ERROR));
        assertNull(checked.value());
    }

    @Test
    void integerFieldRejectsFraction() {
        ImportFieldRule integer =
                new ImportFieldRule(
                        "f001",
                        "桥血管数量（示例）",
                        "桥血管数量",
                        true,
                        FieldType.NUMBER,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        null,
                        null);
        assertEquals(
                List.of(ImportIssue.CODE_TYPE),
                codes(ImportCellValidator.check(integer, cell("2.5")), Level.ERROR));
        assertTrue(ImportCellValidator.check(integer, cell("3")).findings().isEmpty());
    }

    // ---------------------------------------------------------------- 日期 / 日期时间（A 规格 §4
    // 例子：20251345、2025-7-18 报错）

    @Test
    void dateAcceptsCompactAndIsoAndRejectsInvalid() {
        ImportFieldRule date = rule(FieldType.DATE, null, null, null, null, null);
        assertEquals("2026-03-15", ImportCellValidator.check(date, cell("20260315")).value());
        assertEquals("2026-03-15", ImportCellValidator.check(date, cell("2026-03-15")).value());
        assertEquals(
                List.of(ImportIssue.CODE_TYPE),
                codes(ImportCellValidator.check(date, cell("20251345")), Level.ERROR));
        assertEquals(
                List.of(ImportIssue.CODE_TYPE),
                codes(ImportCellValidator.check(date, cell("2025-7-18")), Level.ERROR));
    }

    @Test
    void datetimeAcceptsCompactAndIsoAndRejectsInvalid() {
        ImportFieldRule datetime = rule(FieldType.DATETIME, null, null, null, null, null);
        assertEquals(
                "2026-03-15T08:30",
                ImportCellValidator.check(datetime, cell("202603150830")).value());
        assertEquals(
                "2026-03-15T08:30",
                ImportCellValidator.check(datetime, cell("2026-03-15 08:30")).value());
        assertEquals(
                List.of(ImportIssue.CODE_TYPE),
                codes(ImportCellValidator.check(datetime, cell("08:30")), Level.ERROR));
    }

    // ---------------------------------------------------------------- 选项（Q36 / Q37）

    @Test
    void selectMatchesCodeLabelAndCodePlusText() {
        Map<Integer, String> options = Map.of(1, "心衰", 2, "冠脉疾病", 6, "外周血管疾病（示例）");
        ImportFieldRule select = rule(FieldType.SELECT, null, null, null, null, options);
        assertEquals(2, ImportCellValidator.check(select, cell("2")).value());
        assertEquals(2, ImportCellValidator.check(select, cell("冠脉疾病")).value());
        assertTrue(ImportCellValidator.check(select, cell("2")).findings().isEmpty());
        assertTrue(ImportCellValidator.check(select, cell("冠脉疾病")).findings().isEmpty());
    }

    @Test
    void selectCodePlusTextImportsCodeWithQ37Warning() {
        Map<Integer, String> options = Map.of(6, "外周血管疾病（示例）");
        Checked checked =
                ImportCellValidator.check(
                        rule(FieldType.SELECT, null, null, null, null, options), cell("6颈动脉内剥"));
        assertEquals(6, checked.value(), "Q37：取编码 6 导入");
        assertEquals(List.of(ImportIssue.CODE_OPTION_CODE_TEXT), codes(checked, Level.WARN));
        assertTrue(checked.findings().get(0).message().contains("颈动脉内剥"));
        assertTrue(checked.findings().get(0).message().contains("备注"));
    }

    @Test
    void selectUnknownCodeOrTextIsError() {
        Map<Integer, String> options = Map.of(1, "心衰", 2, "冠脉疾病");
        ImportFieldRule select = rule(FieldType.SELECT, null, null, null, null, options);
        assertEquals(
                List.of(ImportIssue.CODE_OPTION_MISS),
                codes(ImportCellValidator.check(select, cell("9颈椎病")), Level.ERROR));
        assertEquals(
                List.of(ImportIssue.CODE_OPTION_MISS),
                codes(ImportCellValidator.check(select, cell("不存在（示例）")), Level.ERROR));
        assertTrue(
                ImportCellValidator.check(select, cell("9颈椎病"))
                        .findings()
                        .get(0)
                        .message()
                        .contains("心衰"));
    }

    @Test
    void yesNoCellWithZeroOrOneImportsNullWithQ36Warning() {
        Map<Integer, String> yesNo = Map.of(0, "否", 1, "是");
        ImportFieldRule radio = rule(FieldType.RADIO, null, null, null, null, yesNo);
        Checked zero = ImportCellValidator.check(radio, cell("0"));
        assertEquals(List.of(ImportIssue.CODE_BOOLEAN_01), codes(zero, Level.WARN));
        assertNull(zero.value(), "Q36：按空值导入");
        assertEquals(1, ImportCellValidator.check(radio, cell("是")).value());
        assertEquals(0, ImportCellValidator.check(radio, cell("否")).value());
        assertTrue(ImportCellValidator.check(radio, cell("是")).findings().isEmpty());
    }

    @Test
    void nonYesNoRadioAcceptsPlainCode() {
        Map<Integer, String> options = Map.of(0, "无", 1, "消化道出血", 2, "皮肤出血");
        ImportFieldRule radio = rule(FieldType.RADIO, null, null, null, null, options);
        assertTrue(ImportCellValidator.check(radio, cell("2")).findings().isEmpty(), "非是否类：编码照常导入");
        assertEquals(2, ImportCellValidator.check(radio, cell("皮肤出血")).value());
    }

    @Test
    void booleanWithoutOptionSetAcceptsYesNoAndWarnsOnZeroOne() {
        ImportFieldRule bool = rule(FieldType.BOOLEAN, null, null, null, null, null);
        assertEquals(Boolean.TRUE, ImportCellValidator.check(bool, cell("是")).value());
        assertEquals(
                List.of(ImportIssue.CODE_BOOLEAN_01),
                codes(ImportCellValidator.check(bool, cell("1")), Level.WARN));
        assertEquals(
                List.of(ImportIssue.CODE_TYPE),
                codes(ImportCellValidator.check(bool, cell("也许")), Level.ERROR));
    }

    // ---------------------------------------------------------------- 多选 / 数值×2 / 文本 / 派生

    @Test
    void multiSelectSplitsAndChecksEachItem() {
        Map<Integer, String> options = Map.of(1, "心衰", 2, "冠脉疾病", 6, "外周血管疾病（示例）");
        ImportFieldRule multi = rule(FieldType.MULTI_SELECT, null, null, null, null, options);
        Checked checked = ImportCellValidator.check(multi, cell("1、6颈动脉内剥、不存在（示例）"));
        assertEquals(List.of(ImportIssue.CODE_OPTION_CODE_TEXT), codes(checked, Level.WARN));
        assertEquals(List.of(ImportIssue.CODE_OPTION_MISS), codes(checked, Level.ERROR));
        assertEquals(List.of(1, 6), checked.value());
    }

    @Test
    void pairNumberParsesLengthWidthAndChecksRangeOnBoth() {
        ImportFieldRule pair = rule(FieldType.PAIR_NUMBER, 0.0, 60.0, null, null, null);
        Checked ok = ImportCellValidator.check(pair, cell("24×31"));
        assertTrue(ok.findings().isEmpty());
        assertEquals(24.0, ((Map<?, ?>) ok.value()).get("length"));
        assertEquals(31.0, ((Map<?, ?>) ok.value()).get("width"));
        Checked bad = ImportCellValidator.check(pair, cell("70*31"));
        assertEquals(List.of(ImportIssue.CODE_HARD_RANGE), codes(bad, Level.ERROR));
        Checked malformed = ImportCellValidator.check(pair, cell("2431"));
        assertEquals(List.of(ImportIssue.CODE_TYPE), codes(malformed, Level.ERROR));
    }

    @Test
    void textPassesThroughAndDerivedKeepsNumericValue() {
        assertTrue(
                ImportCellValidator.check(
                                rule(FieldType.TEXT, null, null, null, null, null),
                                cell("任意文本（示例）"))
                        .findings()
                        .isEmpty());
        Checked derived =
                ImportCellValidator.check(
                        rule(FieldType.DERIVED, 0.0, 120.0, null, null, null), cell("66"));
        assertTrue(derived.findings().isEmpty(), "派生字段与重算结果的比对在行级（以重算为准），单元格层不做范围检查");
        assertEquals(66.0, derived.value());
    }

    // ---------------------------------------------------------------- 必填为空与空值

    @Test
    void blankRequiredFieldWarnsAndBlankOptionalPasses() {
        ImportFieldRule required =
                rule(FieldType.RADIO, null, null, null, null, Map.of(0, "否", 1, "是"));
        ImportFieldRule rule =
                new ImportFieldRule(
                        "f001",
                        "是否死亡（示例）",
                        "死亡",
                        true,
                        FieldType.RADIO,
                        null,
                        1,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false,
                        Map.of(0, "否", 1, "是"),
                        null);
        assertEquals(
                List.of(ImportIssue.CODE_REQUIRED_EMPTY),
                codes(ImportCellValidator.check(rule, cell("")), Level.WARN));
        assertEquals(
                List.of(ImportIssue.CODE_REQUIRED_EMPTY),
                codes(ImportCellValidator.check(rule, cell("N/A")), Level.WARN));
        assertTrue(ImportCellValidator.check(required, cell("")).findings().isEmpty(), "非必填空值直接通过");
        assertTrue(
                ImportCellValidator.check(rule, new ImportCell("", 2, 3))
                        .findings()
                        .get(0)
                        .message()
                        .contains("历史导入"));
    }

    // ---------------------------------------------------------------- 敏感字段不回显

    @Test
    void sensitiveFieldValueIsNotEchoedInMessages() {
        ImportFieldRule sensitive =
                new ImportFieldRule(
                        "f002",
                        "病案号（示例）",
                        "病案号",
                        true,
                        FieldType.SELECT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        true,
                        Map.of(1, "甲", 2, "乙"),
                        null);
        Checked checked = ImportCellValidator.check(sensitive, cell("9敏感原值（示例）"));
        assertEquals(List.of(ImportIssue.CODE_OPTION_MISS), codes(checked, Level.ERROR));
        assertTrue(!checked.findings().get(0).message().contains("敏感原值"), "敏感字段不回显原值（OQ-101③）");
        assertTrue(checked.findings().get(0).message().contains("该取值"));
    }
}
