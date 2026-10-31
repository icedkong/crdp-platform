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
  logoutApi,
  verifyTwoFactorApi,
} from '#/api';
import { $t } from '#/locales';

/** 登录动作的统一返回：完成登录、停在双因素第二步（E04-T04），或强制改密（E04-T05）。 */
export interface AuthOutcome {
  mustChangePassword: boolean;
  twoFactor: null | TwoFactorChallenge;
  userInfo: null | UserInfo;
}

/** E04-T05 / PLT-AUTH-010：强制改密页路由（pages.md §1）。 */
export const CHANGE_PASSWORD_PATH = '/password/change';

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);
  const twoFactorLoading = ref(false);

  /**
   * 拿到令牌后的公共收尾（E04-T03 起的既有流程）。
   *
   * E04-T05 / PLT-AUTH-010：me 标记强制改密（初始 / 重置 / 到期密码）时不拉权限码与菜单
   * （后端除改密 / 登出 / me 外一律 40305），直接跳 /password/change，改密完成后继续收尾。
   */
  async function finalizeLogin(token: string): Promise<AuthOutcome> {
    accessStore.setAccessToken(token);
    const userInfo = await fetchUserInfo();
    if (userInfo.mustChangePassword) {
      await router.push(CHANGE_PASSWORD_PATH);
      return { mustChangePassword: true, twoFactor: null, userInfo };
    }
    return completeLogin(userInfo);
  }

  /** 强制改密后的收尾：拉权限码、进首页，并按需提示密码到期（PLT-AUTH-006，前 7 天提醒）。 */
  async function completeLogin(userInfo?: UserInfo): Promise<AuthOutcome> {
    const info = userInfo ?? (await fetchUserInfo());
    const accessCodes = await getAccessCodesApi();
    userStore.setUserInfo(info);
    accessStore.setAccessCodes(accessCodes);

    if (accessStore.loginExpired) {
      accessStore.setLoginExpired(false);
    } else {
      await router.push(info.homePath || preferences.app.defaultHomePath);
    }

    const expires = (info as { passwordExpiresInDays?: null | number })
      .passwordExpiresInDays;
    if (typeof expires === 'number' && expires >= 0 && expires <= 7) {
      notification.warning({
        description: `密码将于 ${expires} 天后过期，请及时修改`,
        duration: 5,
        message: '密码到期提醒',
      });
    }

    if (info.realName) {
      notification.success({
        description: `${$t('authentication.loginSuccessDesc')}:${info.realName}`,
        duration: 3,
        message: $t('authentication.loginSuccess'),
      });
    }
    return { mustChangePassword: false, twoFactor: null, userInfo: info };
  }

  /** E04-T05：改密成功后由改密页调用——继续完成登录（拉权限码与菜单、进首页）。 */
  async function continueAfterPasswordChange() {
    await completeLogin();
  }

  /** E04-T05：任意接口返回 40305 时（如会话在其他端被标记），带回改密页。 */
  async function redirectToPasswordChange() {
    if (router.currentRoute.value.path !== CHANGE_PASSWORD_PATH) {
      await router.push(CHANGE_PASSWORD_PATH);
    }
  }

  /** 把登录结果分流：需要双因素时只返回 challenge，不写任何令牌状态。 */
  async function settleLogin(result: LoginResult): Promise<AuthOutcome> {
    const challenge = pickTwoFactorChallenge(result);
    if (challenge) {
      return {
        mustChangePassword: false,
        twoFactor: challenge,
        userInfo: null,
      };
    }
    return finalizeLogin(result.token as string);
  }

  /**
   * 异步处理登录操作（平台账号强制双因素时返回 challenge 而不是令牌，E04-T04 / PLT-AUTH-005）
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
    twoFactorLoading.value = false;
  }

  return {
    $reset,
    authLogin,
    authVerifyTwoFactor,
    continueAfterPasswordChange,
    fetchUserInfo,
    loginLoading,
    logout,
    redirectToPasswordChange,
    twoFactorLoading,
  };
});
