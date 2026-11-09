import type { MenuNode } from '@crdp/api';

import type { RouteRecordStringComponent } from '@vben/types';

import { menuNodesToRoutes } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';
import { responsiveRouteNames } from '#/router/responsive-routes';

/**
 * 医院端菜单全部来自后端（/menus，按角色下发；AGENTS.md §7）。
 * 患者详情为隐藏路由（plt_menu 215，/followup/patient/:id，平台库 V39 随
 * E13-T05 菜单化），由患者列表「详情」打开为新标签页；隐藏子路由的
 * activePath 在 menuNodesToRoutes 里按父菜单路径派生。
 */
export async function getAllMenusApi() {
  const nodes = await requestClient.get<MenuNode[]>(apiPath('/menus'));
  return menuNodesToRoutes(
    nodes,
    responsiveRouteNames,
  ) as RouteRecordStringComponent[];
}
