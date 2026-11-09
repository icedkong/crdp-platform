import type { ViewRuleItem, ViewRuleVersionContent } from './view-rule';

/**
 * E20-T02 / PLT-VIEW-007、008、009 视角规则前端纯逻辑测试：保存校验（与后端
 * ViewRuleService.validate 同口径）、层级形状与脱敏码一致性、提交请求的选择器裁剪、
 * 列表范围 / 角色摘要与效果预览文案；E20-T05 / PLT-VIEW-014、015：高敏判定
 * （与后端 ViewRuleReviewPolicy 同口径）、状态与版本行文案。
 */
import { describe, expect, it } from 'vitest';

import {
  newViewRuleModel,
  toViewRuleSaveRequest,
  validateViewRule,
  viewRuleChangeTypeText,
  viewRuleDecidedByText,
  viewRuleEffectText,
  viewRuleLayerText,
  viewRuleOperationText,
  viewRuleRolesText,
  viewRuleScopeText,
  viewRuleSensitiveReasons,
  viewRuleSensitivityText,
  viewRuleStatusText,
  viewRuleVersionStatusColor,
  viewRuleVersionStatusText,
  viewRuleVisibilityText,
} from './view-rule';

describe('validateViewRule（与后端同口径）', () => {
  it('名称必填、长度上限', () => {
    const model = newViewRuleModel();
    expect(validateViewRule(model)).toContain('请填写规则名称');
    model.name = '长'.repeat(129);
    expect(validateViewRule(model)).toContain(
      '规则名称过长（最多 128 个字符）',
    );
    model.name = '排障放宽（示例）';
    expect(validateViewRule(model)).toEqual([]);
  });

  it('层级形状：租户必选租户、模块必选模块、字段必选资源与字段', () => {
    const model = newViewRuleModel();
    model.name = '形状（示例）';
    model.layer = 'TENANT';
    expect(validateViewRule(model)).toContain('租户层级必须选择租户');
    model.tenantId = 3;
    expect(validateViewRule(model)).toEqual([]);

    model.layer = 'MODULE';
    expect(validateViewRule(model)).toContain('模块层级必须选择模块');
    model.moduleCode = 'followup';
    expect(validateViewRule(model)).toEqual([]);

    model.layer = 'FIELD';
    expect(validateViewRule(model)).toContain('字段层级必须选择资源对象');
    model.resource = 'patient';
    expect(validateViewRule(model)).toContain(
      '字段层级必须选择字段（或勾选「整个资源对象」）',
    );
    model.resourceWhole = true;
    expect(validateViewRule(model)).toEqual([]);
    model.resourceWhole = false;
    model.field = 'phone';
    expect(validateViewRule(model)).toEqual([]);
  });

  it('脱敏可见性必带脱敏码；非脱敏不允许带', () => {
    const model = newViewRuleModel();
    model.name = '脱敏（示例）';
    model.visibility = 'MASKED';
    expect(validateViewRule(model)).toContain('可见性为脱敏时必须选择脱敏规则');
    model.maskRule = 'phone';
    expect(validateViewRule(model)).toEqual([]);
    model.visibility = 'PLAIN';
    expect(validateViewRule(model)).toContain(
      '只有脱敏可见性才允许选择脱敏规则',
    );
  });
});

