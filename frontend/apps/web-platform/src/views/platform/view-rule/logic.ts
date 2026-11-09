/**
 * 视角规则页的纯逻辑（E20-T02 / PLT-VIEW-007、008、009；E20-T05 / PLT-VIEW-014、015）：
 * 左栏层级过滤与计数、表单模型与目录校验（FIELD 层资源与字段必须在目录内）、预览请求模型校验、
 * 高敏变更提交前提示（与后端 ViewRuleReviewPolicy 同口径）、版本行前后值摘要。
 * 文案与枚举逻辑统一在 @crdp/api（view-rule.ts），这里只做形状转换。
 */
import type {
  ViewRuleCatalogField,
  ViewRuleEditModel,
  ViewRuleItem,
  ViewRuleLayer,
  ViewRuleVersionContent,
  ViewRuleVersionItem,
} from '@crdp/api';

import {
  toViewRuleSaveRequest,
  validateViewRule,
  VIEW_RULE_LAYER_OPTIONS,
  viewRuleLayerText,
  viewRuleSensitiveReasons,
  viewRuleSensitivityText,
} from '@crdp/api';

export { viewRuleLayerText };

/** 左栏层级项（含「全部」入口与各层计数）。 */
export interface LayerTab {
  count: number;
  hint: string;
  label: string;
  value: null | ViewRuleLayer;
}

/** 规则清单 → 左栏层级页签（全部 + 四层，顺序与 VIEW_RULE_LAYER_OPTIONS 一致）。 */
export function buildLayerTabs(rules: ViewRuleItem[]): LayerTab[] {
  const tabs: LayerTab[] = [
    {
      count: rules.length,
      hint: '全部规则（含停用）',
      label: '全部',
      value: null,
    },
    ...VIEW_RULE_LAYER_OPTIONS.map((option) => ({
      count: rules.filter((rule) => rule.layer === option.value).length,
      hint: option.hint,
      label: option.label,
      value: option.value,
    })),
  ];
  return tabs;
}

/** 按左栏选中层过滤规则（字段层再按资源对象分组展示交给表格，不在此处理）。 */
export function filterByLayer(
  rules: ViewRuleItem[],
  layer: null | ViewRuleLayer,
): ViewRuleItem[] {
  return layer === null ? rules : rules.filter((rule) => rule.layer === layer);
}

/** 目录内资源对象清单（FIELD 层资源下拉；resourceWhole 用「整个资源对象」入口）。 */
export function catalogResources(fields: ViewRuleCatalogField[]): string[] {
  return [...new Set(fields.map((field) => field.resource))];
}

/** 资源对象内的字段清单（FIELD 层字段下拉；空 = 该资源只支持整体规则）。 */
export function catalogFieldsOf(
  fields: ViewRuleCatalogField[],
  resource: string,
): ViewRuleCatalogField[] {
  return fields.filter(
    (field) => field.resource === resource && field.field !== null,
  );
}

/** 目录里是否存在该资源的「整体」条目（如报告单原件，允许 field 为空的整体规则）。 */
export function catalogHasWholeResource(
  fields: ViewRuleCatalogField[],
  resource: string,
): boolean {
  return fields.some(
    (field) => field.resource === resource && field.field === null,
  );
}

/** 保存校验（@crdp/api 的形状校验 + 目录内资源与字段校验）。 */
export function validateEditModel(
  model: ViewRuleEditModel,
  fields: ViewRuleCatalogField[],
): string[] {
  const errors = validateViewRule(model);
  if (model.layer === 'FIELD' && model.resource) {
    if (!catalogResources(fields).includes(model.resource)) {
      errors.push('资源对象不在视角字段目录内');
    } else if (
      !model.resourceWhole &&
      !catalogFieldsOf(fields, model.resource).some(
        (field) => field.field === model.field,
      )
    ) {
      errors.push('字段不在该资源对象的目录清单内');
    }
  }
  return errors;
}

/** 预览请求校验：租户与至少一个平台角色必选。 */
export function validatePreviewModel(model: {
  roleIds: number[];
  tenantId: null | number;
}): string[] {
  const errors: string[] = [];
  if (!model.tenantId) {
    errors.push('请选择要预览的租户');
  }
  if (model.roleIds.length === 0) {
    errors.push('请选择至少一个平台角色');
  }
  return errors;
}

// ---------------------------------------------------------------- E20-T05 / PLT-VIEW-014、015

/**
 * 高敏变更提交前提示（与后端 ViewRuleReviewPolicy 同口径，最终以后端判定为准）：
 * 变更前生效内容 = 规则行 ACTIVE 时为行内容，否则系统初始默认；返回空串 = 普通变更即时生效。
 * roleCodeToId 把规则行的角色码映射回 id（对话框装载角色后调用方构建）。
 */
export function sensitivityHint(
  rule: null | ViewRuleItem,
  model: ViewRuleEditModel,
  roleCodeToId: Map<string, number>,
): string {
  const request = toViewRuleSaveRequest(model);
  const after: ViewRuleVersionContent = {
    allowExport: request.allowExport,
    field: request.field ?? null,
    layer: request.layer,
    maskRule: request.maskRule ?? null,
    moduleCode: request.moduleCode ?? null,
    name: request.name,
    operation: request.operation,
    resource: request.resource ?? null,
    roleCodes: [],
    roleIds: request.roleIds,
    status: request.status ?? 'ACTIVE',
    tenantId: request.tenantId ?? null,
    visibility: request.visibility,
  };
  const before =
    rule && rule.status === 'ACTIVE'
      ? {
          allowExport: rule.allowExport,
          field: rule.field,
          layer: rule.layer,
          moduleCode: rule.moduleCode,
          operation: rule.operation,
          resource: rule.resource,
          roleIds: (rule.roleCodes ?? [])
            .map((code) => roleCodeToId.get(code))
            .filter((id): id is number => id !== undefined),
          tenantId: rule.tenantId,
          visibility: rule.visibility,
        }
      : null;
  return viewRuleSensitivityText(viewRuleSensitiveReasons(before, after));
}

/** 版本快照 JSON 解析（损坏 / 为空返回 null，展示兜底「—」）。 */
export function parseVersionContent(
  raw: null | string,
): null | ViewRuleVersionContent {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as ViewRuleVersionContent;
  } catch {
    return null;
  }
}

/** 版本快照一行摘要（版本记录与待复核列表共用）：效果 + 角色口径。 */
export function versionContentSummary(
  content: null | ViewRuleVersionContent,
): string {
  if (!content) return '系统初始默认（可见、只读、禁止导出）';
  const visibility =
    content.visibility === 'MASKED' && content.maskRule
      ? `脱敏（${content.maskRule}）`
      : ({ PLAIN: '明文', MASKED: '脱敏', BLINDED: '设盲', HIDDEN: '隐藏' }[
          content.visibility
        ] ?? content.visibility);
  const operation = content.operation === 'EDITABLE' ? '可编辑' : '只读';
  const exportText = content.allowExport ? '允许导出' : '禁止导出';
  const roles =
    (content.roleCodes ?? []).length === 0
      ? '全部角色'
      : (content.roleCodes ?? []).join('、');
  return `可见性 ${visibility} · ${operation} · ${exportText} · ${roles}`;
}

/** 待复核行 → 提示文案（同意前给复核人看清改动）。 */
export function pendingChangeText(version: ViewRuleVersionItem): string {
  const before = parseVersionContent(version.beforeJson);
  const after = parseVersionContent(version.afterJson);
  return `${versionContentSummary(before)} → ${versionContentSummary(after)}`;
}
