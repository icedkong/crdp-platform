/**
 * 后端枚举到中文的映射（components.md §2：StatusTag 的文案来源）。未知值原样返回。
 */

/** 租户状态（PLT-TEN-010 七态 + 开通中初始态）。 */
export const TENANT_STATUS_TEXT: Record<string, string> = {
  PROVISIONING: '开通中',
  TRIAL: '试用',
  ENABLED: '启用',
  GRACE: '宽限期',
  DISABLED: '停用',
  CANCELLING: '注销中',
  CANCELLED: '已注销',
};

/** 列表筛选下拉用（顺序即展示顺序）。 */
export const TENANT_STATUS_OPTIONS: Array<{ label: string; value: string }> =
  Object.entries(TENANT_STATUS_TEXT).map(([value, label]) => ({
    label,
    value,
  }));

export function tenantStatusText(status: string): string {
  return TENANT_STATUS_TEXT[status] ?? status;
}

/** 到期类型：FIXED 有到期日；LONG_TERM 长期有效（页面显示「长期」）。 */
export const EXPIRE_TYPE_TEXT: Record<string, string> = {
  FIXED: '有到期日',
  LONG_TERM: '长期有效',
};

/** 患者随访状态（components.md §2 patient 域；pt_patient.followup_status）。 */
export const PATIENT_STATUS_TEXT: Record<string, string> = {
  ACTIVE: '随访中',
  PAUSED: '已暂停',
  DEAD: '死亡',
  TERMINATED: '停止',
  LOST: '失访',
  FINISHED: '随访结束',
};

/** 生存状态（pt_patient_summary.survival_status，最近已提交记录；E15 写入）。 */
export const SURVIVAL_STATUS_TEXT: Record<string, string> = {
  ALIVE: '存活',
  DEAD: '死亡',
  UNKNOWN: '不详',
};

export function survivalStatusText(status: null | string | undefined): string {
  if (status === null || status === undefined || status === '') return '—';
  return SURVIVAL_STATUS_TEXT[status] ?? status;
}

/** 模块上架状态（PLT-MOD-001）：下架后不能再被新开通。 */
export const MODULE_SHELF_TEXT: Record<string, string> = {
  ON: '上架',
  OFF: '下架',
};

/** 维护对话框里的上下架选项（顺序即展示顺序）。 */
export const MODULE_SHELF_OPTIONS: Array<{ label: string; value: string }> =
  Object.entries(MODULE_SHELF_TEXT).map(([value, label]) => ({
    label,
    value,
  }));

export function moduleShelfText(status: string): string {
  return MODULE_SHELF_TEXT[status] ?? status;
}

/** 租户模块开通状态（PLT-MOD-004、005）：未开通（null）单独口径。 */
export const TENANT_MODULE_STATUS_TEXT: Record<string, string> = {
  OPENING: '开通中',
  OPEN: '已开通',
  OPEN_FAILED: '开通失败',
  MAINTENANCE: '维护中',
  CLOSED: '已关闭',
};

export const TENANT_MODULE_NOT_OPENED_TEXT = '未开通';

export function tenantModuleStatusText(status: null | string): string {
  if (status === null || status === '') {
    return TENANT_MODULE_NOT_OPENED_TEXT;
  }
  return TENANT_MODULE_STATUS_TEXT[status] ?? status;
}

/** 开通记录（PLT-MOD-009）：动作 → 展示文案。 */
export const MODULE_LOG_ACTION_TEXT: Record<string, string> = {
  OPEN: '开通',
  CLOSE: '关闭',
  UPGRADE: '升级',
};

export function moduleLogActionText(action: string): string {
  return MODULE_LOG_ACTION_TEXT[action] ?? action;
}

/** 注册同步结果（PLT-MOD-002）：动作 → 展示文案。 */
export const MODULE_SYNC_ACTION_TEXT: Record<string, string> = {
  REGISTERED: '新注册',
  UPDATED: '已更新',
  UNCHANGED: '无变化',
};

export function moduleSyncActionText(action: string): string {
  return MODULE_SYNC_ACTION_TEXT[action] ?? action;
}
