package com.crdp.form.field.domain;

/** 字段 / 表单 / 选项的启停状态（A-FLD-001「状态」；停用不删除）。 */
public final class FieldDictStatus {

    public static final String ENABLED = "ENABLED";
    public static final String DISABLED = "DISABLED";

    private FieldDictStatus() {}

    /** 只认 ENABLED / DISABLED；其余按脏数据对待。 */
    public static boolean isValid(String status) {
        return ENABLED.equals(status) || DISABLED.equals(status);
    }
}
