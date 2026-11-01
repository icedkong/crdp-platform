package com.crdp.framework.fieldpolicy;

/**
 * 敏感字段掩码算法（E07-T05 / A-PAT-010、C-PAT-012）。纯函数，供 {@link SensitiveFieldSanitizer}、 {@link
 * PolicyMaskedSerializer} 与后续导出、站内信共用；业务代码不要各写一套。
 *
 * <p>口径（hospital-a-spec §3「脱敏」示例「张*」「138****5678」，platform-spec §4「脱敏规则：如手机号保留前 3 位与后 4 位」）：
 *
 * <ul>
 *   <li>姓名：首字 + {@code *} + 尾字（两字姓名「张*」，单字「*」）——与 design-system §17 的「张*明（示例）」一致；
 *   <li>手机号：前 3 位 + {@code *}（位数即剩余位数）+ 后 4 位；
 *   <li>证件号：前 6 位 + {@code *} + 后 4 位；
 *   <li>病案号 / 病历号：前 2 位 + {@code *} + 后 2 位；
 *   <li>住址：前 6 字（省市区县）+ 固定 4 个 {@code *}（不透出剩余长度）；
 *   <li>其他 / 短值：通用规则——首字 + {@code *}(位数-2) + 尾字。
 * </ul>
 *
 * <p>目录将来的新字段码没有专属规则时自动落通用规则，不会漏明文。null 与空白原样返回（没有可掩内容）。
 */
public final class SensitiveValueMasker {

    private SensitiveValueMasker() {}

    /**
     * 按目录字段码脱敏。字段码取 {@link SensitiveFieldCatalog.Entry#field()}；未知字段码走通用规则。
     *
     * @param field 目录字段码（name / idCard / phone / medicalRecordNo / address）
     * @param value 明文值；null / 空白原样返回
     */
    public static String mask(String field, String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return switch (field == null ? "" : field) {
            case "name" -> maskName(value);
            case "phone" -> value.length() >= 8 ? keepBothEnds(value, 3, 4) : generic(value);
            case "idCard" -> value.length() >= 11 ? keepBothEnds(value, 6, 4) : generic(value);
            case "medicalRecordNo" ->
                    value.length() >= 5 ? keepBothEnds(value, 2, 2) : generic(value);
            case "address" -> value.length() > 6 ? value.substring(0, 6) + "****" : generic(value);
            default -> generic(value);
        };
    }

    /** 姓名：三字及以上保留首尾（张*明），两字保留首字（张*），单字整掩（*）。 */
    private static String maskName(String value) {
        return switch (value.length()) {
            case 1 -> "*";
            case 2 -> value.charAt(0) + "*";
            default -> value.charAt(0) + "*" + value.charAt(value.length() - 1);
        };
    }

    /** 保留首尾两段，中间按剩余位数补 {@code *}（位数即真实长度，例：11 位手机号 138****5678）。 */
    private static String keepBothEnds(String value, int head, int tail) {
        int stars = value.length() - head - tail;
        return value.substring(0, head)
                + "*".repeat(stars)
                + value.substring(value.length() - tail);
    }

    /** 通用规则：首字 + {@code *}(位数-2) + 尾字；短值至少盖住一半。 */
    private static String generic(String value) {
        return switch (value.length()) {
            case 1 -> "*";
            case 2 -> value.charAt(0) + "*";
            default ->
                    value.charAt(0)
                            + "*".repeat(value.length() - 2)
                            + value.charAt(value.length() - 1);
        };
    }
}
