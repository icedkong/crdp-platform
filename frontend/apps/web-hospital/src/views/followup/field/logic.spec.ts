import type { FormFieldDef } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  buildFieldUpdate,
  buildStatusToggleUpdate,
  isOptionField,
  statusActionText,
  summarizeFieldChange,
  toFieldEditModel,
  validateFieldEditModel,
} from './logic';

/** A-FLD-002 的关键形态：吸烟史——显示名「吸烟史」、选项 0=否 / 1=是。 */
const smoking: FormFieldDef = {
  categoryKey: 'basic_info',
  categoryLabel: '病例基本信息',
  code: 'smoking_history',
  formCode: 'A_BASELINE',
  formName: '基线档案（示例）',
  key: 'f010',
  label: '吸烟史',
  options: [
    { code: 0, label: '否', sortNo: 10, status: 'ENABLED' },
    { code: 1, label: '是', sortNo: 20, status: 'ENABLED' },
  ],
  required: true,
  sensitive: false,
  status: 'ENABLED',
  storage: 'json',
  type: 'radio',
};

const height: FormFieldDef = {
  categoryKey: 'basic_info',
  categoryLabel: '病例基本信息',
  code: 'height_cm',
  decimals: 1,
  formCode: 'A_BASELINE',
  formName: '基线档案（示例）',
  hardRange: { max: 250, min: 50 },
  key: 'f011',
  label: '身高',
  options: null,
  required: false,
  sensitive: false,
  softRange: { max: 210, min: 130 },
  status: 'ENABLED',
  storage: 'json',
  type: 'number',
  unit: 'cm',
};

describe('e11-t01 字段字典页逻辑', () => {
  it('编辑模型只带可维护列：键与编码不上送、帮助与单位空串转 null', () => {
    const model = toFieldEditModel(smoking);
    model.label = '吸烟史（既往）';
    const request = buildFieldUpdate(model, true);
    expect(request).toEqual({
      helpText: null,
      label: '吸烟史（既往）',
      options: [
        { code: 0, label: '否', sortNo: 10, status: 'ENABLED' },
        { code: 1, label: '是', sortNo: 20, status: 'ENABLED' },
      ],
      required: true,
      softRange: null,
      status: 'ENABLED',
      unit: null,
    });
    expect(request).not.toHaveProperty('key');
    expect(request).not.toHaveProperty('code');
    expect(request).not.toHaveProperty('type');
  });

  it('软范围随编辑模型上送；非选项字段不带 options', () => {
    const model = toFieldEditModel(height);
    const request = buildFieldUpdate(model, false);
    expect(request.softRange).toEqual({ max: 210, min: 130 });
    expect(request.options).toBeUndefined();
    expect(request.unit).toBe('cm');
  });

  it('校验：显示名必填、软范围倒置、选项编码缺失 / 重复 / 显示名空全拦', () => {
    const model = toFieldEditModel(smoking);
    expect(validateFieldEditModel(model, true)).toEqual([]);

    model.label = ' ';
    expect(validateFieldEditModel(model, true)).toContain('显示名不能为空');

    const rangeModel = toFieldEditModel(height);
    rangeModel.softRangeMin = 210;
    rangeModel.softRangeMax = 130;
    expect(validateFieldEditModel(rangeModel, false)).toContain(
      '提示范围的最小值不能大于最大值',
    );

    const optionModel = toFieldEditModel(smoking);
    optionModel.options = [
      { code: null, label: '已戒烟（示例）', sortNo: 30, status: 'ENABLED' },
    ];
    expect(validateFieldEditModel(optionModel, true)).toContain(
      '新增选项必须填选项编码',
    );

    const duplicated = toFieldEditModel(smoking);
    duplicated.options = [
      { code: 1, label: '是', sortNo: 20, status: 'ENABLED' },
      { code: 1, label: '否', sortNo: 10, status: 'ENABLED' },
    ];
    expect(validateFieldEditModel(duplicated, true)).toContain(
      '选项编码重复：1',
    );

    const blankLabel = toFieldEditModel(smoking);
    blankLabel.options = [
      { code: 2, label: ' ', sortNo: 30, status: 'ENABLED' },
    ];
    expect(validateFieldEditModel(blankLabel, true)).toContain(
      '选项显示名不能为空',
    );
  });

  it('行内启停：请求体保持其余可维护列，只翻状态（字段可启停）', () => {
    const request = buildStatusToggleUpdate(smoking, 'DISABLED');
    expect(request.status).toBe('DISABLED');
    expect(request.label).toBe('吸烟史');
    expect(request.options).toHaveLength(2);
    expect(statusActionText('ENABLED')).toBe('停用');
    expect(statusActionText('DISABLED')).toBe('启用');
  });

  it('选项维护区只对带选项集的下拉 / 单选 / 多选开放', () => {
    expect(isOptionField(smoking)).toBe(true);
    expect(isOptionField(height)).toBe(false);
  });
});

describe('e11-t08 字段字典变更记录摘要（A-FLD-009、C-FLD-009）', () => {
  const before = JSON.stringify({
    label: '吸烟史',
    required: true,
    softRange: null,
    status: 'ENABLED',
    unit: null,
  });

  it('显示名与启停的变化逐项列出（前后值、操作人由表格列展示）', () => {
    const summary = summarizeFieldChange(
      before,
      JSON.stringify({
        label: '吸烟史（修订）',
        required: true,
        softRange: null,
        status: 'DISABLED',
        unit: null,
      }),
    );
    expect(summary).toContain('显示名「吸烟史」→「吸烟史（修订）」');
    expect(summary).toContain('字段停用');
  });

  it('软范围与必填变化点明；停用选项进「选项已调整」（C-FLD-009 停用不删除）', () => {
    const summary = summarizeFieldChange(
      before,
      JSON.stringify({
        label: '吸烟史',
        options: [
          { code: 0, label: '否', sortNo: 10, status: 'ENABLED' },
          { code: 1, label: '是', sortNo: 20, status: 'DISABLED' },
        ],
        required: false,
        softRange: { max: 210, min: 130 },
        status: 'ENABLED',
        unit: null,
      }),
    );
    expect(summary).toContain('提示范围「无」→「130–210」');
    expect(summary).toContain('改为选填');
    expect(summary).toContain('选项已调整');
  });

  it('无前值（新增配置）与快照缺失有兜底文案；无变化如实说', () => {
    expect(summarizeFieldChange(null, before)).toContain('显示名「吸烟史」');
    expect(summarizeFieldChange(before, null)).toBe('快照缺失');
    expect(summarizeFieldChange(before, before)).toBe('无变化');
  });
});
