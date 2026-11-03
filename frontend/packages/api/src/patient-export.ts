/**
 * E13-T09 / A-PAT-009、A-PAT-012、C-PAT-014、C-PAT-015：患者导出（审批、水印码）与
 * 删除患者（软删除）的类型与纯逻辑（接口契约 docs/api/import-export.md §3、
 * docs/api/patient.md §2，后端 `PatientExportController` / `PatientDeletionController`）。
 *
 * 导出走 E08-T04 的导出作业框架：租户管理员免审批、普通用户待租户管理员审批、
 * 生成后 24 小时内可下载；导出条件 = 列表当前筛选 + 高级检索条件树（不含检索词——
 * 检索词可能是患者姓名，与保存的筛选同口径不落库）；姓名 / 病案号在文件中为脱敏值。
 */
import type { ConditionGroup } from './advanced-search';
import type { PatientFilterState } from './patient-search';

/** 导出范围：本任务只落 PATIENT（患者列表列）；其余随后续任务开放。 */
export type PatientExportScope = 'PATIENT';

/** 值模式：CODE = 库内编码，LABEL = 中文显示名（A-PAT-009「可选编码或显示名」）。 */
export type PatientExportValueMode = 'CODE' | 'LABEL';

/** 导出条件（与列表检索同构，filters 结构同 README §2.4；不含检索词 keyword）。 */
export interface PatientExportCondition {
  condition?: ConditionGroup;
  filters?: Record<string, unknown>;
}

/** 提交导出请求（POST /api/hospital/followup/exports）。 */
export interface PatientExportRequest {
  condition: PatientExportCondition;
  fields?: string[];
  reason?: string;
  scope: PatientExportScope;
  valueMode: PatientExportValueMode;
}

/** 提交结果：GENERATING = 免审批或已过审并开始生成；PENDING_APPROVAL = 待租户管理员审批。 */
export interface PatientExportSubmitted {
  exportId: number;
  status: string;
}

/** 导出作业行（我的导出 / 待审批列表共用；后端复用审计导出视图结构）。 */
export interface PatientExportJob {
  applicantDisplayName?: null | string;
  applicantName: string;
  approveComment?: null | string;
  approverName?: null | string;
  conditionSummary?: null | string;
  createdAt: string;
  expiresAt?: null | string;
  failReason?: null | string;
  fileHash?: null | string;
  fileName?: null | string;
  id: number;
  reason?: null | string;
  rowCount?: null | number;
  status: string;
}

/** 导出作业分页。 */
export interface PatientExportJobPage {
  items: PatientExportJob[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 删除原因上限（A-PAT-012 软删除原因必填；后端口径 255）。 */
export const PATIENT_DELETE_REASON_MAX = 255;

/** 申请理由上限（导出 / 审批意见共用后端口径 200）。 */
export const PATIENT_EXPORT_REASON_MAX = 200;

/** 导出状态文案 / 颜色复用审计导出的 job_export 状态机（audit.ts jobExportStatusText / jobExportStatusColor）。 */

/** 值模式选项（A-PAT-009）。 */
export const PATIENT_EXPORT_VALUE_MODE_OPTIONS: Array<{
  label: string;
  value: PatientExportValueMode;
}> = [
  { label: '显示名', value: 'LABEL' },
  { label: '编码', value: 'CODE' },
];

/** 构建提交导出的请求体（列表筛选 + 高级检索条件树；检索词不进导出条件）。 */
export function buildPatientExportPayload(
  filters: PatientFilterState,
  advanced: ConditionGroup | null,
  valueMode: PatientExportValueMode,
  reason: string,
): PatientExportRequest {
  const condition: PatientExportCondition = {};
  const filtersPayload: Record<string, unknown> = {};
  if (filters.wardId !== null && filters.wardId !== undefined) {
    filtersPayload.wardIds = [filters.wardId];
  }
  if (filters.status) {
    filtersPayload.status = [filters.status];
  }
  if (filters.dischargeRange?.[0] && filters.dischargeRange?.[1]) {
    filtersPayload.dischargeFrom = filters.dischargeRange[0];
    filtersPayload.dischargeTo = filters.dischargeRange[1];
  }
  if (filters.nextVisitRange?.[0] && filters.nextVisitRange?.[1]) {
    filtersPayload.nextVisitFrom = filters.nextVisitRange[0];
    filtersPayload.nextVisitTo = filters.nextVisitRange[1];
  }
  if (Object.keys(filtersPayload).length > 0) {
    condition.filters = filtersPayload;
  }
  if (advanced && advanced.items.length > 0) {
    condition.condition = advanced;
  }
  return {
    condition,
    reason: reason.trim() || undefined,
    scope: 'PATIENT',
    valueMode,
  };
}

/** 校验导出表单：返回错误消息（可提交时为 null）。 */
export function validatePatientExportForm(reason: string): null | string {
  const trimmed = reason.trim();
  if (trimmed.length > PATIENT_EXPORT_REASON_MAX) {
    return `申请理由最多 ${PATIENT_EXPORT_REASON_MAX} 个字`;
  }
  return null;
}

/** 校验删除原因（A-PAT-012 必填）：返回错误消息（可通过时为 null）。 */
export function validatePatientDeleteReason(reason: string): null | string {
  const trimmed = reason.trim();
  if (!trimmed) {
    return '请填写删除原因';
  }
  if (trimmed.length > PATIENT_DELETE_REASON_MAX) {
    return `删除原因最多 ${PATIENT_DELETE_REASON_MAX} 个字符`;
  }
  return null;
}
