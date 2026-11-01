/**
 * 接口资源页的纯逻辑（E05-T06 / PLT-PERM-004）：未绑定高亮判定、筛选模型、标记表单校验。
 * 校验口径与后端 ApiResourceService 一致。
 */
import type { ApiMarkRequest, ApiResourceItem } from '@crdp/api';

import {
  apiActionTypeText,
  apiAuditLevelText,
  apiSensitiveLevelText,
} from '@crdp/api';

/** 未绑定权限码且非公共：默认拒绝口径下的「待处理」行，页面高亮（pages.md §11 接口资源）。 */
export function isUnbound(item: ApiResourceItem): boolean {
  return !item.publicAccess && (item.permCode === null || item.permCode === '');
}

/** 前端筛选（与后端过滤同口径；模块 / 权限码 / 只看未绑定）。 */
export function filterApiItems(
  items: ApiResourceItem[],
  filters: { moduleCode: string; permCode: string; unbound: boolean },
): ApiResourceItem[] {
  return items.filter((item) => {
    if (filters.moduleCode && item.moduleCode !== filters.moduleCode)
      return false;
    if (filters.permCode && item.permCode !== filters.permCode) return false;
    if (filters.unbound && !isUnbound(item)) return false;
    return true;
  });
}

/** 模块选项（从数据推导，保持目录顺序）。 */
export function moduleOptions(
  items: ApiResourceItem[],
): Array<{ label: string; value: string }> {
  const codes = [...new Set(items.map((item) => item.moduleCode))];
  return codes.map((code) => ({ label: code, value: code }));
}

/** 标记表单模型。 */
export interface ApiMarkModel {
  actionType: ApiResourceItem['actionType'];
  auditLevel: ApiResourceItem['auditLevel'];
  permCode: string;
  publicAccess: boolean;
  sensitiveLevel: ApiResourceItem['sensitiveLevel'];
}

export function toMarkModel(item: ApiResourceItem): ApiMarkModel {
  return {
    actionType: item.actionType,
    auditLevel: item.auditLevel,
    permCode: item.permCode ?? '',
    publicAccess: item.publicAccess,
    sensitiveLevel: item.sensitiveLevel,
  };
}

export function toMarkRequest(model: ApiMarkModel): ApiMarkRequest {
  const trimmed = model.permCode.trim();
  return {
    actionType: model.actionType,
    auditLevel: model.auditLevel,
    permCode: trimmed === '' ? null : trimmed,
    publicAccess: model.publicAccess,
    sensitiveLevel: model.sensitiveLevel,
  };
}

/** 标记校验：未绑定权限码又非公共不允许保存（platform-spec §4 默认拒绝口径）。 */
export function validateMark(model: ApiMarkModel): string[] {
  const errors: string[] = [];
  const permCode = model.permCode.trim();
  if (
    permCode !== '' &&
    !/^[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*){2}$/.test(permCode)
  ) {
    errors.push('权限码格式 {模块}:{资源}:{动作}，如 followup:task:view');
  }
  if (permCode === '' && !model.publicAccess) {
    errors.push('未绑定权限码的接口必须标记为公共接口（否则一律不可访问）');
  }
  return errors;
}

export const ACTION_TYPE_OPTIONS = (
  ['QUERY', 'WRITE', 'EXPORT', 'DOWNLOAD'] as const
).map((value) => ({ label: apiActionTypeText(value), value }));

export const AUDIT_LEVEL_OPTIONS = (
  ['NONE', 'OPERATION', 'DATA_ACCESS'] as const
).map((value) => ({ label: apiAuditLevelText(value), value }));

export const SENSITIVE_LEVEL_OPTIONS = (['NORMAL', 'SENSITIVE'] as const).map(
  (value) => ({
    label: apiSensitiveLevelText(value),
    value,
  }),
);
