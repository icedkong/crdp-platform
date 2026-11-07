package com.crdp.followup.importer.domain;

import java.util.Optional;

/**
 * E18-T03：一条导入校验问题（tech-plan §12⑤「行号、列（含 Excel 列字母）、原值、原因、级别（错误 / 警告）」）。
 *
 * <p>坐标（sheetName + rowNo + colNo）把问题钉回源工作表单元格——批注版校验报告据此在原单元格上标色与加批注
 * （A-PAT-018「校验报告逐个列出问题单元格（行、列、原值、原因），可下载带标注的 Excel」）； 无法定位列的问题（如长表整表都缺某列值时的推导列）colNo / header
 * 为空，批注落在该行已知定位列上。
 *
 * <p>message 只写非敏感文本（OQ-101③ 同口径）：敏感字段（姓名 / 病案号等）的问题不回显原值。 原值不进本模型——问题接口（E18-T06）按需从暂存密文取并脱敏展示。
 *
 * @param sheetName 源工作表名
 * @param rowNo 源工作表行号（表头 = 1、数据从 2 起）
 * @param colNo 列号（0 起下标；无法定位时空）
 * @param header 该列表头（字段显示名 / 原表列名；无法定位时空）
 * @param level 级别：ERROR 阻断提交（confirm 40001），WARN 人工复核或按策略处理
 * @param code 问题类型码（见 CODE_* 常量，接口与报告按它分类）
 * @param message 原因（简体中文，面向医院用户）
 */
public record ImportIssue(
        String sheetName,
        int rowNo,
        Integer colNo,
        String header,
        Level level,
        String code,
        String message) {

    /** 级别（错误 / 警告）。 */
    public enum Level {
        ERROR("错误"),
        WARN("警告");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    // ---------------------------------------------------------------- 问题类型码
    // 格式：大写蛇形；接口与报告按 code 分类展示，新增码先在这里登记

    /** 病案号为空（无法盲索引关联 / 查重）。 */
    public static final String CODE_MRN_BLANK = "MRN_BLANK";

    /** 随访年度解析不了（应为整数年度）。 */
    public static final String CODE_YEAR_INVALID = "YEAR_INVALID";

    /** 类型 / 格式不符（数值、整数、日期、日期时间、长 × 宽、选项编码）。 */
    public static final String CODE_TYPE = "TYPE";

    /** 超出硬范围（不能导入，报错）。 */
    public static final String CODE_HARD_RANGE = "HARD_RANGE";

    /** 超出提示范围（可导入，警告供人工复核，A-FLD-003）。 */
    public static final String CODE_SOFT_RANGE = "SOFT_RANGE";

    /** 取值不在字典选项内（报错，Q37）。 */
    public static final String CODE_OPTION_MISS = "OPTION_MISS";

    /** 编码 + 文字（取编码导入、文字入备注，警告，Q37）。 */
    public static final String CODE_OPTION_CODE_TEXT = "OPTION_CODE_TEXT";

    /** 是否类单元格填了「0/1」（按空值导入，警告供人工复核，Q36）。 */
    public static final String CODE_BOOLEAN_01 = "BOOLEAN_01";

    /** 必填字段为空（历史导入只报警告，完整度按未填计算）。 */
    public static final String CODE_REQUIRED_EMPTY = "REQUIRED_EMPTY";

    /** 宽表前置列与基线列不一致（以基线列为准，警告）。 */
    public static final String CODE_FRONT_MISMATCH = "FRONT_MISMATCH";

    /** 派生字段原值与重算结果不一致（以重算结果为准，警告）。 */
    public static final String CODE_DERIVED_MISMATCH = "DERIVED_MISMATCH";

    /** 文件内病案号重复（同一病案号多行，确认时按重复策略处理）。 */
    public static final String CODE_DUP_FILE = "DUP_FILE";

    /** 病案号已存在于系统（重复，确认时选择更新 / 跳过）。 */
    public static final String CODE_DUP_EXISTING = "DUP_EXISTING";

    /** 同一病案号 + 随访年度出现两行（无法确定唯一记录，报错）。 */
    public static final String CODE_DUP_YEAR = "DUP_YEAR";

    /** 随访行的病案号在「基线」表中不存在（报错）。 */
    public static final String CODE_NO_BASELINE = "NO_BASELINE";

    /** Excel 列字母（0 起下标 → A、Z、AA……，问题清单与批注同一坐标语言）。 */
    public String columnLetters() {
        return Optional.ofNullable(colNo).map(ImportCell::columnLetters).orElse(null);
    }
}
