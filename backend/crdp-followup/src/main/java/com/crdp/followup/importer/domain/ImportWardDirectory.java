package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * E18-T04：导入用<strong>病区值映射字典</strong>（A-WARD-006「导入与录入时『病区/中心』字段必须对应病区字典；历史写法可配置别名映射」； tech-plan
 * §12④「病区值按病区字典与别名映射」）。
 *
 * <p>病区字典的取值匹配键 = 病区<strong>编码、名称、别名</strong>（别名即历史写法，OQ-74④ 定稿复用 {@code ward.alias} 列， 病区管理页
 * E13-T08 已可维护），精确匹配（取值先去首尾空白）；一个取值只应落到一个病区：
 *
 * <ul>
 *   <li>命中唯一启用病区 → {@link MatchStatus#MATCHED}（校验通过，映射结果供 E18-T05 提交写 pt_patient.ward_id）；
 *   <li>命中唯一停用病区 → {@link MatchStatus#DISABLED}（停用病区不能进新患者，A-WARD-008）；
 *   <li>命中多个病区（编码 / 名称 / 别名互相冲突）→ {@link MatchStatus#AMBIGUOUS}（无法确定唯一归属）；
 *   <li>全部未命中 → {@link MatchStatus#UNMAPPED}——未映射值导入前必须处理（tasks.csv E18-T04 验收： 修正原表，或在病区管理维护别名映射）。
 * </ul>
 *
 * <p>纯内存模型：由 app 层加载器从病区字典（{@code WardService}）构造；导入校验与提交（E18-T05）共用同一映射口径。 条目全部为虚构数据由调用方保证（规则 5）。
 *
 * @param wards 病区条目（含停用——停用要单独报「已停用」而不是「未映射」）
 */
public record ImportWardDirectory(List<WardEntry> wards) {

    /** 提示信息里列举的候选病区上限（与 {@link ImportFieldRule#optionSummary} 同口径，避免长文案）。 */
    public static final int SUMMARY_LIMIT = 8;

    public ImportWardDirectory {
        wards = wards == null ? List.of() : List.copyOf(wards);
    }

    /** 一条病区字典条目（id 为病区主键——导入提交写 pt_patient.ward_id 用它）。 */
    public record WardEntry(
            long id, String code, String name, String status, List<String> aliases) {

        public WardEntry {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }

        private boolean enabled() {
            return "ENABLED".equals(status);
        }
    }

    /** 一次取值解析的结果状态。 */
    public enum MatchStatus {
        MATCHED("已映射"),
        UNMAPPED("未映射"),
        DISABLED("已停用"),
        AMBIGUOUS("多区冲突");

        private final String label;

        MatchStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一次取值解析结果：MATCHED 时 ward 为命中病区；DISABLED 时 ward 为停用病区； AMBIGUOUS 时 candidates 为冲突病区清单；UNMAPPED
     * 时两者皆空。
     */
    public record Resolution(MatchStatus status, WardEntry ward, List<WardEntry> candidates) {

        static Resolution of(WardEntry ward) {
            return new Resolution(
                    ward.enabled() ? MatchStatus.MATCHED : MatchStatus.DISABLED, ward, List.of());
        }
    }

    /**
     * 解析一个病区取值（调用方先经 {@link ImportCell#normalized()}：空白 / N/A 不会到这里）。 精确匹配编码 / 名称 /
     * 别名；同一取值命中多个病区即冲突。
     */
    public Resolution resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Resolution(MatchStatus.UNMAPPED, null, List.of());
        }
        Set<WardEntry> matched = new LinkedHashSet<>();
        for (WardEntry ward : wards) {
            if (matches(ward, raw.strip())) {
                matched.add(ward);
            }
        }
        if (matched.isEmpty()) {
            return new Resolution(MatchStatus.UNMAPPED, null, List.of());
        }
        if (matched.size() > 1) {
            return new Resolution(MatchStatus.AMBIGUOUS, null, new ArrayList<>(matched));
        }
        return Resolution.of(matched.iterator().next());
    }

    /** 启用病区名称清单（未映射错误提示「可为：…」，停用病区不作为候选）。 */
    public String enabledSummary(int limit) {
        StringBuilder text = new StringBuilder();
        wards.stream()
                .filter(WardEntry::enabled)
                .limit(limit)
                .forEach(
                        ward -> {
                            if (text.length() > 0) {
                                text.append("、");
                            }
                            text.append(ward.name());
                        });
        return text.toString();
    }

    private static boolean matches(WardEntry ward, String value) {
        if (value.equals(ward.code()) || value.equals(ward.name())) {
            return true;
        }
        for (String alias : ward.aliases()) {
            if (value.equals(alias == null ? "" : alias.strip())) {
                return true;
            }
        }
        return false;
    }
}
