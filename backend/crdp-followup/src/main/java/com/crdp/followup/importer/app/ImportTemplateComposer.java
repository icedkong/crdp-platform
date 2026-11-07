package com.crdp.followup.importer.app;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.LongLayoutAdapter;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx;
import com.crdp.followup.importer.infra.AnnotatedReportXlsx.SheetSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E18-T06 / A-PAT-008：导入模板生成（import-export.md §1「下载模板（模板内只有表头与示例行，示例行全部是虚构数据）」）。
 *
 * <p>模板列完全来自字段字典（{@link ImportFieldRules}），不在代码里硬编码业务字段（AGENTS.md 规则 8）：
 *
 * <ul>
 *   <li>STANDARD（长表，A-PAT-008 新模板）：两张工作表「基线」「随访记录」——基线 = 启用基线字段显示名 + 固定列「原系统编号」； 随访记录 =
 *       固定身份列「病案号」「随访年度」+ 启用随访字段显示名（列集与 {@link LongLayoutAdapter} 解析口径一致）；
 *   <li>WIDE（宽表，A-PAT-018 医院现有格式）：单张工作表 = 前置 9 列 + 带原表列名锚点的基线列 + 1 组随访列（第 N 组即第 N 年，组数不限， 模板只给 1
 *       组示意）；列序与 {@link WideLayoutAdapter} / 批注报告重建（E18-T03）同一确定列序。
 * </ul>
 *
 * <p>示例行只有一行且全部是虚构数据（规则 5：带「示例」标记，不含任何真实患者信息）；真实导入前删除示例行。 生成走 {@link AnnotatedReportXlsx}（ADR 0007
 * 无 POI 直写；无标注时只写表头与单元格）。
 */
final class ImportTemplateComposer {

    /** 宽表模板的工作表名（单张即宽表版面，ImportWorkbookParser.detect）。 */
    static final String WIDE_SHEET_NAME = "患者数据";

    /** 长表基线 / 随访示例行共用的虚构病案号（带「示例」标记，规则 5）。 */
    private static final String EXAMPLE_MRN = "DEMOA0001（示例）";

    private ImportTemplateComposer() {}

    /** 长表模板：基线 + 随访记录两张工作表，各一行虚构示例。 */
    static byte[] standard(ImportFieldRules rules) {
        List<ImportFieldRule> baseline = enabled(rules.baselineRules());
        List<ImportFieldRule> followup = enabled(rules.followupRules());
        List<String> baselineHeader = new ArrayList<>();
        Map<Integer, String> baselineExample = new LinkedHashMap<>();
        for (int i = 0; i < baseline.size(); i++) {
            String label = baseline.get(i).label();
            baselineHeader.add(label);
            if ("病案号".equals(label)) {
                baselineExample.put(i, EXAMPLE_MRN);
            }
        }
        baselineHeader.add(LongLayoutAdapter.LEGACY_NO_COLUMN);
        baselineExample.put(baseline.size(), "DEMO-A-0001（示例）");
        Map<Integer, Map<Integer, String>> baselineCells = new LinkedHashMap<>();
        baselineCells.put(2, baselineExample);
        List<String> followupHeader = new ArrayList<>();
        followupHeader.add(LongLayoutAdapter.MRN_COLUMN);
        followupHeader.add(LongLayoutAdapter.YEAR_COLUMN);
        followup.forEach(rule -> followupHeader.add(rule.label()));
        Map<Integer, String> followupExample = new LinkedHashMap<>();
        followupExample.put(0, EXAMPLE_MRN);
        followupExample.put(1, "1");
        Map<Integer, Map<Integer, String>> followupCells = new LinkedHashMap<>();
        followupCells.put(2, followupExample);
        return AnnotatedReportXlsx.write(
                List.of(
                        new SheetSpec(
                                LongLayoutAdapter.BASELINE_SHEET,
                                baselineHeader,
                                baselineCells,
                                Map.of()),
                        new SheetSpec(
                                LongLayoutAdapter.FOLLOWUP_SHEET,
                                followupHeader,
                                followupCells,
                                Map.of())));
    }

    /** 宽表模板：单张工作表 = 前置 9 + 锚点基线列 + 1 组锚点随访列，示例行只填前置列。 */
    static byte[] wide(ImportFieldRules rules) {
        List<String> header = new ArrayList<>(WideLayoutAdapter.FRONT_COLUMNS);
        anchored(rules.baselineRules()).forEach(rule -> header.add(rule.headerName()));
        anchored(rules.followupRules()).forEach(rule -> header.add(rule.headerName()));
        Map<Integer, String> example = new LinkedHashMap<>();
        example.put(0, "1");
        example.put(1, "DEMO-A-0001（示例）");
        example.put(2, EXAMPLE_MRN);
        example.put(3, "张三（示例）");
        example.put(4, "男");
        example.put(5, "67");
        example.put(6, "20210301");
        example.put(7, "冠心病（示例）");
        Map<Integer, Map<Integer, String>> cells = new LinkedHashMap<>();
        cells.put(2, example);
        return AnnotatedReportXlsx.write(
                List.of(new SheetSpec(WIDE_SHEET_NAME, header, cells, Map.of())));
    }

    private static List<ImportFieldRule> enabled(List<ImportFieldRule> rules) {
        return rules.stream().filter(ImportFieldRule::enabled).toList();
    }

    /** 宽表列 = 带原表列名锚点的字段（OQ-100①；停用字段 #217 也在内，历史数据按导入时字典口径）。 */
    private static List<ImportFieldRule> anchored(List<ImportFieldRule> rules) {
        return rules.stream().filter(rule -> rule.originalHeader() != null).toList();
    }
}
