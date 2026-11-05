import { mount } from '@vue/test-utils';
/**
 * E24-T01 / NFR-RESP-001 · useBreakpoint 断点单测（components.md §1.1）。
 *
 * 锁定三档边界：<768 手机、768–1279 平板、≥1280 桌面（数值来自 tokens.ts
 * breakpoints：tablet 768 / desktop 1280，全站唯一断点来源）；resize 后档位跟随变化。
 */
import { defineComponent } from 'vue';

import { afterEach, describe, expect, it } from 'vitest';

import { useBreakpoint } from './use-breakpoint';

const realMatchMedia = window.matchMedia.bind(window);
const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  window.matchMedia = realMatchMedia;
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

/** 挂一个宿主组件取组合函数状态（onMounted 需要组件上下文） */
function mountBreakpoint() {
  let state!: ReturnType<typeof useBreakpoint>;
  const wrapper = mount(
    defineComponent({
      setup() {
        state = useBreakpoint();
        return () => null;
      },
    }),
  );
  mounted.push(wrapper);
  return state;
}

function setViewport(width: number, coarse = false) {
  window.innerWidth = width;
  window.matchMedia = ((query: string) => {
    if (query.includes('pointer: coarse')) {
      return {
        addEventListener: () => {},
        matches: coarse,
        removeEventListener: () => {},
      } as MediaQueryList;
    }
    return realMatchMedia(query);
  }) as typeof window.matchMedia;
}

describe('useBreakpoint 三档断点（768 / 1280）', () => {
  it.each([
    [375, 'mobile'],
    [767, 'mobile'],
    [768, 'tablet'],
    [1024, 'tablet'],
    [1279, 'tablet'],
    [1280, 'desktop'],
    [1440, 'desktop'],
  ])('视口 %dpx → %s', (width, device) => {
    setViewport(width);
    const state = mountBreakpoint();
    expect(state.device.value).toBe(device);
    expect(state.width.value).toBe(width);
    expect(state.isMobile.value).toBe(device === 'mobile');
    expect(state.isTablet.value).toBe(device === 'tablet');
    expect(state.isDesktop.value).toBe(device === 'desktop');
  });

  it('resize 跨过断点后档位跟随变化', async () => {
    setViewport(1440);
    const state = mountBreakpoint();
    expect(state.isDesktop.value).toBe(true);

    window.innerWidth = 375;
    window.dispatchEvent(new Event('resize'));
    await Promise.resolve();
    expect(state.isMobile.value).toBe(true);

    window.innerWidth = 1024;
    window.dispatchEvent(new Event('resize'));
    await Promise.resolve();
    expect(state.isTablet.value).toBe(true);
  });

  it('pointer: coarse → isTouch（触控设备判定，components.md §0.5）', () => {
    setViewport(1440, true);
    const state = mountBreakpoint();
    expect(state.isTouch.value).toBe(true);
  });

  it('触控笔电（pointer: fine）不算触控', () => {
    setViewport(1440, false);
    const state = mountBreakpoint();
    expect(state.isTouch.value).toBe(false);
  });
});
