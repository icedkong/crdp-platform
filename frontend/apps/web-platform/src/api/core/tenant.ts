import type { CreateTenantRequest, TenantOnboardingResult } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 新建租户向导提交（E02-T07）：走完即触发开通编排。失败重试即以相同参数重提交（编排可重入）。
 */
export async function createTenant(data: CreateTenantRequest) {
  return requestClient.post<TenantOnboardingResult>(apiPath('/tenants'), data);
}
