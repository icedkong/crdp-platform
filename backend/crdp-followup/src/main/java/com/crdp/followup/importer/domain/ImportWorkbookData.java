package com.crdp.followup.importer.domain;

import java.util.List;
import java.util.Optional;

/** E18-T01：导入工作簿（若干 {@link ImportSheetData}），版面识别与适配器按工作表名与结构取用。 */
public interface ImportWorkbookData {

    /** 全部工作表（保持文件内顺序）。 */
    List<ImportSheetData> sheets();

    /** 按名字找一张工作表。 */
    default Optional<ImportSheetData> sheet(String name) {
        return sheets().stream().filter(sheet -> sheet.name().equals(name)).findFirst();
    }
}
