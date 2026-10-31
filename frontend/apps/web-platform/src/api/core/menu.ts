import type { MenuNode } from '@crdp/api';

import type { RouteRecordStringComponent } from '@vben/types';

import { menuNodesToRoutes } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

export async function getAllMenusApi() {
  const nodes = await requestClient.get<MenuNode[]>(apiPath('/menus'));
  return menuNodesToRoutes(nodes) as RouteRecordStringComponent[];
}
