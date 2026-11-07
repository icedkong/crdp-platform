package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * E18-T01：{@link ImportSheetData} 的整表内存实现（单元测试与小文件用；宽表大文件的流式读取属 E18-T02）。
 *
 * @param name 工作表名
 * @param rows 全部行（第 1 项 = 表头行），行内单元格按文本；行可以参差，读取时补齐
 */
public record InMemoryImportSheet(String name, List<List<String>> rows) implements ImportSheetData {

    public InMemoryImportSheet {
        rows = List.copyOf(rows);
    }

    @Override
    public List<String> header() {
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    @Override
    public void dataRows(BiConsumer<Integer, List<String>> rowConsumer) {
        if (rows.isEmpty()) {
            return;
        }
        int width = header().size();
        for (int i = 1; i < rows.size(); i++) {
            rowConsumer.accept(i + 1, padded(rows.get(i), width));
        }
    }

    /** 补齐空白到表头列数（超出的列保留）。 */
    private static List<String> padded(List<String> row, int width) {
        List<String> cells = new ArrayList<>(row);
        while (cells.size() < width) {
            cells.add("");
        }
        return cells;
    }
}
