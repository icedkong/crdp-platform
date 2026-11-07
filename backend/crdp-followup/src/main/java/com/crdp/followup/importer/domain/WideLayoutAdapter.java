package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * E18-T01 / A-PAT-018 宽表版面适配器：医院现有 Excel——一张工作表、一个患者一行， 「9 个前置列 + 基线列（带原表列名锚点的字段）+ N 组 × 随访列（每组列数 =
 * 带锚点的随访字段数，表头相同且无 「第N年」字样）」，总列数 = 221 + 54 × 组数（医院样例 N = 10、共 761 列），组数不设上限。
 *
 * <p>识别与转换（A 规格 §4「宽表历史数据导入流程」）：
 *
 * <ul>
 *   <li>表头按<strong>位置</strong>逐列对位（「表头与字典显示名一致、顺序相同」）：前置列 9 列名字固定；
 *       基线列与随访列按字典顺序匹配「原表列名」锚点（显示名也认，OQ-72①）；列数或表头对不上 {@link ImportLayoutException}
 *       整体报错、不做猜测，差异清单带列字母；
 *   <li>第 N 组随访列即第 N 年（无「第N年」字样），<strong>全空的组跳过</strong>；
 *   <li>单元格一律按文本读入，空白与「N/A」归一为空值；类型 / 日期 / 选项转换与校验属 E18-T03；
 *   <li>前置列：患者编号 → 原系统编号；病案号 / 姓名 / 性别 / 手术年龄 / 入院日期 / 诊断分类保留供
 *       「前置列与基线列冲突」比对（以基线列为准）；序号忽略、数据完整度不导入（A-PAT-019 由系统重算）。
 * </ul>
 */
public final class WideLayoutAdapter implements ImportLayoutAdapter {

    /** 前置列（顺序固定，A 规格 §4「医院现有宽表的列结构」）。 */
    public static final List<String> FRONT_COLUMNS =
            List.of("序号", "患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类", "数据完整度");

    /** 保留进模型的前置列（序号忽略、数据完整度不导入）。 */
    private static final Set<String> KEPT_FRONT_COLUMNS =
            Set.of("患者编号", "病案号", "姓名", "性别", "手术年龄", "入院日期", "诊断分类");

    private static final String FRONT_MRN = "病案号";
    private static final String FRONT_LEGACY_NO = "患者编号";

    @Override
    public ImportLayout layout() {
        return ImportLayout.WIDE;
    }

    @Override
    public ImportParseResult parse(ImportWorkbookData workbook, ImportFieldCatalog catalog) {
        List<ImportPatientRecord> patients = new ArrayList<>();
        stream(workbook, catalog, patients::add);
        return new ImportParseResult(ImportLayout.WIDE, patients, List.of());
    }

    /**
     * 流式解析（E18-T02 暂存管道接缝，验收「大文件不占满内存」）：先校验表头（结构性问题在任何行回调之前 整体报错），再逐行回调患者记录——一个数据行即一位患者，调用方按行写
     * imp_row 后即可丢弃， 内存与文件行数无关（tech-plan §12「逐行读取，每行写入暂存表」）。 一次性模型 {@link #parse} 只是本方法的收集版。
     */
    public void stream(
            ImportWorkbookData workbook,
            ImportFieldCatalog catalog,
            Consumer<ImportPatientRecord> sink) {
        if (workbook.sheets().size() != 1) {
            throw new ImportLayoutException(
                    "宽表导入需要一张工作表，实际 " + workbook.sheets().size() + " 张",
                    List.of(
                            "宽表导入需要一张工作表，实际 "
                                    + workbook.sheets().size()
                                    + " 张（"
                                    + sheetNames(workbook)
                                    + "）；两张工作表的长表模板见导入向导"));
        }
        ImportSheetData sheet = workbook.sheets().get(0);
        ColumnLayout layout =
                validateHeader(
                        sheet,
                        anchored(catalog.baselineFields()),
                        anchored(catalog.followupFields()));
        sheet.dataRows(
                (rowNo, cells) -> {
                    ImportPatientRecord record = record(rowNo, cells, layout, catalog);
                    // 整行空白（含只填了被忽略的序号 / 数据完整度列）不产生患者记录——序号忽略、数据完整度不导入
                    if (!record.frontColumns().isEmpty()
                            || !record.baseline().isEmpty()
                            || !record.followups().isEmpty()) {
                        sink.accept(record);
                    }
                });
    }

