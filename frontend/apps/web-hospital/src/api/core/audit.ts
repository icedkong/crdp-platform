/**
 * E08-T04 / PLT-AUD-005、006、007：医院端审计中心接口（docs/api/audit.md §2）。
 * 查看与患者页签 tenant:audit:view；申请导出与下载 tenant:audit:export（敏感）；
 * 审批 tenant:audit:export:approve（租户管理员）。租户侧不含平台访问记录。
 */
import type {
  AuditDetailView,
  AuditExportJobPage,
  AuditExportRequest,
  AuditExportSubmitted,
  AuditPage,
  AuditSearchRequest,
} from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, requestClient } from '#/api/request';

/** 日志查询（PLT-AUD-005）：type = operation | login | data-access。 */
export function searchAuditLogs(type: string, condition: AuditSearchRequest) {
  return requestClient.post<AuditPage>(
    apiPath(`/system/audit/${type}/search`),
    condition,
  );
}

/** 日志详情（PLT-AUD-006）：前后值对比 + 同 traceId 关联。 */
export function auditDetail(type: string, id: number) {
  return requestClient.get<AuditDetailView>(
    apiPath(`/system/audit/${type}/${id}`),
  );
}

/** 患者「操作日志」页签（audit.md §2）。 */
export function patientLogs(patientId: number, pageNo = 1, pageSize = 20) {
  return requestClient.get<AuditPage>(
    apiPath(`/followup/patients/${patientId}/logs`),
    { params: { pageNo, pageSize } },
  );
}

/** 提交审计导出（PLT-AUD-007）：租户管理员免审批，普通用户待审批。敏感操作：需二次认证（X-Reauth-Token，E04-T08）。 */
export function submitAuditExport(
  data: AuditExportRequest,
  headers?: Record<string, string>,
) {
  return requestClient.post<AuditExportSubmitted>(
    apiPath('/system/audit/exports'),
    data,
    { headers },
  );
}

/** 我的审计导出。 */
export function myAuditExports(pageNo = 1, pageSize = 20) {
  return requestClient.get<AuditExportJobPage>(
    apiPath('/system/audit/exports'),
    {
      params: { pageNo, pageSize },
    },
  );
}

/** 待审批导出列表（租户管理员）。 */
export function pendingExportApprovals(pageNo = 1, pageSize = 20) {
  return requestClient.get<AuditExportJobPage>(
    apiPath('/system/export-approvals'),
    {
      params: { pageNo, pageSize },
    },
  );
}

/** 审批通过：PENDING_APPROVAL → GENERATING。 */
export function approveExport(id: number, comment?: string) {
  return requestClient.post(apiPath(`/system/export-approvals/${id}/approve`), {
    comment,
  });
}

/** 审批驳回（终态）。 */
export function rejectExport(id: number, comment?: string) {
  return requestClient.post(apiPath(`/system/export-approvals/${id}/reject`), {
    comment,
  });
}

/** 下载导出文件（仅申请人，24 小时内有效）；鉴权头手工附加（响应是文件流，无统一信封）。敏感操作：需二次认证（E04-T08）。 */
export async function downloadAuditExport(
  id: number,
  fileName: string,
  headers: Record<string, string> = {},
) {
  const token = useAccessStore().accessToken;
  const response = await fetch(
    apiPath(`/system/audit/exports/${id}/download`),
    {
      headers: {
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
    },
  );
  if (!response.ok) {
    throw new Error(`下载失败（${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName || `audit-export-${id}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}
