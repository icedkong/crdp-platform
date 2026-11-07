import type { CompareRule, FormSchema, FormSchemaField } from '@crdp/api';
import type { CompareHit } from '@crdp/rules';

import { mount } from '@vue/test-utils';

import { Tooltip } from 'ant-design-vue';
import { describe, expect, it } from 'vitest';

import FormRenderer from './form-renderer.vue';

/**
 * E17-T03 / A-FU-027：FormRenderer 对比标红（design-system §14、components.md §10.3
 * 「对比标红」行）。验收「标红字段旁显示对比值与变化量」：
 *
 * - `highlightEnabled` 且模板带启用 compareRules 时实时求值（@crdp/rules
 *   evaluateCompareRules，与后端 CompareEvaluator 共享用例锁定一致）；
 * - 命中字段：红底（.crdp-field-flag）+ ▲ + 变化量（下降 11 个百分点）+ 对比值
 *   （上次 56 %；第 1 年基线回落显示「出院前 xx」）；悬停 tooltip 显示规则说明；
 * - 分组标题显示 ▲n；`highlight` 事件上抛命中列表；不弹窗、不拦提交；
 * - 开关关闭 / 无启用规则不标红（AGENTS.md 规则 7：开关关闭前端隐藏）。
 */
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
      key: 'echo',
      label: '超声复查',
      order: 10,
      fields: [
        f({ key: 'f520', label: 'EF 射血分数', type: 'number', unit: '%' }),
      ],
    },
  ],
  code: 'A_ANNUAL_FU',
  name: '年度随访（示例）',
  rules: [],
  usage: 'FOLLOWUP',
  version: 3,
};

/** Q41 规则行（fu_compare_rule 启用行形状，E17-T01 CompareRule）。 */
function rule(
  partial: Partial<CompareRule> & { ruleCode: string },
): CompareRule {
  return {
    categoryKey: 'echo',
    enabled: true,
    fieldLabel: 'EF 射血分数',
    fieldUnit: '%',
    formCode: 'A_ANNUAL_FU',
    formName: '年度随访记录',
    id: 11,
    message: 'EF 比上次（或出院前）下降 ≥ 10 个百分点',
    name: '射血分数下降',
    params: { direction: 'DROP', threshold: 10 },
    ruleType: 'DIFF',
    ruleTypeLabel: '差值下降 / 上升',
    targetField: 'f520',
    ...partial,
  } as CompareRule;
}

function mountRenderer(props: Record<string, unknown> = {}) {
  return mount(FormRenderer, {
    props: {
      schema,
      modelValue: {},
      ...props,
    },
  });
}

