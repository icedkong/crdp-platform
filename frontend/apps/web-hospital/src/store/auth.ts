import type { LoginResult, TwoFactorChallenge } from '@crdp/api';

import type { Recordable, UserInfo } from '@vben/types';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import { pickTwoFactorChallenge } from '@crdp/api';
import { notification } from 'ant-design-vue';
import { defineStore } from 'pinia';

import {
  getAccessCodesApi,
  getUserInfoApi,
  loginApi,
  loginBySmsApi,
  logoutApi,
  verifyTwoFactorApi,
} from '#/api';
import { $t } from '#/locales';

/** 登录动作的统一返回：要么拿到用户信息完成登录，要么停在第 2 步等短信验证码。 */
export interface AuthOutcome {
  twoFactor: null | TwoFactorChallenge;
  userInfo: null | UserInfo;
}

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);
  const smsLoginLoading = ref(false);
  const twoFactorLoading = ref(false);

  /**
   * 拿到令牌后的公共收尾：写入令牌、拉取用户信息与权限码、跳转首页（E04-T02 起的既有流程）。
   */
  async function finalizeLogin(token: string): Promise<UserInfo> {
    accessStore.setAccessToken(token);
    const [userInfo, accessCodes] = await Promise.all([
      fetchUserInfo(),
      getAccessCodesApi(),
    ]);
    userStore.setUserInfo(userInfo);
    accessStore.setAccessCodes(accessCodes);

    if (accessStore.loginExpired) {
      accessStore.setLoginExpired(false);
    } else {
      await router.push(userInfo.homePath || preferences.app.defaultHomePath);
    }

    if (userInfo.realName) {
      notification.success({
        description: `${$t('authentication.loginSuccessDesc')}:${userInfo.realName}`,
        duration: 3,
        message: $t('authentication.loginSuccess'),
      });
    }
    return userInfo;
  }

  /** 把登录结果分流：需要双因素时只返回 challenge，不写任何令牌状态。 */
  async function settleLogin(result: LoginResult): Promise<AuthOutcome> {
    const challenge = pickTwoFactorChallenge(result);
    if (challenge) {
      return { twoFactor: challenge, userInfo: null };
    }
    const userInfo = await finalizeLogin(result.token as string);
    return { twoFactor: null, userInfo };
  }

  /**
   * 异步处理登录操作
   * Asynchronously handle the login process
   * @param params 登录表单数据
   */
  async function authLogin(params: Recordable<any>) {
    loginLoading.value = true;
    try {
      return await settleLogin(await loginApi(params));
    } finally {
      loginLoading.value = false;
    }
  }

  /** E04-T04 / PLT-AUTH-002：短信验证码登录（后端不会在此步返回双因素 challenge，分流仅作防御）。 */
  async function authLoginBySms(params: {
    code: string;
    phone: string;
    tenantCode: string;
  }): Promise<AuthOutcome> {
    smsLoginLoading.value = true;
    try {
      return await settleLogin(await loginBySmsApi(params));
    } finally {
      smsLoginLoading.value = false;
    }
  }

  /** E04-T04 / PLT-AUTH-003：双因素第二步，验证通过后完成登录。 */
  async function authVerifyTwoFactor(challengeId: string, code: string) {
    twoFactorLoading.value = true;
    try {
      return await settleLogin(await verifyTwoFactorApi({ challengeId, code }));
    } finally {
      twoFactorLoading.value = false;
    }
  }

  async function logout(redirect: boolean = true) {
    try {
      await logoutApi();
    } catch {
      // 不做任何处理
    }
    resetAllStores();
    accessStore.setLoginExpired(false);

    // 回登录页带上当前路由地址
    await router.replace({
      path: LOGIN_PATH,
      query: redirect
        ? {
            redirect: encodeURIComponent(router.currentRoute.value.fullPath),
          }
        : {},
    });
  }

  async function fetchUserInfo() {
    const userInfo = await getUserInfoApi();
    userStore.setUserInfo(userInfo);
    return userInfo;
  }

  function $reset() {
    loginLoading.value = false;
    smsLoginLoading.value = false;
    twoFactorLoading.value = false;
  }

  return {
    $reset,
    authLogin,
    authLoginBySms,
    authVerifyTwoFactor,
    fetchUserInfo,
    loginLoading,
    logout,
    smsLoginLoading,
    twoFactorLoading,
  };
});
