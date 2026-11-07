package com.crdp.followup.importer.domain;

/**
 * E18-T01 / A-PAT-008、A-PAT-018：患者历史数据导入的两种版面。
 *
 * <ul>
 *   <li>{@link #WIDE} 宽表：医院现有 Excel，一张工作表、一个患者一行，9 个前置列 + 基线列 + N 组 × 随访列， 随访年度按组的位置确定（第 N 组即第 N
 *       年）；
 *   <li>{@link #LONG} 长表：新模板两张工作表——「基线」一个患者一行、「随访记录」一次随访一行， 用「病案号 + 随访年度」定位是哪位患者的第几年。
 * </ul>
 *
 * <p>tech-plan §12：两种格式共用同一个导入引擎，只是版面适配器不同（{@link ImportLayoutAdapter}）。
 */
public enum ImportLayout {
    WIDE("宽表"),
    LONG("长表");

    private final String label;

    ImportLayout(String label) {
        this.label = label;
    }

    /** 中文说明（错误信息用）。 */
    public String label() {
        return label;
    }
}
