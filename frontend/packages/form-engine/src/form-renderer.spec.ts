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

/* ----------------------------------------------------------------------
 * E11-T05 / A-FLD-005：派生重算（渲染器实时重算 + 来源缺失手工录入 + 结果无效提示，
 * 字段与公式全部虚构；函数语义与后端 FormDerivedEngine 由共享用例锁定）。
 */
const derivedSchema: FormSchema = {
  categories: [
    {
      key: 'basic_info',
      label: '病例基本信息',
      order: 10,
      fields: [
        f({ key: 'f011', label: '身高', type: 'number', unit: 'cm' }),
        f({ key: 'f013', label: '体重', type: 'number', unit: 'kg' }),
        f({
          key: 'f031',
          label: 'BMI',
          type: 'derived',
          derived: { fn: 'BMI', args: ['f013', 'f011'] },
        }),
      ],
    },
    {
      key: 'surgery',
      label: '手术信息',
      order: 20,
      fields: [
        f({ key: 'f020', label: '手术日期', type: 'date' }),
        f({ key: 'f021', label: '出院日期', type: 'date' }),
        f({
          key: 'f030',
          label: '术后住院天数',
          type: 'derived',
          unit: '天',
          derived: { fn: 'DAYS_INCLUSIVE', args: ['f020', 'f021'] },
        }),
      ],
    },
  ],
  code: 'A_BASELINE',
  name: '基线档案（示例）',
  rules: [],
  usage: 'BASELINE',
  version: 1,
};

function mountDerived(props: Record<string, unknown> = {}) {
  return mount(FormRenderer, {
    props: { schema: derivedSchema, modelValue: {}, ...props },
  });
}

describe('e11-t05 FormRenderer：派生重算（A-FLD-005 / C-FLD-006）', () => {
  it('录入身高 175、体重 70 → BMI 实时算出 22.9（依赖改动即重算并回写）', async () => {
    const wrapper = mountDerived({ modelValue: { f011: 175, f013: 70 } });
    // 挂载即重算回写（tech-plan §6「前端实时显示」）
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f011: 175,
      f013: 70,
      f031: 22.9,
    });
    // 父级应用回写后显示计算值（灰底只读 + chip）
    await wrapper.setProps({
      modelValue: { f011: 175, f013: 70, f031: 22.9 },
    });
    expect(wrapper.find('[data-testid="field-display-f031"]').text()).toContain(
      '22.9',
    );
    expect(wrapper.find('[data-field-key="f031"]').find('input').exists()).toBe(
      false,
    );

    // 改体重 → 重算（来源字段改动实时重算，A-FLD-005）
    await wrapper.setProps({
      modelValue: { f011: 175, f013: 80, f031: 22.9 },
    });
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f011: 175,
      f013: 80,
      f031: 26.1,
    });
  });

  it('修改出院日期后术后住院天数随之更新；出院早于手术 → 留空并提示原因', async () => {
    const wrapper = mountDerived({
      modelValue: { f020: '2026-03-01', f021: '2026-03-08' },
    });
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f020: '2026-03-01',
      f021: '2026-03-08',
      f030: 8,
    });

    // 出院改为 2026-02-28（早于手术）：留空并提示「出院日期早于手术日期」
    await wrapper.setProps({
      modelValue: {
        f020: '2026-03-01',
        f021: '2026-02-28',
        f030: 8,
      },
    });
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f020: '2026-03-01',
      f021: '2026-02-28',
    });
    expect(wrapper.find('[data-testid="derived-hint-f030"]').text()).toBe(
      '出院日期早于手术日期',
    );
  });

  it('来源缺失 → 留空提示「缺少身高」并允许手工录入（meta.manual = true）；来源补齐后以计算值为准', async () => {
    const wrapper = mountDerived({ modelValue: { f013: 70 } });
    expect(wrapper.find('[data-testid="derived-hint-f031"]').text()).toBe(
      '缺少身高',
    );
    // 来源缺失：放开手工录入（多见于历史数据）
    const input = wrapper.find('[data-field-key="f031"]').find('input');
    expect(input.exists()).toBe(true);
    await input.setValue(21.4);
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f013: 70,
      f031: 21.4,
    });
    expect(wrapper.emitted('update:meta')?.at(-1)?.[0]).toEqual({
      f031: { manual: true },
    });

    // 来源补齐（身高 175）：以计算值为准并清 manual 标记（tech-plan §6「来源补齐后自动改为计算值」）
    await wrapper.setProps({
      modelValue: { f011: 175, f013: 70, f031: 21.4 },
      meta: { f031: { manual: true } },
    });
    expect(wrapper.emitted('update:modelValue')?.at(-1)?.[0]).toEqual({
      f011: 175,
      f013: 70,
      f031: 22.9,
    });
    const meta = wrapper.emitted('update:meta')?.at(-1)?.[0] as Record<
      string,
      { manual?: boolean }
    >;
    expect(meta.f031?.manual).toBeUndefined();
  });

  it('结果无效的派生字段带值 → validate 硬错误（rule=derived，与后端写入闸门同口径）', async () => {
    // 出院早于手术且残留旧计算值：同步会清空；即便值仍在（竞态）validate 兜底拦截
    const wrapper = mountDerived({
      modelValue: { f020: '2026-03-01', f021: '2026-02-28', f030: 8 },
    });
    const cleared = wrapper.emitted('update:modelValue')?.at(-1)?.[0] as Record<
      string,
      unknown
    >;
    expect(cleared).toEqual({ f020: '2026-03-01', f021: '2026-02-28' });
    const vm = wrapper.vm as unknown as {
      validate: () => Promise<{
        hardErrors: Array<{ key: string; message: string; rule: string }>;
        ok: boolean;
      }>;
    };
    // 父级应用清空后的值 → 无手工值不报错（留空放行，提示已显示在字段旁）
    await wrapper.setProps({ modelValue: cleared });
    const result = await vm.validate();
    expect(result.hardErrors.some((issue) => issue.rule === 'derived')).toBe(
      false,
    );

    // 手工塞回无效值（防御）：validate 拦 rule=derived
    await wrapper.setProps({
      modelValue: { f020: '2026-03-01', f021: '2026-02-28', f030: 3 },
    });
    const rejected = await vm.validate();
    expect(rejected.ok).toBe(false);
    expect(
      rejected.hardErrors.some(
        (issue) =>
          issue.key === 'f030' &&
          issue.rule === 'derived' &&
          issue.message === '出院日期早于手术日期',
      ),
    ).toBe(true);
  });
});

