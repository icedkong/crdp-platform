import type { Me } from '@crdp/api';

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
