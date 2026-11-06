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
  it('隐藏子路由提升到顶层，activePath 指向父菜单路径', () => {
    const routes = menuNodesToRoutes([node({ children: [detailNode] })]);
    // E15-T11：全隐藏子路由不再挂在父路由下（见下个 describe），提升到顶层
    expect(routes[0]?.children).toBeUndefined();
    const hidden = routes[1];
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
 * E15-T11（M2 验收发现）：Vben generateAccessible 会给所有带子路由的父路由自动加
 * `redirect = firstChild.path`（packages/effects/access accessible.ts）。列表页菜单
 * （我的任务 /followup/task、患者档案 /followup/patient、随访计划 /followup/plan）
 * 的唯一子路由都是隐藏详情路由（/followup/record/:recordId 等），点击菜单被重定向到
 * 字面量参数死路由，M2 主流程侧栏导航全断。转换层修正：隐藏子路由提升到顶层
 * （绝对路径本就全局匹配，activePath 保持侧栏高亮），父路由不再带 children，
 * 也就不会被 Vben 自动加 redirect。
 */
describe('menuNodesToRoutes 全隐藏子路由提升（E15-T11 导航修复）', () => {
  const recordFormNode = node({
    component: 'followup/record/form',
    id: 223,
    meta: { hideInMenu: true, order: 30, title: '随访录入' },
    name: 'FollowupRecordForm',
    path: '/followup/record/:recordId',
  });
  const taskNode = node({
    component: 'followup/task/index',
    id: 22,
    meta: { hideInMenu: false, order: 15, title: '我的任务' },
    name: 'FollowupTask',
    path: '/followup/task',
    children: [recordFormNode],
  });

  it('父路由不带 children（Vben 不会加 redirect 到字面量参数路径）', () => {
    const routes = menuNodesToRoutes([taskNode]);
    const task = routes[0];
    expect(task?.name).toBe('FollowupTask');
    expect(task?.children).toBeUndefined();
  });

  it('隐藏路由提升到顶层且 activePath 指向父菜单', () => {
    const routes = menuNodesToRoutes([taskNode]);
    const hidden = routes.at(-1);
    expect(hidden?.name).toBe('FollowupRecordForm');
    expect(hidden?.path).toBe('/followup/record/:recordId');
    expect(hidden?.meta.hideInMenu).toBe(true);
    expect(hidden?.meta.activePath).toBe('/followup/task');
  });

  it('混合子路由：可见子保持嵌套，隐藏子提升顶层', () => {
    const routes = menuNodesToRoutes([
      node({
        children: [
          node({ id: 216, name: 'Visible', path: '/followup/patient/visible' }),
          detailNode,
        ],
      }),
    ]);
    expect(routes[0]?.children?.map((c) => c.name)).toEqual(['Visible']);
    const hidden = routes.at(-1);
    expect(hidden?.name).toBe('FollowupPatientDetail');
    expect(hidden?.meta.activePath).toBe('/followup/patient');
  });

  it('深层隐藏路由提升到顶层（随访计划 → 计划详情）', () => {
    const planDetailNode = node({
      component: 'followup/plan/detail',
      id: 25,
      meta: { hideInMenu: true, order: 20, title: '计划详情' },
      name: 'FollowupPlanDetail',
      path: '/followup/plan/:planId',
    });
    const planNode = node({
      component: 'followup/plan/index',
      id: 24,
      meta: { hideInMenu: false, order: 15, title: '随访计划' },
      name: 'FollowupPlan',
      path: '/followup/plan',
      children: [planDetailNode],
    });
    const groupNode = node({
      children: [planNode],
      component: 'LAYOUT',
      id: 20,
      meta: { hideInMenu: false, order: 20, title: '随访' },
      name: 'Followup',
      path: '/followup',
    });
    const routes = menuNodesToRoutes([groupNode]);
    // 随访组只保留可见子；随访计划叶子化；计划详情在顶层
    expect(routes[0]?.children?.map((c) => c.name)).toEqual(['FollowupPlan']);
    expect(routes[0]?.children?.[0]?.children).toBeUndefined();
    const hidden = routes.at(-1);
    expect(hidden?.name).toBe('FollowupPlanDetail');
    expect(hidden?.meta.activePath).toBe('/followup/plan');
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
    const record = routes.find((r) => r.name === 'FollowupRecordForm');
    expect(record?.meta.responsive).toBe(true);
    // 名单外（含提升到顶层的隐藏路由）保持 undefined → 手机仍被守卫拦下
    expect(routes[0]?.meta.responsive).toBeUndefined();
    expect(routes[0]?.children).toBeUndefined();
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
