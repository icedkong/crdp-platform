package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * E18-T01：导入工作簿入口——识别版面并分发给对应适配器，两种版面解析到同一模型（tasks.csv E18-T01 验收）。
 *
 * <p>识别规则（不做猜测）：
 *
 * <ul>
 *   <li>有「基线」+「随访记录」两张工作表 → 长表（A-PAT-008 模板）；
 *   <li>只有其中一张 → 整体报错，指出缺哪张；
 *   <li>单张工作表 → 宽表（A-PAT-018 医院现有格式；表头结构再由 {@link WideLayoutAdapter} 校验）；
 *   <li>其余（空工作簿、三张及以上工作表）→ 整体报错。
 * </ul>
 */
public final class ImportWorkbookParser {

    private ImportWorkbookParser() {}

    /** 识别版面 + 解析（E18-T02 的导入作业从这里拿统一模型）。 */
    public static ImportParseResult parse(ImportWorkbookData workbook, ImportFieldCatalog catalog) {
        return switch (detect(workbook)) {
            case WIDE -> new WideLayoutAdapter().parse(workbook, catalog);
            case LONG -> new LongLayoutAdapter().parse(workbook, catalog);
        };
    }

    /** 版面识别（结构性判断；不匹配抛 {@link ImportLayoutException}）。 */
    public static ImportLayout detect(ImportWorkbookData workbook) {
        boolean hasBaseline = workbook.sheet(LongLayoutAdapter.BASELINE_SHEET).isPresent();
        boolean hasFollowup = workbook.sheet(LongLayoutAdapter.FOLLOWUP_SHEET).isPresent();
        if (hasBaseline && hasFollowup) {
            return ImportLayout.LONG;
        }
        List<String> problems = new ArrayList<>();
        if (hasBaseline || hasFollowup) {
            if (!hasBaseline) {
                problems.add("长表导入需要「基线」与「随访记录」两张工作表，缺少「基线」");
            }
            if (!hasFollowup) {
                problems.add("长表导入需要「基线」与「随访记录」两张工作表，缺少「随访记录」");
            }
            throw new ImportLayoutException("导入文件版面不完整", problems);
        }
        if (workbook.sheets().size() == 1) {
            return ImportLayout.WIDE;
        }
        problems.add(
                "导入文件既不是宽表（应只有一张工作表）也不是长表（应有「基线」「随访记录」两张工作表），实际 "
                        + workbook.sheets().size()
                        + " 张："
                        + sheetNames(workbook));
        throw new ImportLayoutException("无法识别导入文件的版面", problems);
    }

    private static String sheetNames(ImportWorkbookData workbook) {
        List<String> names = new ArrayList<>();
        workbook.sheets().forEach(sheet -> names.add(sheet.name()));
        return String.join("、", names);
    }
}
