import type { ViewRuleCatalogField, ViewRuleItem } from '@crdp/api';

import { newViewRuleModel } from '@crdp/api';
/**
 * 视角规则页纯逻辑测试（E20-T02 / PLT-VIEW-007、009）：左栏层级页签与计数、
 * 按层过滤、FIELD 层目录校验（资源 / 字段 / 整体条目）、预览请求校验。
 */
import { describe, expect, it } from 'vitest';

import {
  buildLayerTabs,
  catalogFieldsOf,
  catalogHasWholeResource,
  catalogResources,
  filterByLayer,
  validateEditModel,
  validatePreviewModel,
} from './logic';

function rule(
  layer: ViewRuleItem['layer'],
  extra: Partial<ViewRuleItem> = {},
): ViewRuleItem {
  return {
    allowExport: false,
    code: `VR-${layer}`,
    field: null,
    fieldLabel: null,
    id: 1,
    layer,
    maskRule: null,
    moduleCode: null,
    moduleName: null,
    name: `${layer} 规则（示例）`,
    operation: 'READONLY',
    resource: null,
    resourceLabel: null,
    roleCodes: [],
    status: 'ACTIVE',
    tenantCode: null,
    tenantId: null,
    tenantName: null,
    updatedAt: '2026-11-07T10:00:00',
    version: 1,
    visibility: 'PLAIN',
    ...extra,
  };
}

const catalog: ViewRuleCatalogField[] = [
  {
    field: 'name',
    key: 'patient.name',
    label: '患者姓名',
    moduleCode: 'followup',
    resource: 'patient',
  },
  {
    field: 'phone',
    key: 'patient.phone',
    label: '患者手机号',
    moduleCode: 'followup',
    resource: 'patient',
  },
  {
    field: null,
    key: 'report',
    label: '报告单与电子化资料原件（附件）',
    moduleCode: 'followup',
    resource: 'report',
  },
  {
    field: 'grouping',
    key: 'rct.grouping',
    label: 'RCT 分组',
    moduleCode: null,
    resource: 'rct',
  },
];

describe('buildLayerTabs / filterByLayer', () => {
  it('全部 + 四层页签与计数；按层过滤', () => {
    const rules = [
      rule('GLOBAL'),
      rule('MODULE'),
      rule('MODULE'),
      rule('FIELD'),
    ];
    const tabs = buildLayerTabs(rules);
    expect(tabs.map((tab) => tab.label)).toEqual([
      '全部',
      '全局默认',
      '租户',
      '模块',
      '字段',
    ]);
    expect(tabs[0]?.count).toBe(4);
    expect(tabs[3]?.count).toBe(2);
    expect(tabs[2]?.count).toBe(0);

    expect(filterByLayer(rules, null)).toHaveLength(4);
    expect(filterByLayer(rules, 'MODULE')).toHaveLength(2);
    expect(filterByLayer(rules, 'FIELD')).toHaveLength(1);
  });
});

describe('目录辅助', () => {
  it('资源去重、按资源取字段、整体条目探测', () => {
    expect(catalogResources(catalog)).toEqual(['patient', 'report', 'rct']);
    expect(
      catalogFieldsOf(catalog, 'patient').map((field) => field.field),
    ).toEqual(['name', 'phone']);
    expect(catalogHasWholeResource(catalog, 'report')).toBe(true);
    expect(catalogHasWholeResource(catalog, 'patient')).toBe(false);
  });
});

describe('validateEditModel（目录校验）', () => {
  it('fIELD 层资源与字段必须在目录内；整体条目资源允许空字段', () => {
    const model = newViewRuleModel();
    model.name = '字段规则（示例）';
    model.layer = 'FIELD';
    model.resource = 'wizard';
    model.field = 'wand';
    expect(validateEditModel(model, catalog)).toContain(
      '资源对象不在视角字段目录内',
    );

    model.resource = 'patient';
    model.field = 'wand';
    expect(validateEditModel(model, catalog)).toContain(
      '字段不在该资源对象的目录清单内',
    );

    model.field = 'phone';
    expect(validateEditModel(model, catalog)).toEqual([]);

    model.resource = 'report';
    model.resourceWhole = true;
    model.field = '';
    expect(validateEditModel(model, catalog)).toEqual([]);
  });
});

describe('validatePreviewModel', () => {
  it('租户与角色必选', () => {
    expect(validatePreviewModel({ roleIds: [], tenantId: null })).toEqual([
      '请选择要预览的租户',
      '请选择至少一个平台角色',
    ]);
    expect(validatePreviewModel({ roleIds: [], tenantId: 3 })).toEqual([
      '请选择至少一个平台角色',
    ]);
    expect(validatePreviewModel({ roleIds: [1, 2], tenantId: 3 })).toEqual([]);
  });
});
