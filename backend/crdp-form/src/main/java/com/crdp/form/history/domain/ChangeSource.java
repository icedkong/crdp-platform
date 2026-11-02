package com.crdp.form.history.domain;

/**
 * 字段变更来源（E11-T06 / A-PAT-005、C-PAT-011；tech-plan §6 fe_field_history「来源」枚举定稿）。
 *
 * <p>五个来源与 tech-plan §6 一一对应：用户手工修改、派生字段系统重算（A 规格 §4「来源标为系统重算」）、 导入更新（E18「更新同样留痕」）、报告单映射写入
 * （E16-T07「映射写入留痕」）、平台租户视角支持（tech-plan §14「平台支持人员 + 会话号」，会话号关联随 E20 落地）。
 */
public enum ChangeSource {
    USER("用户"),
    SYSTEM_RECALC("系统重算"),
    IMPORT("导入"),
    REPORT("报告单"),
    PLATFORM_SUPPORT("平台支持");

    private final String label;

    ChangeSource(String label) {
        this.label = label;
    }

    /** 中文标签（变更记录页展示与 HistoryView.sourceLabel 同源）。 */
    public String label() {
        return label;
    }
}
