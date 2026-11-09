import type { Router } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { useAccessStore, useUserStore } from '@vben/stores';
import { startProgress, stopProgress } from '@vben/utils';

import { setupResponsiveGuard } from '@crdp/responsive';
import { resolveLoginRedirect } from '@crdp/rules';

import { accessRoutes, coreRouteNames } from '#/router/routes';
import { useAuthStore } from '#/store';

import { generateAccess } from './access';

/**
 * 通用守卫配置
 * @param router
 */
function setupCommonGuard(router: Router) {
  // 记录已经加载的页面
  const loadedPaths = new Set<string>();

  router.beforeEach((to) => {
    to.meta.loaded = loadedPaths.has(to.path);

    // 页面加载进度条
    if (!to.meta.loaded && preferences.transition.progress) {
      startProgress();
    }
    return true;
  });

  router.afterEach((to) => {
    // 记录页面是否加载,如果已经加载，后续的页面切换动画等效果不在重复执行

    loadedPaths.add(to.path);

    // 关闭页面加载进度条
    if (preferences.transition.progress) {
      stopProgress();
    }
  });
}

/**
 * 权限访问守卫配置
 * @param router
 */
function setupAccessGuard(router: Router) {
  router.beforeEach(async (to, from) => {
    const accessStore = useAccessStore();
    const userStore = useUserStore();
    const authStore = useAuthStore();

    // 基本路由，这些路由不需要进入权限拦截
    if (coreRouteNames.includes(to.name as string)) {
      if (to.path === LOGIN_PATH && accessStore.accessToken) {
        // E22-T11 / PT-SELF-07：redirect 是 URL 查询串（未认证用户可构造），
        // 只放行站内路径（resolveLoginRedirect 白名单），其余回落 homePath / 首页
        return resolveLoginRedirect(
          to.query?.redirect as null | string | undefined,
          userStore.userInfo?.homePath || preferences.app.defaultHomePath,
        );
      }
      // E04-T05 / PLT-AUTH-010：改密页是核心路由但要先有令牌（未登录访问回登录页）；
      // E06-T11 / PLT-ORG-011：个人中心同理（登录即可，但不是匿名页）
      if (
        (to.path === '/password/change' || to.path === '/profile') &&
        !accessStore.accessToken
      ) {
        return { path: LOGIN_PATH, replace: true };
      }
      return true;
    }

    // accessToken 检查
    if (!accessStore.accessToken) {
      // 明确声明忽略权限访问权限，则可以访问
      if (to.meta.ignoreAccess) {
        return true;
      }

      // 没有访问权限，跳转登录页面
      if (to.fullPath !== LOGIN_PATH) {
        return {
          path: LOGIN_PATH,
          // 如不需要，直接删除 query
          query:
            to.fullPath === preferences.app.defaultHomePath
              ? {}
              : { redirect: encodeURIComponent(to.fullPath) },
          // 携带当前跳转的页面，登录后重新跳转该页面
          replace: true,
        };
      }
      return to;
    }

    // 是否已经生成过动态路由
    if (accessStore.isAccessChecked) {
      // E06-T01 / pages.md §0.2：已登录但目标路由未下发（无菜单权限，或权限刷新后
      // 已清掉的旧路由）→ 403 页「没有访问权限，请联系本院管理员」，而不是 404。
      if (to.name === 'FallbackNotFound') {
        return { path: '/403', replace: true };
      }
      return true;
    }

    // 生成路由表
    // 当前登录用户拥有的角色标识列表
    const userInfo = userStore.userInfo || (await authStore.fetchUserInfo());
    const userRoles = userInfo.roles ?? [];

    // 生成菜单和路由
    const { accessibleMenus, accessibleRoutes } = await generateAccess({
      roles: userRoles,
      router,
      // 则会在菜单中显示，但是访问会被重定向到403
      routes: accessRoutes,
    });

    // 保存菜单信息和路由信息
    accessStore.setAccessMenus(accessibleMenus);
    accessStore.setAccessRoutes(accessibleRoutes);
    accessStore.setIsAccessChecked(true);
    // E22-T11 / PT-SELF-07：回跳目标过白名单（站内单斜杠路径），非法值回落原目标链
    const redirectPath = resolveLoginRedirect(
      from.query.redirect as null | string | undefined,
      to.path === preferences.app.defaultHomePath
        ? userInfo.homePath || preferences.app.defaultHomePath
        : to.fullPath,
    );

    return {
      ...router.resolve(redirectPath),
      replace: true,
    };
  });
}

/**
 * 项目守卫配置
 * @param router
 */
function createRouterGuard(router: Router) {
  /** 通用 */
  setupCommonGuard(router);
  /** 权限访问 */
  setupAccessGuard(router);
  /** E24-T01：手机端非响应式页面整页替换（pages.md §0.2） */
  setupResponsiveGuard(router);
}

export { createRouterGuard };
