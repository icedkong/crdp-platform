// A-PAT-014, A-NFR-009, C-PAT-013（E13-T06 高级检索弹窗；字段全部虚构）
import type { AdvancedSearchField, ConditionGroup } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import AdvancedSearch from './advanced-search.vue';

function field(
  partial: Partial<AdvancedSearchField> & { field: string; label: string },
): AdvancedSearchField {
  return {
    categoryKey: 'basic',
    categoryLabel: '基本信息',
    common: true,
    ops: ['EQ', 'NE', 'BETWEEN', 'IS_NULL', 'NOT_NULL'],
    options: [],
    type: 'TEXT',
    ...partial,
  };
}

const CATALOG: AdvancedSearchField[] = [
  field({
    field: 'ward',
    label: '病区/中心',
    type: 'OPTION',
    ops: ['EQ', 'NE', 'IS_NULL', 'NOT_NULL'],
    options: [
      { value: 3, label: '示例一病区' },
      { value: 4, label: '示例二病区' },
    ],
  }),
  field({ field: 'discharge_date', label: '出院日期', type: 'DATE' }),
  field({ field: 'note', label: '随访备注（不常用）', common: false }),
];

/** Modal 内容经 teleport 挂到真实 body，测试直接查 body。 */
async function mountModal(props: Record<string, unknown> = {}) {
  const wrapper = mount(AdvancedSearch, {
    attachTo: document.body,
    props: { fieldCatalog: CATALOG, open: true, ...props },
  });
  await new Promise((resolve) => setTimeout(resolve, 30));
  return wrapper;
}

function bodyText(): string {
  return document.body.textContent ?? '';
}

function buttonByText(text: string): HTMLElement | undefined {
  // antd 两字按钮文案自动插入空格（「查 询」），比较时归一空白
  const normalized = text.replaceAll(' ', '');
  return [...document.body.querySelectorAll('button')].find((item) =>
    (item.textContent ?? '').replaceAll(' ', '').includes(normalized),
  );
}

async function clickButton(text: string) {
  const button = buttonByText(text);
  expect(button, `应存在按钮「${text}」`).toBeTruthy();
  button?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
  await new Promise((resolve) => setTimeout(resolve, 30));
}

describe('advancedSearch 弹窗（components.md §11.2）', () => {
  it('打开即带一行默认条件（目录第一个字段与其首个运算符）', async () => {
    const wrapper = await mountModal();
    expect(
      document.body.querySelectorAll('.ant-select').length,
    ).toBeGreaterThan(0);
    expect(bodyText()).toContain('病区/中心');
    expect(bodyText()).toContain('等于');
    wrapper.unmount();
  });

  it('底部显示逻辑表达式预览（选项值显示文案）', async () => {
    const wrapper = await mountModal({
      value: {
        logic: 'and',
        items: [
          { field: 'ward', op: 'EQ', value: 3 },
          { field: 'discharge_date', op: 'IS_NULL' },
        ],
      } as ConditionGroup,
    });
    const preview = document.body.querySelector(
      '[data-testid="advanced-search-preview"]',
    );
    expect(preview?.textContent).toContain('病区/中心 等于 示例一病区');
    expect(preview?.textContent).toContain('出院日期 为空');
    wrapper.unmount();
  });

  it('mobileCommonOnly 只留常用条件', async () => {
    const wrapper = await mountModal({ mobileCommonOnly: true });
    expect(bodyText()).not.toContain('随访备注（不常用）');
    expect(bodyText()).toContain('病区/中心');
    wrapper.unmount();
  });

  it('条件不完整时查询禁用；完整后 apply 上抛条件树并关闭', async () => {
    const wrapper = await mountModal();
    const apply = buttonByText('查询');
    expect(apply).toBeTruthy();
    expect(apply?.hasAttribute('disabled')).toBe(true);

    await wrapper.setProps({
      open: false,
      value: {
        logic: 'or',
        items: [{ field: 'ward', op: 'EQ', value: 3 }],
      } as ConditionGroup,
    });
    await wrapper.setProps({ open: true });
    const applyReady = buttonByText('查询');
    expect(applyReady?.hasAttribute('disabled')).toBe(false);
    await clickButton('查询');
    expect(wrapper.emitted('apply')).toEqual([
      [{ logic: 'or', items: [{ field: 'ward', op: 'EQ', value: 3 }] }],
    ]);
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });

  it('保存为个人筛选：命名后 save-as 上抛（名称 + 条件树）', async () => {
    const wrapper = await mountModal({
      value: {
        logic: 'and',
        items: [{ field: 'discharge_date', op: 'NOT_NULL' }],
      } as ConditionGroup,
    });
    await clickButton('保存为个人筛选');
    const inputs = wrapper
      .findAllComponents({ name: 'AInput' })
      .filter((item) =>
        (item.props('placeholder') as string)?.includes('筛选名称'),
      );
    expect(inputs.length).toBe(1);
    inputs[0]?.vm.$emit('update:value', '上半年出院（示例）');
    await new Promise((resolve) => setTimeout(resolve, 30));
    await clickButton('保存');
    expect(wrapper.emitted('saveAs')).toEqual([
      [
        '上半年出院（示例）',
        { logic: 'and', items: [{ field: 'discharge_date', op: 'NOT_NULL' }] },
      ],
    ]);
    wrapper.unmount();
  });
});