describe('e17-t03 FormRenderer：对比标红（A-FU-027 / Q41）', () => {
  it('eF 56 → 45 下降 11：红底 + ▲ + 变化量 + 对比值，tooltip 为规则说明，分组标题 ▲1', () => {
    const wrapper = mountRenderer({
      highlightEnabled: true,
      schema: { ...schema, compareRules: [rule({ ruleCode: 'ef_drop' })] },
      modelValue: { f520: 45 },
      previous: {
        date: '2025-11-02',
        kind: 'FOLLOWUP',
        recordId: '8801',
        values: { f520: 56 },
      },
    });

    const field = wrapper.find('[data-field-key="f520"]');
    expect(field.classes()).toContain(
      'crdp-field-flag',
      '命中字段红底（globalCss .crdp-field-flag）',
    );
    expect(wrapper.find('[data-testid="field-highlight-f520"]').text()).toBe(
      '▲下降 11 个百分点（上次 56 %）',
    );
    expect(wrapper.find('[data-testid="category-highlight-echo"]').text()).toBe(
      '▲1',
    );
    // tooltip 显示规则与阈值来源（design-system §14「悬停显示规则与阈值来源」）
    expect(wrapper.findComponent(Tooltip).props('title')).toBe(
      'EF 比上次（或出院前）下降 ≥ 10 个百分点',
    );
    // highlight 事件上抛命中列表（页面并入 CategoryBar 标红数）
    const events = wrapper.emitted<CompareHit[][]>('highlight');
    expect(events?.at(-1)?.[0]).toMatchObject([
      {
        ruleId: 'ef_drop',
        key: 'f520',
        categoryKey: 'echo',
        previousValue: 56,
        change: -11,
        changeText: '下降 11 个百分点',
      },
    ]);
  });

  it('第 1 年基线回落：对比值显示「出院前 xx」（Q41 出院前超声）', () => {
    const wrapper = mountRenderer({
      highlightEnabled: true,
      schema: {
        ...schema,
        compareRules: [
          rule({
            ruleCode: 'ef_drop',
            baselineField: 'f102',
            baselineFormCode: 'A_BASELINE',
          }),
        ],
      },
      modelValue: { f520: 44 },
      previous: { kind: 'BASELINE', values: { f102: 55 } },
    });

    expect(wrapper.find('[data-testid="field-highlight-f520"]').text()).toBe(
      '▲下降 11 个百分点（出院前 55 %）',
    );
  });

  it('实时求值：改回阈值内（44 → 46 下降 9 < 10）标红消失、事件更新', async () => {
    const wrapper = mountRenderer({
      highlightEnabled: true,
      schema: { ...schema, compareRules: [rule({ ruleCode: 'ef_drop' })] },
      modelValue: { f520: 44 },
      previous: {
        date: '2025-11-02',
        kind: 'FOLLOWUP',
        recordId: '8801',
        values: { f520: 55 },
      },
    });
    expect(wrapper.find('[data-testid="field-highlight-f520"]').exists()).toBe(
      true,
    );

    await wrapper.setProps({ modelValue: { f520: 46 } });

    expect(wrapper.find('[data-testid="field-highlight-f520"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.find('[data-testid="category-highlight-echo"]').exists(),
    ).toBe(false);
    expect(wrapper.emitted<CompareHit[][]>('highlight')?.at(-1)?.[0]).toEqual(
      [],
    );
  });

  it('固定阈值规则（OUT_OF_RANGE）：无变化量，只显示 ▲ 与 tooltip 规则说明', () => {
    const wrapper = mountRenderer({
      highlightEnabled: true,
      schema: {
        ...schema,
        compareRules: [
          rule({
            ruleCode: 'ef_low',
            message: 'EF < 40%',
            params: { op: 'LT', value: 40 },
            ruleType: 'OUT_OF_RANGE',
          }),
        ],
      },
      modelValue: { f520: 38 },
      previous: null,
    });

    expect(wrapper.find('[data-testid="field-highlight-f520"]').text()).toBe(
      '▲',
    );
    expect(wrapper.findComponent(Tooltip).props('title')).toBe('EF < 40%');
  });

  it('开关关闭（highlightEnabled 缺省 false）或规则停用不标红（AGENTS.md 规则 7）', () => {
    const props = {
      modelValue: { f520: 45 },
      previous: {
        date: '2025-11-02',
        kind: 'FOLLOWUP',
        recordId: '8801',
        values: { f520: 56 },
      },
    };
    const withRules = {
      ...props,
      schema: { ...schema, compareRules: [rule({ ruleCode: 'ef_drop' })] },
    };
    // 缺省 false：不渲染标记，也不发 highlight 事件
    const off = mountRenderer(withRules);
    expect(off.find('[data-testid="field-highlight-f520"]').exists()).toBe(
      false,
    );
    expect(off.emitted('highlight')).toBeUndefined();
    // 规则停用（enabled: false）：不参与求值
    const disabled = mountRenderer({
      ...withRules,
      highlightEnabled: true,
      schema: {
        ...schema,
        compareRules: [rule({ ruleCode: 'ef_drop', enabled: false })],
      },
    });
    expect(disabled.find('[data-testid="field-highlight-f520"]').exists()).toBe(
      false,
    );
  });

  it('只读视图不实时标红（提交后的标红展示随 E17-T04 持久化）', () => {
    const wrapper = mountRenderer({
      highlightEnabled: true,
      mode: 'readonly',
      schema: { ...schema, compareRules: [rule({ ruleCode: 'ef_drop' })] },
      modelValue: { f520: 45 },
      previous: {
        date: '2025-11-02',
        kind: 'FOLLOWUP',
        recordId: '8801',
        values: { f520: 56 },
      },
    });

    expect(wrapper.find('[data-testid="field-highlight-f520"]').exists()).toBe(
      false,
    );
  });
});
