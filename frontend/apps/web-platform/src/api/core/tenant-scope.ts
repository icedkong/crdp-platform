import type {
  ScopeTemplateUpdateRequest,
  ScopeTemplateView,
  SensitivePermUpdateRequest,
  TenantScopeChangeResult,
  TenantScopeLog,
  TenantScopePreview,
  TenantScopeUpdateRequest,
  TenantScopeView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 租户详情 · 授权范围页签（E05-T07 / PLT-SCOPE-001..006、008）：查看、影响分析、
 * 保存（自动收回租户角色超出项）与变更记录。
 */
export function listTenantScope(tenantId: number) {
  return requestClient.get<TenantScopeView>(
    apiPath(`/tenants/${tenantId}/scope`),
  );
}

/** 变更影响分析（PLT-SCOPE-005）：与保存同一套校验，只读不写。 */
export function previewTenantScope(
  tenantId: number,
  data: TenantScopeUpdateRequest,
) {
  return requestClient.post<TenantScopePreview>(
    apiPath(`/tenants/${tenantId}/scope/preview`),
    data,
  );
}

/**
 * 保存可分配范围（PLT-SCOPE-002、004、006、008）：原因必填；缩小范围自动从租户库
 * 角色移除超出项并刷新受影响用户权限版本。
 */
export function updateTenantScope(
  tenantId: number,
  data: TenantScopeUpdateRequest,
) {
  return requestClient.put<TenantScopeChangeResult>(
    apiPath(`/tenants/${tenantId}/scope`),
    data,
  );
}

/** 变更记录（PLT-SCOPE-008）：操作人、前后差异与原因，倒序。 */
export function tenantScopeLogs(tenantId: number) {
  return requestClient.get<TenantScopeLog[]>(
    apiPath(`/tenants/${tenantId}/scope/logs`),
  );
}

/** 授权范围模板视图（PLT-SCOPE-003、004）：按模块默认项 + 敏感权限清单。 */
export function listScopeTemplate() {
  return requestClient.get<ScopeTemplateView>(apiPath('/scope-template'));
}

/** 保存模块模板（PLT-SCOPE-003）：替换式提交默认可分配全集。 */
export function updateScopeTemplate(
  moduleCode: string,
  data: ScopeTemplateUpdateRequest,
) {
  return requestClient.put<ScopeTemplateView>(
    apiPath(`/scope-template/${moduleCode}`),
    data,
  );
}

/** 保存敏感权限清单（PLT-SCOPE-004）：整表替换。 */
export function updateSensitivePerms(data: SensitivePermUpdateRequest) {
  return requestClient.put<ScopeTemplateView>(
    apiPath('/scope-template/sensitive-perms'),
    data,
  );
}
