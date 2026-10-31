import type { MenuNode } from '@crdp/api';

import type { RouteRecordStringComponent } from '@vben/types';

import { menuNodesToRoutes } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * 患者详情不在现有种子菜单里（隐藏路由留待后续 Flyway）。
 * 样板页先在前端补一条 hideInMenu 路由，后端菜单契约不变。
 */
const patientDetailRoute: RouteRecordStringComponent = {
  component: 'followup/patient/detail',
  meta: {
    activePath: '/followup/patient',
    hideInMenu: true,
    title: '患者详情',
  },
  name: 'FollowupPatientDetail',
  path: '/followup/patient/:id',
};

export async function getAllMenusApi() {
  const nodes = await requestClient.get<MenuNode[]>(apiPath('/menus'));
  return [
    ...menuNodesToRoutes(nodes),
    patientDetailRoute,
  ] as RouteRecordStringComponent[];
}
