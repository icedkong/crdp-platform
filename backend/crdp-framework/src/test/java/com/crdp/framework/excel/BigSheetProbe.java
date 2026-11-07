package com.crdp.framework.excel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * E18-T02「大文件不占满内存」验收探针：在受限堆（{@code -Xmx}）的子进程里流式读完整张大表。
 *
 * <p>退出码：0 成功（stdout 首行 {@code OK <行数> <校验和>}）、2 内容不符、其他 = 崩溃（如 OOM—— 整表读进内存时发生）。
 * 生成端与探针用同一套单元格内容与折叠校验和（{@code h = h*31 + cell.hashCode()} 再 {@code h = h*31 + rowNo}）。
 */
public final class BigSheetProbe {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int expectedRows = Integer.parseInt(args[1]);
        int expectedChecksum = Integer.parseInt(args[2]);
        try (ExcelWorkbook workbook = new StreamingXlsxGateway().open(Files.newInputStream(file))) {
            ExcelSheet sheet = workbook.sheet(workbook.sheetNames().get(0));
            if (!List.of("病案号", "姓名", "年度").equals(sheet.header())) {
                System.out.println("BAD_HEADER");
                System.exit(2);
            }
            long[] rows = {0};
            int[] hasher = {0};
            // 消费方按行处理（校验和代替写库），不保留任何行——流式读取的堆占用与行数无关
            sheet.dataRows(
                    (rowNo, cells) -> {
                        rows[0]++;
                        for (String cell : cells) {
                            hasher[0] = hasher[0] * 31 + cell.hashCode();
                        }
                        hasher[0] = hasher[0] * 31 + rowNo;
                    });
            if (rows[0] != expectedRows || hasher[0] != expectedChecksum) {
                System.out.println("MISMATCH rows=" + rows[0]);
                System.exit(2);
            }
            System.out.println("OK " + rows[0] + " " + hasher[0]);
            System.exit(0);
        }
    }

    private BigSheetProbe() {}
}
