import type { LoginResult, Me } from '@crdp/api';

import { useAccessStore } from '@vben/stores';

import { apiPath, baseRequestClient, requestClient } from '#/api/request';

export namespace AuthApi {
  export interface LoginParams {
    password?: string;
    tenantCode?: string;
    username?: string;
  }

  export interface LoginResultView {
    accessToken: string;
  }
}

export async function loginApi(data: AuthApi.LoginParams) {
  const result = await requestClient.post<LoginResult>(apiPath('/auth/login'), {
    password: data.password,
    username: data.username,
  });
  return { accessToken: result.token };
}

export async function logoutApi() {
  const accessStore = useAccessStore();
  return baseRequestClient.post(apiPath('/auth/logout'), null, {
    headers: accessStore.accessToken
      ? { Authorization: `Bearer ${accessStore.accessToken}` }
      : {},
  });
}

export async function getAccessCodesApi() {
  return requestClient.get<string[]>(apiPath('/auth/codes'));
}

export type { Me };
