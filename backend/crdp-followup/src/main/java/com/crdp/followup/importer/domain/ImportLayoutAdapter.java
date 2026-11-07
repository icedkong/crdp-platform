package com.crdp.followup.importer.domain;

/**
 * E18-T01：版面适配器——把一种版面的工作簿解析成统一模型（{@link ImportParseResult}）。 tech-plan
 * §12「两种格式共用同一个导入引擎，只是版面适配器不同」：E18-T02 起的暂存、校验、提交都只吃统一模型。
 */
public interface ImportLayoutAdapter {

    /** 本适配器处理的版面。 */
    ImportLayout layout();

    /**
     * 解析工作簿。结构性问题（列数 / 表头 / 工作表不符）抛 {@link ImportLayoutException} 整体报错；
     * 数据级问题（年度解析不了、随访行找不到患者）留在模型里，由 E18-T03 校验报告逐格列出。
     */
    ImportParseResult parse(ImportWorkbookData workbook, ImportFieldCatalog catalog);
}
