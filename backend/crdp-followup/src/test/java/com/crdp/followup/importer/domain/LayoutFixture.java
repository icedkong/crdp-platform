package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * E18-T01 版面适配器测试共用夹具：小型虚构字段目录与两种版面的工作表构造。
 *
 * <p>目录形状对齐医院 A 字典的真实差异点：出生日期无原表列名锚点（仅长表）、#217 死亡随访时间带锚点但停用 （仅宽表，Q22 /
 * OQ-72②）、手术年龄显示名与原表列名「年龄」不同（OQ-72① 锚点匹配）、是否死亡锚点「死亡」。 全部虚构数据（规则 5）。
 */
final class LayoutFixture {

    /** 前置列（A 规格 §4「医院现有宽表的列结构」：9 列，顺序固定）。 */
    static final List<String> FRONT_COLUMNS =
            List.of("序号", "患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类", "数据完整度");

    /** 宽表基线列 = 带原表列名锚点的基线字段（本目录 5 个：手术日期、病案号、姓名、性别、年龄）。 */
    static final List<String> WIDE_BASELINE_HEADERS = List.of("手术日期", "病案号", "姓名", "性别", "年龄");

    /** 宽表随访列 = 带锚点的随访字段（含停用的死亡随访时间，共 4 个）。 */
    static final List<String> WIDE_FOLLOWUP_HEADERS = List.of("随访日期", "死亡", "死亡随访时间", "EF");

    /** 长表「基线」表列 = 启用基线字段的显示名 + 可选固定列「原系统编号」。 */
    static final List<String> LONG_BASELINE_HEADERS =
            List.of("手术日期", "病案号", "姓名", "性别", "出生日期", "手术年龄", "原系统编号");

    /** 长表「随访记录」表列 = 固定身份列（病案号、随访年度）+ 启用随访字段的显示名（停用的死亡随访时间不在模板）。 */
    static final List<String> LONG_FOLLOWUP_HEADERS = List.of("病案号", "随访年度", "随访日期", "是否死亡", "EF");

    private LayoutFixture() {}

    /** 虚构字段目录：基线 6（出生日期无锚点）、随访 4（死亡随访时间停用）、病案号 = f002。 */
    static ImportFieldCatalog catalog() {
        return new StaticImportFieldCatalog(
                List.of(
                        field("f001", "手术日期", "手术日期", true),
                        field("f002", "病案号", "病案号", true),
                        field("f003", "姓名", "姓名", true),
                        field("f004", "性别", "性别", true),
                        field("f005", "手术年龄", "年龄", true),
                        field("f267", "出生日期", null, true)),
                List.of(
                        field("f214", "随访日期", "随访日期", true),
                        field("f215", "是否死亡", "死亡", true),
                        field("f217", "死亡随访时间", "死亡随访时间", false),
                        field("f257", "EF", "EF", true)),
                "f002");
    }

    static ImportFieldCatalog.CatalogField field(
            String key, String label, String anchor, boolean enabled) {
        return new ImportFieldCatalog.CatalogField(key, label, anchor, enabled);
    }

    /** 宽表工作簿（一张工作表；表头 = 前置 + 基线 + N 组随访）。 */
    static ImportWorkbookData wideWorkbook(List<String> header, List<List<String>> dataRows) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(header);
        rows.addAll(dataRows);
        return new InMemoryImportWorkbook(List.of(new InMemoryImportSheet("患者数据", rows)));
    }

    /** 完整宽表表头（前置 + 基线锚点 + groups 组随访锚点）。 */
    static List<String> wideHeader(int groups) {
        List<String> header = new ArrayList<>(FRONT_COLUMNS);
        header.addAll(WIDE_BASELINE_HEADERS);
        for (int g = 0; g < groups; g++) {
            header.addAll(WIDE_FOLLOWUP_HEADERS);
        }
        return header;
    }

    /** 长表工作簿（「基线」+「随访记录」两张工作表）。 */
    static ImportWorkbookData longWorkbook(
            List<List<String>> baselineRows, List<List<String>> followupRows) {
        return new InMemoryImportWorkbook(
                List.of(
                        new InMemoryImportSheet("基线", baselineRows),
                        new InMemoryImportSheet("随访记录", followupRows)));
    }

    /** 行构造：缺省补空白到指定列数。 */
    static List<String> row(int columns, String... cells) {
        List<String> row = new ArrayList<>(Arrays.asList(cells));
        while (row.size() < columns) {
            row.add("");
        }
        return row;
    }
}
