// M3 审查 A1：MaskedField 的明文入口只认 `followup:patient:plain`
// （components.md §3 / AGENTS.md §5.3）。组件已删除 `referenceMode` 演示通道，
// 这里锁定：不管传什么 props，没有权限就不渲染明文按钮、不发起明文请求，
// 页面文本始终是脱敏值；有权限才走专用接口且 60 秒后可恢复脱敏。数据全部虚构。
import { mount } from '@vue/test-utils';
import { ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import MaskedField from './masked-field.vue';

// 权限由用例切换：false = 会话没有 followup:patient:plain
const permissionState = { allowed: false };
vi.mock('#/reference/use-permission', () => ({
  usePermission: () => ({
    hasPermission: (code: string) =>
      code === 'followup:patient:plain' && permissionState.allowed,
  }),
}));

// 桌面口径（Popconfirm）；手机形态（Modal）只是同一确认的另一种容器，不重复测
vi.mock('#/reference/use-breakpoint', () => ({
  useBreakpoint: () => ({ isMobile: ref(false) }),
}));

const revealMock = vi.fn();
vi.mock('#/api/core', () => ({
  revealPatientPlaintext: (...args: unknown[]) => revealMock(...args),
}));

const MASKED_NAME = '张*明（示例）';
const PLAIN_NAME = '张小明（示例）';

const mounted: Array<{ unmount: () => void }> = [];

function mountField(props: Record<string, unknown> = {}) {
  const wrapper = mount(MaskedField, {
    attachTo: document.body,
    props: {
      field: 'name',
      patientId: '301',
      value: MASKED_NAME,
      ...props,
    },
  });
  mounted.push(wrapper);
  return wrapper;
}

beforeEach(() => {
  permissionState.allowed = false;
  revealMock.mockReset();
});

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

describe('a1 MaskedField：无 followup:patient:plain 权限不渲染明文', () => {
  it('无权限：即使 revealable 也不渲染「显示明文」按钮，文本保持脱敏值', () => {
    const wrapper = mountField({ revealable: true });
    expect(wrapper.text()).toContain(MASKED_NAME);
    expect(wrapper.text()).not.toContain(PLAIN_NAME);
    expect(
      [...document.body.querySelectorAll('button')].some((item) =>
        (item.textContent ?? '').includes('显示明文'),
      ),
      '无权限不得渲染明文入口',
    ).toBe(false);
  });

  it('无权限：不发起明文请求（也不存在任何可触发路径）', async () => {
    mountField({ revealable: true });
    await Promise.resolve();
    expect(revealMock).not.toHaveBeenCalled();
  });

  it('revealable 关闭：即使有权限也不渲染明文入口', () => {
    permissionState.allowed = true;
    mountField({ revealable: false });
    expect(
      [...document.body.querySelectorAll('button')].some((item) =>
        (item.textContent ?? '').includes('显示明文'),
      ),
    ).toBe(false);
    expect(revealMock).not.toHaveBeenCalled();
  });

  it('有权限：确认后走明文专用接口显示明文，「隐藏」恢复脱敏', async () => {
    permissionState.allowed = true;
    revealMock.mockResolvedValue({
      expiresInSeconds: 60,
      values: { name: PLAIN_NAME },
    });
    const wrapper = mountField({ revealable: true });

    const popconfirm = wrapper.findComponent({ name: 'APopconfirm' });
    expect(popconfirm.exists(), '有权限时渲染确认入口').toBe(true);
    popconfirm.vm.$emit('confirm');
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain(PLAIN_NAME);
    });

    expect(revealMock).toHaveBeenCalledWith('301', ['name'], 'detail');

    const hide = [...document.body.querySelectorAll('button')].find((item) =>
      (item.textContent ?? '').includes('隐藏'),
    );
    expect(hide, '明文态提供隐藏按钮').toBeTruthy();
    hide?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain(MASKED_NAME);
      expect(wrapper.text()).not.toContain(PLAIN_NAME);
    });
  });

  it('目录外字段：有权限也不发起明文请求，保持脱敏值', async () => {
    permissionState.allowed = true;
    const wrapper = mountField({ field: 'not-in-catalog', revealable: true });
    wrapper.findComponent({ name: 'APopconfirm' }).vm.$emit('confirm');
    await Promise.resolve();
    expect(revealMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain(MASKED_NAME);
  });
});
