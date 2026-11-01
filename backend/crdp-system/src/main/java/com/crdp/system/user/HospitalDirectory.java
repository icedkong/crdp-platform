package com.crdp.system.user;

import com.crdp.framework.datascope.ResearchProjectScopeProvider;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.system.infra.mapper.BrandMapper;
import com.crdp.system.infra.mapper.BrandRow;
import com.crdp.system.infra.mapper.HospitalMapper;
import com.crdp.system.infra.mapper.HospitalUserRow;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 读取当前租户库中的账号。调用前必须已经放入租户上下文。 */
@Component
public class HospitalDirectory {

    private final HospitalMapper mapper;
    private final BrandMapper brandMapper;
    private final ResearchProjectScopeProvider projectScopes;

    public HospitalDirectory(
            HospitalMapper mapper,
            BrandMapper brandMapper,
            ResearchProjectScopeProvider projectScopes) {
        this.mapper = mapper;
        this.brandMapper = brandMapper;
        this.projectScopes = projectScopes;
    }

    /** 本院个性化配置行（E06-T11 / PLT-CFG-008）；未配置返回 null（各字段回落平台默认）。 */
    public BrandRow brand() {
        return brandMapper.find();
    }

    /** 本院显示名称（未配置返回 null，调用方回落平台租户名称）。 */
    public String brandDisplayName() {
        return brandMapper.displayName();
    }

    public Optional<HospitalAccount> find(String username) {
        return assemble(mapper.findUser(username));
    }

    /** 按 id 取账号（E04-T05：管理员解锁、改密时重读当前哈希）。 */
    public Optional<HospitalAccount> findById(long id) {
        return assemble(mapper.findUserById(id));
    }

    /** 按手机号盲索引取账号（E04-T04 / PLT-AUTH-002）。参数是 CryptoService.blindIndex 的结果， 数据库里不落手机号明文。 */
    public Optional<HospitalAccount> findByPhoneIdx(String phoneIdx) {
        return assemble(mapper.findUserByPhoneIdx(phoneIdx));
    }

    /** 双因素按角色配置（PLT-AUTH-003）：cfg_param 里逗号分隔的角色编码，空表示无角色强制。 */
    public List<String> twoFactorRoles() {
        String value = mapper.paramValue("security.two-factor.roles");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return value.lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .toList();
    }

