package com.crdp.followup.importer.app;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportRowPayloads.BaselineRow;
import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupGroup;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupRow;
import com.crdp.followup.importer.app.ImportRowPayloads.WideRow;
import com.crdp.followup.importer.app.ImportValidationService.StagedPayload;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportIssue;
import com.crdp.followup.importer.domain.ImportIssue.Level;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.Annotation;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.CellRef;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.Mark;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.SheetSpec;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * E18-T03：批注版校验报告组装——把暂存行（imp_row 密文还原）重建成源工作表、把校验问题（imp_issue 同口径） 钉回原单元格标色加批注，再冠一张「校验报告」问题清单表。
 *
 * <p>重建口径：单元格原文与行列坐标都在暂存载荷里（E18-T02 的暂存形态），按坐标摆放即得与原文件同构的网格； 表头宽表按确定列序重建（前置 9 + 锚点字段序，与 {@link
 * WideLayoutAdapter} 对位口径一致），长表按各行坐标合并出列位、 全表都没出现过的启用字段追加在尾部（列序随文件，见 OQ-102）。
 *
 * <p>问题清单表的「原值」列对敏感字段显示「（已脱敏）」（import-export.md §1「原值（敏感列脱敏显示）」）； 网格中的原值是文件自身内容、按原样保留。
 */
final class ImportReportComposer {

    /** 问题清单表的表头（tech-plan §12⑤：行号、列、原值、原因、级别）。 */
    private static final List<String> ISSUE_HEADER =
            List.of("级别", "工作表", "行", "列", "表头", "原值", "原因");

    /** 敏感前置列（姓名 / 病案号按患者敏感字段口径脱敏显示原值）。 */
    private static final List<String> SENSITIVE_FRONT_COLUMNS = List.of("病案号", "姓名");

    private ImportReportComposer() {}

    /** 组装报告 xlsx 字节（「校验报告」清单表在前，其后按暂存顺序重建源工作表）。 */
    static byte[] compose(
            JobRow job,
            ImportFieldRules rules,
            List<StagedPayload> payloads,
            List<ImportIssue> issues) {
        GridBuilder builder = new GridBuilder(rules);
        for (StagedPayload staged : payloads) {
            switch (staged.row().rowType()) {
                case "PATIENT" -> builder.wide(staged, (WideRow) staged.payload());
                case "BASELINE" -> builder.baseline(staged, (BaselineRow) staged.payload());
                case "FOLLOWUP" -> builder.followup(staged, (FollowupRow) staged.payload());
                default -> throw new IllegalStateException("未知暂存行类型：" + staged.row().rowType());
            }
        }
        List<SheetSpec> sheets = new ArrayList<>();
        sheets.add(issueSheet(rules, issues, builder.sheetCells, builder.columnFieldKeys));
        for (String sheetName : builder.sheetOrder) {
            Map<Integer, String> layout =
                    "WIDE".equals(job.layout())
                            ? wideLayout(rules, builder.wideGroups.getOrDefault(sheetName, 1))
                            : longLayout(rules, sheetName, payloads, builder.columnFieldKeys);
            backfillLegacy(sheetName, layout, builder);
            sheets.add(
                    new SheetSpec(
                            sheetName,
                            headerOf(layout),
                            new TreeMap<>(builder.sheetCells.getOrDefault(sheetName, Map.of())),
                            annotations(issues, sheetName)));
        }
        return AnnotatedReportXlsx.write(sheets);
    }

    // ---------------------------------------------------------------- 网格重建

    /** 逐行摆放单元格（sheetName → rowNo → colNo → 原文）与列位索引。 */
    private static final class GridBuilder {

        private final ImportFieldRules rules;
        private final Set<String> sheetOrder = new java.util.LinkedHashSet<>();
        private final Map<String, Map<Integer, TreeMap<Integer, String>>> sheetCells =
                new LinkedHashMap<>();

