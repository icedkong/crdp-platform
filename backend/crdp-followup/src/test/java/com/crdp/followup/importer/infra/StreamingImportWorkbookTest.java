package com.crdp.followup.importer.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportLayout;
import com.crdp.followup.importer.domain.ImportParseResult;
import com.crdp.followup.importer.domain.ImportWorkbookParser;
import com.crdp.followup.importer.domain.InMemoryImportSheet;
import com.crdp.followup.importer.domain.InMemoryImportWorkbook;
import com.crdp.framework.excel.ExcelGateway;
import com.crdp.framework.excel.StreamingXlsxGateway;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T02 流式桥接测试：{@link StreamingImportWorkbook} 把框架流式网关的 xlsx 逐行读取接成 {@link
 * com.crdp.followup.importer.domain.ImportSheetData}——版面识别 + 两种版面适配器在<strong>真实 xlsx
 * 字节</strong>上的解析结果与 E18-T01 的整表内存实现完全一致（同一模型，OQ-100④ 接入缝兑现）。 全部虚构数据。
 */
class StreamingImportWorkbookTest {

    private final ExcelGateway gateway = new StreamingXlsxGateway();

    @Test
    void wideXlsxParsesSameAsInMemory() throws IOException {
        List<List<String>> wide =
                List.of(
                        LayoutFixtureBridge.wideHeader(2),
                        LayoutFixtureBridge.row(
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
                        LayoutFixtureBridge.row());
        ImportParseResult expected =
                ImportWorkbookParser.parse(
                        new InMemoryImportWorkbook(List.of(new InMemoryImportSheet("患者数据", wide))),
                        catalog());
        ImportParseResult actual;
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        gateway.open(
                                new ByteArrayInputStream(
                                        com.crdp.followup.importer.XlsxTestFiles.workbook(
                                                new com.crdp.followup.importer.XlsxTestFiles.Sheet(
                                                        "患者数据", wide)))))) {
            assertEquals(ImportLayout.WIDE, ImportWorkbookParser.detect(workbook));
            actual = ImportWorkbookParser.parse(workbook, catalog());
        }
        assertEquals(expected, actual);
    }

    @Test
    void longXlsxParsesSameAsInMemory() throws IOException {
        List<List<String>> baseline =
                List.of(
                        LayoutFixtureBridge.LONG_BASELINE_HEADERS,
                        LayoutFixtureBridge.row(
                                "2020-01-10",
                                "MRN001（示例）",
                                "张三（示例）",
                                "男",
                                "1970-01-01",
                                "50",
                                "P001（示例）"));
        List<List<String>> followups =
                List.of(
                        LayoutFixtureBridge.LONG_FOLLOWUP_HEADERS,
                        LayoutFixtureBridge.row("MRN001（示例）", "1", "2021-02-11", "0", "55"),
                        LayoutFixtureBridge.row("MRN404（示例）", "2", "2022-03-12", "0", "56"));
        ImportParseResult expected =
                ImportWorkbookParser.parse(
                        new InMemoryImportWorkbook(
                                List.of(
                                        new InMemoryImportSheet("基线", baseline),
                                        new InMemoryImportSheet("随访记录", followups))),
                        catalog());
        ImportParseResult actual;
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        gateway.open(
                                new ByteArrayInputStream(
                                        com.crdp.followup.importer.XlsxTestFiles.workbook(
                                                new com.crdp.followup.importer.XlsxTestFiles.Sheet(
                                                        "基线", baseline),
                                                new com.crdp.followup.importer.XlsxTestFiles.Sheet(
                                                        "随访记录", followups)))))) {
            assertEquals(ImportLayout.LONG, ImportWorkbookParser.detect(workbook));
            actual = ImportWorkbookParser.parse(workbook, catalog());
        }
        assertEquals(expected, actual);
    }

    // ---------------------------------------------------------------- 夹具

    /** 与 domain.LayoutFixture 同形状的本地夹具（跨包不可见）：宽表 / 长表表头与参差数据行。 */
    private static final class LayoutFixtureBridge {

        static final List<String> FRONT_COLUMNS =
                List.of("序号", "患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类", "数据完整度");

        static final List<String> WIDE_BASELINE_HEADERS = List.of("手术日期", "病案号", "姓名", "性别", "年龄");

        static final List<String> WIDE_FOLLOWUP_HEADERS = List.of("随访日期", "死亡", "死亡随访时间", "EF");

        static final List<String> LONG_BASELINE_HEADERS =
                List.of("手术日期", "病案号", "姓名", "性别", "出生日期", "手术年龄", "原系统编号");

        static final List<String> LONG_FOLLOWUP_HEADERS =
                List.of("病案号", "随访年度", "随访日期", "是否死亡", "EF");

        static List<String> wideHeader(int groups) {
            List<String> header = new ArrayList<>(FRONT_COLUMNS);
            header.addAll(WIDE_BASELINE_HEADERS);
            for (int g = 0; g < groups; g++) {
                header.addAll(WIDE_FOLLOWUP_HEADERS);
            }
            return header;
        }

        /** 参差行（不补齐）：整表内存实现与流式桥接都按表头宽补空白，两条路径等价。 */
        static List<String> row(String... cells) {
            return List.of(cells);
        }
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
}
