// E20-T04 / PLT-VIEW-003（components.md §13 TenantViewBanner：橙色、不可关闭、剩余时长与一键退出）
import type { TenantViewShellSession } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import TenantViewBanner from './tenant-view-banner.vue';

function session(): TenantViewShellSession {
  return {
    accountDisplayName: '平台管理员（示例）',
    accountName: 'platform.admin',
    expiresAt: '2026-11-06T12:00:00+08:00',
    purpose: 'TROUBLESHOOT',
    remainingSeconds: 4200,
    sessionId: 1,
    sessionNo: 'a1b2c3d4e5f60718',
    startedAt: '2026-11-06T10:00:00+08:00',
    tenantCode: 'demoa',
    tenantId: 1,
    tenantName: '虚构示例医院甲',
  };
}

describe('tenantViewBanner（PLT-VIEW-003 视角标识）', () => {
  it('常驻标识：标题「租户视角：租户名称」+ 会话号 + 剩余时长', () => {
    const wrapper = mount(TenantViewBanner, {
      props: { remainingSeconds: 4200, session: session() },
    });
    const text = wrapper.text();
    expect(text).toContain('租户视角：虚构示例医院甲');
    expect(text).toContain('a1b2c3d4e5f60718');
    expect(text).toContain('70 分 0 秒');
    expect(text).toContain('无操作 15 分钟自动退出');
  });

  it('一键退出按钮：点击向父级发 exit（由视角 store 落地）', async () => {
    const wrapper = mount(TenantViewBanner, {
      props: { remainingSeconds: 60, session: session() },
    });
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('exit')).toHaveLength(1);
  });

  it('不可关闭：不渲染关闭图标（components.md §13「不可关闭」）', () => {
    const wrapper = mount(TenantViewBanner, {
      props: { remainingSeconds: 60, session: session() },
    });
    expect(wrapper.find('.ant-alert-close-icon').exists()).toBe(false);
    expect(wrapper.find('[role="button"].ant-alert-close-text').exists()).toBe(
      false,
    );
  });

  it('到时口径：剩余时长按 remainingSeconds 递减展示', () => {
    const wrapper = mount(TenantViewBanner, {
      props: { remainingSeconds: 0, session: session() },
    });
    expect(wrapper.text()).toContain('已到时');
  });
});
