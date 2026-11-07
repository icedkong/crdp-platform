package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * E18-T01 / A-PAT-008 长表版面适配器：新模板两张工作表——「基线」一个患者一行、「随访记录」一次随访一行， 用「病案号 + 随访年度」确定是哪位患者的第几年（A 规格
 * §4「历史数据导入流程」「导入格式说明：宽表与长表」）。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>列集 = <strong>启用</strong>字段的显示名（模板由字段字典生成；停用字段不进模板下发，如合并停用的 #217 死亡随访时间，Q22 /
 *       OQ-72②；原表列名锚点也认，宽松到两种写法）；
 *   <li>「基线」表可带固定列「原系统编号」（可选项，宽表对应前置列「患者编号」）；「随访记录」表固定身份列 「病案号」「随访年度」在最前，列顺序不影响（按表头名定位，重复 / 未知 /
 *       缺列整体报错不做猜测）；
 *   <li>随访行按病案号并入患者（同一病案号 + 随访年度出现两行、病案号在「基线」表中不存在、随访年度解析不了 都是数据级问题，留在模型里由 E18-T03 校验报告报错，这里不丢行）；
 *   <li>整行空白跳过；单元格空白 / 「N/A」归一为空值；类型转换与校验属 E18-T03。
 * </ul>
 */
public final class LongLayoutAdapter implements ImportLayoutAdapter {

    /** 长表两张工作表名（模板固定）。 */
    public static final String BASELINE_SHEET = "基线";

    public static final String FOLLOWUP_SHEET = "随访记录";

    /** 固定身份 / 可选列。 */
    public static final String MRN_COLUMN = "病案号";

    public static final String YEAR_COLUMN = "随访年度";

    public static final String LEGACY_NO_COLUMN = "原系统编号";

    @Override
    public ImportLayout layout() {
        return ImportLayout.LONG;
    }

    @Override
    public ImportParseResult parse(ImportWorkbookData workbook, ImportFieldCatalog catalog) {
        // 一次性模型 = 流式产出的收集 + 跨行关联（同一病案号的随访行并入第一行基线骨架）
        List<ImportPatientRecord> skeletons = new ArrayList<>();
        streamBaseline(workbook, catalog, skeletons::add);
        List<ImportFollowup> allFollowups = new ArrayList<>();
        streamFollowups(workbook, catalog, allFollowups::add);

        Map<String, Integer> firstIndexByMrn = new LinkedHashMap<>();
        for (int i = 0; i < skeletons.size(); i++) {
            String mrn = skeletons.get(i).mrn();
            if (mrn != null && !firstIndexByMrn.containsKey(mrn)) {
                firstIndexByMrn.put(mrn, i);
            }
        }
        List<List<ImportFollowup>> attached = new ArrayList<>();
        for (int i = 0; i < skeletons.size(); i++) {
            attached.add(new ArrayList<>());
        }
        for (ImportFollowup followup : allFollowups) {
            Integer index =
                    followup.mrnCell() == null || followup.mrnCell().isBlank()
                            ? null
                            : firstIndexByMrn.get(followup.mrnCell().normalized());
            if (index != null) {
                attached.get(index).add(followup);
            }
        }

        List<ImportPatientRecord> patients = new ArrayList<>();
        for (int i = 0; i < skeletons.size(); i++) {
            ImportPatientRecord skeleton = skeletons.get(i);
            patients.add(
                    new ImportPatientRecord(
                            skeleton.mrnCell(),
                            skeleton.legacyNoCell(),
                            Map.of(),
                            skeleton.baseline(),
                            attached.get(i),
                            skeleton.rowNo()));
        }

        List<ImportFollowup> unmatched =
                allFollowups.stream()
                        .filter(
                                followup ->
                                        followup.mrnCell() == null
                                                || followup.mrnCell().isBlank()
                                                || !firstIndexByMrn.containsKey(
                                                        followup.mrnCell().normalized()))
                        .toList();
        return new ImportParseResult(ImportLayout.LONG, patients, unmatched);
    }