/* ----------------------------------------------------------------------
 * E11-T11：表单锚点与填写进度（A-PAT-021、C-PAT-018；口径 A-PAT-019）——
 * 渲染器为每个分类 / 分节输出稳定锚点键与模板版本（anchors()，报告单位置随 E16-T13 用），
 * 并实时上抛「已填 / 应填」（progress 事件，给 CategoryBar / E13-T11）；
 * 条件不显示的字段不计入（前后端同口径，共享用例 form-progress-cases.json）。
 */
const progressSchema: FormSchema = {
  categories: [
    {
      key: 'overview',
      label: '随访概况',
      order: 10,
      fields: [
        f({ key: 'f601', label: '随访日期', type: 'date', required: true }),
        f({
          key: 'f602',
          label: '随访方式',
          type: 'radio',
          required: true,
          optionSet: [
            { code: 1, label: '电话' },
            { code: 2, label: '门诊' },
          ],
        }),
      ],
    },
    {
      key: 'endpoint',
      label: '随访结局',
      order: 20,
      fields: [
        f({
          key: 'f610',
          label: '本次结局',
          type: 'select',
          optionSet: [
            { code: 0, label: '正常随访' },
            { code: 1, label: '死亡' },
          ],
        }),
        f({
          key: 'f611',
          label: '死亡日期',
          type: 'date',
          visibleWhen: { all: [{ field: 'f610', op: 'eq', value: 1 }] },
          requiredWhen: { all: [{ field: 'f610', op: 'eq', value: 1 }] },
        }),
        f({
          key: 'f612',
          label: '联系电话（示例）',
          type: 'text',
          storage: 'encrypted',
          required: true,
        }),
      ],
    },
  ],
  code: 'A_ANNUAL_FU',
  name: '年度随访（示例）',
  rules: [],
  usage: 'FOLLOWUP',
  version: 5,
};

