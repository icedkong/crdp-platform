import type { DeviceKind } from '@crdp/tokens';

import { computed, onMounted, onUnmounted, ref } from 'vue';

import { breakpoints, deviceOf } from '@crdp/tokens';

/**
 * 全站唯一断点来源（NFR-RESP-001、components.md §1.1）：<768 手机、768–1279 平板、
 * ≥1280 桌面。数值取自 tokens.ts 的 breakpoints，禁止在组件里写
 * `window.innerWidth < 800` 之类的魔法数。
 *
 * setup 阶段同步读一次视口，避免手机首帧按桌面（1280）布局闪动；
 * SSR 安全：不在模块顶层访问 window。
 */
export function useBreakpoint() {
  const width = ref(
    typeof window === 'undefined' ? breakpoints.desktop : window.innerWidth,
  );
  const coarse = ref(false);
  let queries: MediaQueryList[] = [];

  function read() {
    if (typeof window === 'undefined') return;
    width.value = window.innerWidth;
    coarse.value = window.matchMedia('(pointer: coarse)').matches;
  }

  read();

  function onChange() {
    read();
  }

  onMounted(() => {
    read();
    queries = [
      window.matchMedia(`(max-width: ${breakpoints.tablet - 1}px)`),
      window.matchMedia(`(max-width: ${breakpoints.desktop - 1}px)`),
      window.matchMedia('(pointer: coarse)'),
    ];
    for (const query of queries) query.addEventListener('change', onChange);
    window.addEventListener('resize', onChange);
  });

  onUnmounted(() => {
    for (const query of queries) query.removeEventListener('change', onChange);
    window.removeEventListener('resize', onChange);
  });

  const device = computed<DeviceKind>(() => deviceOf(width.value));

  return {
    device,
    isDesktop: computed(() => device.value === 'desktop'),
    isMobile: computed(() => device.value === 'mobile'),
    isTablet: computed(() => device.value === 'tablet'),
    isTouch: computed(() => coarse.value),
    width,
  };
}
