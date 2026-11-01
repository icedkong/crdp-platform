package com.crdp.server.platform;

import com.crdp.framework.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 业务角色模板（E06-T05 / PLT-ROLE-005）：模块开通时把平台库登记的角色模板下发到该租户库（platform-spec §3 开通流程第 4
 * 步「写入模块配置默认值与业务角色模板」）。
 *
 * <p>模板定义在平台库 {@code plt_role_template}（种子 V105 登记随访模块五个模板），下发产物是租户库 {@code sys_role} 里 {@code
 * role_type='TEMPLATE'} 的只读行：租户不能直接改模板，复制生成自定义角色后调整（PLT-ORG-005，见 {@code
 * RoleAdminService}）。下发幂等（重复开通 / 重新开通只补不重），菜单授权直插 {@code sys_role_menu}——与 TENANT_ADMIN 直授同口径（OQ-46
 * ①），超范围项由会话层「角色并集 ∩ 已开通模块 ∩ 可分配范围」最终裁剪（E05-T07）。
 *
 * <p>模板角色不直接分配给用户：{@code RoleAdminService.updateMembers} 拒绝 TEMPLATE（规格「租户可复制后调整」）。
 */
@Service
public class RoleTemplateService {

    private final JdbcTemplate platform;

    public RoleTemplateService(JdbcTemplate platform) {
        this.platform = platform;
    }

    /** 模板行（平台库视角；status 只取 ENABLED）。 */
    public record TemplateRow(
            long id, String moduleCode, String code, String name, String description, int sortNo) {}

    /** 把模块的启用模板下发到租户库（幂等）：sys_role upsert 成 TEMPLATE 行 + 模板菜单授权。 返回本次确保存在的模板数（含已存在的）。 */
    public int seedToTenant(long tenantId, String tenantCode, String moduleCode) {
        List<TemplateRow> templates = templatesOf(moduleCode);
        if (templates.isEmpty()) {
            return 0;
        }
        Map<Long, List<Long>> menusByTemplate = menusOfTemplates(templates);
        return TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () -> {
                    int ensured = 0;
                    for (TemplateRow template : templates) {
                        platform.update(
                                "INSERT INTO sys_role (code, name, built_in, status, role_type,"
                                        + " module_code, description) VALUES (?, ?, 0, 'ENABLED',"
                                        + " 'TEMPLATE', ?, ?)"
                                        + " ON DUPLICATE KEY UPDATE name = VALUES(name), status ="
                                        + " 'ENABLED', role_type = 'TEMPLATE', module_code ="
                                        + " VALUES(module_code), description = VALUES(description)",
                                template.code(),
                                template.name(),
                                moduleCode,
                                template.description());
                        ensured++;
                        Long roleId =
                                platform.queryForObject(
                                        "SELECT id FROM sys_role WHERE code = ?",
                                        Long.class,
                                        template.code());
                        if (roleId == null) {
                            continue;
                        }
                        for (Long menuId : menusByTemplate.getOrDefault(template.id(), List.of())) {
                            platform.update(
                                    "INSERT IGNORE INTO sys_role_menu (role_id, menu_id)"
                                            + " VALUES (?, ?)",
                                    roleId,
                                    menuId);
                        }
                    }
                    return ensured;
                });
    }

    /** 模块登记的启用模板（按 sort_no）。 */
    public List<TemplateRow> templatesOf(String moduleCode) {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT id, module_code, code, name, description, sort_no"
                                        + " FROM plt_role_template"
                                        + " WHERE module_code = ? AND status = 'ENABLED'"
                                        + " ORDER BY sort_no, id",
                                (rs, rowNum) ->
                                        new TemplateRow(
                                                rs.getLong("id"),
                                                rs.getString("module_code"),
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getString("description"),
                                                rs.getInt("sort_no")),
                                moduleCode));
    }

    /** 全部启用模板（平台端清单接口用，按模块与排序）。 */
    public List<TemplateRow> allTemplates() {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT id, module_code, code, name, description, sort_no"
                                        + " FROM plt_role_template WHERE status = 'ENABLED'"
                                        + " ORDER BY module_code, sort_no, id",
                                (rs, rowNum) ->
                                        new TemplateRow(
                                                rs.getLong("id"),
                                                rs.getString("module_code"),
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getString("description"),
                                                rs.getInt("sort_no"))));
    }

    private Map<Long, List<Long>> menusOfTemplates(List<TemplateRow> templates) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<Long, List<Long>> menus = new LinkedHashMap<>();
                    for (TemplateRow template : templates) {
                        menus.put(
                                template.id(),
                                platform.queryForList(
                                        "SELECT m.menu_id FROM plt_role_template_menu m"
                                                + " JOIN plt_menu p ON p.id = m.menu_id"
                                                + " WHERE m.template_id = ?"
                                                + " AND p.side = 'HOSPITAL' AND p.status = 'ENABLED'"
                                                + " ORDER BY m.menu_id",
                                        Long.class,
                                        template.id()));
                    }
                    return menus;
                });
    }
}