describe('e24-t03 AdvancedSearch：手机底部抽屉形态（pages.md §3、design-system §5）', () => {
  it('mobileCommonOnly 渲染为底部抽屉（不是弹窗），只留常用条件', async () => {
    const wrapper = await mountModal({
      mobileCommonOnly: true,
      value: {
        logic: 'and',
        items: [{ field: 'ward', op: 'EQ', value: 3 }],
      } as ConditionGroup,
    });
    const drawer = document.body.querySelector('.ant-drawer');
    expect(drawer).not.toBeNull();
    expect(drawer?.className).toContain('ant-drawer-bottom');
    expect(document.body.querySelector('.ant-modal')).toBeNull();
    expect(bodyText()).toContain('病区/中心');
    expect(bodyText()).not.toContain('随访备注（不常用）');
    expect(bodyText()).toContain('逻辑表达式预览');
    wrapper.unmount();
  });

  it('底部抽屉里「查询」上抛条件树并关闭', async () => {
    const wrapper = await mountModal({
      mobileCommonOnly: true,
      value: {
        logic: 'and',
        items: [{ field: 'ward', op: 'EQ', value: 3 }],
      } as ConditionGroup,
    });
    await clickButton('查询');
    expect(wrapper.emitted('apply')).toEqual([
      [{ logic: 'and', items: [{ field: 'ward', op: 'EQ', value: 3 }] }],
    ]);
    expect(wrapper.emitted('update:open')?.at(-1)).toEqual([false]);
    wrapper.unmount();
  });
});

// E24-T07 真机兼容：structuredClone 在微信 XWeb 等旧内核（Chromium < 98）不存在，
// 打开高级检索即 TypeError——患者列表 / 任务页真机阻断。删掉全局 structuredClone
// 模拟旧内核，续编回显必须照常。
describe('e24-t07 AdvancedSearch：structuredClone 缺失（微信 XWeb）时打开不崩溃', () => {
  it('无 structuredClone 环境下带条件树打开：正常回显且草稿不回写父状态', async () => {
    const source: ConditionGroup = {
      logic: 'and',
      items: [{ field: 'ward', op: 'EQ', value: 3 }],
    };
    const clone = globalThis.structuredClone;
    Object.defineProperty(globalThis, 'structuredClone', {
      configurable: true,
      value: undefined,
    });
    let wrapper: undefined | { unmount: () => void };
    try {
      wrapper = await mountModal({ value: source });
      expect(bodyText()).toContain('病区/中心');
      expect(bodyText()).toContain('等于');
    } finally {
      Object.defineProperty(globalThis, 'structuredClone', {
        configurable: true,
        value: clone,
      });
      wrapper?.unmount();
    }
    // 源条件树未被续编草稿改动
    expect(source.items[0]?.value).toBe(3);
  });
});
