import type { Component } from 'vue';
import type { Router, RouteRecordRaw } from 'vue-router';

import { preferences } from '@vben/preferences';

import MobileBlocker from './mobile-blocker.vue';

/** 手机阻断页路径（两端一致） */
export const MOBILE_BLOCKED_PATH = '/mobile';

export const MOBILE_BLOCKED_ROUTE_NAME = 'MobileBlocked';

/**
 * 不拦截的路由：认证三页（登录三端可用，pages.md §1；忘记密码 / 强制改密
 * 是登录链路的一部分）与 403 / 404 兜底（终端态，拦了会变成死循环）。
 */
const EXEMPT_ROUTE_NAMES = new Set([
  'Authentication',
  'ChangePassword',
  'ChangePasswordForm',
  'FallbackForbidden',
  'FallbackNotFound',
  'ForgotPassword',
  'ForgotPasswordForm',
  'Login',
]);

/**
 * E24-T01 / pages.md §0.2 路由守卫：手机（<768）且路由 meta `responsive !== true`
 * （默认）→ 重定向 /mobile，整页显示 MobileBlocker「请在电脑或平板上操作」。
 * 桌面 / 平板不拦截；已适配手机的具体页面在自己的任务里给路由 meta 加
 * `responsive: true`（E24-T02～T06）。
 *
 * isMobile 读 Vben 的 preferences.app.isMobile（<768 自动翻转，与 tokens
 * 断点同界）；注册在权限守卫之后，未登录先由权限守卫导去登录页。
 */
export function setupResponsiveGuard(router: Router) {
  router.beforeEach((to) => {
    if (to.path === MOBILE_BLOCKED_PATH) return true;
    if (typeof to.name === 'string' && EXEMPT_ROUTE_NAMES.has(to.name)) {
      return true;
    }
    if (!preferences.app.isMobile) return true;
    if (to.meta.responsive === true) return true;
    return { path: MOBILE_BLOCKED_PATH, replace: true };
  });
}

/**
 * /mobile 兜底路由（挂在主壳 BasicLayout 下，侧栏 / 抽屉菜单仍可用）。
 * 与 403 / 404 兜底同款套路（core.ts）。
 */
export function createMobileBlockedRoute(
  basicLayout: Component,
): RouteRecordRaw {
  return {
    component: basicLayout,
    meta: {
      hideInBreadcrumb: true,
      hideInMenu: true,
      hideInTab: true,
      title: '手机端暂不支持',
    },
    name: MOBILE_BLOCKED_ROUTE_NAME,
    path: MOBILE_BLOCKED_PATH,
    children: [
      {
        component: MobileBlocker,
        meta: {
          hideInBreadcrumb: true,
          hideInMenu: true,
          hideInTab: true,
          title: '手机端暂不支持',
        },
        name: `${MOBILE_BLOCKED_ROUTE_NAME}Index`,
        path: '',
      },
    ],
  };
}
