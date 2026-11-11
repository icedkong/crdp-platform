/**
 * 租户注销与数据处理（E05-T08 / PLT-TEN-018..021）：申请与审批、冷静期撤销、
 * 迁出包生成 / 下载 / 签收、法定保留封存、销毁与证明。
 * 下载迁出包与销毁证明为敏感操作，经 ReauthModal 携带 X-Reauth-Token（E04-T08）。
 */
import type {
  TenantCancelApplyRequest,
  TenantCancelApproveRequest,
  TenantCancelFileView,
  TenantCancelOverview,
  TenantDestroyRequest,
  TenantRetentionSaveRequest,
  TenantRetentionView,
} from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, requestClient } from '#/api/request';

export interface CancelStageResultView {
  previousStage: string;
  stage: string;
  tenantCode: string;
  tenantId: number;
  tenantStatus: string;
}

export interface CancelApplyResultView {
  retentionItemCount: number;
  stage: string;
  tenantId: number;
}

export interface ExportGenerateResultView {
  expiresAt: string;
  exportId: number;
  fileCount: number;
  maxDownloads: number;
  packageNo: string;
  reissued: boolean;
  sizeBytes: number;
  tableCount: number;
  tenantId: number;
  totalRows: number;
}

/** 注销流程总览（页面四块：流程 + 保留清单 + 迁出包 + 证明）。 */
export function tenantCancelOverview(tenantId: number) {
  return requestClient.get<TenantCancelOverview>(
    apiPath(`/tenants/${tenantId}/cancel`),
  );
}

/** 发起注销申请：须先上传医院书面确认拿 fileId（PLT-TEN-018）。 */
export function applyTenantCancel(
  tenantId: number,
  data: TenantCancelApplyRequest,
) {
  return requestClient.post<CancelApplyResultView>(
    apiPath(`/tenants/${tenantId}/cancel`),
    data,
  );
}

/** 上传医院书面确认（PDF / JPG / PNG ≤ 20MB）。 */
export function uploadCancelConfirmation(
  tenantId: number,
  file: File,
): Promise<TenantCancelFileView> {
  return uploadCancelFile(tenantId, 'confirmation-file', file);
}

/** 上传迁出包签收单。 */
export function uploadCancelAck(
  tenantId: number,
  file: File,
): Promise<TenantCancelFileView> {
  return uploadCancelFile(tenantId, 'ack-file', file);
}

async function uploadCancelFile(
  tenantId: number,
  endpoint: 'ack-file' | 'confirmation-file',
  file: File,
): Promise<TenantCancelFileView> {
  const form = new FormData();
  form.append('file', file);
  return requestClient.post<TenantCancelFileView>(
    apiPath(`/tenants/${tenantId}/cancel/${endpoint}`),
    form,
    { headers: { 'Content-Type': 'multipart/form-data' } },
  );
}

/** 审批注销（PLT-TEN-018）：须由另一名平台管理员完成，发起人自审后端 40901。 */
export function approveTenantCancel(
  tenantId: number,
  data: TenantCancelApproveRequest,
) {
  return requestClient.post<CancelStageResultView>(
    apiPath(`/tenants/${tenantId}/cancel/approve`),
    data,
  );
}

/** 冷静期内撤销注销：租户回到停用。 */
export function revokeTenantCancel(tenantId: number, reason: string) {
  return requestClient.post<CancelStageResultView>(
    apiPath(`/tenants/${tenantId}/cancel/revoke`),
    { reason },
  );
}

/** 生成迁出包（PLT-TEN-019）：冷静期满后；过期 / 次数用完可重新生成。 */
export function generateTenantExport(tenantId: number) {
  return requestClient.post<ExportGenerateResultView>(
    apiPath(`/tenants/${tenantId}/cancel/exports`),
  );
}

/**
 * 下载迁出包（限时 7 天 / 5 次）：响应是加密容器文件流（无统一信封），
 * 须携带 X-Reauth-Token（E04-T08），经 ReauthModal.run 注入。
 */
export async function downloadTenantExport(
  tenantId: number,
  exportId: number,
  packageNo: string,
  headers: Record<string, string> = {},
) {
  const token = useAccessStore().accessToken;
  const response = await fetch(
    apiPath(`/tenants/${tenantId}/cancel/exports/${exportId}/download`),
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
  anchor.download = `${packageNo}.crdpa`;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 医院签收迁出包（平台账号代录）：说明必填，可附签收单。 */
export function ackTenantExport(
  tenantId: number,
  exportId: number,
  note: string,
  ackFileId?: number,
) {
  return requestClient.post(
    apiPath(`/tenants/${tenantId}/cancel/exports/${exportId}/ack`),
    { ackFileId, note },
  );
}

/** 保存法定保留清单（整体替换未封存项，PLT-TEN-021）。 */
export function saveTenantRetention(
  tenantId: number,
  data: TenantRetentionSaveRequest,
) {
  return requestClient.put<TenantRetentionView[]>(
    apiPath(`/tenants/${tenantId}/cancel/retention`),
    data,
  );
}

/** 封存保留数据：独立密码加密，登记保留期满日。 */
export function sealTenantRetention(tenantId: number, reason: string) {
  return requestClient.post(
    apiPath(`/tenants/${tenantId}/cancel/retention/seal`),
    { reason },
  );
}

/** 访问封存包（须两名平台管理员：操作人 + reviewerId 复核人；X-Reauth-Token）。 */
export async function downloadTenantRetentionSealed(
  tenantId: number,
  itemId: number,
  fileName: string,
  reviewerId: number,
  headers: Record<string, string> = {},
) {
  const token = useAccessStore().accessToken;
  const response = await fetch(
    apiPath(
      `/tenants/${tenantId}/cancel/retention/${itemId}/download?reviewerId=${reviewerId}`,
    ),
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
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 保留期满销毁（PLT-TEN-021）：补充出具销毁证明。 */
export function destroyTenantRetention(
  tenantId: number,
  itemId: number,
  data: TenantDestroyRequest,
) {
  return requestClient.post(
    apiPath(`/tenants/${tenantId}/cancel/retention/${itemId}/destroy`),
    data,
  );
}

/** 执行数据销毁（PLT-TEN-020）：双人复核 + 二次认证；出具销毁证明。 */
export function destroyTenantCancel(
  tenantId: number,
  data: TenantDestroyRequest,
  headers?: Record<string, string>,
) {
  return requestClient.post(
    apiPath(`/tenants/${tenantId}/cancel/destroy`),
    data,
    { headers },
  );
}

/** 下载销毁证明 PDF（X-Reauth-Token）。 */
export async function downloadTenantCertificate(
  tenantId: number,
  certificateId: number,
  certNo: string,
  headers: Record<string, string> = {},
) {
  const token = useAccessStore().accessToken;
  const response = await fetch(
    apiPath(
      `/tenants/${tenantId}/cancel/certificates/${certificateId}/download`,
    ),
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
  anchor.download = `销毁证明-${certNo}.pdf`;
  anchor.click();
  URL.revokeObjectURL(url);
}
