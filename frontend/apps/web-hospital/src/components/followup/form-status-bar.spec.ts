// NFR-SAVE-001/002/003 · FormStatusBar 单测（components.md §13、design-system §13 状态提示照抄）
import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import FormStatusBar from './form-status-bar.vue';

/**
 * 锁定：四态文案照抄（已自动保存 HH:mm / 有改动待同步（每 2 分钟）/
 * 离线 · 改动存在本机草稿 / 版本冲突 · 自动保存已暂停）；idle 不渲染；
 * 语气色随状态（成功 / 次要 / 警告 / 错误，tokens.colors）。
 */
describe('formStatusBar（自动保存状态提示）', () => {
  it('saved：显示「已自动保存 HH:mm」，成功色', () => {
    const wrapper = mount(FormStatusBar, {
      props: { lastSavedAt: '14:02', status: 'saved' },
    });
    expect(wrapper.text()).toBe('已自动保存 14:02');
    expect(wrapper.classes()).toContain('status-saved');
  });

  it('dirty：显示「有改动待同步（每 2 分钟）」', () => {
    const wrapper = mount(FormStatusBar, {
      props: { status: 'dirty' },
    });
    expect(wrapper.text()).toBe('有改动待同步（每 2 分钟）');
    expect(wrapper.classes()).toContain('status-dirty');
  });

  it('offline：显示「离线 · 改动存在本机草稿」，警告色', () => {
    const wrapper = mount(FormStatusBar, {
      props: { status: 'offline' },
    });
    expect(wrapper.text()).toBe('离线 · 改动存在本机草稿');
    expect(wrapper.classes()).toContain('status-offline');
  });

  it('conflict：显示「版本冲突 · 自动保存已暂停」，错误色', () => {
    const wrapper = mount(FormStatusBar, {
      props: { status: 'conflict' },
    });
    expect(wrapper.text()).toBe('版本冲突 · 自动保存已暂停');
    expect(wrapper.classes()).toContain('status-conflict');
  });

  it('idle：不渲染任何内容', () => {
    const wrapper = mount(FormStatusBar, {
      props: { status: 'idle' },
    });
    expect(wrapper.find('[data-testid="form-status-bar"]').exists()).toBe(
      false,
    );
    expect(wrapper.text()).toBe('');
  });
});
