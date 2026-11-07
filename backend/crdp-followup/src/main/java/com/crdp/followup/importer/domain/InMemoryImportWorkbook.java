package com.crdp.followup.importer.domain;

import java.util.List;

/** E18-T01：{@link ImportWorkbookData} 的内存实现（{@link InMemoryImportSheet} 列表）。 */
public record InMemoryImportWorkbook(List<ImportSheetData> sheets) implements ImportWorkbookData {

    public InMemoryImportWorkbook {
        sheets = List.copyOf(sheets);
    }
}