        /** sheetName → colNo → fieldKey / 前置列名 / 固定列名（脱敏与表头用）。 */
        private final Map<String, Map<Integer, String>> columnFieldKeys = new LinkedHashMap<>();

        private final Map<String, Integer> wideGroups = new LinkedHashMap<>();

        GridBuilder(ImportFieldRules rules) {
            this.rules = rules;
        }

        void wide(StagedPayload staged, WideRow payload) {
            String sheetName = staged.row().sheetName();
            TreeMap<Integer, String> row = rowOf(sheetName, staged.row().rowNo());
            payload.front()
                    .forEach(
                            (name, cell) -> {
                                row.put(cell.c(), cell.v());
                                column(sheetName, cell.c(), name);
                            });
            putValues(sheetName, row, payload.baseline());
            int maxGroup = 0;
            for (FollowupGroup group : payload.followups()) {
                maxGroup = Math.max(maxGroup, group.group() == null ? 0 : group.group());
                putValues(sheetName, row, group.values());
            }
            if (payload.mrnCell() != null && payload.mrnCell().normalized() != null) {
                row.putIfAbsent(payload.mrnCell().c(), payload.mrnCell().v());
                column(sheetName, payload.mrnCell().c(), "__mrn__");
            }
            wideGroups.merge(sheetName, Math.max(maxGroup, 1), Math::max);
        }

        void baseline(StagedPayload staged, BaselineRow payload) {
            String sheetName = staged.row().sheetName();
            TreeMap<Integer, String> row = rowOf(sheetName, staged.row().rowNo());
            putValues(sheetName, row, payload.baseline());
            if (payload.mrnCell() != null && payload.mrnCell().normalized() != null) {
                row.putIfAbsent(payload.mrnCell().c(), payload.mrnCell().v());
                column(sheetName, payload.mrnCell().c(), "__mrn__");
            }
            if (payload.legacyNo() != null) {
                // 原系统编号列位随文件（可能全表都没出现过），先按行记值，布局定下后统一回填
                legacyValues
                        .computeIfAbsent(sheetName, key -> new TreeMap<>())
                        .put(staged.row().rowNo(), payload.legacyNo());
            }
        }

        void followup(StagedPayload staged, FollowupRow payload) {
            String sheetName = staged.row().sheetName();
            TreeMap<Integer, String> row = rowOf(sheetName, staged.row().rowNo());
            putValues(sheetName, row, payload.values());
            if (payload.mrnCell() != null && payload.mrnCell().normalized() != null) {
                row.putIfAbsent(payload.mrnCell().c(), payload.mrnCell().v());
                column(sheetName, payload.mrnCell().c(), "__mrn__");
            }
            if (payload.yearCell() != null && payload.yearCell().normalized() != null) {
                row.putIfAbsent(payload.yearCell().c(), payload.yearCell().v());
                column(sheetName, payload.yearCell().c(), "__year__");
            }
        }

        private final Map<String, TreeMap<Integer, String>> legacyValues = new LinkedHashMap<>();

        private void putValues(
                String sheetName, TreeMap<Integer, String> row, Map<String, CellValue> values) {
            values.forEach(
                    (fieldKey, cell) -> {
                        row.put(cell.c(), cell.v());
                        column(sheetName, cell.c(), fieldKey);
                    });
        }

        private TreeMap<Integer, String> rowOf(String sheetName, int rowNo) {
            sheetOrder.add(sheetName);
            return sheetCells
                    .computeIfAbsent(sheetName, key -> new TreeMap<>())
                    .computeIfAbsent(rowNo, key -> new TreeMap<>());
        }

        private void column(String sheetName, int colNo, String key) {
            columnFieldKeys
                    .computeIfAbsent(sheetName, name -> new TreeMap<>())
                    .putIfAbsent(colNo, key);
        }
    }

    // ---------------------------------------------------------------- 布局（表头）

