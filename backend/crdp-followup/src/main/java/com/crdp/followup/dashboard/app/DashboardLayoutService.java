package com.crdp.followup.dashboard.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardLayoutMapper;
import com.crdp.followup.dashboard.infra.mapper.DashboardLayoutMapper.CardRow;
import com.crdp.followup.dashboard.infra.mapper.DashboardLayoutMapper.RoleLayoutRow;
import com.crdp.followup.dashboard.infra.mapper.DashboardLayoutMapper.RoleOptionRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首页卡片目录、按角色布局与用户隐藏 / 显示（E19-T06 / A-DASH-013、A-DASH-014；docs/api/system.md §5、 stats.md §2 / §3，页面
 * pages.md §2 首页「卡片设置」与 §10「首页卡片配置」）。
 *
 * <p>解析口径（A 规格 §9 业务规则）：
 *
 * <ul>
 *   <li>目录 ∩ 功能开关 ∩ 权限：卡片目录（{@code dash_card}，followup V77）只决定显示与顺序，不放宽数据 权限——每张卡片照常受数据范围（各取数
 *       Mapper 的 {@code @DataScope}）与病区隔离限制；{@code feature_code} 开关关闭（含无行）或会话未持有 {@code
 *       permission_code} 时该卡片直接不出现（「没有对应 功能权限时，该卡片即使已配置也不显示」）；
 *   <li>未配置的角色使用默认布局（目录 default_visible / default_order；医院 A 默认不显示节点随访率， B / C 初始化后经角色布局开启，A 规格
 *       §14，不写死租户编码）；
 *   <li>用户有多个角色时显示各角色允许卡片的并集，顺序取第一个已配置布局的角色（按角色创建序 = {@code sys_role.id} 升序，暂行口径
 *       OQ-111），其余卡片排在后面；角色都未配置时使用默认布局；
 *   <li>用户隐藏 / 显示（P1）：只能在本人角色允许（已解析可见）且 allowUserHide 的卡片范围内隐藏或重新 显示，不能加入角色未允许的卡片，也不能调整顺序（{@code
 *       dash_user_pref} 无顺序字段）；恢复为角色默认 = 空清单全量覆盖。
 * </ul>
 *
 * <p>即时生效（tasks.csv E19-T06 验收「按角色配置后即生效」）：读取路径每次实时查库，不做缓存（stats.md §1 的 60s
 * 结果缓存只针对卡片数据聚合，OQ-107①）。PUT / DELETE 全量覆盖 + 审计 {@code DASHBOARD_ROLE_LAYOUT} （配置变更）与 {@code
 * DASHBOARD_USER_PREF}（个人偏好变更）。
 */
@Service
public class DashboardLayoutService {

    private final DashboardLayoutMapper layouts;
    private final DashboardFeatureMapper features;
    private final AuditLogger audit;

