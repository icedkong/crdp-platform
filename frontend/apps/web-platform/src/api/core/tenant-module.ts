import type {
  TenantModuleChangeResult,
  TenantModuleCloseImpact,
  TenantModuleConfig,
  TenantModuleConfigUpdateRequest,
  TenantModulesView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 租户详情 · 模块页签（E05-T05 / PLT-MOD-004、005）：目录行 + 该租户的开通状态与痕迹。
 */
export function listTenantModules(tenantId: number) {
  return requestClient.get<TenantModulesView>(
    apiPath(`/tenants/${tenantId}/modules`),
  );
}

/** 关闭影响评估（PLT-MOD-005）：在线用户数、进行中任务数与仍依赖它的已开通模块。 */
export function tenantModuleCloseImpact(tenantId: number, moduleCode: string) {
  return requestClient.get<TenantModuleCloseImpact>(
    apiPath(`/tenants/${tenantId}/modules/${moduleCode}/close-impact`),
  );
}

/** 开通模块（PLT-MOD-004）：依赖与租户状态校验、执行该租户库模块迁移、菜单进入授权范围。 */
export function openTenantModule(
  tenantId: number,
  moduleCode: string,
  reason?: string,
) {
  return requestClient.post<TenantModuleChangeResult>(
    apiPath(`/tenants/${tenantId}/modules/${moduleCode}/open`),
    { reason: reason ?? null },
  );
}

/** 关闭模块（PLT-MOD-005）：原因必填；收回菜单与接口（40301），数据保留。 */
export function closeTenantModule(
  tenantId: number,
  moduleCode: string,
  reason: string,
) {
  return requestClient.post<TenantModuleChangeResult>(
    apiPath(`/tenants/${tenantId}/modules/${moduleCode}/close`),
    { reason },
  );
}

/** 模块级配置（PLT-MOD-008）：默认取模块定义，租户覆盖值优先。 */
export function tenantModuleConfig(tenantId: number, moduleCode: string) {
  return requestClient.get<TenantModuleConfig>(
    apiPath(`/tenants/${tenantId}/modules/${moduleCode}/config`),
  );
}

/** 修改模块级配置（PLT-MOD-008）：只提交覆盖值；后端写 MODULE_CONFIG 审计。 */
export function updateTenantModuleConfig(
  tenantId: number,
  moduleCode: string,
  data: TenantModuleConfigUpdateRequest,
) {
  return requestClient.put<TenantModuleConfig>(
    apiPath(`/tenants/${tenantId}/modules/${moduleCode}/config`),
    data,
  );
}
