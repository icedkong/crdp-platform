import type { DeviceKind } from '@crdp/tokens';

import { computed, onMounted, onUnmounted, ref } from 'vue';

import { breakpoints, deviceOf } from '@crdp/tokens';

/** 全站唯一断点来源。不在模块顶层读 window。 */
export function useBreakpoint() {
  const width = ref(1280);
  const coarse = ref(false);
  let queries: MediaQueryList[] = [];

  function read() {
    if (typeof window === 'undefined') return;
    width.value = window.innerWidth;
    coarse.value = window.matchMedia('(pointer: coarse)').matches;
  }

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
