/**
 * E24-T02 / NFR-RESP-001、NFR-RESP-002：表单渲染器响应式（pages.md §5.2、
 * design-system §12）。
 *
 * 锁定三档形态（断点来自 tokens.breakpoints，渲染器统一用 @crdp/responsive 的
 * useBreakpoint，不写魔法数）：
 * - 桌面 ≥1280：字段按类型定宽流动排布、标签右对齐 140px（§6.2 不回归）；
 * - 平板 768–1279：字段单列、标签右对齐 120px；
 * - 手机 <768：单列、标签在上（vertical）、输入框占满宽（高 36 / 字号 16 由
 *   globalCss 的 .crdp-form 手机媒体查询提供——渲染器根节点挂 crdp-form）、
 *   数值字段弹数字键盘（inputmode="decimal"，含长 × 宽双数值）。
 */
import type { FormSchema, FormSchemaField } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { afterEach, describe, expect, it } from 'vitest';

import FormRenderer from './form-renderer.vue';

function f(
  partial: Partial<FormSchemaField> & { key: string; label: string },
): FormSchemaField {
  return {
    code: partial.key,
    required: false,
    sensitive: false,
    storage: 'json',
    type: 'text',
    ...partial,
  };
}

const schema: FormSchema = {
  categories: [
    {
      key: 'overview',
      label: '随访概况',
      order: 10,
      fields: [
        f({ key: 'f701', label: '随访日期', type: 'date', required: true }),
        f({ key: 'f702', label: 'EF 射血分数', type: 'number', unit: '%' }),
        f({ key: 'f703', label: '病灶大小', type: 'pair_number' }),
        f({ key: 'f704', label: '备注', type: 'text' }),
      ],
    },
    {
      key: 'echo',
      label: '超声复查',
      order: 20,
      fields: [f({ key: 'f710', label: 'LVDd 舒张末径', type: 'number' })],
    },
  ],
  code: 'A_ANNUAL_FU',
  name: '年度随访（示例）',
  rules: [],
  usage: 'FOLLOWUP',
  version: 3,
};

const mounted: Array<{ unmount: () => void }> = [];

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount();
});

/** 视口宽度在挂载前设置（useBreakpoint 在 setup 同步读一次视口）。 */
function mountAt(width: number, props: Record<string, unknown> = {}) {
  window.innerWidth = width;
  const wrapper = mount(FormRenderer, {
    props: { schema, modelValue: {}, ...props },
  });
  mounted.push(wrapper);
  return wrapper;
}

function rootStyle(wrapper: ReturnType<typeof mount>): string {
  return wrapper.find('.form-renderer').attributes('style') ?? '';
}

describe('e24-t02 FormRenderer 响应式：三档布局（NFR-RESP-001）', () => {
  it('桌面（≥1280）：字段流动排布不单列，标签右对齐 140px，数值按类型定宽', () => {
    const wrapper = mountAt(1440);
    const root = wrapper.find('.form-renderer');
    expect(root.classes()).toContain('form-desktop');
    expect(root.classes()).not.toContain('form-mobile');
    expect(wrapper.find('.category-fields').classes()).not.toContain(
      'single-column',
    );
    expect(rootStyle(wrapper)).toContain('--form-label-width: 140px');
    // 数值字段按类型定宽（不随屏幕拉伸，§6.2 桌面不回归）
    expect(
      wrapper
        .find('[data-field-key="f702"]')
        .find('.ant-input-number')
        ?.attributes('style'),
    ).toContain('var(--form-field-number)');
  });

  it('平板（768–1279）：字段单列，标签右对齐 120px（仍横向，标签不在上）', () => {
    const wrapper = mountAt(1024);
    const root = wrapper.find('.form-renderer');
    expect(root.classes()).toContain('form-tablet');
    expect(root.classes()).not.toContain('form-mobile');
    // 单列：每个字段占满一行
    expect(wrapper.find('.category-fields').classes()).toContain(
      'single-column',
    );
    expect(rootStyle(wrapper)).toContain('--form-label-width: 120px');
    // 平板标签仍在左侧（右对齐 120），不是「标签在上」
    expect(wrapper.find('[data-field-key="f701"]').classes()).not.toContain(
      'field-vertical',
    );
  });

  it('手机（<768）：单列、标签在上、输入框占满宽；根节点挂 crdp-form（globalCss 手机 36/16 生效）', () => {
    const wrapper = mountAt(375);
    const root = wrapper.find('.form-renderer');
    expect(root.classes()).toContain('form-mobile');
    expect(root.classes()).toContain('crdp-form');
    expect(wrapper.find('.category-fields').classes()).toContain(
      'single-column',
    );
    // 标签在上（vertical）
    expect(wrapper.find('[data-field-key="f701"]').classes()).toContain(
      'field-vertical',
    );
    // 输入框占满宽（日期 / 数值 / 文本不再按桌面定宽）
    expect(
      wrapper
        .find('[data-field-key="f701"]')
        .find('.ant-picker')
        .attributes('style'),
    ).toContain('width: 100%');
    expect(
      wrapper
        .find('[data-field-key="f702"]')
        .find('.ant-input-number')
        .attributes('style'),
    ).toContain('width: 100%');
    expect(
      wrapper
        .find('[data-field-key="f704"]')
        .find('.ant-input')
        .attributes('style'),
    ).toContain('width: 100%');
  });

  it('只读视图：平板 / 手机同样单列；标签保持右对齐 120（§6.2 只读不回归）', () => {
    const tablet = mountAt(1024, { mode: 'readonly' });
    expect(tablet.find('.category-fields').classes()).toContain(
      'single-column',
    );
    expect(rootStyle(tablet)).toContain('--form-label-width: 120px');

    const mobile = mountAt(375, { mode: 'readonly' });
    expect(mobile.find('.category-fields').classes()).toContain(
      'single-column',
    );
    expect(mobile.find('[data-field-key="f701"]').classes()).not.toContain(
      'field-vertical',
    );
  });
});

describe('e24-t02 FormRenderer：数字键盘（NFR-RESP-002）', () => {
  it('数值字段 inputmode=decimal（手机弹数字键盘；平板 / 桌面同样设置）', () => {
    const wrapper = mountAt(375);
    expect(
      wrapper
        .find('[data-field-key="f702"]')
        .find('input')
        .attributes('inputmode'),
    ).toBe('decimal');
  });

  it('长 × 宽双数值（pair_number）两个输入都弹数字键盘', () => {
    const wrapper = mountAt(375);
    const inputs = wrapper.find('[data-field-key="f703"]').findAll('input');
    expect(inputs).toHaveLength(2);
    for (const input of inputs) {
      expect(input.attributes('inputmode')).toBe('decimal');
    }
  });
});
