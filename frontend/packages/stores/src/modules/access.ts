import type { RouteRecordRaw } from 'vue-router';

import type { MenuRecordRaw } from '@vben-core/typings';

import { acceptHMRUpdate, defineStore } from 'pinia';

type AccessToken = null | string;

interface AccessState {
  /**
   * 权限码
   */
  accessCodes: string[];
  /**
   * 可访问的菜单列表
   */
  accessMenus: MenuRecordRaw[];
  /**
   * 可访问的路由列表
   */
  accessRoutes: RouteRecordRaw[];
  /**
   * 登录 accessToken
   */
  accessToken: AccessToken;
  /**
   * 是否已经检查过权限
   */
  isAccessChecked: boolean;
  /**
   * 是否锁屏状态
   */
  isLockScreen: boolean;
  /**
   * 锁屏密码
   */
  lockScreenPassword?: string;
  /**
   * 登录是否过期
   */
  loginExpired: boolean;
  /**
   * 登录 accessToken
   */
  refreshToken: AccessToken;
}

/**
 * E22-T11 / PT-SELF-05（中危，修复）：会话令牌持久化配置。
 *
 * 原实现把 accessToken / refreshToken / lockScreenPassword pick 进全局持久化
 * 载体（localStorage；SecureLS 的密钥内联在构建产物里，只算混淆不算加密）——
 * 医院共享工位上关闭浏览器后，TTL 内仍有效的令牌留在盘上可被恢复复用；租户
 * 视角令牌（≤2 小时）同路径落盘。修复口径：
 * - 载体改 sessionStorage：标签页内延续（含 window.open 开出的同源新标签，
 *   按规范继承副本）、关闭即清；同仓库 tabbar store 已有 per-store storage
 *   覆盖先例；
 * - lockScreenPassword（口令类，永不落盘）与 refreshToken（enableRefreshToken
 *   全局关闭，恒为 null）移出 pick；
 * - accessToken / accessCodes 保留：刷新不掉线、按钮显隐不闪烁。
 * 导出为常量并让 defineStore 引用同一对象，`access-persist.test.ts` 锁定防漂移。
 */
export const ACCESS_PERSIST_OPTIONS = {
  storage: sessionStorage,
  pick: ['accessToken', 'accessCodes', 'isLockScreen'],
} as const;

/**
 * @zh_CN 访问权限相关
 */
export const useAccessStore = defineStore('core-access', {
  actions: {
    getMenuByPath(path: string) {
      function findMenu(
        menus: MenuRecordRaw[],
        path: string,
      ): MenuRecordRaw | undefined {
        for (const menu of menus) {
          if (menu.path === path) {
            return menu;
          }
          if (menu.children) {
            const matched = findMenu(menu.children, path);
            if (matched) {
              return matched;
            }
          }
        }
      }
      return findMenu(this.accessMenus, path);
    },
    lockScreen(password: string) {
      this.isLockScreen = true;
      this.lockScreenPassword = password;
    },
    setAccessCodes(codes: string[]) {
      this.accessCodes = codes;
    },
    setAccessMenus(menus: MenuRecordRaw[]) {
      this.accessMenus = menus;
    },
    setAccessRoutes(routes: RouteRecordRaw[]) {
      this.accessRoutes = routes;
    },
    setAccessToken(token: AccessToken) {
      this.accessToken = token;
    },
    setIsAccessChecked(isAccessChecked: boolean) {
      this.isAccessChecked = isAccessChecked;
    },
    setLoginExpired(loginExpired: boolean) {
      this.loginExpired = loginExpired;
    },
    setRefreshToken(token: AccessToken) {
      this.refreshToken = token;
    },
    unlockScreen() {
      this.isLockScreen = false;
      this.lockScreenPassword = undefined;
    },
  },
  persist: ACCESS_PERSIST_OPTIONS,
  state: (): AccessState => ({
    accessCodes: [],
    accessMenus: [],
    accessRoutes: [],
    accessToken: null,
    isAccessChecked: false,
    isLockScreen: false,
    lockScreenPassword: undefined,
    loginExpired: false,
    refreshToken: null,
  }),
});

// 解决热更新问题
const hot = import.meta.hot;
if (hot) {
  hot.accept(acceptHMRUpdate(useAccessStore, hot));
}
