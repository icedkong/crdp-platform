import { mount } from '@vue/test-utils';
/**
 * E24-T01 / NFR-RESP-002、OQ-24 · MobileBlocker 组件单测（components.md §22）。
 *
 * 锁定：非响应式页面在手机打开时的整页替换内容——a-result status=info、
 * 标题「请在电脑或平板上操作」、按钮「返回首页」点击后回首页。
 */
import { reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import MobileBlocker from './mobile-blocker.vue';

const push = vi.hoisted(() => vi.fn());

vi.mock('vue-router', async (importOriginal) => {
  const original = await importOriginal<typeof import('vue-router')>();
  return {
    ...original,
    useRouter: () => ({ push }),
  };
});

vi.mock('@vben/preferences', () => {
  return {
    preferences: reactive({
      app: { defaultHomePath: '/dashboard' },
    }),
  };
});

const mounted: Array<{ unmount: () => void }> = [];

function mountBlocker(props: { homePath?: string } = {}) {
  const wrapper = mount(MobileBlocker, { props });
  mounted.push(wrapper);
  return wrapper;
}

beforeEach(() => {
  push.mockClear();
});

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

describe('手机阻断页（文案照抄 components.md §22）', () => {
  it('显示 info 结果与标题「请在电脑或平板上操作」', () => {
    const wrapper = mountBlocker();
    expect(wrapper.text()).toContain('请在电脑或平板上操作');
    expect(wrapper.find('.ant-result-info').exists()).toBe(true);
  });

  it('按钮「返回首页」缺省回到 preferences.app.defaultHomePath', async () => {
    const wrapper = mountBlocker();
    const button = wrapper.find('button');
    expect(button.text()).toBe('返回首页');
    await button.trigger('click');
    expect(push).toHaveBeenCalledWith('/dashboard');
  });

  it('可用 homePath 覆盖返回目标', async () => {
    const wrapper = mountBlocker({ homePath: '/followup/patient' });
    await wrapper.find('button').trigger('click');
    expect(push).toHaveBeenCalledWith('/followup/patient');
  });
});
