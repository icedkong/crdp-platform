package com.crdp.platform.infra.mapper;

import com.crdp.platform.menu.MenuRow;
import com.crdp.platform.tenant.TenantRecord;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformMapper {

    @Select(
            """
            SELECT id, code, name, status
            FROM plt_tenant
            WHERE code = #{code}
            """)
    TenantRecord findTenantByCode(@Param("code") String code);

    @Select(
            """
            SELECT module_code
            FROM plt_tenant_module
            WHERE tenant_id = #{tenantId} AND status = 'OPEN'
            """)
    List<String> openedModules(@Param("tenantId") long tenantId);

    @Select(
            """
            SELECT id, parent_id, side, type, module_code, title, route_name, path, component,
                   perm_code, icon, sort_no, hidden
            FROM plt_menu
            WHERE side = #{side} AND status = 'ENABLED'
            ORDER BY sort_no ASC, id ASC
            """)
    List<MenuRow> listMenus(@Param("side") String side);

    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status,
                   phone, must_change_password AS mustChangePassword,
                   password_updated_at AS passwordUpdatedAt
            FROM plt_account
            WHERE username = #{username}
            """)
    PlatformAccountRow findAccount(@Param("username") String username);

    /** 平台账号双因素短信按手机号盲索引取号（E04-T04 / PLT-AUTH-003、005）。 */
    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status,
                   phone, must_change_password AS mustChangePassword,
                   password_updated_at AS passwordUpdatedAt
            FROM plt_account
            WHERE phone_idx = #{phoneIdx}
            """)
    PlatformAccountRow findAccountByPhoneIdx(@Param("phoneIdx") String phoneIdx);

    /** 按 id 取平台账号（E04-T06：权限重算时按会话里的账号 id 重读）。 */
    @Select(
            """
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status,
                   phone, must_change_password AS mustChangePassword,
                   password_updated_at AS passwordUpdatedAt
            FROM plt_account
            WHERE id = #{accountId}
            """)
    PlatformAccountRow findAccountById(@Param("accountId") long accountId);

    @Select(
            """
            SELECT r.code
            FROM plt_role r
            JOIN plt_account_role ar ON ar.role_id = r.id
            WHERE ar.account_id = #{accountId} AND r.status = 'ENABLED'
            """)
    List<String> accountRoles(@Param("accountId") long accountId);

    @Select(
            """
            SELECT DISTINCT rm.menu_id
            FROM plt_role_menu rm
            JOIN plt_account_role ar ON ar.role_id = rm.role_id
            WHERE ar.account_id = #{accountId}
            """)
    List<Long> accountMenuIds(@Param("accountId") long accountId);

    // ---- E04-T05 / PLT-AUTH-006、010：平台端自助改密 ----

    /** 改密落库：清强制改密标记并把 password_updated_at 推到现在（有效期 90 天从此重算）。 */
    @Update(
            """
            UPDATE plt_account
            SET password_hash = #{passwordHash}, must_change_password = 0,
                password_updated_at = #{updatedAt}
            WHERE id = #{accountId}
            """)
    int updateAccountPassword(
            @Param("accountId") long accountId,
            @Param("passwordHash") String passwordHash,
            @Param("updatedAt") java.time.LocalDateTime updatedAt);

    /** 最近若干个历史密码哈希（新到旧），用于「不得与最近 5 次相同」。 */
    @Select(
            """
            SELECT password_hash FROM plt_password_history
            WHERE account_id = #{accountId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<String> accountPasswordHistory(
            @Param("accountId") long accountId, @Param("limit") int limit);

    @Insert(
            "INSERT INTO plt_password_history (account_id, password_hash) VALUES (#{accountId}, #{passwordHash})")
    int insertAccountPasswordHistory(
            @Param("accountId") long accountId, @Param("passwordHash") String passwordHash);
}