describe('toViewRuleSaveRequest（选择器裁剪）', () => {
  it('全局默认不带任何选择器；脱敏码只在 MASKED 时带上', () => {
    const model = newViewRuleModel();
    model.name = ' 全局（示例） ';
    model.tenantId = 9;
    model.moduleCode = 'followup';
    model.maskRule = 'phone';
    const request = toViewRuleSaveRequest(model);
    expect(request.tenantId).toBeNull();
    expect(request.moduleCode).toBeNull();
    expect(request.resource).toBeNull();
    expect(request.maskRule).toBeNull();
    expect(request.name).toBe('全局（示例）');
    expect(request.roleIds).toEqual([]);
  });

  it('字段层按资源整体 / 具体字段裁剪；模块层带模块', () => {
    const model = newViewRuleModel();
    model.name = '字段（示例）';
    model.layer = 'MODULE';
    model.moduleCode = 'base';
    expect(toViewRuleSaveRequest(model)).toMatchObject({
      layer: 'MODULE',
      moduleCode: 'base',
      resource: null,
    });

    model.layer = 'FIELD';
    model.moduleCode = '';
    model.resource = 'report';
    model.resourceWhole = true;
    expect(toViewRuleSaveRequest(model)).toMatchObject({
      layer: 'FIELD',
      field: null,
      resource: 'report',
      moduleCode: null,
    });
    model.resourceWhole = false;
    model.resource = 'patient';
    model.field = 'phone';
    model.visibility = 'MASKED';
    model.maskRule = 'phone';
    expect(toViewRuleSaveRequest(model)).toMatchObject({
      field: 'phone',
      resource: 'patient',
      maskRule: 'phone',
    });
  });
});

