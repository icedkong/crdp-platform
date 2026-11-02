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
 * （E11-T03 / E11-T04 前后端同语义）。派生重算（E11-T05）、对比标红（E17）、已填 / 应填
 * （E11-T11）随后续任务接入同一组件。
 */
export { default as FormRenderer } from './form-renderer.vue';

export {
  formatFieldValue,
  previousValueText,
  readonlyFieldValue,
} from './format-value';
export {
  anchorId,
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