    // ---------------------------------------------------------------- 表头识别

    /** 表头校验后的列布局（各段的起始列与列数）。 */
    private record ColumnLayout(
            List<ImportFieldCatalog.CatalogField> baselineFields,
            List<ImportFieldCatalog.CatalogField> followupFields,
            int groups) {}

    private ColumnLayout validateHeader(
            ImportSheetData sheet,
            List<ImportFieldCatalog.CatalogField> baselineFields,
            List<ImportFieldCatalog.CatalogField> followupFields) {
        List<String> problems = new ArrayList<>();
        List<String> header = sheet.header();
        if (header.isEmpty()) {
            problems.add("工作表「" + sheet.name() + "」为空，没有表头行");
            throw new ImportLayoutException("宽表表头不匹配", problems);
        }
        for (int i = 0; i < FRONT_COLUMNS.size(); i++) {
            String expected = FRONT_COLUMNS.get(i);
            String actual = cell(header, i);
            if (!expected.equals(actual.strip())) {
                problems.add(
                        "第 "
                                + (i + 1)
                                + " 列（"
                                + ImportCell.columnLetters(i)
                                + "）表头「"
                                + actual.strip()
                                + "」应为前置列「"
                                + expected
                                + "」");
            }
        }
        for (int i = 0; i < baselineFields.size(); i++) {
            matchProblem(problems, header, FRONT_COLUMNS.size() + i, baselineFields.get(i), "基线");
        }
        int followupStart = FRONT_COLUMNS.size() + baselineFields.size();
        int remainder = header.size() - followupStart;
        if (followupFields.isEmpty()) {
            problems.add("字段字典没有带原表列名的随访字段，无法识别随访列组");
        } else if (remainder < followupFields.size()) {
            problems.add(
                    "随访列组列数不对：总列数 "
                            + header.size()
                            + "，应为 "
                            + followupStart
                            + " + "
                            + followupFields.size()
                            + " × 组数（随访列每组 "
                            + followupFields.size()
                            + " 列，至少 1 组）");
        } else if (remainder % followupFields.size() != 0) {
            problems.add(
                    "随访列组列数不对：基线列之后 "
                            + remainder
                            + " 列，不是每组 "
                            + followupFields.size()
                            + " 列的整数倍（列数或表头须为 "
                            + followupStart
                            + " + "
                            + followupFields.size()
                            + " × 组数）");
        }
        int groups =
                followupFields.isEmpty() || remainder < followupFields.size()
                        ? 0
                        : remainder / followupFields.size();
        for (int g = 0; g < groups; g++) {
            for (int j = 0; j < followupFields.size(); j++) {
                matchProblem(
                        problems,
                        header,
                        followupStart + g * followupFields.size() + j,
                        followupFields.get(j),
                        "第 " + (g + 1) + " 组随访");
            }
        }
        if (!problems.isEmpty()) {
            throw new ImportLayoutException(
                    "宽表表头不匹配（" + problems.size() + " 处，整体报错不做猜测）", problems);
        }
        return new ColumnLayout(baselineFields, followupFields, groups);
    }

