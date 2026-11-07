/**
 * 视角规则页的纯逻辑（E20-T02 / PLT-VIEW-007、008、009）：左栏层级过滤与计数、
 * 表单模型与目录校验（FIELD 层资源与字段必须在目录内）、预览请求模型校验。
 * 文案与枚举逻辑统一在 @crdp/api（view-rule.ts），这里只做形状转换。
 */
import type {
  ViewRuleCatalogField,
  ViewRuleEditModel,
  ViewRuleItem,
  ViewRuleLayer,
} from '@crdp/api';

import {
  validateViewRule,
  VIEW_RULE_LAYER_OPTIONS,
  viewRuleLayerText,
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