describe('e11-t11 FormRenderer：锚点（稳定键 + 模板版本）与已填 / 应填进度', () => {
  it('anchors()：每个分类输出稳定锚点键、显示名与模板版本（报告单位置按版本校验）', () => {
    const wrapper = mountRenderer({ schema: progressSchema });
    const vm = wrapper.vm as unknown as {
      anchors: () => Array<{
        key: string;
        label: string;
        order: number;
        templateVersion: number;
      }>;
    };
    expect(vm.anchors()).toEqual([
      { key: 'overview', label: '随访概况', order: 10, templateVersion: 5 },
      { key: 'endpoint', label: '随访结局', order: 20, templateVersion: 5 },
    ]);
  });

  it('progress 事件：按分类上抛已填 / 应填（必填口径 A-PAT-019），值变化实时更新', async () => {
    const wrapper = mountRenderer({ schema: progressSchema });
    // overview 两个必填未填；endpoint：加密列不计入、f611 条件未成立不计入 → 0/0
    expect(wrapper.emitted('progress')?.at(-1)?.[0]).toEqual([
      { key: 'overview', label: '随访概况', filled: 0, total: 2 },
      { key: 'endpoint', label: '随访结局', filled: 0, total: 0 },
    ]);

    // 补齐 overview 两个必填 → 2/2；结局=死亡 → 死亡日期条件必填计入应填（未填）
    await wrapper.setProps({
      modelValue: { f601: '2026-11-02', f602: 1, f610: 1 },
    });
    expect(wrapper.emitted('progress')?.at(-1)?.[0]).toEqual([
      { key: 'overview', label: '随访概况', filled: 2, total: 2 },
      { key: 'endpoint', label: '随访结局', filled: 0, total: 1 },
    ]);

    // 死亡日期已填 → 1/1（filled = total 时分类条不再显示橙色，E13-T11）
    await wrapper.setProps({
      modelValue: { f601: '2026-11-02', f602: 1, f610: 1, f611: '2026-10-30' },
    });
    expect(wrapper.emitted('progress')?.at(-1)?.[0]).toEqual([
      { key: 'overview', label: '随访概况', filled: 2, total: 2 },
      { key: 'endpoint', label: '随访结局', filled: 1, total: 1 },
    ]);
  });

  it('条件转隐藏的字段不计入：结局改回正常随访后，死亡日期移出应填', async () => {
    const wrapper = mountRenderer({
      schema: progressSchema,
      modelValue: { f601: '2026-11-02', f602: 1, f610: 1, f611: '2026-10-30' },
    });
    expect(wrapper.emitted('progress')?.at(-1)?.[0]).toEqual([
      { key: 'overview', label: '随访概况', filled: 2, total: 2 },
      { key: 'endpoint', label: '随访结局', filled: 1, total: 1 },
    ]);

    await wrapper.setProps({
      modelValue: { f601: '2026-11-02', f602: 1, f610: 0, f611: '2026-10-30' },
    });
    expect(wrapper.emitted('progress')?.at(-1)?.[0]).toEqual([
      { key: 'overview', label: '随访概况', filled: 2, total: 2 },
      { key: 'endpoint', label: '随访结局', filled: 0, total: 0 },
    ]);
  });
});

/* ----------------------------------------------------------------------
 * E16-T14 / A-PAT-020：分类末尾作用域插槽——随访展开记录的分组末尾注入附件
 * 缩略图条（InlineAttachmentStrip）用；不传插槽时渲染不受影响。
 */
describe('e16-t14 FormRenderer：category-end 分类末尾作用域插槽', () => {
  it('每个分类末尾各渲染一次，作用域带 category.key / label；顺序在字段之后', () => {
    const wrapper = mount(FormRenderer, {
      props: { schema, modelValue: {} },
      slots: {
        'category-end':
          '<div class="strip" :data-category="category.key">{{ category.label }} 末尾</div>',
      },
    });
    const strips = wrapper.findAll('.strip');
    expect(strips.map((node) => node.attributes('data-category'))).toEqual([
      'overview',
      'outcome',
      'echo',
      'medication',
    ]);
    expect(strips[0]?.text()).toBe('随访概况 末尾');
    // 插槽渲染在该分类 section 内（末尾 = 字段区之后）
    const section = wrapper.find('#crdp-anchor-overview');
    expect(section.find('.strip').exists()).toBe(true);
    expect(section.find('[data-field-key]').exists()).toBe(true);
  });

  it('不传插槽时分类正常渲染（既有调用方不受影响）', () => {
    const wrapper = mountRenderer({ mode: 'readonly' });
    expect(wrapper.findAll('.form-category').length).toBeGreaterThan(0);
    expect(wrapper.find('.strip').exists()).toBe(false);
  });
});
