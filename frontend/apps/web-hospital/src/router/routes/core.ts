import type { RouteRecordRaw } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';

import { createMobileBlockedRoute } from '@crdp/responsive';

const BasicLayout = () => import('#/layouts/basic.vue');
const AuthPageLayout = () => import('#/layouts/auth.vue');

const fallbackNotFoundRoute: RouteRecordRaw = {
  component: () => import('#/views/_core/fallback/not-found.vue'),
  meta: {
    hideInBreadcrumb: true,
    hideInMenu: true,
    hideInTab: true,
    title: '404',
  },
  name: 'FallbackNotFound',
  path: '/:path(.*)*',
};

/**
 * E06-T01 / pages.md §0.2：无菜单权限的 403 页。后端菜单模式下未下发的路由
 * （含权限刷新后清掉的旧路由）会落到 404 兜底，守卫把已登录用户改导向这里。
 */
const fallbackForbiddenRoute: RouteRecordRaw = {
  component: () => import('#/views/_core/fallback/forbidden.vue'),
  meta: {
    hideInBreadcrumb: true,
    hideInMenu: true,
    hideInTab: true,
    title: '403',
  },
  name: 'FallbackForbidden',
  path: '/403',
};

/** 基本路由。登录页是 /login（pages.md），不是上游默认的 /auth/login。 */
const coreRoutes: RouteRecordRaw[] = [
  {
    component: BasicLayout,
    meta: {
      hideInBreadcrumb: true,
      title: 'Root',
    },
    name: 'Root',
    path: '/',
    redirect: preferences.app.defaultHomePath,
    children: [],
  },
  fallbackForbiddenRoute,
  // E24-T01 / NFR-RESP-002：手机上访问未适配页面（meta responsive !== true，默认）
  // 时由响应式守卫整页重定向到这里（components.md §22 MobileBlocker）。
  createMobileBlockedRoute(BasicLayout),
  {
    component: AuthPageLayout,
    meta: {
      hideInTab: true,
      title: '登录',
    },
    name: 'Authentication',
    path: LOGIN_PATH,
    children: [
      {
        name: 'Login',
        path: '',
        component: () => import('#/views/_core/authentication/login.vue'),
        meta: {
          hideInTab: true,
          title: '登录',
        },
      },
    ],
  },
  {
    // E04-T07 / PLT-AUTH-011：忘记密码（免登录，入口在登录页；pages.md §1）。
    // 短信验证（发到账号绑定手机号）后重置，成功后该账号全部会话失效。
    component: AuthPageLayout,
    meta: {
      hideInTab: true,
      title: '忘记密码',
    },
    name: 'ForgotPassword',
    path: '/password/forgot',
    children: [
      {
        name: 'ForgotPasswordForm',
        path: '',
        component: () =>
          import('#/views/_core/authentication/forget-password.vue'),
        meta: {
          hideInTab: true,
          title: '忘记密码',
        },
      },
    ],
  },
  {
    // E04-T05 / PLT-AUTH-010：首次登录 / 密码到期强制改密（pages.md §1）。
    // 需要已登录（带令牌），但不进入权限路由生成（改密期间 menus/codes 返回 40305）。
    component: AuthPageLayout,
    meta: {
      hideInTab: true,
      title: '修改密码',
    },
    name: 'ChangePassword',
    path: '/password/change',
    children: [
      {
        name: 'ChangePasswordForm',
        path: '',
        component: () =>
          import('#/views/_core/authentication/change-password.vue'),
        meta: {
          hideInTab: true,
          title: '修改密码',
        },
      },
    ],
  },
  {
    // E06-T11 / PLT-ORG-011：个人中心（pages.md §10，登录即可，不占菜单）。
    // 走主壳布局（顶栏 / 侧栏照常）；未登录访问由守卫导回登录页（与 /password/change 同款）。
    component: BasicLayout,
    meta: {
      hideInBreadcrumb: true,
      hideInMenu: true,
      title: '个人中心',
    },
    name: 'HospitalProfile',
    path: '/profile',
    children: [
      {
        name: 'HospitalProfileIndex',
        path: '',
        component: () => import('#/views/_core/profile/index.vue'),
        meta: {
          hideInMenu: true,
          title: '个人中心',
        },
      },
    ],
  },
];

export { coreRoutes, fallbackNotFoundRoute };
