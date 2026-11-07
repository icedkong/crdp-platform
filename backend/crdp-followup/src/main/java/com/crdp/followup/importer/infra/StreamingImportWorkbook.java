package com.crdp.followup.importer.infra;

import com.crdp.followup.importer.domain.ImportSheetData;
import com.crdp.followup.importer.domain.ImportWorkbookData;
import com.crdp.framework.excel.ExcelWorkbook;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * E18-T02：{@link ImportWorkbookData} 的流式实现——把框架 {@link ExcelWorkbook}（ADR 0007 逐行读取）接进 E18-T01
 * 的版面适配器，兑现 OQ-100④ 的接入缝：适配器与 {@link com.crdp.followup.importer.domain.InMemoryImportSheet}
 * 两条路径同一模型。
 *
 * <p>内存口径（验收「大文件不占满内存」）：构造只取工作表名（不读行）；每张表句柄在第一次读表头时才打开条目流，
 * 数据行逐行回调、按表头列数补齐空白后交给适配器、用完即弃——任何时刻堆里至多一行。 表可任意顺序读（版面识别先于适配器取表）。
 */
public final class StreamingImportWorkbook implements ImportWorkbookData, AutoCloseable {

    private final ExcelWorkbook workbook;

    public StreamingImportWorkbook(ExcelWorkbook workbook) {
        this.workbook = workbook;
    }

    @Override
    public List<ImportSheetData> sheets() {
        List<ImportSheetData> sheets = new ArrayList<>();
        for (String name : workbook.sheetNames()) {
            sheets.add(new Sheet(name));
        }
        return sheets;
    }

    @Override
    public void close() throws IOException {
        workbook.close();
    }

    /** 一张工作表的句柄（懒打开：首次读表头才开条目流；表头缓存后 dataRows 从第 2 行继续）。 */
    private final class Sheet implements ImportSheetData {

        private final String name;
        private com.crdp.framework.excel.ExcelSheet sheet;

        Sheet(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<String> header() {
            try {
                return open().header();
            } catch (IOException failure) {
                throw new IllegalStateException("读取导入文件的工作表「" + name + "」表头失败", failure);
            }
        }

        @Override
        public void dataRows(BiConsumer<Integer, List<String>> rowConsumer) {
            try {
                com.crdp.framework.excel.ExcelSheet open = open();
                int width = open.header().size();
                open.dataRows((rowNo, cells) -> rowConsumer.accept(rowNo, padded(cells, width)));
            } catch (IOException failure) {
                throw new IllegalStateException("读取导入文件的工作表「" + name + "」失败", failure);
            }
        }

        private com.crdp.framework.excel.ExcelSheet open() {
            if (sheet == null) {
                sheet = workbook.sheet(name);
                if (sheet == null) {
                    throw new IllegalStateException("导入文件的工作表「" + name + "」不存在");
                }
            }
            return sheet;
        }

        private static List<String> padded(List<String> cells, int width) {
            if (cells.size() >= width) {
                return cells;
            }
            List<String> padded = new ArrayList<>(width);
            padded.addAll(cells);
            for (int i = cells.size(); i < width; i++) {
                padded.add("");
            }
            return padded;
        }
    }
}
