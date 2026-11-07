package com.crdp.framework.excel;

import java.io.IOException;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 一张工作表的逐行读取（E18-T02 / ADR 0007）。行内单元格一律按文本返回（sharedStrings / inlineStr / 公式结果取文本， 数值 / 布尔出原始文本或
 * TRUE / FALSE——类型与日期转换由业务侧做，tech-plan §12「单元格一律按文本读入再转换」）。
 *
 * <p>契约：{@link #header()} 先调用（读并缓存第 1 行；空表返回空列表），{@link #dataRows} 接着从第 2 行起
 * 逐行回调、<strong>只能遍历一次</strong>。回调给出工作表行号（表头 = 1、数据从 2 起；取行元素 {@code r} 属性，没有就按出现顺序推算）与该行单元格： 按
 * {@code r} 属性的列字母定位、行内缺口补 {@code ""}（补到该行最后一个出现单元格；表头宽度以内的对齐由调用方做）。 行元素存在但没有任何单元格（整行空白）回调一个空列表；XML
 * 里缺号跳过的行不合成。
 */
public interface ExcelSheet {

    /** 表头行（工作表第 1 行，缓存重复可用）；空工作表返回空列表。 */
    List<String> header() throws IOException;

    /** 表头之后的数据行逐行回调；每个句柄只能遍历一次（再遍历抛 IllegalStateException）。 */
    void dataRows(BiConsumer<Integer, List<String>> rowConsumer) throws IOException;
}
