import { watch } from 'vue';

import { updatePreferences } from '@vben/preferences';

import { useBreakpoint } from './use-breakpoint';

/**
 * E24-T01 / NFR-RESP-001 三档外壳布局（design-system §12）：
 *
 * - 桌面 ≥1280：侧栏展开 + 顶栏 + 多标签页；
 * - 平板 768–1279：菜单收成图标栏，保留多标签页（可横向滚动）；
 * - 手机 <768：菜单进抽屉（Vben 外壳 app.isMobile 自带）、不显示多标签页，
 *   且抽屉默认收起（collapsed=false 在手机上会直接弹开抽屉）。
 *
 * 在两端主壳 BasicLayout 的 setup 里各调用一次。与 Vben 自身 app.isMobile
 * 的翻转同款走 updatePreferences（设备态会随断点自动纠正，无需手工清理缓存）。
 */
export function useResponsiveShell() {
  const { device } = useBreakpoint();

  watch(
    device,
    (kind) => {
      if (kind === 'mobile') {
        updatePreferences({
          sidebar: { collapsed: true },
          tabbar: { enable: false },
        });
      } else if (kind === 'tablet') {
        updatePreferences({
          sidebar: { collapsed: true },
          tabbar: { enable: true },
        });
      } else {
        updatePreferences({
          sidebar: { collapsed: false },
          tabbar: { enable: true },
        });
      }
    },
    { immediate: true },
  );
}
