export {
  applyCarryOver,
  canCarryOver,
  carryOverButtonText,
  type CarryOverItem,
  carryOverPlan,
  type PreviousRecord,
} from './carry-over';
/**
 * @crdp/form-engine：表单渲染器（E11-T07 / components.md §10 FormRenderer 契约）。
 *
 * FormRenderer 按模板（FormSchema）渲染：分类布局（A-FLD-007）、多选互斥与「其他」说明
 * （C-FLD-004）、带入上次值（A-FU-015、C-FU-016）、条件显示与校验复用 @crdp/rules
 * （E11-T03 / E11-T04 前后端同语义）、派生重算（E11-T05 / A-FLD-005、C-FLD-006：
 * 来源拓扑顺序实时重算、来源缺失允许手工录入 meta.manual）、已填 / 应填与分类锚点
 * （E11-T11，见下）。对比标红（E17）随后续任务接入同一组件。
 */
export { default as FormRenderer } from './form-renderer.vue';
export {
  formatFieldValue,
  previousValueText,
  readonlyFieldValue,
} from './format-value';

export {
  anchorId,
  type CategoryAnchor,
  categoryAnchors,
  type ChoiceOption,
  choiceOptions,
  controlKind,
  type FieldControlKind,
  flattenRendererFields,
  layoutCategories,
  type RendererCategory,
  type RendererField,
} from './layout';
export {
  applyMultiselectChange,
  exclusiveCodes,
  type FieldMetaValue,
  isOptionLocked,
  missingDetailIssues,
  needsDetailCodes,
  normalizeMultiselectValue,
  selectedNeedsDetailCodes,
} from './multiselect';
export type { RendererValidateResult } from './types';
/**
 * E11-T11 / A-PAT-021、C-PAT-018（口径 A-PAT-019）：已填 / 应填进度与分类锚点。
 * FormRenderer 实时上抛 `progress`（给 CategoryBar / E13-T11）、`anchors()` 暴露每个
 * 分类 / 分节的稳定锚点键与模板版本（报告单位置 E16-T13）；计算口径与后端
 * FormProgressEngine（POST /api/hospital/form/forms/{code}/progress）共享用例锁定一致。
 */
export type { CategoryProgress } from '@crdp/rules';
