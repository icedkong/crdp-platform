import { mount } from '@vue/test-utils';
/**
 * E24-T01 / NFR-RESP-001 · useResponsiveShell 三档布局单测（design-system §12）。
 *
 * 锁定：
 * - 手机 <768：菜单进抽屉（Vben isMobile 外壳行为）、多标签页关闭、抽屉默认收起；
 * - 平板 768–1279：菜单收成图标栏（sidebar.collapsed=true），保留多标签页；
 * - 桌面 ≥1280：侧栏展开 + 多标签页。
 */
import { defineComponent, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useResponsiveShell } from './use-responsive-shell';

const updatePreferences = vi.fn();

vi.mock('@vben/preferences', () => {
  return {
    preferences: reactive({
      app: { isMobile: false },
      sidebar: { collapsed: false },
      tabbar: { enable: true },
    }),
    updatePreferences: (updates: unknown) => updatePreferences(updates),
  };
});

const realMatchMedia = window.matchMedia.bind(window);
const mounted: Array<{ unmount: () => void }> = [];

function setViewport(width: number) {
  window.innerWidth = width;
  window.matchMedia = ((query: string) => {
    void query;
    return {
      addEventListener: () => {},
      matches: false,
      removeEventListener: () => {},
    } as MediaQueryList;
  }) as typeof window.matchMedia;
}

/** 挂宿主组件并等到 onMounted 读完视口 */
async function mountShell() {
  const wrapper = mount(
    defineComponent({
      setup() {
        useResponsiveShell();
        return () => null;
      },
    }),
  );
  mounted.push(wrapper);
  await Promise.resolve();
  return wrapper;
}

beforeEach(() => {
  updatePreferences.mockClear();
});

afterEach(() => {
  window.matchMedia = realMatchMedia;
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

describe('useResponsiveShell 三档布局（E24-T01 验收：三档宽度下布局正确）', () => {
  it('手机（<768）：隐藏多标签页，抽屉默认收起', async () => {
    setViewport(375);
    await mountShell();
    expect(updatePreferences).toHaveBeenCalledWith({
      sidebar: { collapsed: true },
      tabbar: { enable: false },
    });
  });

  it('平板（768–1279）：菜单收成图标栏，保留多标签页', async () => {
    setViewport(1024);
    await mountShell();
    expect(updatePreferences).toHaveBeenCalledWith({
      sidebar: { collapsed: true },
      tabbar: { enable: true },
    });
  });

  it('桌面（≥1280）：侧栏展开 + 多标签页', async () => {
    setViewport(1440);
    await mountShell();
    expect(updatePreferences).toHaveBeenCalledWith({
      sidebar: { collapsed: false },
      tabbar: { enable: true },
    });
  });

  it('跨档切换时重新下发（桌面 → 手机）', async () => {
    setViewport(1440);
    await mountShell();
    updatePreferences.mockClear();

    window.innerWidth = 375;
    window.dispatchEvent(new Event('resize'));
    await Promise.resolve();
    expect(updatePreferences).toHaveBeenCalledWith({
      sidebar: { collapsed: true },
      tabbar: { enable: false },
    });
  });

  it('同档 resize 不重复下发', async () => {
    setViewport(1440);
    await mountShell();
    updatePreferences.mockClear();

    window.innerWidth = 1500;
    window.dispatchEvent(new Event('resize'));
    await Promise.resolve();
    expect(updatePreferences).not.toHaveBeenCalled();
  });
});
