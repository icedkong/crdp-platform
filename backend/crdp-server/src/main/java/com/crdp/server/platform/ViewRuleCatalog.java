package com.crdp.server.platform;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 视角规则的可选字段资源与枚举口径（E20-T02 / PLT-VIEW-007、008）。
 *
 * <p>规则字段「资源对象与字段」取自第 4 章字段资源（platform-spec §8「关键字段 · 视角规则」）。M1 与 {@link
 * com.crdp.framework.fieldpolicy.SensitiveFieldCatalog} 同口径先以代码目录交付（配置页随 PLT-PERM-009
 * 的平台部分落地时才迁库），视角规则的 FIELD 层只能引用本目录条目；目录外的资源对象 由模块扩展时在此登记（新增 = 配置变更，不是硬编码绕过）。
 *
 * <p>脱敏规则沿用第 4 章 {@code SensitiveValueMasker} 的字段码（name / phone / idCard / medicalRecordNo /
 * address），未知码落通用规则；{@code generic} 显式表示通用规则。
 *
 * <p>RCT 设盲字段（分组、随机表、揭盲信息）属后续 rct 模块（platform-spec §8 默认规则表）：目录先行 登记，规则行模块留空（空 =
 * 全部模块）即可在模块上线后自动被解析命中，不需要补迁移。
 */
public final class ViewRuleCatalog {

    // ---------------------------------------------------------------- 枚举（含文案）

    /** 规则层级（PLT-VIEW-009 匹配优先级：字段 &gt; 模块 &gt; 租户 &gt; 全局默认）。 */
    public static final String LAYER_GLOBAL = "GLOBAL";

    public static final String LAYER_TENANT = "TENANT";

    public static final String LAYER_MODULE = "MODULE";

    public static final String LAYER_FIELD = "FIELD";

    public static final Set<String> LAYERS =
            Set.of(LAYER_GLOBAL, LAYER_TENANT, LAYER_MODULE, LAYER_FIELD);

    /** 可见性（platform-spec §8「关键字段 · 视角规则」）。 */
    public static final String VISIBILITY_PLAIN = "PLAIN";

    public static final String VISIBILITY_MASKED = "MASKED";

    public static final String VISIBILITY_BLINDED = "BLINDED";

    public static final String VISIBILITY_HIDDEN = "HIDDEN";

    public static final Set<String> VISIBILITIES =
            Set.of(VISIBILITY_PLAIN, VISIBILITY_MASKED, VISIBILITY_BLINDED, VISIBILITY_HIDDEN);

    /** 操作。 */
    public static final String OPERATION_READONLY = "READONLY";

    public static final String OPERATION_EDITABLE = "EDITABLE";

    public static final Set<String> OPERATIONS = Set.of(OPERATION_READONLY, OPERATION_EDITABLE);

    /** 状态：待复核（PENDING_REVIEW）与复核流随 E20-T05（PLT-VIEW-014）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_DISABLED = "DISABLED";

    public static final Set<String> STATUSES = Set.of(STATUS_ACTIVE, STATUS_DISABLED);

    /** 脱敏规则码（沿用第 4 章 SensitiveValueMasker 的字段码 + generic）。 */
    public static final Set<String> MASK_RULES =
            Set.of("name", "phone", "idCard", "medicalRecordNo", "address", "generic");

    // ---------------------------------------------------------------- 字段资源目录

    /**
     * 目录条目。{@code field} 为空表示整个资源对象（如「报告单与电子化资料原件」按资源整体隐藏）； {@code moduleCode} 为空表示不限定模块（RCT 字段属后续
     * rct 模块，规则解析按「空 = 全部模块」）。
     */
    public record Entry(String resource, String field, String label, String moduleCode) {

        /** 目录定位键（resource + field；field 空即资源整体）。 */
        public String key() {
            return resource + (field == null ? "" : "." + field);
        }
    }

    /** 资源对象文案（配置页与效果预览展示）。 */
    public static final String resourceLabel(String resource) {
        return switch (resource == null ? "" : resource) {
            case "patient" -> "患者档案";
            case "user" -> "用户账号";
            case "report" -> "报告单与电子化资料";
            case "contact" -> "联系记录";
            case "rct" -> "RCT 研究（后续模块）";
            default -> resource;
        };
    }

    /** 视角字段目录（顺序即配置页展示顺序）。 */
    public static final List<Entry> ENTRIES =
            List.of(
                    new Entry("patient", "name", "患者姓名", "followup"),
                    new Entry("patient", "idCard", "患者证件号", "followup"),
                    new Entry("patient", "phone", "患者手机号", "followup"),
                    new Entry("patient", "medicalRecordNo", "患者病案号（病历号）", "followup"),
                    new Entry("patient", "address", "患者住址", "followup"),
                    new Entry("user", "phone", "用户手机号", "base"),
                    new Entry("user", "idCard", "用户证件号", "base"),
                    new Entry("report", null, "报告单与电子化资料原件（附件）", "followup"),
                    new Entry("contact", "recording", "通话录音", "followup"),
                    new Entry("rct", "grouping", "RCT 分组", null),
                    new Entry("rct", "randomTable", "RCT 随机表", null),
                    new Entry("rct", "unblinding", "RCT 揭盲信息", null));

    public static List<Entry> all() {
        return ENTRIES;
    }

    /** 按资源对象与字段定位目录条目（field 为空找资源整体条目）。 */
    public static Optional<Entry> find(String resource, String field) {
        return ENTRIES.stream()
                .filter(
                        entry ->
                                entry.resource().equals(resource)
                                        && java.util.Objects.equals(entry.field(), field))
                .findFirst();
    }

    /** 资源对象是否在目录中（资源整体规则用）。 */
    public static boolean resourceKnown(String resource) {
        return ENTRIES.stream().anyMatch(entry -> entry.resource().equals(resource));
    }

    /** 字段条目的默认脱敏规则（MASKED 但规则未带脱敏码时的兜底，一般走规则自带值）。 */
    public static String defaultMaskRule(String resource, String field) {
        return find(resource, field)
                .map(entry -> MASK_RULES.contains(entry.field()) ? entry.field() : "generic")
                .orElse("generic");
    }

    private ViewRuleCatalog() {}
}