    /** 基线 / 随访列逐列对位：表头须匹配该字段的原表列名锚点或显示名。 */
    private static void matchProblem(
            List<String> problems,
            List<String> header,
            int colNo,
            ImportFieldCatalog.CatalogField field,
            String segment) {
        String actual = cell(header, colNo).strip();
        if (!field.matches(actual)) {
            problems.add(
                    "第 "
                            + (colNo + 1)
                            + " 列（"
                            + ImportCell.columnLetters(colNo)
                            + "）表头「"
                            + actual
                            + "」应为"
                            + segment
                            + "字段「"
                            + field.label()
                            + "」"
                            + (field.originalHeader() == null
                                            || field.originalHeader().equals(field.label())
                                    ? ""
                                    : "（原表列名「" + field.originalHeader() + "」"));
        }
    }

    // ---------------------------------------------------------------- 行解析

    private ImportPatientRecord record(
            int rowNo, List<String> cells, ColumnLayout layout, ImportFieldCatalog catalog) {
        Map<String, ImportCell> frontColumns = new LinkedHashMap<>();
        for (int i = 0; i < FRONT_COLUMNS.size(); i++) {
            String name = FRONT_COLUMNS.get(i);
            ImportCell cell = new ImportCell(cell(cells, i), rowNo, i);
            if (KEPT_FRONT_COLUMNS.contains(name) && !cell.isBlank()) {
                frontColumns.put(name, cell);
            }
        }
        Map<String, ImportCell> baseline = new LinkedHashMap<>();
        int baselineStart = FRONT_COLUMNS.size();
        for (int j = 0; j < layout.baselineFields().size(); j++) {
            putValue(
                    baseline,
                    layout.baselineFields().get(j).fieldKey(),
                    cells,
                    rowNo,
                    baselineStart + j);
        }
        ImportCell mrnCell = baseline.get(catalog.mrnFieldKey().orElse(null));
        if (mrnCell == null) {
            mrnCell = frontColumns.get(FRONT_MRN); // 基线列空白时回落前置列（冲突警告随 E18-T03）
        }
        ImportCell legacyNoCell = frontColumns.get(FRONT_LEGACY_NO);

        List<ImportFollowup> followups = new ArrayList<>();
        int followupStart = baselineStart + layout.baselineFields().size();
        for (int g = 0; g < layout.groups(); g++) {
            Map<String, ImportCell> values = new LinkedHashMap<>();
            int groupStart = followupStart + g * layout.followupFields().size();
            for (int j = 0; j < layout.followupFields().size(); j++) {
                putValue(
                        values,
                        layout.followupFields().get(j).fieldKey(),
                        cells,
                        rowNo,
                        groupStart + j);
            }
            if (!values.isEmpty()) { // 全空的组跳过
                followups.add(new ImportFollowup(g + 1, null, mrnCell, rowNo, values));
            }
        }
        return new ImportPatientRecord(
                mrnCell, legacyNoCell, frontColumns, baseline, followups, rowNo);
    }

    /** 非空单元格才进模型（空白 / N/A 归一为空值）。 */
    private static void putValue(
            Map<String, ImportCell> values,
            String fieldKey,
            List<String> cells,
            int rowNo,
            int colNo) {
        ImportCell cell = new ImportCell(cell(cells, colNo), rowNo, colNo);
        if (!cell.isBlank()) {
            values.put(fieldKey, cell);
        }
    }

    /** 带原表列名锚点的字段（宽表列集，OQ-72①；停用字段带锚点也保留——#217 仍是医院原表的列，OQ-72②）。 */
    private static List<ImportFieldCatalog.CatalogField> anchored(
            List<ImportFieldCatalog.CatalogField> fields) {
        return fields.stream().filter(field -> field.originalHeader() != null).toList();
    }

    private static String cell(List<String> cells, int colNo) {
        return colNo < cells.size() && cells.get(colNo) != null ? cells.get(colNo) : "";
    }

    private static String sheetNames(ImportWorkbookData workbook) {
        List<String> names = new ArrayList<>();
        workbook.sheets().forEach(sheet -> names.add(sheet.name()));
        return String.join("、", names);
    }
}
