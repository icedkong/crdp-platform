import type { ViewRuleCatalogField, ViewRuleItem } from '@crdp/api';

import { newViewRuleModel } from '@crdp/api';
/**
 * 视角规则页纯逻辑测试（E20-T02 / PLT-VIEW-007、009）：左栏层级页签与计数、
 * 按层过滤、FIELD 层目录校验（资源 / 字段 / 整体条目）、预览请求校验；
 * E20-T05 / PLT-VIEW-014、015：高敏变更提交前提示、版本快照解析与摘要。
 */
import { describe, expect, it } from 'vitest';

import {
  buildLayerTabs,
  catalogFieldsOf,
  catalogHasWholeResource,
  catalogResources,
  filterByLayer,
  parseVersionContent,
  pendingChangeText,
  sensitivityHint,
  validateEditModel,
  validatePreviewModel,
  versionContentSummary,
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

describe('sensitivityHint（高敏提交前提示，与后端同口径）', () => {
  const codeToId = new Map([
    ['IMPLEMENTATION', 2],
    ['SUPPORT', 3],
  ]);

  it('新建可编辑 / 允许导出规则提示待复核；新建脱敏规则不提示', () => {
    const editable = newViewRuleModel();
    editable.name = '可编辑（示例）';
    editable.operation = 'EDITABLE';
    expect(sensitivityHint(null, editable, codeToId)).toContain('高敏变更');

    const exportable = newViewRuleModel();
    exportable.name = '允许导出（示例）';
    exportable.allowExport = true;
    expect(sensitivityHint(null, exportable, codeToId)).toContain('允许导出');

    const masked = newViewRuleModel();
    masked.name = '脱敏（示例）';
    masked.layer = 'FIELD';
    masked.resource = 'patient';
    masked.field = 'phone';
    masked.visibility = 'MASKED';
    masked.maskRule = 'phone';
    expect(sensitivityHint(null, masked, codeToId)).toBe('');
  });

  it('维护生效中的脱敏规则：改明文提示；改名称不提示', () => {
    const maskedRule: ViewRuleItem = {
      allowExport: false,
      code: 'VR-FIELD-PATIENT-NAME',
      field: 'name',
      fieldLabel: '患者姓名',
      id: 9,
      layer: 'FIELD',
      maskRule: 'name',
      moduleCode: 'followup',
      moduleName: '随访',
      name: '敏感字段脱敏：患者姓名',
      operation: 'READONLY',
      pendingChange: false,
      resource: 'patient',
      resourceLabel: '患者档案',
      roleCodes: [],
      status: 'ACTIVE',
      tenantCode: null,
      tenantId: null,
      tenantName: null,
      updatedAt: '2026-11-01T10:00:00+08:00',
      version: 1,
      visibility: 'MASKED',
    };
    const toPlain = newViewRuleModel();
    toPlain.name = maskedRule.name;
    toPlain.layer = 'FIELD';
    toPlain.moduleCode = 'followup';
    toPlain.resource = 'patient';
    toPlain.field = 'name';
    toPlain.visibility = 'PLAIN';
    expect(sensitivityHint(maskedRule, toPlain, codeToId)).toContain(
      '可见性放宽',
    );

    // 编辑流程按规则行预填模块（toViewRuleEditModel），仅改名不动选择器 → 普通变更即时生效
    const rename = newViewRuleModel();
    rename.name = '敏感字段脱敏：患者姓名（改名）';
    rename.layer = 'FIELD';
    rename.moduleCode = 'followup';
    rename.resource = 'patient';
    rename.field = 'name';
    rename.visibility = 'MASKED';
    rename.maskRule = 'name';
    expect(sensitivityHint(maskedRule, rename, codeToId)).toBe('');
  });
});

describe('版本快照解析与摘要（PLT-VIEW-015）', () => {
  const after = {
    allowExport: false,
    field: 'name',
    layer: 'FIELD',
    maskRule: 'name',
    moduleCode: 'followup',
    name: '敏感字段脱敏：患者姓名',
    operation: 'READONLY',
    resource: 'patient',
    roleCodes: ['IMPLEMENTATION', 'SUPPORT'],
    roleIds: [2, 3],
    status: 'ACTIVE',
    tenantId: null,
    visibility: 'MASKED',
  };

  it('解析与摘要（脱敏码 / 角色清单 / 初始默认兜底）', () => {
    expect(parseVersionContent(JSON.stringify(after))?.maskRule).toBe('name');
    expect(parseVersionContent(null)).toBeNull();
    expect(parseVersionContent('not-json')).toBeNull();
    expect(
      versionContentSummary(parseVersionContent(JSON.stringify(after))),
    ).toBe('可见性 脱敏（name） · 只读 · 禁止导出 · IMPLEMENTATION、SUPPORT');
    expect(versionContentSummary(null)).toBe(
      '系统初始默认（可见、只读、禁止导出）',
    );
  });

  it('待复核行前 → 后文案', () => {
    const text = pendingChangeText({
      afterJson: JSON.stringify(after),
      appliedAt: null,
      beforeJson: null,
      changeType: 'UPDATE',
      id: 12,
      proposedAt: '2026-11-09T10:00:00+08:00',
      proposedByName: 'platform.admin',
      reviewComment: null,
      reviewedAt: null,
      reviewedByName: null,
      ruleCode: 'VR-FIELD-PATIENT-NAME',
      ruleId: 9,
      ruleName: '敏感字段脱敏：患者姓名',
      sensitive: true,
      status: 'PENDING_REVIEW',
      version: 2,
    });
    expect(text).toBe(
      '系统初始默认（可见、只读、禁止导出） → 可见性 脱敏（name） · 只读 · 禁止导出 · IMPLEMENTATION、SUPPORT',
    );
  });
});
