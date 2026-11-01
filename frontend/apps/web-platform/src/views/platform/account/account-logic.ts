/**
 * 平台账号页的纯逻辑（E06-T04 / PLT-ROLE-001）：表单模型与提交请求、校验、
 * 初始密码客户提示（临时密码只展示一次）。
 */
import type { PlatformAccountView, PlatformRoleView } from '@crdp/api';

import { platformAccountStatusText, validatePlatformAccount } from '@crdp/api';

export { platformAccountStatusText };

export type { PlatformAccountView };

/** 编辑表单模型（新建模式 username/password 可填；维护模式不回填密码）。 */
export interface AccountEditModel {
  displayName: string;
  password: string;
  phone: string;
  roleIds: number[];
  status: 'DISABLED' | 'ENABLED';
  username: string;
}

export function toAccountEditModel(
  account: null | PlatformAccountView,
  roleIds: number[],
): AccountEditModel {
  if (!account) {
    return {
      displayName: '',
      password: '',
      phone: '',
      roleIds: [],
      status: 'ENABLED',
      username: '',
    };
  }
  return {
    displayName: account.displayName,
    password: '',
    phone: '',
    roleIds,
    status: account.status,
    username: account.username,
  };
}

/** 表单 → 提交请求：维护模式不带 username / password（密码走改密 / 重置）。 */
export function toAccountSaveRequest(
  model: AccountEditModel,
  mode: 'create' | 'update',
): {
  displayName: string;
  password?: string;
  phone?: string;
  roleIds: number[];
  status?: 'DISABLED' | 'ENABLED';
  username?: string;
} {
  if (mode === 'create') {
    return {
      displayName: model.displayName,
      password: model.password,
      phone: model.phone.trim(),
      roleIds: model.roleIds,
      username: model.username.trim(),
    };
  }
  return {
    displayName: model.displayName,
    phone: model.phone.trim() || undefined,
    roleIds: model.roleIds,
    status: model.status,
  };
}

/** 保存校验（前端先行，与后端同口径）。 */
export function validateAccountModel(
  model: AccountEditModel,
  mode: 'create' | 'update',
): string[] {
  return validatePlatformAccount(model, mode);
}

/** 手机号列（后端只回脱敏值；空显示「—」）。 */
export function phoneText(account: PlatformAccountView): string {
  return account.phoneMasked ?? '—';
}

/** 角色列：角色名并集。 */
export function roleNamesText(account: PlatformAccountView): string {
  return account.roleNames.length === 0 ? '—' : account.roleNames.join('、');
}

/** 时间列（精确到分钟；空「—」）。 */
export function accountTimeText(value: null | string): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}

/** 角色选择项（只列启用角色；停用角色后端拒绝授予）。 */
export function selectableRoles(roles: PlatformRoleView[]): PlatformRoleView[] {
  return roles.filter((role) => role.status === 'ENABLED');
}

/** 重置密码结果的一次性提示文案（提示后由页面展示并要求确认已保存）。 */
export function resetResultLines(temporaryPassword: string): string[] {
  return [
    `临时密码：${temporaryPassword}`,
    '临时密码只显示这一次，请立即复制并交给本人。',
    '该账号的全部会话已失效，下次登录须用临时密码并强制修改密码。',
  ];
}