    /**
     * 租户在平台下限之上加严的密码最小长度（E04-T05 / PLT-AUTH-006，cfg_param security.password.min-length）。 未配置或非法返回
     * 0，调用方取 max(平台下限, 本值)。
     */
    public int passwordMinLengthOverride() {
        String value = mapper.paramValue("security.password.min-length");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** 最近若干个历史密码哈希（新到旧，E04-T05 改密用例）。 */
    public List<String> passwordHistory(long userId, int limit) {
        return mapper.passwordHistory(userId, limit);
    }

    /**
     * 租户加严的会话空闲超时，秒（E04-T06 / PLT-AUTH-008，cfg_param security.session.idle-minutes， 单位分钟）。未配置或非法返回
     * 0；调用方取 min(平台默认, 本值)——租户只能把空闲窗口改得更短。
     */
    public int sessionIdleOverrideSeconds() {
        String value = mapper.paramValue("security.session.idle-minutes");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            int minutes = Integer.parseInt(value.trim());
            return minutes > 0 ? minutes * 60 : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * 是否单端登录（E04-T06 / PLT-AUTH-008 并发会话策略，cfg_param security.session.single-login=true）。
     * 默认多端登录；开启后新登录使该账号旧会话全部下线。
     */
    public boolean singleLoginEnabled() {
        String value = mapper.paramValue("security.session.single-login");
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    /** 改密落库：新哈希、清强制改密标记、刷新 password_updated_at（E04-T05）。 */
    public void changePassword(
            long userId, String newPasswordHash, java.time.LocalDateTime updatedAt) {
        mapper.updatePassword(userId, newPasswordHash, updatedAt);
    }

    /** 把被替换的旧哈希追加进历史表（只增不改，E04-T05）。 */
    public void recordPasswordHistory(long userId, String oldPasswordHash) {
        mapper.insertPasswordHistory(userId, oldPasswordHash);
    }

    private Optional<HospitalAccount> assemble(HospitalUserRow row) {
        if (row == null || row.getId() == null) {
            return Optional.empty();
        }
        long userId = row.getId();
        List<String> scopeTypes = mapper.scopeTypes(userId);
        ScopeSnapshot scope =
                scopeOf(
                        scopeTypes,
                        wants(scopeTypes, "WARD") ? mapper.wardIds(userId) : List.of(),
                        wants(scopeTypes, "RESPONSIBLE_DEPT") ? mapper.deptIds(userId) : List.of(),
                        wants(scopeTypes, "DEPT") ? mapper.mainDeptId(userId) : null,
                        wants(scopeTypes, "CUSTOM_DEPT") ? mapper.customDeptIds(userId) : List.of(),
                        // E07-T03 / PLT-PERM-008：研究项目成员关系走扩展点（默认空实现，rct 模块落地时替换）
                        wants(scopeTypes, "RESEARCH_PROJECT")
                                ? projectScopes.memberProjectIds(userId)
                                : List.of());
        return Optional.of(
                new HospitalAccount(
                        userId,
                        row.getUsername(),
                        row.getDisplayName(),
                        row.getPasswordHash(),
                        row.getStatus(),
                        row.getPhone(),
                        Boolean.TRUE.equals(row.getMustChangePassword()),
                        row.getPasswordUpdatedAt(),
                        mapper.roles(userId),
                        mapper.menuIds(userId),
                        List.of(),
                        scope));
    }

    /**
     * E07-T02 / PLT-PERM-007：七种范围类型 → 会话快照（多角色类型先由 SQL DISTINCT 并集，这里 归并触发类型的清单）。
     *
     * <ul>
     *   <li>ALL：任一角色即全院（A-WARD-007），不再读清单；
     *   <li>WARD：本人负责病区清单（sys_user_ward），未分配病区则该分支为空——只有此类型时即空范围，看不到患者；
     *   <li>DEPT / RESPONSIBLE_DEPT / CUSTOM_DEPT：主科室（sys_user.main_dept_id）∪ 负责科室
     *       （sys_user_dept_scope）∪ 角色自定义科室（sys_role_scope_dept，多角色并集），去重保序；
     *   <li>SELF / RESPONSIBLE_PATIENT：本人分支（患者责任医生 / 任务负责人 = 本人，等值条件在改写侧）。
     * </ul>
     *
     * <p>E07-T03 / PLT-PERM-008：RESEARCH_PROJECT（研究项目）取扩展点返回的成员项目清单进快照（projectIds）； rct
     * 模块未落地时空实现返回空——只有此类型即空范围，看不到患者（默认拒绝）。项目分支的 SQL 改写随 rct 模块提供。
     *
     * <p>病区与科室在改写侧以 OR 组合（病区字典不挂科室树下，两者无上下级关系）；未触发的类型即使传入 清单也不生效（防御脏数据）。未知类型忽略。
     *
     * @param scopeTypes 启用角色的范围类型并集
     * @param responsibleWardIds 本人负责病区（WARD 触发时）
     * @param responsibleDeptIds 本人负责科室（RESPONSIBLE_DEPT 触发时）
     * @param mainDeptId 主科室（DEPT 触发时；未配置为 null）
     * @param customDeptIds 角色自定义科室（CUSTOM_DEPT 触发时，已跨角色并集）
     * @param memberProjectIds 成员研究项目（RESEARCH_PROJECT 触发时，来自扩展点）
     */
    static ScopeSnapshot scopeOf(
            List<String> scopeTypes,
            List<Long> responsibleWardIds,
            List<Long> responsibleDeptIds,
            Long mainDeptId,
            List<Long> customDeptIds,
            List<Long> memberProjectIds) {
        if (scopeTypes.contains("ALL")) {
            return ScopeSnapshot.allowAll();
        }
        List<Long> wardIds =
                wants(scopeTypes, "WARD") ? List.copyOf(responsibleWardIds) : List.of();
        LinkedHashSet<Long> deptIds = new LinkedHashSet<>();
        if (wants(scopeTypes, "DEPT") && mainDeptId != null) {
            deptIds.add(mainDeptId);
        }
        if (wants(scopeTypes, "RESPONSIBLE_DEPT")) {
            deptIds.addAll(responsibleDeptIds);
        }
        if (wants(scopeTypes, "CUSTOM_DEPT")) {
            deptIds.addAll(customDeptIds);
        }
        List<Long> projectIds =
                wants(scopeTypes, "RESEARCH_PROJECT") ? List.copyOf(memberProjectIds) : List.of();
        return ScopeSnapshot.restricted(
                wardIds,
                List.copyOf(deptIds),
                wants(scopeTypes, "SELF", "RESPONSIBLE_PATIENT"),
                projectIds);
    }

    private static boolean wants(List<String> types, String... names) {
        for (String name : names) {
            if (types.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
