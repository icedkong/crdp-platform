package com.crdp.followup.importer.domain;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * E18-T01：导入工作表的读取口（版面适配器只面向它，不关心 xlsx 从哪来）。
 *
 * <p>E18-T01 配 {@link InMemoryImportSheet}（小表 / 单元测试）；E18-T02 的流式解析（大文件不占满内存） 在框架 Excel
 * 网关上再提供一个逐行实现，两个版面适配器无需改动（tech-plan §12「POI 事件模型逐行读取」的接入缝）。
 *
 * <p>约定：单元格一律按文本读入、空白为 ""；{@link #header()} 先于 {@link #dataRows} 调用一次， 数据行按工作表行号（表头 = 1、数据从 2
 * 起）逐行回调，行内单元格已按表头列数补齐空白（超出的列保留，适配器只读表头范围内的列）。
 */
public interface ImportSheetData {

    /** 工作表名。 */
    String name();

    /** 表头行（工作表第 1 行）；空工作表返回空列表。 */
    List<String> header();

    /** 遍历表头之后的数据行；rowConsumer 的 (行号, 单元格)。 */
    void dataRows(BiConsumer<Integer, List<String>> rowConsumer);
}
