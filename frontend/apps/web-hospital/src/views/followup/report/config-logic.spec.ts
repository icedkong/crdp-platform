/**
 * E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012 · 报告单设置页逻辑（config-logic.ts）单测。
 *
 * 锁定：类型编辑模型（编码锁定 / 校验与后端 40001 同口径 / 载荷不上送编码列于更新请求）、
 * 名称编辑模型、指标模板项目校验（至少一行、代码重复拦截）与载荷（全量替换、空串转 null）、
 * 删除确认文案（类型下有名称 / 模板被绑定时给出指引，对应后端 40901）。
 */

import type {
  ReportIndicatorTplView,
  ReportNameView,
  ReportTypeView,
} from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  buildNameCreatePayload,
  buildNameUpdatePayload,
  buildTplCreatePayload,
  buildTplUpdatePayload,
  buildTypeCreatePayload,
  buildTypeUpdatePayload,
  deleteTplConfirmText,
  deleteTypeConfirmText,
  emptyNameEditModel,
  emptyTypeEditModel,
  matchNameKeyword,
  toNameEditModel,
  toTplEditModel,
  toTypeEditModel,
  validateNameEditModel,
  validateTplEditModel,
  validateTypeEditModel,
} from './config-logic';

function typeView(overrides: Partial<ReportTypeView> = {}): ReportTypeView {
  return {
    code: 'LAB',
    defaultDigitizeStatus: null,
    formCode: 'RPT_LAB',
    formName: '检验报告单',
    id: '11',
    nameCount: 6,
    name: '检验',
    nameRequired: true,
    remark: null,
    sortNo: 50,
    status: 'ENABLED',
    ...overrides,
  };
}

function nameView(overrides: Partial<ReportNameView> = {}): ReportNameView {
  return {
    digitizeStatusOverride: null,
    id: '50',
    indicatorTplId: '77',
    indicatorTplName: '血常规',
    name: '血常规',
    sortNo: 10,
    status: 'ENABLED',
    typeCode: 'LAB',
    typeName: '检验',
    ...overrides,
  };
}

function tplView(
  overrides: Partial<ReportIndicatorTplView> = {},
): ReportIndicatorTplView {
  return {
    boundNameCount: 1,
    id: '77',
    items: [
      {
        itemCode: 'WBC',
        itemName: '白细胞',
        refRange: '3.5–9.5',
        refRangeFemale: null,
        refRangeMale: null,
        sortNo: 10,
        unit: '10^9/L',
      },
    ],
    name: '血常规',
    remark: null,
    status: 'ENABLED',
    ...overrides,
  };
}

describe('config-logic 资料类型（A-ATT-003）', () => {
  it('编辑态编码锁定：不校验也不上送编码', () => {
    const model = toTypeEditModel(typeView({ code: 'LAB' }));
    expect(
      Object.keys(validateTypeEditModel(model, { codeLocked: true })),
    ).toHaveLength(0);
    const payload = buildTypeUpdatePayload(model);
    expect(payload).not.toHaveProperty('code');
    expect(payload.name).toBe('检验');
    expect(payload.status).toBe('ENABLED');
  });

  it('新建态校验编码 / 名称 / 字段集 / 排序', () => {
    const model = emptyTypeEditModel();
    const errors = validateTypeEditModel(model, { codeLocked: false });
    expect(errors.code).toBe('请填写类型编码');
    expect(errors.name).toBe('请填写类型名称');
    expect(errors.formCode).toBe('请选择字段集（报告单类型的表单）');

    expect(
      validateTypeEditModel(
        {
          ...model,
          code: 'ECHO',
          name: '心脏超声',
          formCode: 'RPT_ECHO',
          sortNo: 10_000,
        },
        { codeLocked: false },
      ).sortNo,
    ).toBe('排序取值 0–9999');
  });

  it('新建载荷带编码并裁剪空白', () => {
    const payload = buildTypeCreatePayload({
      ...emptyTypeEditModel(),
      code: ' echo-x ',
      formCode: 'RPT_ECHO',
      name: ' 心脏超声 ',
      nameRequired: false,
      sortNo: 15,
    });
    expect(payload.code).toBe('echo-x');
    expect(payload.name).toBe('心脏超声');
    expect(payload.nameRequired).toBe(false);
    expect(payload.remark).toBeNull();
  });
});

