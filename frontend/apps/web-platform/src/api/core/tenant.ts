import type {
  CreateTenantRequest,
  PageView,
  TenantDetail,
  TenantListItem,
  TenantListQuery,
  TenantOnboardingResult,
  TenantProvisionView,
  TenantUpdateRequest,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 租户列表（E05-T01 / PLT-TEN-001）：按名称/编码关键词、状态、到期日、模块检索。
 * query 的组装见 views/platform/tenant/list.ts（纯逻辑，可测）。
 */
export async function listTenants(query: TenantListQuery) {
  const params: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') {
      params[key] = value;
    }
  }
  return requestClient.get<PageView<TenantListItem>>(apiPath('/tenants'), {
    params,
  });
}

/** 租户详情（PLT-TEN-009）：基本信息、联系人与备注。 */
export function tenantDetail(tenantId: number) {
  return requestClient.get<TenantDetail>(apiPath(`/tenants/${tenantId}`));
}

/** 维护租户信息（PLT-TEN-009）：修改在后端写 TENANT_UPDATE 审计。 */
export function updateTenant(tenantId: number, data: TenantUpdateRequest) {
  return requestClient.put<TenantDetail>(apiPath(`/tenants/${tenantId}`), data);
}

/**
 * 新建租户向导提交（E02-T07）：走完即触发开通编排。失败重试即以相同参数重提交（编排可重入）。
 */
export async function createTenant(data: CreateTenantRequest) {
  return requestClient.post<TenantOnboardingResult>(apiPath('/tenants'), data);
}

/** 开通进度（E02-T08 / PLT-TEN-008）：租户状态与逐步结果。 */
export function provisionOf(tenantId: number) {
  return requestClient.get<TenantProvisionView>(
    apiPath(`/tenants/${tenantId}/provision`),
  );
}

/** 从失败步骤重试开通（可重入；不重复建管理员）。 */
export function retryProvision(tenantId: number) {
  return requestClient.post<TenantOnboardingResult>(
    apiPath(`/tenants/${tenantId}/retry`),
  );
}

/** 放弃回收：仅开通未完成的租户（正式注销是 PLT-TEN-020）。 */
export function recycleTenant(tenantId: number) {
  return requestClient.delete(apiPath(`/tenants/${tenantId}`));
}
