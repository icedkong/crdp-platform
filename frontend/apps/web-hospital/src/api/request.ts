/**
 * 统一响应 {code, message, data, traceId}，成功码 0。
 * 令牌放在 Authorization: Bearer。刷新令牌关闭（服务端会话，不是 JWT）。
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import { preferences } from '@vben/preferences';
import { errorMessageResponseInterceptor, RequestClient } from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { shouldRefreshPermissions } from '@crdp/rules';
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
      // E04-T05 / PLT-AUTH-010：强制改密会话除改密 / 登出 / me 外一律 40305——带回改密页
      // （登录页拉取菜单 / 权限码等残留请求也走这里；错误提示照常由下方拦截器显示）。
      const body = error?.response?.data ?? error?.data ?? {};
      if (body?.code === 40_305 && !url.includes('/auth/password')) {
        const authStore = useAuthStore();
        await authStore.redirectToPasswordChange();
      }
      // E06-T01 / PLT-PERM-010、OQ-40：40300（缺权限码）/ 40301（模块未开通）时
      // 重新拉取权限码与菜单并重建路由（后端此时已按最新授权重算会话）。
      // 40302 是后端未声明数据范围的缺陷（components.md §1.2），不触发刷新。
      if (shouldRefreshPermissions(body?.code)) {
        const authStore = useAuthStore();
        void authStore.refreshAccess();
      }
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