describe('config-logic 名称字典（A-ATT-011）', () => {
  it('校验名称必填与长度，载荷保 null 绑定', () => {
    const errors = validateNameEditModel(emptyNameEditModel());
    expect(errors.name).toBe('请填写名称');

    const payload = buildNameCreatePayload(
      { ...emptyNameEditModel(), name: ' 凝血四项 ' },
      'LAB',
    );
    expect(payload.typeCode).toBe('LAB');
    expect(payload.name).toBe('凝血四项');
    expect(payload.indicatorTplId).toBeNull();
  });

  it('编辑载荷含启停，回显保留绑定', () => {
    const model = toNameEditModel(nameView({ status: 'DISABLED' }));
    const payload = buildNameUpdatePayload(model);
    expect(payload.status).toBe('DISABLED');
    expect(payload.indicatorTplId).toBe('77');
  });

  it('关键词匹配名称与绑定模板名', () => {
    const view = nameView();
    expect(matchNameKeyword(view, '血常规')).toBe(true);
    expect(matchNameKeyword(view, ' tpl ')).toBe(false);
    const unbound = nameView({ indicatorTplId: null, indicatorTplName: null });
    expect(matchNameKeyword(unbound, '')).toBe(true);
  });
});

describe('config-logic 指标模板（A-ATT-012 / C-SCAN-004）', () => {
  it('至少一行项目、代码必填且不重复', () => {
    const empty = toTplEditModel(null);
    const nothing = validateTplEditModel({ ...empty, name: '模板', items: [] });
    expect(nothing.errors.items).toContain('至少需要一行项目');

    const dup = validateTplEditModel({
      ...empty,
      name: '模板',
      items: [
        {
          itemCode: 'PT',
          itemName: '凝血酶原时间',
          refRange: '',
          refRangeFemale: '',
          refRangeMale: '',
          sortNo: 10,
          unit: 's',
        },
        {
          itemCode: 'PT',
          itemName: '重复代码',
          refRange: '',
          refRangeFemale: '',
          refRangeMale: '',
          sortNo: 20,
          unit: '',
        },
        {
          itemCode: 'INR',
          itemName: '',
          refRange: '',
          refRangeFemale: '',
          refRangeMale: '',
          sortNo: 30,
          unit: '',
        },
      ],
    });
    expect(dup.itemErrors.join(';')).toContain('项目代码「PT」重复');
    expect(dup.itemErrors.join(';')).toContain('请填写项目名称');
  });

  it('回显项目并补默认排序，载荷空串转 null', () => {
    const model = toTplEditModel(
      tplView({
        items: [
          {
            itemCode: 'PT',
            itemName: '凝血酶原时间',
            refRange: null,
            refRangeFemale: null,
            refRangeMale: null,
            sortNo: 0,
            unit: null,
          },
        ],
      }),
    );
    expect(model.items[0]?.sortNo).toBe(10, 'sortNo 为 0 时按行号补默认排序');

    const payload = buildTplCreatePayload({
      ...model,
      items: [
        {
          itemCode: ' PT ',
          itemName: ' 凝血酶原时间 ',
          refRange: ' 11–13 ',
          refRangeFemale: '',
          refRangeMale: '',
          sortNo: 10,
          unit: ' s ',
        },
      ],
    });
    expect(payload.items[0]).toEqual({
      itemCode: 'PT',
      itemName: '凝血酶原时间',
      refRange: '11–13',
      refRangeFemale: null,
      refRangeMale: null,
      sortNo: 10,
      unit: 's',
    });
    expect(buildTplUpdatePayload({ ...model, status: 'DISABLED' }).status).toBe(
      'DISABLED',
    );
  });
});

describe('config-logic 删除确认文案（对应后端 40901 拦截）', () => {
  it('类型下有名称时提示先删名称', () => {
    expect(deleteTypeConfirmText(typeView({ nameCount: 6 }))).toContain(
      '6 个名称',
    );
    expect(deleteTypeConfirmText(typeView({ nameCount: 0 }))).toContain(
      '不可恢复',
    );
  });

  it('模板被绑定时提示先解绑', () => {
    expect(deleteTplConfirmText(tplView({ boundNameCount: 2 }))).toContain(
      '2 个名称绑定',
    );
    expect(deleteTplConfirmText(tplView({ boundNameCount: 0 }))).toContain(
      '不可恢复',
    );
  });
});