    public DashboardLayoutService(
            DashboardLayoutMapper layouts, DashboardFeatureMapper features, AuditLogger audit) {
        this.layouts = layouts;
        this.features = features;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 合并后的一张卡片（visible=false 仅来自用户隐藏；角色隐藏 / 开关关 / 无权限的卡片不在响应里）。 */
    public record LayoutCardView(
            String code,
            String title,
            String width,
            int order,
            boolean visible,
            boolean allowUserHide,
            boolean userHidden,
            String permission,
            String feature) {}

    /** 当前用户布局：source = DEFAULT 目录默认 / ROLE_CONFIG 角色配置（用户隐藏不改变 source）。 */
    public record DashboardLayoutView(String source, List<LayoutCardView> cards) {

        /** 全部卡片编码（含用户隐藏行，按展示顺序）。 */
        public List<String> codes() {
            return cards.stream().map(LayoutCardView::code).toList();
        }
    }

    /** 配置页角色候选。 */
    public record RoleOptionView(long id, String code, String name) {}

    /** 配置页一行卡片（全部目录卡片都列出，featureOn=false 表示开关关闭、配置了也不显示）。 */
    public record RoleCardView(
            String code,
            String title,
            String width,
            boolean visible,
            int order,
            boolean allowUserHide,
            boolean featureOn,
            String permission,
            String feature) {}

    /** 单个角色的布局视图（configured=false 时 visible / order / allowUserHide 为目录默认值）。 */
    public record RoleLayoutView(
            long roleId, String roleName, boolean configured, List<RoleCardView> cards) {}

    /** GET role-layouts 响应：角色候选 + 当前查看角色（roleId 缺省取第一个角色）。 */
    public record RoleLayoutsView(List<RoleOptionView> roles, RoleLayoutView current) {}

    /** PUT 请求体一行：visible / order 必填，allowUserHide 缺省 = 目录默认（允许）。 */
    public record RoleCardSave(
            String code, Boolean visible, Integer order, Boolean allowUserHide) {}

    /** PUT user-pref 请求体：hidden 为要隐藏的卡片编码全量清单（空 = 恢复角色默认）。 */
    public record UserPrefSave(List<String> hidden) {}

    /** 解析中间态：角色解析结果（不含用户隐藏），供用户偏好校验与落库。 */
    private record Resolved(boolean roleConfigured, List<LayoutCardView> cards) {}

    // ---------------------------------------------------------------- 当前用户布局（登录即可）

    /** 当前用户合并后的卡片（stats.md §2 GET /dashboard/layout）。 */
    public DashboardLayoutView current(SessionPrincipal user) {
        Resolved resolved = resolve(user);
        Set<String> hidden = new HashSet<>();
        for (var row : layouts.findUserPrefs(user.userId())) {
            hidden.add(row.cardCode());
        }
        List<LayoutCardView> cards = new ArrayList<>();
        for (LayoutCardView card : resolved.cards()) {
            boolean userHidden = hidden.contains(card.code()) && card.allowUserHide();
            cards.add(
                    new LayoutCardView(
                            card.code(),
                            card.title(),
                            card.width(),
                            card.order(),
                            card.visible() && !userHidden,
                            card.allowUserHide(),
                            userHidden,
                            card.permission(),
                            card.feature()));
        }
        return new DashboardLayoutView(
                resolved.roleConfigured() ? "ROLE_CONFIG" : "DEFAULT", cards);
    }

    /**
     * 用户隐藏 / 显示（A-DASH-014，P1）：全量覆盖本人隐藏清单。只能在角色允许（解析后可见）且 allowUserHide 的卡片内隐藏（40001）；空清单 = 恢复角色默认。
     */
    @Transactional
    public DashboardLayoutView saveUserPref(SessionPrincipal user, UserPrefSave request) {
        List<String> hidden =
                request == null || request.hidden() == null ? List.of() : request.hidden();
        Resolved resolved = resolve(user);
        Map<String, LayoutCardView> visibleByCode = new LinkedHashMap<>();
        for (LayoutCardView card : resolved.cards()) {
            visibleByCode.put(card.code(), card);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String code : hidden) {
            if (code == null || code.isBlank()) {
                throw invalid("hidden（卡片编码）");
            }
            LayoutCardView card = visibleByCode.get(code.trim());
            if (card == null) {
                throw new ApiException(
                        400, 40001, "不能隐藏角色未允许的卡片：" + code + "（A-DASH-014，卡片范围以角色配置为准）");
            }
            if (!card.allowUserHide()) {
                throw new ApiException(400, 40001, "该卡片不允许用户隐藏：" + code + "（角色配置「允许用户隐藏」为否）");
            }
            unique.add(code.trim());
        }
        layouts.deleteUserPrefs(user.userId());
        for (String code : unique) {
            layouts.insertUserPrefRow(user.userId(), code);
        }
        audit.append(
                AuditEntry.operation("followup", "DASHBOARD_USER_PREF")
                        .objectType("DASH_USER_PREF")
                        .objectId(String.valueOf(user.userId()))
                        .description(
                                unique.isEmpty()
                                        ? "恢复角色默认首页布局（用户卡片设置）"
                                        : "隐藏首页卡片：" + String.join("、", unique) + "（用户卡片设置）")
                        .afterJson("{\"hidden\":" + codesJson(unique) + "}")
                        .build());
        return current(user);
    }

    // ----------------------------------------------------------------
    // 配置页（followup:dashboard:config）

    /** 角色布局视图（roleId 缺省取第一个角色；带角色候选，避免配置页依赖 tenant:role:view）。 */
    public RoleLayoutsView roleView(Long roleId) {
        List<RoleOptionRow> options = layouts.findRoleOptions();
        List<RoleOptionView> roles =
                options.stream()
                        .map(
                                option ->
                                        new RoleOptionView(
                                                option.id(), option.code(), option.name()))
                        .toList();
        long target =
                roleId == null
                        ? options.isEmpty() ? 0L : options.get(0).id()
                        : requireRole(roleId, options);
        return new RoleLayoutsView(roles, roleLayoutView(target, options));
    }

    /** 保存角色布局（全量覆盖）：校验角色存在（40400）、卡片在目录内且顺序唯一（40001）；写审计。 */
    @Transactional
    public RoleLayoutsView saveRoleLayout(
            SessionPrincipal operator, long roleId, List<RoleCardSave> cards) {
        List<RoleOptionRow> options = layouts.findRoleOptions();
        requireRole(roleId, options);
        Map<String, CardRow> catalog = catalogByCode();
        if (cards == null) {
            throw invalid("cards（卡片清单）");
        }
        Set<String> codes = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (RoleCardSave item : cards) {
            if (item == null || item.code() == null || !catalog.containsKey(item.code())) {
                throw invalid("cards[].code（首页卡片目录内的编码）");
            }
            if (!codes.add(item.code())) {
                throw invalid("cards[].code（重复：" + item.code() + "）");
            }
            if (item.visible() == null) {
                throw invalid("cards[].visible");
            }
            if (item.order() == null || item.order() < 1) {
                throw invalid("cards[].order（从 1 起的正整数）");
            }
            if (!orders.add(item.order())) {
                throw invalid("cards[].order（重复：" + item.order() + "）");
            }
        }
        String before = roleLayoutJson(roleId);
        layouts.deleteRoleLayout(roleId);
        for (RoleCardSave item : cards) {
            CardRow card = catalog.get(item.code());
            boolean allowUserHide =
                    item.allowUserHide() == null ? card.allowUserHide() : item.allowUserHide();
            layouts.insertRoleLayoutRow(
                    roleId,
                    item.code(),
                    item.visible(),
                    item.order(),
                    allowUserHide,
                    operator.userId());
        }
        long visibleCount = cards.stream().filter(RoleCardSave::visible).count();
        audit.append(
                AuditEntry.operation("followup", "DASHBOARD_ROLE_LAYOUT")
                        .objectType("DASH_ROLE_LAYOUT")
                        .objectId(String.valueOf(roleId))
                        .description(
                                "配置角色首页卡片布局："
                                        + roleNameOf(roleId, options)
                                        + "（显示 "
                                        + visibleCount
                                        + " 张 / 共 "
                                        + cards.size()
                                        + " 张）")
                        .beforeJson(before)
                        .afterJson(roleLayoutJson(roleId))
                        .build());
        return new RoleLayoutsView(optionViews(options), roleLayoutView(roleId, options));
    }

    /** 恢复角色默认布局（DELETE）：清空该角色配置行；写审计。 */
    @Transactional
    public RoleLayoutsView resetRoleLayout(SessionPrincipal operator, long roleId) {
        List<RoleOptionRow> options = layouts.findRoleOptions();
        requireRole(roleId, options);
        String before = roleLayoutJson(roleId);
        layouts.deleteRoleLayout(roleId);
        audit.append(
                AuditEntry.operation("followup", "DASHBOARD_ROLE_LAYOUT")
                        .objectType("DASH_ROLE_LAYOUT")
                        .objectId(String.valueOf(roleId))
                        .description("恢复角色默认首页布局：" + roleNameOf(roleId, options) + "（清空配置行）")
                        .beforeJson(before)
                        .afterJson("{\"rows\":[]}")
                        .build());
        return new RoleLayoutsView(optionViews(options), roleLayoutView(roleId, options));
    }

    // ---------------------------------------------------------------- 解析（目录 ∩ 开关 ∩ 权限 × 角色布局）

    /**
     * 解析当前用户的角色布局（不含用户隐藏）：
     *
     * <ul>
     *   <li>角色都未配置（或无角色）→ 目录默认布局（default_visible，default_order）；
     *   <li>有任一角色配置 → 可见 = 各配置角色 visible 行的并集，顺序 = 第一个已配置角色（角色 id 升序） 的
     *       sort_order，其余并集卡片按后续配置角色的顺序排在后面；
     *   <li>allowUserHide 取第一个配置了该卡的角色行，否则目录默认；
     *   <li>功能开关关闭（含无行）或会话无卡片权限的卡片直接淘汰（配置不放宽开关与权限）。
     * </ul>
     */
    private Resolved resolve(SessionPrincipal user) {
        List<CardRow> cards = layouts.findCards();
        Set<String> permissions = new HashSet<>(user.permissionCodes());
        Map<String, Boolean> featureCache = new HashMap<>();
        Map<String, CardRow> usable = new LinkedHashMap<>();
        for (CardRow card : cards) {
            if (card.featureCode() != null
                    && !featureCache.computeIfAbsent(
                            card.featureCode(),
                            code -> Boolean.TRUE.equals(features.findEnabled(code)))) {
                continue;
            }
            if (!permissions.contains(card.permissionCode())) {
                continue;
            }
            usable.put(card.cardCode(), card);
        }
        List<Long> roleIds =
                user.roles().isEmpty() ? List.of() : layouts.findRoleIdsByCodes(user.roles());
        Map<Long, List<RoleLayoutRow>> configured = new TreeMap<>();
        if (!roleIds.isEmpty()) {
            for (RoleLayoutRow row : layouts.findRoleLayouts(roleIds)) {
                if (usable.containsKey(row.cardCode())) {
                    configured.computeIfAbsent(row.roleId(), key -> new ArrayList<>()).add(row);
                }
            }
        }
        if (configured.isEmpty()) {
            List<LayoutCardView> views = new ArrayList<>();
            for (CardRow card : usable.values()) {
                if (card.defaultVisible()) {
                    views.add(toView(card, card.defaultOrder(), true, card.allowUserHide()));
                }
            }
            return new Resolved(false, order(views));
        }
        List<String> sequence = new ArrayList<>();
        Map<String, Boolean> allowHideByCode = new HashMap<>();
        for (Map.Entry<Long, List<RoleLayoutRow>> entry : configured.entrySet()) {
            List<RoleLayoutRow> rows = entry.getValue();
            rows.sort(java.util.Comparator.comparingInt(RoleLayoutRow::sortOrder));
            for (RoleLayoutRow row : rows) {
                if (row.visible() && !sequence.contains(row.cardCode())) {
                    sequence.add(row.cardCode());
                }
                allowHideByCode.putIfAbsent(row.cardCode(), row.allowUserHide());
            }
        }
        List<LayoutCardView> views = new ArrayList<>();
        for (String code : sequence) {
            CardRow card = usable.get(code);
            views.add(
                    toView(
                            card,
                            sequence.indexOf(code) + 1,
                            true,
                            allowHideByCode.getOrDefault(code, card.allowUserHide())));
        }
        return new Resolved(true, order(views));
    }

    /** 顺序归一为从 1 起（保持相对顺序；展示顺序 = 响应数组顺序）。 */
    private static List<LayoutCardView> order(List<LayoutCardView> views) {
        List<LayoutCardView> normalized = new ArrayList<>();
        for (int i = 0; i < views.size(); i++) {
            LayoutCardView view = views.get(i);
            normalized.add(
                    new LayoutCardView(
                            view.code(),
                            view.title(),
                            view.width(),
                            i + 1,
                            view.visible(),
                            view.allowUserHide(),
                            view.userHidden(),
                            view.permission(),
                            view.feature()));
        }
        return normalized;
    }

    private static LayoutCardView toView(
            CardRow card, int order, boolean visible, boolean allowUserHide) {
        return new LayoutCardView(
                card.cardCode(),
                card.title(),
                card.width(),
                order,
                visible,
                allowUserHide,
                false,
                card.permissionCode(),
                card.featureCode());
    }

    // ---------------------------------------------------------------- 配置页内部

    private Map<String, CardRow> catalogByCode() {
        Map<String, CardRow> byCode = new LinkedHashMap<>();
        for (CardRow card : layouts.findCards()) {
            byCode.put(card.cardCode(), card);
        }
        return byCode;
    }

    private RoleLayoutView roleLayoutView(long roleId, List<RoleOptionRow> options) {
        Map<String, CardRow> catalog = catalogByCode();
        List<RoleLayoutRow> rows = roleId == 0L ? List.of() : layouts.findRoleLayout(roleId);
        List<RoleLayoutRow> configuredRows =
                new ArrayList<>(
                        rows.stream().filter(row -> catalog.containsKey(row.cardCode())).toList());
        configuredRows.sort(java.util.Comparator.comparingInt(RoleLayoutRow::sortOrder));
        Map<String, RoleLayoutRow> byCode = new LinkedHashMap<>();
        for (RoleLayoutRow row : configuredRows) {
            byCode.put(row.cardCode(), row);
        }
        // 已配置行在前（sort_order 序），未配置的目录卡片按默认顺序补在后（配置页可继续勾选）
        List<CardRow> orderedCards = new ArrayList<>();
        for (RoleLayoutRow row : configuredRows) {
            CardRow card = catalog.get(row.cardCode());
            if (card != null) {
                orderedCards.add(card);
            }
        }
        for (CardRow card : catalog.values()) {
            if (!byCode.containsKey(card.cardCode())) {
                orderedCards.add(card);
            }
        }
        List<RoleCardView> cards = new ArrayList<>();
        for (CardRow card : orderedCards) {
            RoleLayoutRow row = byCode.get(card.cardCode());
            boolean featureOn =
                    card.featureCode() == null
                            || Boolean.TRUE.equals(features.findEnabled(card.featureCode()));
            cards.add(
                    new RoleCardView(
                            card.cardCode(),
                            card.title(),
                            card.width(),
                            row != null ? row.visible() : card.defaultVisible(),
                            cards.size() + 1,
                            row != null ? row.allowUserHide() : card.allowUserHide(),
                            featureOn,
                            card.permissionCode(),
                            card.featureCode()));
        }
        return new RoleLayoutView(roleId, roleNameOf(roleId, options), !byCode.isEmpty(), cards);
    }

    private static List<RoleOptionView> optionViews(List<RoleOptionRow> options) {
        return options.stream()
                .map(option -> new RoleOptionView(option.id(), option.code(), option.name()))
                .toList();
    }

    private long requireRole(long roleId, List<RoleOptionRow> options) {
        if (options.stream().noneMatch(option -> option.id() == roleId)) {
            throw new ApiException(404, 40400, "角色不存在");
        }
        return roleId;
    }

    private static String roleNameOf(long roleId, List<RoleOptionRow> options) {
        return options.stream()
                .filter(option -> option.id() == roleId)
                .map(RoleOptionRow::name)
                .findFirst()
                .orElse(String.valueOf(roleId));
    }

    /** 审计用紧凑 JSON：该角色当前配置行（读库后拼装，保存路径在写库前后各取一次）。 */
    private String roleLayoutJson(long roleId) {
        List<RoleLayoutRow> rows = layouts.findRoleLayout(roleId);
        if (rows.isEmpty()) {
            return "{\"rows\":[]}";
        }
        StringBuilder json = new StringBuilder("{\"rows\":[");
        rows.stream()
                .sorted(java.util.Comparator.comparingInt(RoleLayoutRow::sortOrder))
                .forEach(
                        row ->
                                json.append(
                                        "{\"card\":\""
                                                + row.cardCode()
                                                + "\",\"visible\":"
                                                + row.visible()
                                                + ",\"order\":"
                                                + row.sortOrder()
                                                + ",\"allowUserHide\":"
                                                + row.allowUserHide()
                                                + "},"));
        json.setLength(json.length() - 1);
        return json.append("]}").toString();
    }

    private static String codesJson(Set<String> codes) {
        if (codes.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (String code : codes) {
            json.append("\"").append(code).append("\",");
        }
        json.setLength(json.length() - 1);
        return json.append("]").toString();
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
