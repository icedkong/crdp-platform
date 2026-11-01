/**
 * E08-T04 / PLT-AUD-005、006、007、009：平台端审计中心接口（docs/api/audit.md §3）。
 * 查看与验链结果 platform:audit:view；申请导出与下载 platform:audit:export；
 * 审批 platform:audit:export:approve（平台管理员，申请人不能自审）。
 */
import type {
  AuditChainCheckView,
  AuditDetailView,
  AuditExportJobPage,
  AuditExportRequest,
  AuditExportSubmitted,
  AuditPage,
  AuditSearchRequest,
} from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, requestClient } from '#/api/request';

/** 日志查询：type = operation | login | tenant-view | view-rule | control-plane，可按租户筛选。 */
export function searchPlatformAuditLogs(
  type: string,
  condition: AuditSearchRequest,
) {
  return requestClient.post<AuditPage>(
    apiPath(`/audit/${type}/search`),
    condition,
  );
}

/** 日志详情：前后值对比 + 同 traceId 关联。 */
export function platformAuditDetail(type: string, id: number) {
  return requestClient.get<AuditDetailView>(apiPath(`/audit/${type}/${id}`));
}

/** 最近一次哈希链校验结果（PLT-AUD-009）；tenantId 为空表示平台库自身。 */
export function platformAuditIntegrity(tenantId?: number) {
  return requestClient.get<AuditChainCheckView[]>(apiPath('/audit/integrity'), {
    params: tenantId === undefined ? {} : { tenantId },
  });
}

/** 提交平台审计导出：一律待平台管理员审批（PLT-AUD-007 已确认）。 */
export function submitPlatformAuditExport(data: AuditExportRequest) {
  return requestClient.post<AuditExportSubmitted>(
    apiPath('/audit/exports'),
    data,
  );
}

/** 我的审计导出。 */
export function myPlatformAuditExports(pageNo = 1, pageSize = 20) {
  return requestClient.get<AuditExportJobPage>(apiPath('/audit/exports'), {
    params: { pageNo, pageSize },
  });
}

/** 待审批导出列表（platform:audit:export:approve）。 */
export function pendingPlatformExportApprovals(pageNo = 1, pageSize = 20) {
  return requestClient.get<AuditExportJobPage>(
    apiPath('/audit/export-approvals'),
    {
      params: { pageNo, pageSize },
    },
  );
}

/** 审批通过：申请人不能审批自己的申请（40901）。 */
export function approvePlatformExport(id: number, comment?: string) {
  return requestClient.post(apiPath(`/audit/export-approvals/${id}/approve`), {
    comment,
  });
}

/** 审批驳回（终态）。 */
export function rejectPlatformExport(id: number, comment?: string) {
  return requestClient.post(apiPath(`/audit/export-approvals/${id}/reject`), {
    comment,
  });
}

/** 下载导出文件（仅申请人，24 小时内有效）；响应是文件流，无统一信封。 */
export async function downloadPlatformAuditExport(
  id: number,
  fileName: string,
) {
  const token = useAccessStore().accessToken;
  const response = await fetch(apiPath(`/audit/exports/${id}/download`), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    throw new Error(`下载失败（${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName || `platform-audit-export-${id}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}
