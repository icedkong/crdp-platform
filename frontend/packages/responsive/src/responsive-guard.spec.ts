/**
 * E24-T01 / NFR-RESP-002 · 响应式守卫单测（pages.md §0.2 路由守卫行）。
 *
 * 锁定：手机（<768，app.isMobile）且路由 meta `responsive !== true` → 重定向
 * /mobile 整页 MobileBlocker；桌面 / 平板、responsive 页、登录与兜底路由不拦截；
 * /mobile 自身放行（不得形成重定向环）。
 */
import { defineComponent } from 'vue';
import { createMemoryHistory, createRouter } from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createMobileBlockedRoute,
  MOBILE_BLOCKED_PATH,
  setupResponsiveGuard,
} from './responsive-guard';

const preferences = vi.hoisted(() => ({ app: { isMobile: false } }));

vi.mock('@vben/preferences', () => {
  return { preferences };
});

const Dummy = defineComponent({ render: () => null });

/** 组一个带守卫的测试路由器 */
function buildRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        component: Dummy,
        meta: { title: '登录' },
        name: 'Login',
        path: '/login',
      },
      {
        component: Dummy,
        meta: { title: '仅桌面' },
        name: 'DesktopOnly',
        path: '/desktop-only',
      },
      {
        component: Dummy,
        meta: { responsive: true, title: '响应式页' },
        name: 'ResponsivePage',
        path: '/responsive-page',
      },
      createMobileBlockedRoute(Dummy),
    ],
  });
  setupResponsiveGuard(router);
  return router;
}

beforeEach(() => {
  preferences.app.isMobile = false;
});

describe('手机端路由拦截（响应式守卫）', () => {
  it('手机访问未声明 responsive 的页面 → 重定向 /mobile', async () => {
    preferences.app.isMobile = true;
    const router = buildRouter();
    await router.push('/desktop-only');
    expect(router.currentRoute.value.path).toBe(MOBILE_BLOCKED_PATH);
  });

  it('手机访问 meta.responsive = true 的页面放行', async () => {
    preferences.app.isMobile = true;
    const router = buildRouter();
    await router.push('/responsive-page');
    expect(router.currentRoute.value.path).toBe('/responsive-page');
  });

  it('桌面 / 平板不拦截任何页面', async () => {
    const router = buildRouter();
    await router.push('/desktop-only');
    expect(router.currentRoute.value.path).toBe('/desktop-only');
  });

  it('登录等认证路由在手机上放行（登录页三端可用）', async () => {
    preferences.app.isMobile = true;
    const router = buildRouter();
    await router.push('/login');
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('/mobile 自身放行，不形成重定向环', async () => {
    preferences.app.isMobile = true;
    const router = buildRouter();
    await router.push(MOBILE_BLOCKED_PATH);
    expect(router.currentRoute.value.path).toBe(MOBILE_BLOCKED_PATH);
  });
});
