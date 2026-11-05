import type { LoginResult, TwoFactorChallenge } from '@crdp/api';

import type { Recordable, UserInfo } from '@vben/types';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import { pickTwoFactorChallenge } from '@crdp/api';
import { clearAllLocalDrafts } from '@crdp/form-engine';
import { permissionVersionChanged } from '@crdp/rules';
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
import { refreshAccessRoutes } from '#/router/access';

/** 登录动作的统一返回：完成登录、停在第 2 步等短信验证码，或强制改密。 */
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
  const smsLoginLoading = ref(false);
  const twoFactorLoading = ref(false);

  /** E06-T01 / PLT-PERM-010：上次拉取权限码时看到的 me.permissionVersion。 */
  const permissionVersionSeen = ref<null | number>(null);
  /** 刷新单飞标志：40300 风暴与版本检测并发时只重建一次。 */
  const refreshingAccess = ref(false);

  /**
   * 拿到令牌后的公共收尾：写入令牌、拉取用户信息与权限码、跳转首页（E04-T02 起的既有流程）。
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

  /**
   * E04-T05：改密成功后由改密页调用——继续完成登录（拉权限码与菜单、进首页）。
   */
  async function continueAfterPasswordChange() {
    await completeLogin();
  }

  /** E04-T05：任意接口返回 40305 时（如会话在其他端被标记），带回改密页。 */
  async function redirectToPasswordChange() {
    if (router.currentRoute.value.path !== CHANGE_PASSWORD_PATH) {
      await router.push(CHANGE_PASSWORD_PATH);
    }
  }

  /**
   * E06-T01 / PLT-PERM-005、006、010：权限变化后重新拉取 me + 权限码 + 菜单并重建路由。
   * 触发源（components.md §1.2）：接口返回 40300（缺权限码）/ 40301（模块未开通），
   * 或 me.permissionVersion 变化。后端在权限版本落后时已按最新授权重算会话，
   * 这里重新拉取即可让菜单与按钮跟上（按权限码显示菜单与按钮）。
   */
  async function refreshAccess() {
    if (!accessStore.accessToken || refreshingAccess.value) {
      return;
    }
    refreshingAccess.value = true;
    try {
      const userInfo = await fetchUserInfo();
      accessStore.setAccessCodes(await getAccessCodesApi());
      const { accessibleMenus, accessibleRoutes } = await refreshAccessRoutes(
        router,
        userInfo.roles ?? [],
      );
      accessStore.setAccessMenus(accessibleMenus);
      accessStore.setAccessRoutes(accessibleRoutes);
      accessStore.setIsAccessChecked(true);
    } finally {
      refreshingAccess.value = false;
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
    // E25-T02 / NFR-SAVE-002：退出登录清空本机全部草稿（design-system §13）
    void clearAllLocalDrafts();

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
    // E06-T01 / PLT-PERM-010：me 的权限版本号与上次不同 → 权限已变，重新拉取权限码与菜单。
    // 首次登录（seen 为空）或强制改密期（codes 为空）不触发；单飞标志防止与 refreshAccess 重入。
    const version =
      (userInfo as { permissionVersion?: number }).permissionVersion ?? null;
    const changed = permissionVersionChanged(
      permissionVersionSeen.value,
      version,
    );
    if (typeof version === 'number') {
      permissionVersionSeen.value = version;
    }
    if (changed && accessStore.accessCodes.length > 0) {
      void refreshAccess();
    }
    return userInfo;
  }

  function $reset() {
    loginLoading.value = false;
    smsLoginLoading.value = false;
    twoFactorLoading.value = false;
    permissionVersionSeen.value = null;
    refreshingAccess.value = false;
  }

  return {
    $reset,
    authLogin,
    authLoginBySms,
    authVerifyTwoFactor,
    continueAfterPasswordChange,
    fetchUserInfo,
    loginLoading,
    logout,
    redirectToPasswordChange,
    refreshAccess,
    smsLoginLoading,
    twoFactorLoading,
  };
});