    /**
     * 长表流式解析之一（E18-T02 暂存管道，验收「大文件不占满内存」）：「基线」表逐行回调患者骨架（前置列恒空、 随访行不并入——跨行「病案号 +
     * 随访年度」关联属校验阶段，按暂存行的病案号盲索引做，tech-plan §12「每行写入暂存表」）。 表头映射与 {@link #parse} 同一套（缺列 / 未知列 /
     * 重复列整体报错，在任何行回调之前）。
     */
    public void streamBaseline(
            ImportWorkbookData workbook,
            ImportFieldCatalog catalog,
            Consumer<ImportPatientRecord> sink) {
        ImportSheetData baselineSheet = requireSheet(workbook, BASELINE_SHEET);
        List<ImportFieldCatalog.CatalogField> baselineFields = enabled(catalog.baselineFields());
        HeaderMap baselineHeader =
                mapHeader(baselineSheet, baselineFields, Set.of(), Set.of(LEGACY_NO_COLUMN), "基线");
        baselineSheet.dataRows(
                (rowNo, cells) -> {
                    if (isBlankRow(cells)) {
                        return;
                    }
                    Map<String, ImportCell> baseline = new LinkedHashMap<>();
                    for (ImportFieldCatalog.CatalogField field : baselineFields) {
                        putValue(
                                baseline,
                                field.fieldKey(),
                                cells,
                                rowNo,
                                baselineHeader.column(field.fieldKey()));
                    }
                    int legacyCol = baselineHeader.columnOrNull(LEGACY_NO_COLUMN);
                    ImportCell legacyNoCell =
                            legacyCol < 0
                                    ? null
                                    : new ImportCell(cell(cells, legacyCol), rowNo, legacyCol);
                    // 病案号空单元格也带坐标（E18-T03「病案号为空」要钉回原单元格批注）
                    String mrnKey = catalog.mrnFieldKey().orElse(null);
                    ImportCell mrnCell =
                            mrnKey == null
                                    ? null
                                    : new ImportCell(
                                            cell(cells, baselineHeader.column(mrnKey)),
                                            rowNo,
                                            baselineHeader.column(mrnKey));
                    sink.accept(
                            new ImportPatientRecord(
                                    mrnCell, legacyNoCell, Map.of(), baseline, List.of(), rowNo));
                });
    }

    /**
     * 长表流式解析之二：「随访记录」表逐行回调一次随访（含病案号不在基线表的行——数据级问题留给 E18-T03 校验报告， 这里不丢行；年度解析不了保留原文、{@code year} 为
     * null）。
     */
    public void streamFollowups(
            ImportWorkbookData workbook,
            ImportFieldCatalog catalog,
            Consumer<ImportFollowup> sink) {
        ImportSheetData followupSheet = requireSheet(workbook, FOLLOWUP_SHEET);
        List<ImportFieldCatalog.CatalogField> followupFields = enabled(catalog.followupFields());
        HeaderMap followupHeader =
                mapHeader(
                        followupSheet,
                        followupFields,
                        Set.of(MRN_COLUMN, YEAR_COLUMN),
                        Set.of(),
                        "随访记录");
        followupSheet.dataRows(
                (rowNo, cells) -> {
                    if (isBlankRow(cells)) {
                        return;
                    }
                    ImportCell mrnCell =
                            new ImportCell(
                                    cell(cells, followupHeader.column(MRN_COLUMN)),
                                    rowNo,
                                    followupHeader.column(MRN_COLUMN));
                    ImportCell yearCell =
                            new ImportCell(
                                    cell(cells, followupHeader.column(YEAR_COLUMN)),
                                    rowNo,
                                    followupHeader.column(YEAR_COLUMN));
                    Map<String, ImportCell> values = new LinkedHashMap<>();
                    for (ImportFieldCatalog.CatalogField field : followupFields) {
                        putValue(
                                values,
                                field.fieldKey(),
                                cells,
                                rowNo,
                                followupHeader.column(field.fieldKey()));
                    }
                    sink.accept(
                            new ImportFollowup(
                                    parseYear(yearCell), yearCell, mrnCell, rowNo, values));
                });
    }

    /** 取指定工作表；缺表整体报错（版面不完整，不做猜测）。 */
    private static ImportSheetData requireSheet(ImportWorkbookData workbook, String name) {
        return workbook.sheet(name)
                .orElseThrow(
                        () ->
                                new ImportLayoutException(
                                        "长表导入需要「基线」与「随访记录」两张工作表", List.of("缺少「" + name + "」工作表")));
    }

    // ---------------------------------------------------------------- 表头映射

    /** 表头名 → 列下标（目标键 = 字段稳定键或固定列名）。 */
    private record HeaderMap(Map<String, Integer> columns) {

