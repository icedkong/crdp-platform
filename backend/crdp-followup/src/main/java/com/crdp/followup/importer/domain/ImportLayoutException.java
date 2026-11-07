package com.crdp.followup.importer.domain;

import java.util.List;

/**
 * E18-T01：版面不匹配——列数或表头对不上、工作表缺失等结构性问题，整体报错、不做猜测（A-PAT-018 流程第 2 步； tech-plan
 * §12「不匹配直接预检失败并给出差异列」）。E18-T02 的导入作业把它转为预检失败状态与差异清单，这里只是领域异常。
 */
public final class ImportLayoutException extends RuntimeException {

    private final List<String> problems;

    /**
     * @param problems 差异清单（逐列：列字母、实际表头、应为的字段）
     */
    public ImportLayoutException(String message, List<String> problems) {
        super(message);
        this.problems = List.copyOf(problems);
    }

    /** 差异清单（预检报告展示用）。 */
    public List<String> problems() {
        return problems;
    }
}
