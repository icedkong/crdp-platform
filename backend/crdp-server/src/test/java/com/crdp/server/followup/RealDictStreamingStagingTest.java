package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.followup.importer.domain.ImportFieldCatalog;
import com.crdp.followup.importer.domain.ImportLayout;
import com.crdp.followup.importer.domain.ImportPatientRecord;
import com.crdp.followup.importer.domain.ImportWorkbookParser;
import com.crdp.followup.importer.domain.InMemoryImportSheet;
import com.crdp.followup.importer.domain.InMemoryImportWorkbook;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.importer.infra.StreamingImportWorkbook;
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.excel.StreamingXlsxGateway;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E18-T02 核对（单元，不依赖数据库）：流式管道对<strong>真实医院 A 字段字典</strong>（V38）的医院样例宽表—— 761 列（9 + 212 + 10 ×
 * 54，A-PAT-018）真实 xlsx 字节（SimpleXlsx 生成）经框架流式网关 + {@link StreamingImportWorkbook} 桥接 + {@link
 * WideLayoutAdapter#stream} 逐行回调， 解析结果与整表内存路径（E18-T01 parse）完全一致（tasks.csv 验收「大文件不占满内存」的端到端形状核对；
 * OQ-100④ 接入缝）。测试数据全部虚构（规则 5）。
 */
class RealDictStreamingStagingTest {

    private static final ImportFieldCatalog CATALOG = RealDictLayoutAdapterTest.realDictCatalog();

    @Test
    void streamsHospitalSampleWideWorkbookSameAsInMemoryParse() throws IOException {
        List<String> header = wideHeader(10);
        assertEquals(761, header.size(), "医院样例 9 + 212 + 10 × 54 = 761 列");
        List<List<String>> rows = List.of(patientRow(1), patientRow(2), patientRow(3));
        byte[] xlsx = SimpleXlsx.write(header, rows);

        List<ImportPatientRecord> streamed = new ArrayList<>();
        try (StreamingImportWorkbook workbook =
                new StreamingImportWorkbook(
                        new StreamingXlsxGateway().open(new ByteArrayInputStream(xlsx)))) {
            assertEquals(ImportLayout.WIDE, ImportWorkbookParser.detect(workbook));
            new WideLayoutAdapter().stream(workbook, CATALOG, streamed::add);
        }

        List<List<String>> withHeader = new ArrayList<>();
        withHeader.add(header);
        withHeader.addAll(rows);
        List<ImportPatientRecord> expected =
                ImportWorkbookParser.parse(
                                new InMemoryImportWorkbook(
                                        List.of(new InMemoryImportSheet("患者数据", withHeader))),
                                CATALOG)
                        .patients();
        assertEquals(expected, streamed, "流式（真实 xlsx 字节）与整表内存路径同一模型");
        assertEquals(3, streamed.size());
        assertEquals("X0001（示例）", streamed.get(0).mrn());
        assertEquals(2, streamed.get(0).rowNo(), "数据行从工作表第 2 行起");
        assertEquals(
                3,
                streamed.get(0).followups().size(),
                "第 1、2、3 年有数据（第 2 组只有 #217 也算非空，4–10 年全空跳过）");
        assertEquals(1, streamed.get(0).followups().get(0).year());
        assertEquals(2, streamed.get(0).followups().get(1).year());
        assertEquals(3, streamed.get(0).followups().get(2).year());
        assertEquals(
                "20270315", streamed.get(0).followups().get(0).values().get("f214").normalized());
        // 单元格坐标按 761 列网格保留（E18-T03 批注版报告定位）：基线首列 = J 列，第 3 年随访日期 = 第 9+212+2×54+1 列（f214 是随访锚点列的第 2
        // 个）
        assertEquals(2, streamed.get(0).baseline().get("f001").rowNo());
        assertEquals(9, streamed.get(0).baseline().get("f001").colNo());
        assertEquals(2, streamed.get(0).followups().get(2).values().get("f214").rowNo());
        assertEquals(330, streamed.get(0).followups().get(2).values().get("f214").colNo());
    }

    // ---------------------------------------------------------------- 夹具

    /** 宽表表头：前置 9 + 基线锚点 212 + groups 组随访锚点 54。 */
    private static List<String> wideHeader(int groups) {
        List<String> header = new ArrayList<>(WideLayoutAdapter.FRONT_COLUMNS);
        CATALOG.baselineFields().stream()
                .filter(field -> field.originalHeader() != null)
                .forEach(field -> header.add(field.originalHeader()));
        List<String> followupAnchors =
                CATALOG.followupFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(ImportFieldCatalog.CatalogField::originalHeader)
                        .toList();
        for (int g = 0; g < groups; g++) {
            header.addAll(followupAnchors);
        }
        return header;
    }

    /** 第 no 位虚构患者：基线 4 值 + 第 1、3 年随访（第 2 组只有 #217 死亡随访时间）。 */
    private static List<String> patientRow(int no) {
        Map<Integer, String> row = new HashMap<>();
        row.put(0, String.valueOf(no)); // 序号
        row.put(1, "L000" + no + "（示例）"); // 患者编号 → 原系统编号
        row.put(3, "患者" + no + "（示例）"); // 前置列姓名
        putBaseline(row, "f001", "20260115");
        putBaseline(row, "f002", "X000" + no + "（示例）");
        putBaseline(row, "f003", "患者" + no + "（示例）");
        putBaseline(row, "f005", String.valueOf(60 + no));
        putFollowup(row, 1, "f214", "20270315");
        putFollowup(row, 1, "f248", String.valueOf(40 + no));
        putFollowup(row, 2, "f217", "20270320"); // 第 2 组只有 #217（停用字段）有值
        putFollowup(row, 3, "f214", "20290315");
        putFollowup(row, 3, "f215", "0");
        return cells(row, 761);
    }

    private static void putBaseline(Map<Integer, String> row, String key, String value) {
        List<String> keys = anchoredBaselineKeys();
        row.put(WideLayoutAdapter.FRONT_COLUMNS.size() + keys.indexOf(key), value);
    }

    private static void putFollowup(Map<Integer, String> row, int year, String key, String value) {
        List<String> keys =
                CATALOG.followupFields().stream()
                        .filter(field -> field.originalHeader() != null)
                        .map(ImportFieldCatalog.CatalogField::fieldKey)
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
                .map(ImportFieldCatalog.CatalogField::fieldKey)
                .toList();
    }

    private static List<String> cells(Map<Integer, String> sparse, int width) {
        List<String> row = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            row.add(sparse.getOrDefault(i, ""));
        }
        return row;
    }
}