describe('文案与摘要', () => {
  const rule: ViewRuleItem = {
    allowExport: false,
    code: 'VR-GLOBAL-DEFAULT',
    field: null,
    fieldLabel: null,
    id: 1,
    layer: 'GLOBAL',
    maskRule: null,
    moduleCode: null,
    moduleName: null,
    name: '全局默认：可见、只读、禁止导出',
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
  };

  it('枚举文案', () => {
    expect(viewRuleLayerText('FIELD')).toBe('字段');
    expect(viewRuleLayerText('GLOBAL')).toBe('全局默认');
    expect(viewRuleVisibilityText('MASKED')).toBe('脱敏');
    expect(viewRuleVisibilityText('BLINDED')).toBe('设盲');
    expect(viewRuleVisibilityText('HIDDEN')).toBe('隐藏');
    expect(viewRuleOperationText('EDITABLE')).toBe('可编辑');
    expect(viewRuleStatusText('DISABLED')).toBe('停用');
    expect(viewRuleLayerText(null)).toBe('—');
  });

  it('范围 / 角色摘要：全部租户与全部角色、字段层带资源与字段', () => {
    expect(viewRuleScopeText(rule)).toBe('全部租户');
    expect(viewRuleRolesText(rule)).toBe('全部角色');
    const fieldRule: ViewRuleItem = {
      ...rule,
      layer: 'FIELD',
      moduleCode: 'followup',
      moduleName: '随访',
      resource: 'patient',
      resourceLabel: '患者档案',
      field: 'phone',
      fieldLabel: '患者手机号',
      roleCodes: ['IMPLEMENTATION', 'SUPPORT'],
      tenantId: 3,
      tenantName: '虚构示例医院甲',
    };
    expect(viewRuleScopeText(fieldRule)).toBe(
      '虚构示例医院甲 / 随访 / 患者档案 · 患者手机号',
    );
    expect(viewRuleRolesText(fieldRule)).toBe('IMPLEMENTATION、SUPPORT');
    const wholeRule: ViewRuleItem = {
      ...fieldRule,
      field: null,
      fieldLabel: null,
      resource: 'report',
      resourceLabel: '报告单与电子化资料',
    };
    expect(viewRuleScopeText(wholeRule)).toContain('（整体）');
  });

  it('效果摘要与命中规则回显', () => {
    expect(
      viewRuleEffectText({
        allowExport: false,
        maskRule: 'phone',
        operation: 'READONLY',
        visibility: 'MASKED',
      }),
    ).toBe('脱敏（手机号（前 3 后 4）） · 只读 · 禁止导出');
    expect(
      viewRuleEffectText({
        allowExport: true,
        maskRule: null,
        operation: 'EDITABLE',
        visibility: 'PLAIN',
      }),
    ).toBe('明文 · 可编辑 · 允许导出');
    expect(viewRuleDecidedByText([])).toBe('系统初始默认（无命中规则）');
    expect(
      viewRuleDecidedByText([
        { code: 'VR-00001', id: 1, layer: 'MODULE', name: '业务数据只读' },
      ]),
    ).toBe('VR-00001 业务数据只读');
  });

  // ---------------------------------------------------------------- E20-T05 / PLT-VIEW-014、015

  it('状态文案含待复核（PENDING_REVIEW）', () => {
    expect(viewRuleStatusText('PENDING_REVIEW')).toBe('待复核');
    expect(viewRuleStatusText('ACTIVE')).toBe('生效');
    expect(viewRuleStatusText('DISABLED')).toBe('停用');
  });

  it('高敏判定：规格四类放宽与等效放宽触发，收严与中性变更不触发（与后端同口径）', () => {
    const masked = {
      allowExport: false,
      field: 'name',
      layer: 'FIELD' as const,
      moduleCode: 'followup',
      operation: 'READONLY' as const,
      resource: 'patient',
      roleIds: [],
      tenantId: null,
      visibility: 'MASKED' as const,
    };
    const content = (
      over: Partial<ViewRuleVersionContent>,
    ): ViewRuleVersionContent => ({
      allowExport: false,
      field: 'name',
      layer: 'FIELD',
      maskRule: null,
      moduleCode: 'followup',
      name: '规则（示例）',
      operation: 'READONLY',
      resource: 'patient',
      roleCodes: [],
      roleIds: [],
      status: 'ACTIVE',
      tenantId: null,
      visibility: 'MASKED',
      ...over,
    });
    // 改明文 / 取消只读 / 允许导出
    expect(
      viewRuleSensitiveReasons(masked, content({ visibility: 'PLAIN' })).length,
    ).toBeGreaterThan(0);
    expect(
      viewRuleSensitiveReasons(masked, content({ operation: 'EDITABLE' }))
        .length,
    ).toBeGreaterThan(0);
    expect(
      viewRuleSensitiveReasons(masked, content({ allowExport: true })).length,
    ).toBeGreaterThan(0);
    // 停用脱敏规则（后值不生效 = 初始默认）
    expect(viewRuleSensitiveReasons(masked, null).length).toBeGreaterThan(0);
    // 新建即允许导出
    expect(
      viewRuleSensitiveReasons(null, content({ allowExport: true })).length,
    ).toBeGreaterThan(0);
    // 收严（明文 → 脱敏）与中性（只改名称）不触发
    expect(
      viewRuleSensitiveReasons(
        { ...masked, visibility: 'PLAIN' },
        content({ visibility: 'MASKED', maskRule: 'name' }),
      ),
    ).toEqual([]);
    expect(
      viewRuleSensitiveReasons(masked, content({ name: '改名（示例）' })),
    ).toEqual([]);
    // 限制性规则改作用范围触发
    expect(
      viewRuleSensitiveReasons(
        masked,
        content({ resource: 'user', field: 'phone' }),
      ).length,
    ).toBeGreaterThan(0);
    // 限制性规则收窄适用角色触发；加宽（→ 全部角色）不触发
    const bound = { ...masked, roleIds: [2, 3] };
    expect(
      viewRuleSensitiveReasons(bound, content({ roleIds: [2] })).length,
    ).toBeGreaterThan(0);
    expect(viewRuleSensitiveReasons(bound, content({ roleIds: [] }))).toEqual(
      [],
    );
  });

  it('高敏提示文案拼接', () => {
    expect(viewRuleSensitivityText([])).toBe('');
    expect(viewRuleSensitivityText(['取消只读'])).toBe(
      '高敏变更（取消只读），提交后需另一名平台管理员复核通过才生效',
    );
  });

  it('版本行文案：变更类型与状态', () => {
    expect(viewRuleChangeTypeText('CREATE')).toBe('新建');
    expect(viewRuleChangeTypeText('UPDATE')).toBe('维护');
    expect(viewRuleChangeTypeText('ROLLBACK')).toBe('回滚');
    expect(viewRuleVersionStatusText('PENDING_REVIEW')).toBe('待复核');
    expect(viewRuleVersionStatusText('APPLIED')).toBe('已生效');
    expect(viewRuleVersionStatusText('REJECTED')).toBe('已驳回');
    expect(viewRuleVersionStatusColor('PENDING_REVIEW')).toBe('warning');
  });
});
