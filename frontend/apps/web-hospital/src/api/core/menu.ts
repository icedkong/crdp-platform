import type { MenuNode } from '@crdp/api';

import type { RouteRecordStringComponent } from '@vben/types';

import { menuNodesToRoutes } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E24 / NFR-RESP-002：已做手机（<768）响应式的路由（按路由名登记，随 E24-T02～T06
 * 逐页打开）。名单外的路由在手机上由响应式守卫（E24-T01）整页替换为
 * MobileBlocker「请在电脑或平板上操作」。
 */
const responsiveRouteNames = new Set([
  // E24-T02：随访录入整页表单（plt_menu 223，平板 / 手机单列 + 底部固定操作栏）
  'FollowupRecordForm',
]);

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
