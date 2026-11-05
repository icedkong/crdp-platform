/**
 * E24-T02 / NFR-RESP-002 · 分类导航的响应式形态（pages.md §5.2）：
 * 平板 / 桌面 = 吸顶横向滚动的分组条（mode="bar"，E15-T02 既有形态）；
 * 手机 = 「跳到分组」下拉（触发按钮）+ 底部抽屉（design-system §7：底部抽屉
 * 100% 宽，只用于手机筛选 / 操作菜单 / 附件导航 / 手机「跳到分组」）。
 */
import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import CategoryBar from './category-bar.vue';

const items = [
  { key: 'overview', label: '随访概况', filled: 2, total: 2 },
  { key: 'outcome', label: '随访结局', filled: 0, total: 1 },
  { key: 'echo', label: '超声复查', filled: 1, total: 3 },
];

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
  document.body.innerHTML = '';
});

function mountBar(props: Record<string, unknown> = {}) {
  const wrapper = mount(CategoryBar, {
    attachTo: document.body,
    props: { items, ...props },
  });
  mounted.push(wrapper);
  return wrapper;
}

/** Drawer teleport 挂到 body，渲染需要一拍。 */
async function tick(ms = 30): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms));
}

describe('e24-t02 CategoryBar：手机「跳到分组」下拉 + 底部抽屉', () => {
  it('select 形态：渲染「跳到分组」触发按钮（显示当前分组），点击打开底部抽屉', async () => {
    const wrapper = mountBar({ mode: 'select', activeKey: 'echo' });
    const trigger = wrapper.find('[data-testid="category-jump-trigger"]');
    expect(trigger.exists()).toBe(true);
    expect(trigger.text()).toContain('跳到分组');
    expect(trigger.text()).toContain('超声复查');

    await trigger.trigger('click');
    await tick();
    const drawer = document.body.querySelector('.ant-drawer');
    expect(drawer).not.toBeNull();
    // 底部抽屉（placement=bottom）
    expect(drawer?.className).toContain('ant-drawer-bottom');
    expect(document.body.textContent).toContain('跳到分组');
    // 抽屉里列出分组与已填 / 应填
    expect(document.body.textContent).toContain('随访概况');
    expect(document.body.textContent).toContain('2/2');
    expect(document.body.textContent).toContain('0/1');
  });

  it('抽屉内点分组：发出 jump 并关闭抽屉', async () => {
    const wrapper = mountBar({ mode: 'select' });
    await wrapper
      .find('[data-testid="category-jump-trigger"]')
      .trigger('click');
    await tick();

    const chips = [
      ...document.body.querySelectorAll('.ant-drawer .chip'),
    ] as HTMLElement[];
    expect(chips.length).toBe(3);
    chips[1]?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await tick();

    expect(wrapper.emitted('jump')?.at(-1)?.[0]).toBe('outcome');
    expect(wrapper.emitted('update:activeKey')?.at(-1)?.[0]).toBe('outcome');
    // 选择后抽屉关闭
    await tick(200);
    const mask = document.body.querySelector('.ant-drawer-open');
    expect(mask).toBeNull();
  });

  it('bar 形态（平板 / 桌面）不弹抽屉：分组条直接跳转', async () => {
    const wrapper = mountBar({ mode: 'bar' });
    expect(wrapper.find('[data-testid="category-bar"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="category-jump-trigger"]').exists()).toBe(
      false,
    );

    const chips = wrapper.findAll('[data-testid="category-bar"] .chip');
    expect(chips).toHaveLength(3);
    await chips[2]?.trigger('click');
    expect(wrapper.emitted('jump')?.at(-1)?.[0]).toBe('echo');
    expect(document.body.querySelector('.ant-drawer')).toBeNull();
  });

  it('auto 形态按断点切换：手机视口用「跳到分组」触发按钮', () => {
    window.innerWidth = 375;
    const wrapper = mountBar({ mode: 'auto' });
    expect(wrapper.find('[data-testid="category-jump-trigger"]').exists()).toBe(
      true,
    );

    window.innerWidth = 1024;
    window.dispatchEvent(new Event('resize'));
    const desktop = mountBar({ mode: 'auto' });
    expect(desktop.find('[data-testid="category-bar"]').exists()).toBe(true);
  });
});