        int column(String target) {
            Integer colNo = columns.get(target);
            if (colNo == null) {
                throw new IllegalStateException("列「" + target + "」不在表头映射里（校验应已拦截）");
            }
            return colNo;
        }

        int columnOrNull(String target) {
            Integer colNo = columns.get(target);
            return colNo == null ? -1 : colNo;
        }
    }

    /**
     * 按表头名映射列：每个期望字段与固定列都要有且只有一列，未知表头报错。
     *
     * @param requiredFixed 必须存在的固定列（随访记录：病案号、随访年度）
     * @param optionalFixed 可缺省的固定列（基线：原系统编号）
     */
    private static HeaderMap mapHeader(
            ImportSheetData sheet,
            List<ImportFieldCatalog.CatalogField> fields,
            Set<String> requiredFixed,
            Set<String> optionalFixed,
            String segment) {
        List<String> problems = new ArrayList<>();
        List<String> header = sheet.header();
        if (header.isEmpty()) {
            problems.add("工作表「" + sheet.name() + "」为空，没有表头行");
            throw new ImportLayoutException("长表「" + segment + "」表头不匹配", problems);
        }
        Map<String, String> nameToTarget = new LinkedHashMap<>();
        for (String fixed : requiredFixed) {
            nameToTarget.put(fixed, fixed);
        }
        for (String fixed : optionalFixed) {
            nameToTarget.put(fixed, fixed);
        }
        for (ImportFieldCatalog.CatalogField field : fields) {
            for (String name : field.matchNames()) {
                String existing = nameToTarget.putIfAbsent(name, field.fieldKey());
                if (existing != null && !existing.equals(field.fieldKey())) {
                    problems.add(
                            "表头「"
                                    + name
                                    + "」同时指向「"
                                    + existing
                                    + "」与「"
                                    + field.fieldKey()
                                    + "」，不能共用");
                }
            }
        }
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = cell(header, i).strip();
            if (name.isEmpty()) {
                problems.add(
                        "「"
                                + segment
                                + "」表第 "
                                + (i + 1)
                                + " 列（"
                                + ImportCell.columnLetters(i)
                                + "）表头为空");
                continue;
            }
            String target = nameToTarget.get(name);
            if (target == null) {
                problems.add(
                        "「"
                                + segment
                                + "」表第 "
                                + (i + 1)
                                + " 列（"
                                + ImportCell.columnLetters(i)
                                + "）表头「"
                                + name
                                + "」不在模板列中");
                continue;
            }
            Integer previous = columns.put(target, i);
            if (previous != null) {
                problems.add(
                        "「"
                                + segment
                                + "」表头「"
                                + name
                                + "」重复（第 "
                                + (previous + 1)
                                + "、"
                                + (i + 1)
                                + " 列）");
            }
        }
        for (ImportFieldCatalog.CatalogField field : fields) {
            if (!columns.containsKey(field.fieldKey())) {
                problems.add("「" + segment + "」表缺少「" + field.label() + "」列");
            }
        }
        for (String fixed : requiredFixed) {
            if (!columns.containsKey(fixed)) {
                problems.add("「" + segment + "」表缺少固定列「" + fixed + "」");
            }
        }
        if (!problems.isEmpty()) {
            throw new ImportLayoutException(
                    "长表「" + segment + "」表头不匹配（" + problems.size() + " 处，整体报错不做猜测）", problems);
        }
        return new HeaderMap(columns);
    }

    // ---------------------------------------------------------------- 内部

    /** 「随访年度」列 → 年度整数；解析不了返回 null（报错随 E18-T03 校验报告）。 */
    private static Integer parseYear(ImportCell yearCell) {
        String text = yearCell.normalized();
        if (text == null) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

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

    private static boolean isBlankRow(List<String> cells) {
        return cells.stream().allMatch(cell -> cell == null || cell.strip().isEmpty());
    }

    private static List<ImportFieldCatalog.CatalogField> enabled(
            List<ImportFieldCatalog.CatalogField> fields) {
        return fields.stream().filter(ImportFieldCatalog.CatalogField::enabled).toList();
    }

    private static String cell(List<String> cells, int colNo) {
        return colNo >= 0 && colNo < cells.size() && cells.get(colNo) != null
                ? cells.get(colNo)
                : "";
    }
}
