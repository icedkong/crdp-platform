/**
 * E18-T06 / A-PAT-008、A-PAT-018：患者历史数据导入向导接口
 * （docs/api/import-export.md §1，后端 `ImportJobController`）。权限
 * followup:patient:import（隐藏路由 /followup/patient/import，plt_menu 353）；
 * 功能开关 wide_import 关闭时后端 40301（前端按通用错误处理，AGENTS.md 规则 7）。
 */
import type {
  ImportConfirmRequest,
  ImportIssuePage,
  ImportJobView,
} from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, requestClient } from '#/api/request';

/** 下载导入模板（type=STANDARD 长表 / WIDE 宽表）；响应是文件流，无统一信封。 */
export async function downloadImportTemplate(type: 'STANDARD' | 'WIDE') {
  await downloadByFetch(
    apiPath(`/followup/imports/template?type=${type}`),
    type === 'WIDE'
      ? '患者导入模板（宽表）.xlsx'
      : '患者导入模板（基线+随访记录）.xlsx',
  );
}

/** 上传导入文件（multipart 字段 file）→ { jobId }，预检异步开始（进度轮询 fetchImportJob）。 */
export function uploadImportFile(file: File) {
  const form = new FormData();
  form.append('file', file);
  // Content-Type 由浏览器按 FormData 自动带 boundary（与科室 / 用户导入同口径）
  return requestClient.post<{ jobId: number }>(
    apiPath('/followup/imports'),
    form,
  );
}

/** 作业状态与进度（轮询：stagedRows 预检进度、batches 提交批次进度与统计）。 */
export function fetchImportJob(jobId: number) {
  return requestClient.get<ImportJobView>(
    apiPath(`/followup/imports/${jobId}`),
  );
}

/** 预检问题分页（level=ERROR|WARN 可空）。 */
export function fetchImportIssues(
  jobId: number,
  level?: 'ERROR' | 'WARN' | null,
  pageNo = 1,
  pageSize = 20,
) {
  const params: Record<string, unknown> = { pageNo, pageSize };
  if (level) {
    params.level = level;
  }
  return requestClient.get<ImportIssuePage>(
    apiPath(`/followup/imports/${jobId}/issues`),
    { params },
  );
}

/** 确认提交（{ duplicateStrategy: UPDATE|SKIP, defaultWardId? }）；有 ERROR 问题后端 40001。 */
export function confirmImport(jobId: number, data: ImportConfirmRequest) {
  return requestClient.post<{ jobId: number }>(
    apiPath(`/followup/imports/${jobId}/confirm`),
    data,
  );
}

/** 取消作业（只允许确认前）。 */
export function cancelImport(jobId: number) {
  return requestClient.post<ImportJobView>(
    apiPath(`/followup/imports/${jobId}/cancel`),
  );
}

/** 重试失败批次（PARTIAL_FAILED → IMPORTING，只重跑 FAILED 批）。 */
export function retryImport(jobId: number) {
  return requestClient.post<{ jobId: number }>(
    apiPath(`/followup/imports/${jobId}/retry`),
  );
}

/** 回滚整个作业（全部已完成批次）。 */
export function rollbackImport(jobId: number) {
  return requestClient.post<unknown[]>(
    apiPath(`/followup/imports/${jobId}/rollback`),
  );
}

/** 回滚一批（只允许已完成批次；失败批次无写数，用重试）。 */
export function rollbackImportBatch(jobId: number, batchNo: number) {
  return requestClient.post<unknown>(
    apiPath(`/followup/imports/${jobId}/batches/${batchNo}/rollback`),
  );
}

/** 下载批注版校验报告（错误 / 警告单元格标色加批注 + 问题清单表）；响应是文件流。 */
export async function downloadImportReport(jobId: number) {
  await downloadByFetch(
    apiPath(`/followup/imports/${jobId}/result`),
    `导入校验报告-作业${jobId}.xlsx`,
  );
}

/** 带令牌的文件流下载（与患者导出下载同口径：blob → a[download]）。 */
async function downloadByFetch(url: string, fileName: string) {
  const token = useAccessStore().accessToken;
  const response = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  if (!response.ok) {
    throw new Error(`下载失败（${response.status}）`);
  }
  const blob = await response.blob();
  const objectUrl = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = objectUrl;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(objectUrl);
}
