package com.crdp.system.user;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.system.infra.mapper.BrandMapper;
import com.crdp.system.infra.mapper.BrandRow;
import com.crdp.system.infra.mapper.HospitalMapper;
import com.crdp.system.infra.mapper.HospitalUserRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 读取当前租户库中的账号。调用前必须已经放入租户上下文。 */
@Component
public class HospitalDirectory {

    private final HospitalMapper mapper;
    private final BrandMapper brandMapper;

    public HospitalDirectory(HospitalMapper mapper, BrandMapper brandMapper) {
        this.mapper = mapper;
        this.brandMapper = brandMapper;
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
        boolean all = scopeTypes.stream().anyMatch("ALL"::equals);
        ScopeSnapshot scope =
                all
                        ? ScopeSnapshot.allowAll()
                        : ScopeSnapshot.restricted(
                                wants(scopeTypes, "WARD") ? mapper.wardIds(userId) : List.of(),
                                wants(scopeTypes, "DEPT", "CUSTOM_DEPT", "RESPONSIBLE_DEPT")
                                        ? mapper.deptIds(userId)
                                        : List.of(),
                                wants(scopeTypes, "SELF", "RESPONSIBLE_PATIENT"));
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

    private static boolean wants(List<String> types, String... names) {
        for (String name : names) {
            if (types.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