    /** 宽表布局：前置 9 + 锚点基线 + groups 组锚点随访（列序确定，与适配器对位口径一致）。 */
    private static Map<Integer, String> wideLayout(ImportFieldRules rules, int groups) {
        Map<Integer, String> layout = new TreeMap<>();
        int col = 0;
        for (String front : WideLayoutAdapter.FRONT_COLUMNS) {
            layout.put(col++, front);
        }
        for (ImportFieldRule rule : rules.baselineRules()) {
            if (rule.originalHeader() != null) {
                layout.put(col++, rule.headerName());
            }
        }
        List<ImportFieldRule> anchoredFollowup =
                rules.followupRules().stream()
                        .filter(rule -> rule.originalHeader() != null)
                        .toList();
        for (int group = 0; group < Math.max(groups, 1); group++) {
            for (ImportFieldRule rule : anchoredFollowup) {
                layout.put(col++, rule.headerName());
            }
        }
        return layout;
    }

    /** 长表布局：各行坐标合并出的列位，缺的启用字段（含「原系统编号」固定列）按字典序追加在尾部。 */
    private static Map<Integer, String> longLayout(
            ImportFieldRules rules,
            String sheetName,
            List<StagedPayload> payloads,
            Map<String, Map<Integer, String>> columnFieldKeys) {
        boolean followupSheet =
                payloads.stream()
                        .anyMatch(
                                staged ->
                                        sheetName.equals(staged.row().sheetName())
                                                && "FOLLOWUP".equals(staged.row().rowType()));
        Map<Integer, String> layout = new TreeMap<>();
        Map<Integer, String> known = columnFieldKeys.getOrDefault(sheetName, Map.of());
        Map<String, ImportFieldRule> ruleIndex =
                followupSheet ? rules.followupByKey() : rules.baselineByKey();
        known.forEach(
                (colNo, key) -> {
                    if (key.equals("__mrn__")) {
                        layout.put(colNo, "病案号");
                    } else if (key.equals("__year__")) {
                        layout.put(colNo, "随访年度");
                    } else {
                        ImportFieldRule rule = ruleIndex.get(key);
                        layout.put(colNo, rule == null ? key : rule.label());
                    }
                });
        int next = layout.keySet().stream().max(Integer::compareTo).orElse(-1) + 1;
        if (!followupSheet) {
            layout.put(next++, "原系统编号");
        }
        for (ImportFieldRule rule : followupSheet ? rules.followupRules() : rules.baselineRules()) {
            if (rule.enabled() && !layout.containsValue(rule.label())) {
                layout.put(next++, rule.label());
            }
        }
        return layout;
    }

    private static List<String> headerOf(Map<Integer, String> layout) {
        if (layout.isEmpty()) {
            return List.of("");
        }
        int last = layout.keySet().stream().max(Integer::compareTo).orElse(0);
        List<String> header = new ArrayList<>(last + 1);
        for (int col = 0; col <= last; col++) {
            header.add(layout.getOrDefault(col, ""));
        }
        return header;
    }

    // ---------------------------------------------------------------- 标注与清单表

    /** 一张源工作表的标注：问题 → 单元格标色 + 合并批注（多条问题一行一条）。 */
    private static Map<CellRef, Annotation> annotations(
            List<ImportIssue> issues, String sheetName) {
        Map<CellRef, Annotation> merged = new LinkedHashMap<>();
        for (ImportIssue issue : issues) {
            if (!sheetName.equals(issue.sheetName())) {
                continue;
            }
            CellRef ref = new CellRef(issue.rowNo(), issue.colNo() == null ? 0 : issue.colNo());
            Annotation existing = merged.get(ref);
            Mark mark =
                    existing != null && existing.mark() == Mark.ERROR
                            ? Mark.ERROR
                            : issue.level() == Level.ERROR ? Mark.ERROR : Mark.WARN;
            List<String> lines =
                    existing == null ? new ArrayList<>() : new ArrayList<>(existing.commentLines());
            lines.add("【" + issue.level().label() + "】" + issue.message());
            merged.put(ref, new Annotation(mark, lines));
        }
        return merged;
    }

