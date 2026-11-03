/**
 * E13-T09 / A-PAT-009、A-PAT-012：患者导出（审批、水印码）与删除患者（软删除）接口
 * （docs/api/import-export.md §3、patient.md §2）。申请与下载 followup:patient:export
 * （敏感，需二次认证 X-Reauth-Token，E04-T08）；审批 followup:patient:export:approve
 * （租户管理员）；删除 / 恢复 followup:patient:delete（敏感，需二次认证）。
 */
import type {
  PatientExportJobPage,
  PatientExportRequest,
  PatientExportSubmitted,
} from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, requestClient } from '#/api/request';

/** 提交患者导出：租户管理员免审批，普通用户待租户管理员审批。敏感操作：需二次认证。 */
export function submitPatientExport(
  data: PatientExportRequest,
  headers?: Record<string, string>,
) {
  return requestClient.post<PatientExportSubmitted>(
    apiPath('/followup/exports'),
    data,
    { headers },
  );
}

/** 我的导出（含状态：待审批 / 生成中 / 可下载 / 已驳回 / 失败）。 */
export function myPatientExports(pageNo = 1, pageSize = 20) {
  return requestClient.get<PatientExportJobPage>(apiPath('/followup/exports'), {
    params: { pageNo, pageSize },
  });
}

/** 待审批患者导出列表（租户管理员）。 */
export function pendingPatientExportApprovals(pageNo = 1, pageSize = 20) {
  return requestClient.get<PatientExportJobPage>(
    apiPath('/followup/export-approvals'),
    { params: { pageNo, pageSize } },
  );
}

/** 审批通过：PENDING_APPROVAL → GENERATING，异步生成文件。 */
export function approvePatientExport(id: number, comment?: string) {
  return requestClient.post(
    apiPath(`/followup/export-approvals/${id}/approve`),
    { comment },
  );
}

/** 审批驳回（终态）。 */
export function rejectPatientExport(id: number, comment?: string) {
  return requestClient.post(
    apiPath(`/followup/export-approvals/${id}/reject`),
    { comment },
  );
}

/** 下载导出文件（仅申请人，24 小时内有效）；响应是文件流，无统一信封。敏感操作：需二次认证。 */
export async function downloadPatientExport(
  id: number,
  fileName: string,
  headers: Record<string, string> = {},
) {
  const token = useAccessStore().accessToken;
  const response = await fetch(apiPath(`/followup/exports/${id}/download`), {
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
  });
  if (!response.ok) {
    throw new Error(`下载失败（${response.status}）`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName || `patient-export-${id}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 软删除患者（A-PAT-012）：{ reason } 必填，数据长期保留（A-NFR-008 只软删）。敏感操作：需二次认证。 */
export function deletePatient(
  patientId: number | string,
  reason: string,
  headers?: Record<string, string>,
) {
  return requestClient.delete(apiPath(`/followup/patients/${patientId}`), {
    data: { reason },
    headers,
  });
}

/** 恢复已软删患者（A-PAT-012「可由租户管理员恢复」）。敏感操作：需二次认证。 */
export function restorePatient(
  patientId: number | string,
  headers?: Record<string, string>,
) {
  return requestClient.post(
    apiPath(`/followup/patients/${patientId}/restore`),
    undefined,
    { headers },
  );
}
