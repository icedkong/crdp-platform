import type { FormSchema, FormSchemaField } from '@crdp/api';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import FormRenderer from './form-renderer.vue';

/* ----------------------------------------------------------------------
 * A 年度随访表单的迷你模板（字段全部虚构；分组名取 components.md §10.4 A 随访分组），
 * 覆盖 E11-T07 三件事：分类布局（A-FLD-007）、多选互斥（C-FLD-004）、带入上次值（A-FU-015）。
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
      key: 'overview',
      label: '随访概况',
      order: 10,
      fields: [
        f({ key: 'f501', label: '随访日期', type: 'date', required: true }),
        f({
          key: 'f502',
          label: '随访方式',
          type: 'radio',
          required: true,
          optionSet: [
            { code: 1, label: '电话' },
            { code: 2, label: '门诊' },
            { code: 3, label: '微信' },
          ],
        }),
        f({ key: 'f504', label: '被访者', type: 'text' }),
      ],
    },
    {
      key: 'outcome',
      label: '随访结局',
      order: 20,
      fields: [
        f({
          key: 'f510',
          label: '本次结局',
          type: 'select',
          optionSet: [
            { code: 0, label: '正常随访' },
            { code: 1, label: '死亡' },
            { code: 2, label: '失访' },
          ],
        }),
        f({
          key: 'f511',
          label: '死亡日期',
          type: 'date',
          visibleWhen: { all: [{ field: 'f510', op: 'eq', value: 1 }] },
        }),
      ],
    },
    {
      key: 'echo',
      label: '超声复查',
      order: 30,
      fields: [
        f({
          key: 'f520',
          label: 'EF 射血分数',
          type: 'number',
          unit: '%',
          softRange: { min: 40, max: 75 },
        }),
        f({
          key: 'f521',
          label: 'LVDd 舒张末径',
          type: 'number',
          unit: 'mm',
          hardRange: { min: 20, max: 90 },
        }),
      ],
    },
    {
      key: 'medication',
      label: '用药',
      order: 40,
      fields: [
        f({
          key: 'f530',
          label: '他汀类',
          type: 'radio',
          carryOver: true,
          optionSet: [
            { code: 0, label: '未用' },
            { code: 1, label: '辛伐他汀' },
            { code: 2, label: '阿托伐他汀' },
          ],
        }),
        f({
          key: 'f531',
          label: 'β 受体阻滞剂',
          type: 'boolean',
          carryOver: true,
        }),
        f({
          key: 'f532',
          label: '合并用药',
          type: 'multiselect',
          carryOver: true,
          optionSet: [
            { code: 0, label: '无', exclusive: true },
            { code: 1, label: '阿司匹林' },
            { code: 2, label: '氯吡格雷' },
            { code: 9, label: '其他', needsDetail: true },
          ],
        }),
        f({ key: 'f533', label: '用药备注', type: 'textarea' }),
      ],
    },
  ],
  code: 'A_ANNUAL_FU',
  name: '年度随访（示例）',
  rules: [],
  usage: 'FOLLOWUP',
  version: 3,
};

const previous = {
  recordId: '8801',
  date: '2025-11-02',
  values: {
    f520: 56,
    f530: 2,
    f531: true,
    f532: [1, 2],
  } as Record<string, unknown>,
};

function mountRenderer(props: Record<string, unknown> = {}) {
  return mount(FormRenderer, {
    props: {
      schema,
      modelValue: {},
      previous,
      ...props,
    },
  });
}

describe('e11-t07 FormRenderer：按分类渲染 A 的表单（A-FLD-007 / C-FLD-010）', () => {
  it('按模板 order 渲染分组、每个分组输出稳定锚点，组内字段保持模板顺序', () => {
    const wrapper = mountRenderer();
    const sections = wrapper.findAll('[data-testid="form-category"]');
    expect(sections.map((s) => s.attributes('id'))).toEqual([
      'crdp-anchor-overview',
      'crdp-anchor-outcome',
      'crdp-anchor-echo',
      'crdp-anchor-medication',
    ]);
    expect(sections.map((s) => s.find('h4').text())).toEqual([
      '随访概况',
      '随访结局',
      '超声复查',
      '用药',
    ]);
    const medFields = sections[3]?.findAll('[data-testid="form-field"]');
    expect(medFields?.map((row) => row.attributes('data-field-key'))).toEqual([
      'f530',
      'f531',
      'f532',
      'f533',
    ]);
  });

  it('条件显示：未选「死亡」不渲染死亡日期；条件转隐藏时值被清空并发出 cleared-hidden', async () => {
    const wrapper = mountRenderer({
      modelValue: { f510: 1, f511: '2026-10-01' },
    });
    expect(wrapper.find('[data-field-key="f511"]').exists()).toBe(true);

    await wrapper.setProps({ modelValue: { f510: 0, f511: '2026-10-01' } });
    expect(wrapper.find('[data-field-key="f511"]').exists()).toBe(false);
    // 值被清空并上报（隐藏字段不进 Tab 顺序、不计完整度、值清空）
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f510: 0,
    });
    expect(wrapper.emitted('clearedHidden')?.at(-1)?.[0]).toEqual(['f511']);
  });

  it('enter 不提交（阻止默认行为；textarea 保留换行）；失焦发出 field-blur 供本地草稿', async () => {
    const wrapper = mountRenderer();
    const events: string[] = [];
    wrapper.element.addEventListener('keydown', (event) => {
      events.push(event.defaultPrevented ? 'prevented' : 'passthrough');
    });
    // 文本输入的 Enter：阻止默认（不触发隐式提交）
    await wrapper
      .find('[data-field-key="f504"]')
      .find('input')
      .trigger('keydown', {
        key: 'Enter',
      });
    expect(events).toContain('prevented');
    // 多行文本的 Enter：保留换行（不阻止）
    await wrapper
      .find('[data-field-key="f533"]')
      .find('textarea')
      .trigger('keydown', {
        key: 'Enter',
      });
    expect(events).toContain('passthrough');
    // 字段失焦（focusout 冒泡到行）→ field-blur（本地草稿信号，E25 接 useAutoSave）
    await wrapper
      .find('[data-field-key="f533"]')
      .find('textarea')
      .trigger('focusout');
    expect(wrapper.emitted('fieldBlur')?.at(-1)?.[0]).toBe('f533');
  });
});

describe('e11-t07 FormRenderer：带入上次值（A-FU-015 / C-FU-016）', () => {
  it('字段下方显示「上次 xx」；按钮一键带入模板标记的设置项（测量值 EF 不带入）', async () => {
    const wrapper = mountRenderer();
    // 上次值提示
    expect(wrapper.find('[data-field-key="f520"] .previous-value').text()).toBe(
      '上次 56 %',
    );
    expect(wrapper.find('[data-field-key="f530"] .previous-value').text()).toBe(
      '上次 阿托伐他汀',
    );

    // 一键带入
    const button = wrapper.find('[data-testid="carry-over-button"]');
    expect(button.text()).toContain('带入上次值（2025-11-02）');
    await button.trigger('click');
    const emittedData = wrapper
      .emitted('update:modelValue')
      ?.at(-1)?.[0] as Record<string, unknown>;
    expect(emittedData.f530).toBe(2);
    expect(emittedData.f531).toBe(true);
    expect(emittedData.f532).toEqual([1, 2]);
    expect(emittedData.f520).toBeUndefined(); // 测量值不带入
    expect(emittedData.f501).toBeUndefined();
    expect(wrapper.emitted('carryOver')?.at(-1)?.[0]).toEqual([
      'f530',
      'f531',
      'f532',
    ]);
  });

  it('带入的值以浅色标出（已带入上次值 chip），修改后恢复普通样式', async () => {
    const wrapper = mountRenderer();
    await wrapper.find('[data-testid="carry-over-button"]').trigger('click');
    // 受控组件：父级应用带入结果
    await wrapper.setProps({
      modelValue: { f530: 2, f531: true, f532: [1, 2] },
    });
    const carried = wrapper.find('[data-field-key="f530"]');
    expect(carried.classes()).toContain('field-carried');
    expect(carried.find('.carried').text()).toBe('已带入上次值');

    // 用户改动（选中第 2 个选项「辛伐他汀」）→ 该字段恢复普通样式（再修改语义）
    await carried.findAll('input')[1]?.setValue(true);
    const updated = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as Record<
      string,
      unknown
    >;
    expect(updated.f530).toBe(1);
    expect(wrapper.find('[data-field-key="f530"]').classes()).not.toContain(
      'field-carried',
    );
    // 未改动的带入字段仍标浅色
    expect(wrapper.find('[data-field-key="f531"]').classes()).toContain(
      'field-carried',
    );
  });

  it('无上次记录时不渲染按钮；只读模式不渲染按钮', () => {
    const noPrevious = mountRenderer({ previous: null });
    expect(noPrevious.find('[data-testid="carry-over-button"]').exists()).toBe(
      false,
    );
    const readonly = mountRenderer({ mode: 'readonly' });
    expect(readonly.find('[data-testid="carry-over-button"]').exists()).toBe(
      false,
    );
  });
});

describe('e11-t07 FormRenderer：多选互斥与「其他」说明（C-FLD-004）', () => {
  it('勾选「无」自动取消其他选项；已选「无」时其他选项禁用', async () => {
    const wrapper = mountRenderer({ modelValue: { f532: [1] } });
    const checkboxes = () =>
      wrapper.find('[data-field-key="f532"]').findAll('input[type="checkbox"]');

    // 勾「无」（code 0）：其他自动取消
    await checkboxes()[0]?.setValue(true);
    let emittedData = wrapper
      .emitted('update:modelValue')
      ?.at(-1)?.[0] as Record<string, unknown>;
    expect(emittedData.f532).toEqual([0]);

    // 受控组件：父级应用 [0] 后，「无」以外的选项禁用（不可勾选）
    await wrapper.setProps({ modelValue: { f532: [0] } });
    const secondCheckbox = checkboxes()[1]?.element as
      | HTMLInputElement
      | undefined;
    expect(secondCheckbox?.disabled).toBe(true);

    // 取消「无」再勾「阿司匹林」：值只有 [1]（互斥项让位）
    await checkboxes()[0]?.setValue(false);
    await wrapper.setProps({ modelValue: {} });
    await checkboxes()[1]?.setValue(true);
    emittedData = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as Record<
      string,
      unknown
    >;
    expect(emittedData.f532).toEqual([1]);
  });

  it('选「其他」出现补充说明短文本，说明写入字段备注（update:meta）；未填时 validate 拦截', async () => {
    const wrapper = mountRenderer({ modelValue: { f532: [9] } });
    const group = wrapper.find('[data-field-key="f532"]');
    const detail = group.find('input[data-role="option-detail"]');
    expect(detail.exists()).toBe(true);

    await detail.setValue('尼可地尔（示例）');
    const meta = wrapper.emitted('update:meta')?.at(-1)?.[0] as Record<
      string,
      unknown
    >;
    expect(meta.f532).toEqual({ note: '尼可地尔（示例）' });

    // 未填说明：validate 返回 needsDetail 硬错误（勾选“其他”未填说明时不能保存）
    const empty = mountRenderer({ modelValue: { f532: [9] } });
    const vm = empty.vm as unknown as {
      validate: () => Promise<{
        hardErrors: Array<{ key: string; rule: string }>;
      }>;
    };
    const validateResult = await vm.validate();
    expect(
      validateResult.hardErrors.some(
        (issue) => issue.key === 'f532' && issue.rule === 'needsDetail',
      ),
    ).toBe(true);

    // 软范围提示直接显示（EF 30 低于提示范围 40：橙色提示，不拦截）
    const soft = mountRenderer({ modelValue: { f520: 30 } });
    expect(
      soft.find('[data-field-key="f520"] .field-warning').text(),
    ).toContain('取值应在 40–75 之间');
  });

  it('必填缺失与硬范围在 showErrors 时标红；validate() 汇总缺失必填', async () => {
    const wrapper = mountRenderer({
      modelValue: { f521: 300 },
      showErrors: true,
    });
    expect(wrapper.find('[data-field-key="f501"] .field-error').text()).toBe(
      '必填',
    );
    expect(
      wrapper.find('[data-field-key="f521"] .field-error').text(),
    ).toContain('取值应在 20–90 之间');
    const vm = wrapper.vm as unknown as {
      validate: () => Promise<{
        missingRequired: Array<{ key: string }>;
        ok: boolean;
      }>;
    };
    const validateResult = await vm.validate();
    expect(validateResult.ok).toBe(false);
    expect(validateResult.missingRequired.map((item) => item.key)).toEqual([
      'f501',
      'f502',
    ]);
  });
});

describe('e11-t07 FormRenderer：只读视图（mode=readonly）', () => {
  it('不渲染输入控件；空值显示「未填写」；多选显示选项名', () => {
    const wrapper = mountRenderer({
      mode: 'readonly',
      modelValue: { f502: 1, f532: [1, 2], f531: true },
    });
    expect(wrapper.find('input').exists()).toBe(false);
    expect(wrapper.find('[data-field-key="f501"]').text()).toContain('未填写');
    expect(wrapper.find('[data-field-key="f502"]').text()).toContain('电话');
    expect(wrapper.find('[data-field-key="f532"]').text()).toContain(
      '阿司匹林、氯吡格雷',
    );
    expect(wrapper.find('[data-field-key="f531"]').text()).toContain('是');
  });
});
