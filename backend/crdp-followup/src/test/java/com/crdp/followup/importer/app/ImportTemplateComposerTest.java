package com.crdp.followup.importer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportFieldCatalog.CatalogField;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportFollowup;
import com.crdp.followup.importer.domain.ImportLayout;
import com.crdp.followup.importer.domain.ImportLayoutException;
import com.crdp.followup.importer.domain.ImportPatientRecord;
import com.crdp.followup.importer.domain.ImportWorkbookParser;
import com.crdp.followup.importer.domain.LongLayoutAdapter;
import com.crdp.followup.importer.domain.StaticImportFieldCatalog;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.importer.infra.StreamingImportWorkbook;
import com.crdp.form.field.domain.FieldType;
import com.crdp.framework.excel.StreamingXlsxGateway;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E18-T06 模板生成测试（import-export.md §1「模板内只有表头与示例行，示例行全部是虚构数据」）： 生成的模板字节必须能被版面适配器直接识别与解析 （宽表模板单张 →
 * WIDE、长表模板两张 → LONG），示例行解析成模型且值为虚构数据—— 模板下载即端到端可导入的起点，不产出「下回来还要改表头」的模板。
 */
class ImportTemplateComposerTest {

    /** 与目录同一套字段（含无锚点字段 / 停用字段，验证列集过滤口径 OQ-72）。 */
    private static ImportFieldRules rules() {
        return new ImportFieldRules(
                List.of(
                        rule("f001", "手术日期", "手术日期"),
                        rule("f002", "病案号", "病案号"),
                        rule("f003", "姓名", "姓名"),
                        rule("f267", "出生日期", null),
                        disabled("f310", "备注列（停用）", "备注")),
                List.of(
                        rule("f214", "随访日期", "随访日期"),
                        rule("f257", "EF", null),
                        disabled("f217", "死亡随访时间", "死亡随访时间")),
                "f002",
                null,
                "baseline-form",
                "followup-form");
    }

    private static ImportFieldCatalog catalog() {
        List<CatalogField> baseline =
                rules().baselineRules().stream()
                        .map(
                                rule ->
                                        new CatalogField(
                                                rule.fieldKey(),
                                                rule.label(),
                                                rule.originalHeader(),
                                                rule.enabled()))
                        .toList();
        List<CatalogField> followup =
                rules().followupRules().stream()
                        .map(
                                rule ->
                                        new CatalogField(
                                                rule.fieldKey(),
                                                rule.label(),
                                                rule.originalHeader(),
                                                rule.enabled()))
                        .toList();
        return new StaticImportFieldCatalog(baseline, followup, "f002");
    }

    @Test
    void standardTemplateDetectsAsLongLayoutAndParsesExampleRow() throws Exception {
        byte[] template = ImportTemplateComposer.standard(rules());
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        new StreamingXlsxGateway().open(new ByteArrayInputStream(template)))) {
            assertEquals(ImportLayout.LONG, ImportWorkbookParser.detect(workbook));
            List<ImportPatientRecord> patients = new ArrayList<>();
            new LongLayoutAdapter().streamBaseline(workbook, catalog(), patients::add);
            List<ImportFollowup> followups = new ArrayList<>();
            new LongLayoutAdapter().streamFollowups(workbook, catalog(), followups::add);
            assertEquals(1, patients.size(), "基线示例行恰好一行");
            assertEquals("DEMOA0001（示例）", patients.get(0).mrn());
            assertEquals("DEMO-A-0001（示例）", patients.get(0).legacyNo());
            assertEquals(1, followups.size(), "随访示例行恰好一行");
            assertEquals("DEMOA0001（示例）", followups.get(0).mrnCell().normalized());
            assertEquals(1, followups.get(0).year());
        }
    }

    @Test
    void wideTemplateDetectsAsWideLayoutAndParsesExampleRow() throws Exception {
        byte[] template = ImportTemplateComposer.wide(rules());
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        new StreamingXlsxGateway().open(new ByteArrayInputStream(template)))) {
            assertEquals(ImportLayout.WIDE, ImportWorkbookParser.detect(workbook));
            // 表头 = 前置 9 + 锚点基线列（f267 出生日期无锚点不进列、f310 停用但有锚点仍在，OQ-72）+ 1 组锚点随访列
            assertEquals(
                    List.of(
                            "序号", "患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类", "数据完整度",
                            "手术日期", "病案号", "姓名", "备注", "随访日期", "死亡随访时间"),
                    workbook.sheets().get(0).header());
            List<ImportPatientRecord> patients = new ArrayList<>();
            new WideLayoutAdapter().stream(workbook, catalog(), patients::add);
            assertEquals(1, patients.size(), "宽表示例行恰好一位患者");
            assertEquals("DEMOA0001（示例）", patients.get(0).mrn());
            // 示例行只填前置列 → 基线与随访列全空（导入前删除示例行）
            assertEquals(0, patients.get(0).baseline().size());
            assertEquals(0, patients.get(0).followups().size());
        }
    }

    /** 模板示例行的虚构值必须带「示例」标记（规则 5：种子 / 示例不含真实患者数据）。 */
    @Test
    void exampleValuesAreFictional() throws Exception {
        byte[] template = ImportTemplateComposer.standard(rules());
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        new StreamingXlsxGateway().open(new ByteArrayInputStream(template)))) {
            List<ImportPatientRecord> patients = new ArrayList<>();
            new LongLayoutAdapter().streamBaseline(workbook, catalog(), patients::add);
            assertEquals(1, patients.size());
            assertEquals("DEMOA0001（示例）", patients.get(0).mrn());
        }
    }

    /** 宽表模板缺锚点列时适配器整体报错——反向证明模板列序与适配器对位（防御性用例）。 */
    @Test
    void wideTemplateColumnSetIsExactlyAnchoredFields() throws Exception {
        byte[] template = ImportTemplateComposer.wide(rules());
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        new StreamingXlsxGateway().open(new ByteArrayInputStream(template)))) {
            // 目录里去掉一个锚点字段（f310），表头列数对不上 → 整体报错
            ImportFieldCatalog smaller =
                    new StaticImportFieldCatalog(
                            catalog().baselineFields().stream()
                                    .filter(field -> !"f310".equals(field.fieldKey()))
                                    .toList(),
                            catalog().followupFields(),
                            "f002");
            assertThrows(
                    ImportLayoutException.class,
                    () -> new WideLayoutAdapter().stream(workbook, smaller, record -> {}));
        }
    }

    private static ImportFieldRule rule(String key, String label, String anchor) {
        return new ImportFieldRule(
                key,
                label,
                anchor,
                "basic",
                "column",
                true,
                FieldType.TEXT,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of(),
                null);
    }

    private static ImportFieldRule disabled(String key, String label, String anchor) {
        return new ImportFieldRule(
                key,
                label,
                anchor,
                "basic",
                "column",
                false,
                FieldType.TEXT,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                Map.of(),
                null);
    }
}
