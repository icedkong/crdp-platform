package com.crdp.platform.infra.mapper;

import com.crdp.platform.apires.ApiResourceRow;
import com.crdp.platform.menu.MenuRow;
import com.crdp.platform.resource.ResourceChangeRow;
import com.crdp.platform.tenant.TenantRecord;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlatformMapper {

    @Select(
            """
            SELECT id, code, name, status, grace_read_only AS graceReadOnly
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

    /** 账号经角色并集得到的菜单：只统计启用角色（E06-T04 / PLT-ROLE-002「角色停用后，持有该角色的用户 立即失去对应权限」；停用角色的行保留，重新启用自动恢复）。 */
    @Select(
            """
            SELECT DISTINCT rm.menu_id
            FROM plt_role_menu rm
            JOIN plt_account_role ar ON ar.role_id = rm.role_id
            JOIN plt_role r ON r.id = rm.role_id AND r.status = 'ENABLED'
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

    // ---- E05-T06 / PLT-PERM-001..004、011：全局菜单树、接口资源登记与清单同步 ----

    /** 菜单管理树（含按钮、含停用行；软删行不返回）。 */
    @Select(
            """
            SELECT id, parent_id, side, type, module_code, title, route_name, path, component,
                   perm_code, icon, sort_no, hidden, status, source
            FROM plt_menu
            WHERE side = #{side} AND status <> 'DELETED'
            ORDER BY sort_no ASC, id ASC
            """)
    List<MenuRow> listAllMenus(@Param("side") String side);

    @Select(
            """
            SELECT id, parent_id, side, type, module_code, title, route_name, path, component,
                   perm_code, icon, sort_no, hidden, status, source
            FROM plt_menu WHERE id = #{id}
            """)
    MenuRow findMenu(@Param("id") long id);

    @Insert(
            """
            INSERT INTO plt_menu
              (parent_id, side, type, module_code, title, route_name, path, component,
               perm_code, icon, sort_no, hidden, status, source)
            VALUES (#{parentId}, #{side}, #{type}, #{moduleCode}, #{title}, #{routeName}, #{path},
                    #{component}, #{permCode}, #{icon}, #{sortNo}, #{hidden}, #{status}, #{source})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMenu(MenuRow row);

    @Update(
            """
            UPDATE plt_menu
            SET title = #{title}, icon = #{icon}, sort_no = #{sortNo}, hidden = #{hidden},
                route_name = #{routeName}, path = #{path}, component = #{component},
                status = #{status}
            WHERE id = #{id}
            """)
    int updateMenu(MenuRow row);

    /** 软删除（PLT-PERM-001）：status 置 DELETED，行保留供审计追溯。 */
    @Update("UPDATE plt_menu SET status = 'DELETED' WHERE id = #{id}")
    int softDeleteMenu(@Param("id") long id);

    /** 权限码占用计数：软删行仍持有 uk_plt_menu_perm 唯一键，须一并计入（复活走清单同步或重新启用）。 */
    @Select(
            """
            SELECT COUNT(*) FROM plt_menu
            WHERE side = #{side} AND perm_code = #{permCode}
              AND id <> #{excludeId}
            """)
    int countMenuByPermCode(
            @Param("side") String side,
            @Param("permCode") String permCode,
            @Param("excludeId") long excludeId);

    @Select(
            """
            SELECT COUNT(*) FROM plt_menu
            WHERE side = #{side} AND route_name = #{routeName} AND status <> 'DELETED'
              AND id <> #{excludeId}
            """)
    int countMenuByRouteName(
            @Param("side") String side,
            @Param("routeName") String routeName,
            @Param("excludeId") long excludeId);

    @Select(
            """
            SELECT COUNT(*) FROM plt_menu
            WHERE parent_id = #{id} AND status <> 'DELETED'
            """)
    int countChildMenus(@Param("id") long id);

    /** 清单同步：某模块某端的清单来源行（含已下线，供差异比对）。 */
    @Select(
            """
            SELECT id, parent_id, side, type, module_code, title, route_name, path, component,
                   perm_code, icon, sort_no, hidden, status, source
            FROM plt_menu
            WHERE module_code = #{moduleCode} AND side = #{side} AND source = 'MANIFEST'
            ORDER BY sort_no ASC, id ASC
            """)
    List<MenuRow> listManifestMenus(
            @Param("moduleCode") String moduleCode, @Param("side") String side);

    @Select(
            """
            SELECT id FROM plt_menu
            WHERE side = #{side} AND route_name = #{routeName} AND status <> 'DELETED'
            """)
    Long findMenuIdByRouteName(@Param("side") String side, @Param("routeName") String routeName);

    @Delete("DELETE FROM plt_role_menu WHERE menu_id = #{menuId}")
    int deleteRoleMenuGrants(@Param("menuId") long menuId);

    /** 菜单删除 / 停用后受影响的平台账号（权限版本递增用）。 */
    @Select(
            """
            SELECT DISTINCT ar.account_id
            FROM plt_role_menu rm
            JOIN plt_account_role ar ON ar.role_id = rm.role_id
            WHERE rm.menu_id = #{menuId}
            """)
    List<Long> accountIdsGrantedMenu(@Param("menuId") long menuId);

    // ---- 接口资源（PLT-PERM-004）----

    /** 平台库已登记模块编码（接口资源登记推导模块用）。 */
    @Select("SELECT code FROM plt_module")
    List<String> listModuleCodes();

    @Select(
            """
            SELECT id, http_method, path_pattern, perm_code, module_code, public_access,
                   audit_level, sensitive_level, action_type, source, synced_at
            FROM plt_api_resource
            ORDER BY module_code ASC, path_pattern ASC, http_method ASC
            """)
    List<ApiResourceRow> listApiResources();

    @Select(
            """
            SELECT id, http_method, path_pattern, perm_code, module_code, public_access,
                   audit_level, sensitive_level, action_type, source, synced_at
            FROM plt_api_resource
            WHERE http_method = #{httpMethod} AND path_pattern = #{pathPattern}
            """)
    ApiResourceRow findApiResource(
            @Param("httpMethod") String httpMethod, @Param("pathPattern") String pathPattern);

    @Select(
            """
            SELECT id, http_method, path_pattern, perm_code, module_code, public_access,
                   audit_level, sensitive_level, action_type, source, synced_at
            FROM plt_api_resource WHERE id = #{id}
            """)
    ApiResourceRow findApiResourceById(@Param("id") long id);

    @Insert(
            """
            INSERT INTO plt_api_resource
              (http_method, path_pattern, perm_code, module_code, public_access, audit_level,
               sensitive_level, action_type, source, synced_at)
            VALUES (#{httpMethod}, #{pathPattern}, #{permCode}, #{moduleCode}, #{publicAccess},
                    #{auditLevel}, #{sensitiveLevel}, #{actionType}, #{source},
                    CURRENT_TIMESTAMP(3))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertApiResource(ApiResourceRow row);

    /** 启动同步只写代码声明的列（权限码 / 模块 / 公共标记）；平台端标记的审计级别、敏感级别、动作类型不覆盖。 */
    @Update(
            """
            UPDATE plt_api_resource
            SET perm_code = #{permCode}, module_code = #{moduleCode},
                public_access = #{publicAccess}, synced_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int updateApiResourceRegistration(ApiResourceRow row);

    @Update(
            """
            UPDATE plt_api_resource
            SET perm_code = #{permCode}, public_access = #{publicAccess},
                audit_level = #{auditLevel}, sensitive_level = #{sensitiveLevel},
                action_type = #{actionType}, source = 'MANUAL', synced_at = CURRENT_TIMESTAMP(3)
            WHERE id = #{id}
            """)
    int updateApiResourceMarks(ApiResourceRow row);

    @Update("UPDATE plt_api_resource SET synced_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int touchApiResource(@Param("id") long id);

    @Delete("DELETE FROM plt_api_resource WHERE id = #{id} AND source = 'SCAN'")
    int deleteScannedApiResource(@Param("id") long id);

    /** 资源变更记录只追加（PLT-PERM-012 / E05-T10），无修改与删除。 */
    @Insert(
            """
            INSERT INTO plt_resource_change_log
              (resource_type, resource_id, resource_name, resource_ident, action, channel,
               detail, operator_id, operator_name)
            VALUES (#{resourceType}, #{resourceId}, #{resourceName}, #{resourceIdent}, #{action},
                    #{channel}, #{detail}, #{operatorId}, #{operatorName})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertResourceChange(ResourceChangeRow row);
}
