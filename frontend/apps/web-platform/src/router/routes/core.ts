import type { RouteRecordRaw } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';

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
];

export { coreRoutes, fallbackNotFoundRoute };
