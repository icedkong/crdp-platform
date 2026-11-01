package com.crdp.framework.fieldpolicy;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 敏感字段目录（E07-T06 / PLT-PERM-009 的配置面数据源）。
 *
 * <p>platform-spec §8「默认视角规则」与 AGENTS.md §5.3 划定的患者身份字段：姓名、证件号、手机号、住址、 病案号（病历号），初始默认策略一律
 * MASKED（脱敏）。字段编码与患者域接口口径一致（`docs/api/patient.md` §5 明文查看的 {@code name / phone / idCard}、§2 重复检查的
 * {@code medicalRecordNo}）。
 *
 * <p>M1 先以代码目录交付（平台侧「字段资源与策略」维护页 {@code /platform/field} 随 PLT-PERM-009 的平台
 * 部分落地，届时可替换为平台库下发）；E07-T04 在此之上做多角色「取最宽松」合并（platform-spec §4 业务 规则：可编辑 &gt; 可见 &gt; 脱敏 &gt;
 * 隐藏），E07-T05 落脱敏序列化与明文查看。平台上限（不能宽于平台默认， PLT-SCOPE-007）为 P2 后续规划，本目录暂不设上限。
 */
public final class SensitiveFieldCatalog {

    /** 字段策略（docs/api/system.md §3）：可见 / 脱敏 / 隐藏。 */
    public static final String POLICY_VISIBLE = "VISIBLE";

    public static final String POLICY_MASKED = "MASKED";
    public static final String POLICY_HIDDEN = "HIDDEN";

    public static final Set<String> POLICIES = Set.of(POLICY_VISIBLE, POLICY_MASKED, POLICY_HIDDEN);

    /** 目录条目：resource + field 全局定位一个敏感字段（字段码在资源内唯一）。 */
    public record Entry(String resource, String field, String label, String defaultPolicy) {

        public String key() {
            return resource + "." + field;
        }
    }

    /** 患者身份敏感字段（resource=patient）。顺序即配置页展示顺序。 */
    public static final List<Entry> PATIENT_FIELDS =
            List.of(
                    new Entry("patient", "name", "姓名", POLICY_MASKED),
                    new Entry("patient", "idCard", "证件号", POLICY_MASKED),
                    new Entry("patient", "phone", "手机号", POLICY_MASKED),
                    new Entry("patient", "medicalRecordNo", "病案号（病历号）", POLICY_MASKED),
                    new Entry("patient", "address", "住址", POLICY_MASKED));

    public static List<Entry> all() {
        return PATIENT_FIELDS;
    }

    public static Optional<Entry> find(String resource, String field) {
        return PATIENT_FIELDS.stream()
                .filter(entry -> entry.resource().equals(resource) && entry.field().equals(field))
                .findFirst();
    }

    private SensitiveFieldCatalog() {}
}
