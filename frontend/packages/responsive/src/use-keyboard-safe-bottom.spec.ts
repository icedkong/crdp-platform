/**
 * E24-T02 / NFR-RESP-002 · 键盘弹出时底部操作栏跟随可视区域（pages.md §5.2
 * 「随 visualViewport 跟随键盘」）。
 *
 * useKeyboardSafeBottom 返回底部固定栏距视口底部的偏移（px）：
 * - 无 visualViewport（旧浏览器 / 桌面无键盘）→ 恒 0；
 * - 键盘弹出（visualViewport 变矮）→ 偏移 = innerHeight − vv.height − vv.offsetTop；
 * - visualViewport resize / scroll 与 window resize 都触发重算；卸载后解绑。
 */
import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { afterEach, describe, expect, it } from 'vitest';

import { useKeyboardSafeBottom } from './use-keyboard-safe-bottom';

type Listener = () => void;

/** 可驱动的 visualViewport 桩（happy-dom 未实现键盘语义，手动派发事件）。 */
function stubVisualViewport(initial: { height: number; offsetTop: number }) {
  const state = { ...initial };
  const listeners = new Map<string, Set<Listener>>();
  const vv = {
    get height() {
      return state.height;
    },
    get offsetTop() {
      return state.offsetTop;
    },
    addEventListener(type: string, listener: Listener) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type)?.add(listener);
    },
    removeEventListener(type: string, listener: Listener) {
      listeners.get(type)?.delete(listener);
    },
    dispatch(type: string) {
      for (const listener of listeners.get(type) ?? []) listener();
    },
    setState(next: Partial<typeof state>) {
      Object.assign(state, next);
      vv.dispatch('resize');
      vv.dispatch('scroll');
    },
  };
  Object.defineProperty(window, 'visualViewport', {
    configurable: true,
    value: vv,
  });
  return vv;
}

const realVisualViewport = window.visualViewport;
const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  Object.defineProperty(window, 'visualViewport', {
    configurable: true,
    value: realVisualViewport,
  });
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

function mountHook() {
  let offset!: ReturnType<typeof useKeyboardSafeBottom>;
  const wrapper = mount(
    defineComponent({
      setup() {
        offset = useKeyboardSafeBottom();
        return () => null;
      },
    }),
  );
  mounted.push(wrapper);
  return offset;
}

describe('e24-t02 useKeyboardSafeBottom（底部操作栏跟随键盘）', () => {
  it('无 visualViewport 时恒为 0（不跟随，桌面 / 旧浏览器）', () => {
    Object.defineProperty(window, 'visualViewport', {
      configurable: true,
      value: undefined,
    });
    const offset = mountHook();
    expect(offset.value).toBe(0);
    window.dispatchEvent(new Event('resize'));
    expect(offset.value).toBe(0);
  });

  it('键盘未弹出（visualViewport 占满视口）→ 偏移 0', () => {
    window.innerWidth = 375;
    window.innerHeight = 812;
    stubVisualViewport({ height: 812, offsetTop: 0 });
    const offset = mountHook();
    expect(offset.value).toBe(0);
  });

  it('键盘弹出（可视高度 400）→ 偏移 = innerHeight − height − offsetTop', () => {
    window.innerWidth = 375;
    window.innerHeight = 812;
    const vv = stubVisualViewport({ height: 812, offsetTop: 0 });
    const offset = mountHook();
    expect(offset.value).toBe(0);

    vv.setState({ height: 400, offsetTop: 0 }); // 键盘占掉下方 412px
    expect(offset.value).toBe(412);

    vv.setState({ height: 500, offsetTop: 100 }); // 键盘 + 页面滚动
    expect(offset.value).toBe(212);
  });

  it('卸载后解绑监听（visualViewport 事件不再触发重算）', () => {
    window.innerWidth = 375;
    window.innerHeight = 812;
    const vv = stubVisualViewport({ height: 812, offsetTop: 0 });
    const offset = mountHook();
    mounted.splice(0)[0]?.unmount();

    vv.setState({ height: 400, offsetTop: 0 });
    expect(offset.value).toBe(0); // 已解绑：不再更新
  });
});
