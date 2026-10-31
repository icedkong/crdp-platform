import type {
  CreateTenantRequest,
  TenantOnboardingResult,
  TenantProvisionView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

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
