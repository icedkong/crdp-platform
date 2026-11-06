import { useUserStore } from '@vben/stores';

// E16-T06：水印查看人信息来自 me（components.md §12「viewer 来自 me」）——
// userStore.userInfo（/auth/me 映射：realName=displayName、username、tenantName）。
import { createPinia, setActivePinia } from 'pinia';
import { describe, expect, it } from 'vitest';

import { useWatermarkViewer } from './use-watermark-viewer';

describe('e16-t06 useWatermarkViewer（水印查看人来自 me）', () => {
  it('映射 displayName（realName）/ 账号 / 医院名称', () => {
    setActivePinia(createPinia());
    const userStore = useUserStore();
    userStore.setUserInfo({
      realName: '王医生（示例）',
      tenantName: '医院 A（示例租户）',
      userId: '9',
      username: 'doctor.demo',
    } as never);

    const viewer = useWatermarkViewer();
    expect(viewer.value).toEqual({
      account: 'doctor.demo',
      displayName: '王医生（示例）',
      tenantName: '医院 A（示例租户）',
    });
  });

  it('未登录 / 资料缺失时降级为空串与未知医院（水印文本仍可拼出）', () => {
    setActivePinia(createPinia());
    const viewer = useWatermarkViewer();
    expect(viewer.value).toEqual({
      account: '',
      displayName: '',
      tenantName: null,
    });
  });
});
