import type {
  CreateTenantRequest,
  PageView,
  PlatformResponsibleOwner,
  TenantDetail,
  TenantExpireRequest,
  TenantExpireResult,
  TenantListItem,
  TenantListQuery,
  TenantOnboardingResult,
  TenantProvisionView,
  TenantQuotaUpdateRequest,
  TenantQuotaView,
  TenantStatusAction,
  TenantStatusImpact,
  TenantStatusResult,
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

/** 状态变更影响面（E05-T02 / PLT-TEN-010）：在线用户数、进行中任务数，供二次确认对话框展示。 */
export function tenantStatusImpact(tenantId: number) {
  return requestClient.get<TenantStatusImpact>(
    apiPath(`/tenants/${tenantId}/status/impact`),
  );
}

/**
 * 状态变更（PLT-TEN-010）：ENABLE 转正（试用→启用）/ 恢复（停用→启用），DISABLE 手动停用。
 * 非法流转后端 40901；人工变更必须填原因并写 TENANT_STATUS 审计。
 */
export function changeTenantStatus(
  tenantId: number,
  action: TenantStatusAction,
  reason: string,
) {
  return requestClient.post<TenantStatusResult>(
    apiPath(`/tenants/${tenantId}/status`),
    { action, reason },
  );
}

/**
 * 到期设置与续期（PLT-TEN-011、013）：改到期日或转长期有效；宽限期 / 停用中的租户续期后
 * 立即恢复启用；省略的提醒节点、宽限期沿用当前配置。
 */
export function applyTenantExpiry(tenantId: number, data: TenantExpireRequest) {
  return requestClient.put<TenantExpireResult>(
    apiPath(`/tenants/${tenantId}/expire`),
    data,
  );
}

/** 按租户反查负责人（PLT-VIEW-006，E20-T01）：负责该租户的平台账号。 */
export function listResponsibleOwners(tenantId: number) {
  return requestClient.get<PlatformResponsibleOwner[]>(
    apiPath(`/tenants/${tenantId}/responsible-owners`),
  );
}

/**
 * 配额视图（E05-T09 / PLT-TEN-014）：上限（null=不限）与实时用量（启用中用户数、存储字节）；
 * 用量达 80% 预警、达到上限即满额；租户库不可达时用量为空。
 */
export function tenantQuota(tenantId: number) {
  return requestClient.get<TenantQuotaView>(
    apiPath(`/tenants/${tenantId}/quota`),
  );
}

/**
 * 维护配额（PLT-TEN-014）：用户数与存储上限，null = 不限（清除）；写 TENANT_QUOTA 审计。
 * 租户侧拦截：达用户数上限禁止新建 / 启用用户（PLT-ORG-010），达存储上限禁止上传。
 */
export function updateTenantQuota(
  tenantId: number,
  data: TenantQuotaUpdateRequest,
) {
  return requestClient.put<TenantQuotaView>(
    apiPath(`/tenants/${tenantId}/quota`),
    data,
  );
}
