/**
 * E16-T06：报告单全屏查看器的水印查看人（components.md §12「viewer 来自 me」）。
 *
 * userStore.userInfo 由 /auth/me 映射（realName = me.displayName、username、
 * tenantName）；租户名缺失时为 null（水印文本按「未知医院」兜底，见 @crdp/viewer
 * watermarkText）。平台端视角（TENANT_VIEW）沿用同一会话资料，不在此分支。
 */
import type { WatermarkViewer } from '@crdp/viewer';

import { computed } from 'vue';

import { useUserStore } from '@vben/stores';

export function useWatermarkViewer() {
  const userStore = useUserStore();

  return computed<WatermarkViewer>(() => {
    const info = userStore.userInfo as null | {
      realName?: null | string;
      tenantName?: null | string;
      username?: null | string;
    };

    return {
      account: info?.username ?? '',
      displayName: info?.realName ?? '',
      tenantName: info?.tenantName ?? null,
    };
  });
}
