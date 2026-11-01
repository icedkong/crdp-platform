import type {
  Me,
  TenantUserCreated,
  TenantUserCreateRequest,
  TenantUserDetailView,
  TenantUserPage,
  TenantUserResetResult,
  TenantUserStatusRequest,
  TenantUserUpdateRequest,
  UserEffectivePermissionsView,
  UserImportReport,
  UserOptionView,
  WardOptionView,
} from '@crdp/api';

import type { UserInfo } from '@vben/types';

import { apiPath, requestClient } from '#/api/request';

export function mapMe(me: Me): UserInfo & {
  mustChangePassword?: boolean;
  passwordExpiresInDays?: null | number;
  permissionVersion: number;
  tenantCode: null | string;
  tenantName: null | string;
} {
  return {
    avatar: '',
    desc: me.tenantName ?? '',
    homePath: '/dashboard',
    mustChangePassword: me.mustChangePassword === true,
    passwordExpiresInDays: me.passwordExpiresInDays ?? null,
    permissionVersion: me.permissionVersion,
    realName: me.displayName,
    roles: me.roles ?? [],
    // 会话令牌只放在 accessStore，不复制进用户资料。
    tenantCode: me.tenantCode,
    tenantName: me.tenantName,
    token: '',
    userId: String(me.userId),
    username: me.username,
  };
}

export async function getUserInfoApi() {
  const me = await requestClient.get<Me>(apiPath('/auth/me'));
  return mapMe(me);
}

/**
 * E06-T07 / PLT-ORG-006、008、A-WARD-002、C-WARD-003：医院端用户管理接口。
 * 契约见 docs/api/system.md §2；查看 tenant:user:view，维护 tenant:user:edit，
 * 重置密码与解锁 tenant:user:reset（解锁为 E04-T05 落地接口）。
 */

/** 用户列表查询条件（PLT-ORG-006「按科室与状态筛选」；病区 / 角色供跳入场景）。 */
export interface TenantUserQuery {
  deptId?: number;
  keyword?: string;
  pageNo?: number;
  pageSize?: number;
  roleId?: number;
  status?: string;
  wardId?: number;
}

/** 分页列表：手机号脱敏；pageNo 从 1 开始，默认 20 条。 */
export function listUsers(query: TenantUserQuery) {
  return requestClient.get<TenantUserPage>(apiPath('/system/users'), {
    params: Object.fromEntries(
      Object.entries(query).filter(
        ([, value]) => value !== undefined && value !== '',
      ),
    ),
  });
}

/** 用户详情（编辑回显）：含 roleIds、wardIds（负责病区）、deptScopeIds（负责科室）。 */
export function userDetail(userId: number) {
  return requestClient.get<TenantUserDetailView>(
    apiPath(`/system/users/${userId}`),
  );
}

/** 新建用户：账号 / 手机号本院唯一（40902）；初始密码只随本次响应下发一次。 */
export function createUser(data: TenantUserCreateRequest) {
  return requestClient.post<TenantUserCreated>(apiPath('/system/users'), data);
}

/** 编辑用户：主科室、角色、负责病区 / 负责科室多选全量覆盖，立即生效（权限版本递增）。 */
export function updateUser(userId: number, data: TenantUserUpdateRequest) {
  return requestClient.put<TenantUserDetailView>(
    apiPath(`/system/users/${userId}`),
    data,
  );
}

/** 停用 / 启用 / 离职（离职为终态）；停用与离职立即下线全部会话。 */
export function changeUserStatus(
  userId: number,
  data: TenantUserStatusRequest,
) {
  return requestClient.post<TenantUserDetailView>(
    apiPath(`/system/users/${userId}/status`),
    data,
  );
}

/** 重置密码：一次性临时密码（响应只返回一次），首登强制改密，全部会话失效并解锁。敏感操作：需二次认证（X-Reauth-Token，E04-T08）。 */
export function resetUserPassword(
  userId: number,
  headers?: Record<string, string>,
) {
  return requestClient.post<TenantUserResetResult>(
    apiPath(`/system/users/${userId}/reset-password`),
    {},
    { headers },
  );
}

/** 解锁登录锁定（连续失败 5 次锁 30 分钟，管理员可提前解锁；E04-T05 接口）。 */
export function unlockUser(userId: number) {
  return requestClient.post<{
    unlocked: boolean;
    userId: number;
    username: string;
  }>(apiPath(`/system/users/${userId}/unlock`), {});
}

/** 病区候选（「负责病区」多选，只读启用项；病区字典维护属病区管理任务）。 */
export function wardOptions() {
  return requestClient.get<WardOptionView[]>(apiPath('/system/wards/options'));
}

/**
 * 用户权限预览（E06-T09 / PLT-ORG-009、A-WARD-009、C-WARD-008）：最终菜单树（含按钮与
 * 来源角色）、权限码与数据范围（范围类型 / 可见病区 / 负责科室）；与登录会话同一计算入口。
 */
export function effectivePermissions(userId: number) {
  return requestClient.get<UserEffectivePermissionsView>(
    apiPath(`/system/users/${userId}/effective-permissions`),
  );
}

/** 用户选择器查询条件（E06-T09 / PLT-ORG-012）：keyword 命中账号 / 姓名 / 工号。 */
export interface UserOptionQuery {
  deptId?: number;
  keyword?: string;
}

/**
 * 用户选择器候选（E06-T09 / PLT-ORG-012）：本院启用用户，登录即可调用——业务模块
 * 选责任人 / 研究成员共用（原组织接口 /orgs/user-options 为暂行实现，本任务起替换）。
 */
export function userOptions(query: UserOptionQuery = {}) {
  return requestClient.get<UserOptionView[]>(apiPath('/system/users/options'), {
    params: Object.fromEntries(
      Object.entries(query).filter(
        ([, value]) => value !== undefined && value !== '',
      ),
    ),
  });
}

/**
 * 下载用户导入模板（E06-T08 / PLT-ORG-007）：xlsx 单工作表，表头 + 两行示例（全部虚构数据）。
 * 浏览器端触发保存（Content-Disposition 的 UTF-8 文件名）。
 */
export async function downloadUserImportTemplate() {
  const response = await requestClient.download(
    apiPath('/system/users/import-template'),
    { responseReturn: 'raw' },
  );
  const disposition = String(response.headers?.['content-disposition'] ?? '');
  const encoded = /filename\*=UTF-8''([^;]+)/.exec(disposition)?.[1];
  const filename = encoded ? decodeURIComponent(encoded) : '用户导入模板.xlsx';
  const blob = response.data as Blob;
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/**
 * 用户批量导入（E06-T08 / PLT-ORG-007）：上传 xlsx，逐行校验并新建，单行失败不影响
 * 其他行；返回逐行结果（CREATED / FAILED 与原因）。只新建不更新既有用户；初始密码
 * 不随导入返回（首次短信登录后设置）。权限 tenant:user:import。
 */
export function importUsers(file: File) {
  const form = new FormData();
  form.append('file', file);
  return requestClient.post<UserImportReport>(
    apiPath('/system/users/import'),
    form,
  );
}