    /** 「校验报告」问题清单表：级别列标色；原值列敏感脱敏（网格内保留原值——那是文件自身内容）。 */
    private static SheetSpec issueSheet(
            ImportFieldRules rules,
            List<ImportIssue> issues,
            Map<String, Map<Integer, TreeMap<Integer, String>>> sheetCells,
            Map<String, Map<Integer, String>> columnFieldKeys) {
        Map<Integer, Map<Integer, String>> cells = new TreeMap<>();
        Map<CellRef, Annotation> annotations = new LinkedHashMap<>();
        int rowNo = 2; // 第 1 行表头
        for (ImportIssue issue : issues) {
            Map<Integer, String> row = new TreeMap<>();
            row.put(0, issue.level().label());
            row.put(1, issue.sheetName());
            row.put(2, String.valueOf(issue.rowNo()));
            row.put(3, issue.columnLetters() == null ? "" : issue.columnLetters());
            row.put(4, issue.header() == null ? "" : issue.header());
            row.put(5, rawValue(rules, issue, sheetCells, columnFieldKeys));
            row.put(6, issue.message());
            cells.put(rowNo, row);
            annotations.put(
                    new CellRef(rowNo, 0),
                    new Annotation(
                            issue.level() == Level.ERROR ? Mark.ERROR : Mark.WARN, List.of()));
            rowNo++;
        }
        return new SheetSpec("校验报告", ISSUE_HEADER, cells, annotations);
    }

    /** 问题单元格的原值（清单表「原值」列）：敏感字段 / 敏感前置列脱敏；无坐标或空单元格为空串。 */
    private static String rawValue(
            ImportFieldRules rules,
            ImportIssue issue,
            Map<String, Map<Integer, TreeMap<Integer, String>>> sheetCells,
            Map<String, Map<Integer, String>> columnFieldKeys) {
        if (issue.colNo() == null) {
            return "";
        }
        TreeMap<Integer, String> row =
                sheetCells.getOrDefault(issue.sheetName(), Map.of()).get(issue.rowNo());
        String raw = row == null ? null : row.get(issue.colNo());
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String columnKey =
                columnFieldKeys.getOrDefault(issue.sheetName(), Map.of()).get(issue.colNo());
        if (columnKey == null) {
            return raw;
        }
        if (SENSITIVE_FRONT_COLUMNS.contains(columnKey) || "__mrn__".equals(columnKey)) {
            return "（已脱敏）"; // 病案号 / 姓名（患者敏感字段口径）
        }
        if (columnKey.startsWith("__")) {
            return raw; // __year__ 随访年度等固定身份列不敏感
        }
        ImportFieldRule baseline = rules.baselineByKey().get(columnKey);
        ImportFieldRule followup = rules.followupByKey().get(columnKey);
        boolean sensitive =
                baseline != null && baseline.sensitive()
                        || followup != null && followup.sensitive();
        return sensitive ? "（已脱敏）" : raw;
    }

    /** 长表「原系统编号」列值回填（列位在布局定下后才能确定）。 */
    private static void backfillLegacy(
            String sheetName, Map<Integer, String> layout, GridBuilder builder) {
        TreeMap<Integer, String> legacy = builder.legacyValues.get(sheetName);
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        Integer legacyCol = null;
        for (Map.Entry<Integer, String> entry : layout.entrySet()) {
            if ("原系统编号".equals(entry.getValue())) {
                legacyCol = entry.getKey();
                break;
            }
        }
        if (legacyCol == null) {
            return;
        }
        int target = legacyCol;
        Map<Integer, TreeMap<Integer, String>> cells =
                builder.sheetCells.getOrDefault(sheetName, Map.of());
        legacy.forEach(
                (rowNo, value) ->
                        cells.computeIfAbsent(rowNo, key -> new TreeMap<>())
                                .putIfAbsent(target, value));
    }
}
