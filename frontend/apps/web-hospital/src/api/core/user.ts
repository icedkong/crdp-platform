import type {
  Me,
  TenantUserCreated,
  TenantUserCreateRequest,
  TenantUserDetailView,
  TenantUserPage,
  TenantUserResetResult,
  TenantUserStatusRequest,
  TenantUserUpdateRequest,
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

/** 重置密码：一次性临时密码（响应只返回一次），首登强制改密，全部会话失效并解锁。 */
export function resetUserPassword(userId: number) {
  return requestClient.post<TenantUserResetResult>(
    apiPath(`/system/users/${userId}/reset-password`),
    {},
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
