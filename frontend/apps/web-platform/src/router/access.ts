import type { Router } from 'vue-router';

import type {
  ComponentRecordType,
  GenerateMenuAndRoutesOptions,
} from '@vben/types';

import { generateAccessible } from '@vben/access';
import { preferences } from '@vben/preferences';

import { getAllMenusApi } from '#/api';
import { BasicLayout, IFrameView } from '#/layouts';
import { accessRoutes } from '#/router/routes';

const forbiddenComponent = () => import('#/views/_core/fallback/forbidden.vue');

async function generateAccess(options: GenerateMenuAndRoutesOptions) {
  const pageMap: ComponentRecordType = import.meta.glob('../views/**/*.vue');

  const layoutMap: ComponentRecordType = {
    BasicLayout,
    // 后端目录节点的 component 是 LAYOUT，和 Vben 的 BasicLayout 是同一个壳。
    LAYOUT: BasicLayout,
    IFrameView,
  };

  return await generateAccessible(preferences.app.accessMode, {
    ...options,
    fetchMenuListAsync: async () => {
      return await getAllMenusApi();
    },
    // 可以指定没有权限跳转403页面
    forbiddenComponent,
    // 如果 route.meta.menuVisibleWithForbidden = true
    layoutMap,
    pageMap,
  });
}

/**
 * E06-T01 / PLT-PERM-005、PLT-PERM-010：权限变化（40300 / 40301 / permissionVersion 变化）
 * 后按最新下发菜单重建路由。Vben 的 generateAccessible 只“按名更新 + 追加”，
 * 这里再清掉不再下发的旧路由，让失去权限的菜单（含隐藏路由）不可直达（守卫转 403）。
 */
async function refreshAccessRoutes(router: Router, roles: string[]) {
  const { accessibleMenus, accessibleRoutes } = await generateAccess({
    roles,
    router,
    routes: accessRoutes,
  });

  const keepNames = new Set(
    accessibleRoutes
      .map((route) => route.name)
      .filter((name): name is string => typeof name === 'string'),
  );
  const root = router.getRoutes().find((item) => item.path === '/');
  for (const child of root?.children ?? []) {
    if (typeof child.name === 'string' && !keepNames.has(child.name)) {
      router.removeRoute(child.name);
    }
  }

  return { accessibleMenus, accessibleRoutes };
}

export { generateAccess, refreshAccessRoutes };
