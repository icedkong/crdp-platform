package com.crdp.framework.excel;

import java.io.IOException;
import java.io.InputStream;

/**
 * 宽表导入导出的 Excel 网关（E18-T02 起为真实流式实现，签名变更立案 ADR 0007；M0 的 {@code assertSupported()} 占位随之删除）。
 *
 * <p>{@link #open} 只解析工作表目录，行数据经 {@link ExcelSheet} 逐行回调——大文件不占满内存（tech-plan §12「逐行读取」； A-PAT-018
 * 医院宽表 5000 患者 × 761 列）。小表的一次性读写仍用 {@link SimpleXlsx}（OQ-52②）。
 */
public interface ExcelGateway {

    /**
     * 流式打开一个 xlsx。
     *
     * <p>实现会把输入落成临时文件再按 zip 条目随机访问（堆里不保留整份文件；{@code sharedStrings} 一次性载入——上限是文件内的去重字符串数，与行数无关）；
     * 用完必须 {@link ExcelWorkbook#close()}（同时清理临时文件）。
     *
     * @param xlsx xlsx 字节流（调用方负责来源，如对象存储读回）
     * @throws IllegalStateException 不是有效的 xlsx 文件（缺工作簿目录 / 工作表条目等）抛出，消息面向用户
     */
    ExcelWorkbook open(InputStream xlsx) throws IOException;
}
