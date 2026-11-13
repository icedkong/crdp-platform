package com.crdp.followup.plan.domain;

import java.util.Map;

/**
 * ERI 判定的共享谓词（E21-T19 / C-DASH-005「最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命低于阈值」； 切换侧同判定见
 * PlanSwitchService SW-7 / SW-8，键契约 OQ-130①）。
 *
 * <p>数据源：随访记录表单值（fu_record.data，键 = C_PROGRAM_FU 稳定键）——ERI 状态（字典 c_eri_status：0 正常 / 1 接近ERI / 2
 * 已达ERI(EOI)）与预计电池寿命（年，Q13）。A / B 表单无这些键，判定天然不命中。 值编码容错与 {@code PlanSwitchService}
 * 原实现一致：状态按字符串编码比较（数字 / 字符串皆可），电池寿命接受数字或数字字符串。
 */
public final class EriEval {

    /** C_PROGRAM_FU 稳定键契约（OQ-130①，附录 A 随访组 #n → f3nn）：ERI 状态（单选 0/1/2）。 */
    public static final String KEY_ERI_STATUS = "f323";

    /** C_PROGRAM_FU 稳定键：预计电池寿命（年，≤ 阈值视同接近 ERI，切换表「接近 ERI」行、Q13）。 */
    public static final String KEY_BATTERY_LIFE = "f324";

    /** ERI 状态选项编码（字典 c_eri_status，V82 / OQ-130①）：0 正常。 */
    public static final String STATUS_NORMAL = "0";

    /** ERI 状态选项编码：1 接近ERI。 */
    public static final String STATUS_NEAR = "1";

    /** ERI 状态选项编码：2 已达ERI(EOI)。 */
    public static final String STATUS_EOI = "2";

    /** 预计电池寿命的接近 ERI 阈值（年，切换表「预计电池寿命 ≤ 1 年」；Q13「≤ 1 视同接近 ERI」）。 */
    public static final double BATTERY_LIFE_THRESHOLD = 1d;

    private EriEval() {}

    /** 命中接近 ERI：ERI 状态为接近 / 已达，或预计电池寿命 ≤ 阈值（两值都缺失不命中）。 */
    public static boolean marked(Map<String, Object> data) {
        String status = statusCode(data);
        if (STATUS_NEAR.equals(status) || STATUS_EOI.equals(status)) {
            return true;
        }
        Double batteryLife = batteryLife(data);
        return batteryLife != null && batteryLife <= BATTERY_LIFE_THRESHOLD;
    }

    /** 解除（SW-8）：ERI 状态更正为正常且预计电池寿命不再偏低（缺失视同不偏低，OQ-137）。 */
    public static boolean cleared(Map<String, Object> data) {
        if (!STATUS_NORMAL.equals(statusCode(data))) {
            return false;
        }
        Double batteryLife = batteryLife(data);
        return batteryLife == null || batteryLife > BATTERY_LIFE_THRESHOLD;
    }

    /** ERI 状态编码（值缺失 / 空白为 null）。 */
    public static String statusCode(Map<String, Object> data) {
        Object value = data == null ? null : data.get(KEY_ERI_STATUS);
        return value == null ? null : String.valueOf(value).trim();
    }

    /** 预计电池寿命（数字或数字字符串；其他形态与缺失为 null）。 */
    public static Double batteryLife(Map<String, Object> data) {
        Object value = data == null ? null : data.get(KEY_BATTERY_LIFE);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        return null;
    }
}
