/**
 * E04-T03 / PLT-AUTH-005：平台端独立登录的表单定义。
 * 平台端没有租户上下文：登录只提交账号与密码，不携带 tenantCode 等医院端字段
 * （docs/design/pages.md §1；与后端 PlatformLoginRequest 校验一致）。
 */
import type { VbenFormSchema } from '@vben/common-ui';

import { z } from '@vben/common-ui';

/** 账号规则与后端 PlatformLoginRequest.username 一致：字母开头，2–64 位。 */
export const USERNAME_RULE = z
  .string()
  .regex(/^[a-zA-Z][a-zA-Z0-9._-]{1,63}$/, '账号格式不正确');

/** 密码规则与后端 PlatformLoginRequest.password 一致：8–64 位。 */
export const PASSWORD_RULE = z
  .string()
  .min(8, '密码至少 8 位')
  .max(64, '密码不超过 64 位');

export type PlatformLoginPayload = {
  password: string;
  username: string;
};

export function platformLoginSchema(): VbenFormSchema[] {
  return [
    {
      component: 'VbenInput',
      componentProps: {
        placeholder: '账号',
      },
      fieldName: 'username',
      label: '账号',
      rules: USERNAME_RULE,
    },
    {
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: '密码',
      },
      fieldName: 'password',
      label: '密码',
      rules: PASSWORD_RULE,
    },
  ];
}

/** 只取账号与密码；多余字段（含误传的 tenantCode）一律不进登录请求。 */
export function platformLoginPayload(
  values: Record<string, unknown>,
): PlatformLoginPayload {
  return {
    password: typeof values.password === 'string' ? values.password : '',
    username: typeof values.username === 'string' ? values.username : '',
  };
}
