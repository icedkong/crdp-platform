/**
 * 统一响应 {code, message, data, traceId}，成功码 0。
 * 令牌放在 Authorization: Bearer。刷新令牌关闭（服务端会话，不是 JWT）。
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { preferences } from '@vben/preferences';
import { errorMessageResponseInterceptor, RequestClient } from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { message } from 'ant-design-vue';

import { useAuthStore } from '#/store';

import { API_SCOPE } from './scope';

const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);

function createRequestClient(baseURL: string, options?: RequestClientOptions) {
  const client = new RequestClient({
    ...options,
    baseURL,
  });

  async function doReAuthenticate() {
    const accessStore = useAccessStore();
    const authStore = useAuthStore();
    accessStore.setAccessToken(null);
    await authStore.logout();
  }

  function formatToken(token: null | string) {
    return token ? `Bearer ${token}` : null;
  }

  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();
      config.headers.Authorization = formatToken(accessStore.accessToken);
      config.headers['Accept-Language'] = preferences.app.locale;
      return config;
    },
  });

  client.addResponseInterceptor({
    fulfilled: (response) => {
      const { config, data: responseData, status } = response;
      if (config.responseReturn === 'raw') {
        return response;
      }
      if (status >= 200 && status < 400 && responseData?.code === 0) {
        return responseData.data;
      }
      throw Object.assign({}, response, { response });
    },
  });

  client.addResponseInterceptor({
    rejected: async (error) => {
      const url = String(error?.config?.url ?? '');
      const status = error?.response?.status;
      // 登录失败也是 HTTP 401，不能当成令牌过期去登出。
      if (status === 401 && !url.includes('/auth/login')) {
        await doReAuthenticate();
      }
      throw error;
    },
  });

  client.addResponseInterceptor(
    errorMessageResponseInterceptor((msg: string, error) => {
      const responseData = error?.response?.data ?? error?.data ?? {};
      const errorMessage = responseData?.message ?? responseData?.error ?? '';
      const traceId = responseData?.traceId
        ? `（${responseData.traceId}）`
        : '';
      message.error(`${errorMessage || msg}${traceId}`);
    }),
  );

  return client;
}

export const requestClient = createRequestClient(apiURL, {
  responseReturn: 'data',
});

export const baseRequestClient = new RequestClient({ baseURL: apiURL });

export function apiPath(path: string) {
  return `/${API_SCOPE}${path}`;
}
