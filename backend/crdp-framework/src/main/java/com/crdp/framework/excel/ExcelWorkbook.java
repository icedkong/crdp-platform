package com.crdp.framework.excel;

import java.io.IOException;
import java.util.List;

/**
 * 流式打开的 xlsx 工作簿（E18-T02 / ADR 0007）：只解析工作表目录（{@code xl/workbook.xml} + 关系）， 行数据按表、按需逐行读取——
 * 打开工作簿不读任何行，堆占用与文件大小无关。
 *
 * <p>{@link #sheet(String)} 每次调用都重新打开该表的条目流：表可按任意顺序读、可重复读； 同一张表的同时只能有一段在迭代。
 */
public interface ExcelWorkbook extends AutoCloseable {

    /** 工作表名（文件内顺序）。 */
    List<String> sheetNames();

    /**
     * 取一张工作表的逐行读取句柄；表名不存在返回 {@code null}。
     *
     * <p>返回的 {@link ExcelSheet} 自持有流，用完不必单独关（工作簿 {@link #close()} 统一清理落盘副本）；
     * 已在迭代中的表再取会得到新句柄、从表头重新开始。
     */
    ExcelSheet sheet(String name);

    @Override
    void close() throws IOException;
}
