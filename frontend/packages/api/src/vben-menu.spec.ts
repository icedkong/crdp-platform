/**
 * E13-T05：后端菜单 → Vben 路由转换（vben-menu.ts）。
 *
 * 隐藏菜单（hideInMenu，如患者详情 plt_menu 215 /followup/patient/:id）
 * 由页面动作打开为新标签页，不在侧栏出现；activePath 指向父菜单（患者列表
 * /followup/patient），侧栏高亮与面包屑保持正确（样板期 patientDetailRoute
 * 的手工 meta 随平台库 V39 菜单化移除）。
 */
import { describe, expect, it } from 'vitest';

import { menuNodesToRoutes, menuNodeToRoute } from './vben-menu';

/** MenuNode 夹具（结构见 index.ts；meta.hideInMenu 由后端 hidden=1 下发）。 */
function node(patch: Record<string, unknown> = {}): Record<string, any> {
  return {
    children: [],
    component: 'followup/patient/index',
    id: 21,
    meta: { hideInMenu: false, order: 10, title: '患者档案' },
    name: 'FollowupPatient',
    path: '/followup/patient',
    type: 'MENU',
    ...patch,
  };
}

const detailNode = node({
  component: 'followup/patient/detail',
  id: 215,
  meta: { hideInMenu: true, order: 20, title: '患者详情' },
  name: 'FollowupPatientDetail',
  path: '/followup/patient/:id',
});

describe('menuNodesToRoutes 隐藏菜单 activePath 派生', () => {
  it('隐藏子路由的 activePath 指向父菜单路径', () => {
    const routes = menuNodesToRoutes([node({ children: [detailNode] })]);
    const hidden = routes[0]?.children?.[0];
    expect(hidden).toBeDefined();
    expect(hidden?.meta.activePath).toBe('/followup/patient');
    expect(hidden?.meta.hideInMenu).toBe(true);
    expect(hidden?.name).toBe('FollowupPatientDetail');
  });

  it('可见子路由不设置 activePath（默认按自身路径高亮）', () => {
    const routes = menuNodesToRoutes([
      node({
        children: [
          node({ id: 216, name: 'Other', path: '/followup/patient/other' }),
        ],
      }),
    ]);
    expect(routes[0]?.children?.[0]?.meta.activePath).toBeUndefined();
  });

  it('顶层隐藏路由无父路径，不设置 activePath', () => {
    const routes = menuNodesToRoutes([
      node({
        meta: { hideInMenu: true, order: 20, title: '隐藏页' },
        path: '/x/hidden',
      }),
    ]);
    expect(routes[0]?.meta.activePath).toBeUndefined();
  });
});

describe('menuNodeToRoute 基础转换不回归', () => {
  it('布局组件映射 BasicLayout，hideInMenu 透传', () => {
    const route = menuNodeToRoute(
      node({
        component: 'LAYOUT',
        meta: { hideInMenu: true, order: 1, title: '随访' },
      }),
    );
    expect(route.component).toBe('BasicLayout');
    expect(route.meta.hideInMenu).toBe(true);
  });
});

/**
 * E24-T02 / NFR-RESP-002：响应式路由放行名单。手机（<768）访问 meta
 * responsive !== true 的路由会被响应式守卫整页替换为 MobileBlocker
 * （E24-T01）；已适配的页面（E24-T02 起逐页打开）按路由名登记，转换时
 * 写入 meta.responsive = true。
 */
describe('menuNodesToRoutes 响应式名单（E24-T02）', () => {
  it('名单内的路由 meta.responsive = true（手机可进入）', () => {
    const routes = menuNodesToRoutes(
      [
        node({ children: [detailNode] }),
        node({
          id: 223,
          component: 'followup/record/form',
          meta: { hideInMenu: true, order: 30, title: '随访录入' },
          name: 'FollowupRecordForm',
          path: '/followup/record/:recordId',
        }),
      ],
      new Set(['FollowupRecordForm']),
    );
    const record = routes[1];
    expect(record?.meta.responsive).toBe(true);
    // 名单外（含隐藏路由）保持 undefined → 手机仍被守卫拦下
    expect(routes[0]?.meta.responsive).toBeUndefined();
    expect(routes[0]?.children?.[0]?.meta.responsive).toBeUndefined();
  });

  it('不传名单时全部保持默认（不响应式，行为不回归）', () => {
    const routes = menuNodesToRoutes([
      node({
        name: 'FollowupRecordForm',
        path: '/followup/record/:recordId',
      }),
    ]);
    expect(routes[0]?.meta.responsive).toBeUndefined();
  });
});
