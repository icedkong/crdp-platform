package com.fu.platform.infra.mapper;

import com.fu.platform.menu.MenuRow;
import com.fu.platform.tenant.TenantRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
            SELECT id, username, display_name AS displayName, password_hash AS passwordHash, status
            FROM plt_account
            WHERE username = #{username}
            """)
    PlatformAccountRow findAccount(@Param("username") String username);

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
}
