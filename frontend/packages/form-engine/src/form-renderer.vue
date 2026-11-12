<script lang="ts" setup>
/**
 * E11-T07 / A-FLD-007、C-FLD-004、C-FLD-010、A-FU-015、C-FU-016：表单渲染器
 * （components.md §10 FormRenderer 契约的 T07 落地）。
 *
 * - **分类布局**：按模板分类（基线「分类」/ 随访「分组」）渲染，分类按 order、字段按模板顺序
 *   （顺序只来自模板，C-FLD-010），每个分类输出稳定锚点 `crdp-anchor-<key>`。
 * - **多选**：复选组 + 互斥（「无」选中自动取消其他且不可再勾）+「其他」补充说明（写入字段备注
 *   data_meta.note，Q37 口径；未填说明进 validate 硬错误——「不能保存」）。
 * - **带入上次值**：模板标记 carryOver 的设置项一键带入上一次的值（测量值不带入），带入字段
 *   浅色标出（C-FU-016），用户改动后恢复普通样式（「再修改」）。
 * - 条件显示（E11-T03 的 @crdp/rules 求值）：隐藏字段不渲染、值清空并 `cleared-hidden`；
 *   校验（E11-T04 的 validateFormData）：软范围橙色提示、硬错误随 showErrors 标红，
 *   `validate()` 供页面提交闸门用。
 * - 派生重算（E11-T05 的 computeDerivedValues，A-FLD-005 / C-FLD-006）：依赖拓扑顺序实时
 *   重算并回写（来源齐全以计算值为准）；来源缺失留空提示原因并允许手工录入
 *   （`meta.manual = true`，components.md §10.3）；结果无效不允许手工值（validate 硬错误，
 *   与后端写入闸门同口径）。
 * - 已填 / 应填（E11-T11 的 computeCategoryProgress，A-PAT-021 / C-PAT-018，口径
 *   A-PAT-019）：按分类实时上抛 `progress` 事件（给 CategoryBar / E13-T11）；条件不显示
 *   的字段不计入、派生与加密列不计入（与后端 FormProgressEngine 同口径，共享用例锁定）；
 *   `anchors()` 输出每个分类的稳定锚点键与模板版本（报告单位置 E16-T13 用）。
 * - 对比标红（E17-T03 / A-FU-027，design-system §14）：`highlightEnabled` 且模板带
 *   `compareRules` 时按 @crdp/rules 的 evaluateCompareRules（与后端 CompareEvaluator
 *   共享用例锁定一致）实时求值：命中字段红底（globalCss .crdp-field-flag）+ ▲ + 变化量
 *   与对比值，悬停显示规则说明（tooltip）；分组标题显示 ▲n，`highlight` 事件上抛命中
 *   列表（页面并入 CategoryBar 标红数）；不弹窗、不发通知、不拦提交。
 * - Enter 不提交（本组件只阻止默认行为；Ctrl/⌘+S、Ctrl/⌘+Enter 由页面监听）。
 * - 响应式（E24-T02 / NFR-RESP-001、002，pages.md §5.2、design-system §12）：
 *   断点统一走 @crdp/responsive 的 useBreakpoint（全站唯一断点来源）——
 *   桌面字段按类型定宽流动排布、标签右对齐 140；平板 / 手机字段单列（平板标签
 *   右对齐 120）；手机标签在上、输入框占满宽（高 36 / 字号 16 由根节点
 *   crdp-form 命中 globalCss 手机媒体查询提供）、数值弹数字键盘
 *   （inputmode="decimal"）。
 * 不在本组件（后续任务）：附件条（E16）、标红结果持久化展示（E17-T04）。
 */
import type { FormSchema } from '@crdp/api';
import type {
  CategoryProgress,
  CompareHit,
  CompareRuleInput,
  DerivedOutcome,
  FieldIssue,
  OrdinalMap,
} from '@crdp/rules';

import type { PreviousRecord } from './carry-over';
import type {
  CategoryAnchor,
  ChoiceOption,
  RendererCategory,
  RendererField,
} from './layout';
import type { FieldMetaValue } from './multiselect';
import type { NextVisitAffordance } from './next-visit';
import type { RendererValidateResult } from './types';

import { computed, ref, watch } from 'vue';

import { useBreakpoint } from '@crdp/responsive/use-breakpoint';
import {
  computeCategoryProgress,
  computeDerivedValues,
  derivedManualKind,
  evaluateCompareRules,
  isDerivedManualEditable,
  isEmptyFormValue,
  isFieldVisible,
  isRequiredField,
  stripHiddenFieldValues,
  toCompareRuleInput,
  validateFormData,
} from '@crdp/rules';
import { colors, sizes } from '@crdp/tokens';
import {
  Checkbox,
  CheckboxGroup,
  DatePicker,
  Input,
  InputNumber,
  RadioGroup,
  Select,
  Textarea,
  Tooltip,
} from 'ant-design-vue';

import {
  applyCarryOver,
  canCarryOver,
  carryOverButtonText,
  carryOverPlan,
} from './carry-over';
import {
  formatFieldValue,
  previousValueText,
  readonlyFieldValue,
} from './format-value';
import {
  anchorId,
  categoryAnchors,
  choiceOptions,
  controlKind,
  flattenRendererFields,
  layoutCategories,
} from './layout';
import {
  applyMultiselectChange,
  isOptionLocked,
  missingDetailIssues,
  normalizeMultiselectValue,
  selectedNeedsDetailCodes,
} from './multiselect';
import {
  NEXT_VISIT_FIELD_CODE,
  NEXT_VISIT_PLACEHOLDER,
  nextVisitHintOf,
  nextVisitQuickOptions,
} from './next-visit';

const props = withDefaults(
  defineProps<{
    /** 条件上下文（如医院 C 的 device.*，E21-T03）。 */
    context?: null | Record<string, unknown>;
    /**
     * 对比标红开关（E17-T03 / A-FU-027，design-system §14）：功能开关 compare_highlight
     * （后端按 previous / compareRules 是否下发收敛，记录页传 detail 两字段非空）。
     */
    highlightEnabled?: boolean;
    /** 布局：auto 桌面 140 / 平板 120 / 手机标签在上；horizontal 固定横向；vertical 标签在上。 */
    layout?: 'auto' | 'horizontal' | 'vertical';
    /** 字段附加信息（data_meta）：manual / note（「其他」说明）/ fromReport（报告单带入）。 */
    meta?: null | Record<string, FieldMetaValue | undefined>;
    /** edit 录入 / readonly 只读（空值显示「未填写」）。 */
    mode?: 'edit' | 'readonly';
    /** 表单数据（键 = 稳定键；v-model）。 */
    modelValue: Record<string, unknown>;
    /**
     * 「下次随访时间」字段的录入辅助（E21-T08 / C-FU-005，Q05）：间隔快捷 +1..+12 个月、
     * 占位与窗口预览 / 补充提示。按字段编码 next_visit_date 识别（C 程控表单 f333）；
     * A / B 模板无该字段，null 即整体不生效。
     */
    nextVisit?: NextVisitAffordance | null;
    /** 上一次记录（带入上次值 / 对比标红；规则参数决定取法，后端给）。 */
    previous?: null | PreviousRecord;
    /** 表单模板（FormSchema，字段 / 分组 / 选项 / 条件 / 校验 / 对比规则全部来自它）。 */
    schema: FormSchema;
    /** 提交校验后为 true：显示必填 / 硬错误标红。 */
    showErrors?: boolean;
  }>(),
  {
    context: undefined,
    highlightEnabled: false,
    layout: 'auto',
    meta: undefined,
    mode: 'edit',
    nextVisit: null,
    previous: null,
    showErrors: false,
  },
);
const emit = defineEmits<{
  carryOver: [keys: string[]];
  clearedHidden: [keys: string[]];
  fieldBlur: [key: string];
  /** 对比标红命中（E17-T03）：页面据此给 CategoryBar 并入标红数；命中字段内渲染见模板。 */
  highlight: [hits: CompareHit[]];
  progress: [items: CategoryProgress[]];
  'update:meta': [meta: Record<string, FieldMetaValue | undefined>];
  'update:modelValue': [data: Record<string, unknown>];
  validate: [result: RendererValidateResult];
}>();
/** chip 文案（v-text 渲染，保持模板行宽）。 */
const CHIP_CARRIED = '已带入上次值';
const CHIP_DERIVED = '自动计算 · 只读';
const CHIP_FROM_REPORT = '报告单带入';
const CHIP_SENSITIVE = '敏感 · 默认脱敏';

// ---------------------------------------------------------------- 布局

const categories = computed<RendererCategory[]>(() =>
  layoutCategories(props.schema),
);
const allFields = computed<RendererField[]>(() =>
  flattenRendererFields(categories.value),
);
const isReadonly = computed(() => props.mode === 'readonly');

/** 带入后未改动的字段（浅色标出，C-FU-016）；用户改动即移出。 */
const carriedKeys = ref(new Set<string>());

const root = ref<HTMLElement>();

/** 断点（E24-T02）：统一走 @crdp/responsive 的 useBreakpoint（全站唯一断点来源）。 */
const { device: deviceKind, isMobile } = useBreakpoint();

/** 只读固定 120（detailLabelWidth）；auto 按断点；手机标签在上。 */
const resolvedLayout = computed(() => {
  if (isReadonly.value) return 'horizontal';
  if (props.layout === 'vertical') return 'vertical';
  if (props.layout === 'horizontal') return 'horizontal';
  return isMobile.value ? 'vertical' : 'horizontal';
});

/** 平板 / 手机字段单列（每字段占满一行）；桌面保持流动排布（pages.md §5.2）。 */
const singleColumn = computed(() => deviceKind.value !== 'desktop');

const labelWidth = computed(() => {
  if (resolvedLayout.value === 'vertical') return undefined;
  if (isReadonly.value) return sizes.detailLabelWidth;
  if (props.layout === 'auto' && deviceKind.value === 'tablet') {
    return sizes.formLabelWidth.tablet;
  }
  return sizes.formLabelWidth.desktop;
});

/** 数值全部来自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = computed(() => ({
  '--form-label-width': labelWidth.value ? `${labelWidth.value}px` : undefined,
  '--form-gutter': `${sizes.gutter}px`,
  '--form-field-number': `${sizes.fieldWidth.number}px`,
  '--form-field-date': `${sizes.fieldWidth.date}px`,
  '--form-field-select': `${sizes.fieldWidth.selectMax}px`,
  '--form-field-short-text': `${sizes.fieldWidth.shortText}px`,
  '--form-text-secondary': colors.textSecondary,
  '--form-warning': colors.warning,
  '--form-error': colors.error,
  '--form-carry-bg': colors.primaryBg,
  '--form-carry-border': colors.primary,
}));

function visibleFieldsOf(category: RendererCategory): RendererField[] {
  return category.fields.filter((field) =>
    isFieldVisible(field, props.modelValue, props.context),
  );
}

function kindOf(field: RendererField): string {
  return controlKind(field);
}

function radioOptions(field: RendererField): ChoiceOption[] {
  return choiceOptions(field);
}

function isRequired(field: RendererField): boolean {
  return isRequiredField(field, props.modelValue, props.context);
}

function labelCls(field: RendererField): string {
  return isRequired(field) ? 'field-label required' : 'field-label';
}

function valueOf(field: RendererField): unknown {
  return props.modelValue[field.key];
}

/**
 * 控件宽度（按类型定宽，不随屏幕拉伸；长文本 / 选项组占满一行）。
 * 手机（<768）输入框占满宽（design-system §12）——高 36 / 字号 16 由根节点
 * crdp-form 命中 globalCss 手机媒体查询提供。
 */
function fieldControlStyle(field: RendererField): Record<string, string> {
  const kind = kindOf(field);
  if (isMobile.value) return { width: '100%' };
  if (kind === 'number') return { width: 'var(--form-field-number)' };
  if (kind === 'date' || kind === 'datetime')
    return { width: 'var(--form-field-date)' };
  if (kind === 'select') return { width: 'var(--form-field-select)' };
  if (kind === 'text') return { width: 'var(--form-field-short-text)' };
  if (kind === 'derived') {
    return derivedManualInputKind(field) === 'number'
      ? { width: 'var(--form-field-number)' }
      : { width: 'var(--form-field-short-text)' };
  }
  return {};
}

function isFullRow(field: RendererField): boolean {
  return ['multiselect', 'textarea'].includes(kindOf(field));
}

// ---------------------------------------------------------------- 值更新

function setField(key: string, value: unknown): void {
  carriedKeys.value.delete(key);
  emit('update:modelValue', { ...props.modelValue, [key]: value });
}

function onMultiselectChange(field: RendererField, next: unknown): void {
  setField(
    field.key,
    applyMultiselectChange(field, props.modelValue[field.key], next),
  );
}

/** 「其他」说明写入字段备注（data_meta.note）。 */
function onDetailInput(field: RendererField, note: string): void {
  const base = props.meta ?? {};
  emit('update:meta', {
    ...base,
    [field.key]: { ...base[field.key], note },
  });
}

/** 行内焦点移出（focusout 冒泡）：整个字段失焦才发 fieldBlur（本地草稿信号）。 */
function onFieldFocusOut(event: FocusEvent, key: string): void {
  const row = event.currentTarget;
  if (row instanceof HTMLElement) {
    const next = event.relatedTarget;
    if (next instanceof Node && row.contains(next)) return;
  }
  emit('fieldBlur', key);
}

/** Enter 不提交：阻止默认行为（textarea 保留换行；Ctrl/⌘+Enter 由页面监听）。 */
function onKeydownEnter(event: KeyboardEvent): void {
  const target = event.target;
  if (target instanceof HTMLTextAreaElement) return;
  event.preventDefault();
}

// ---------------------------------------------------------------- 隐藏字段清空（E11-T03 同语义）

watch(
  () => [props.modelValue, props.context] as const,
  () => {
    const { data, clearedKeys } = stripHiddenFieldValues(
      allFields.value,
      props.modelValue,
      props.context,
    );
    if (clearedKeys.length === 0) return;
    emit('update:modelValue', data);
    emit('clearedHidden', clearedKeys);
  },
  { immediate: true, deep: true },
);

// ---------------------------------------------------------------- 派生重算（E11-T05 / A-FLD-005、C-FLD-006）

/** 全部派生字段的求值结果（依赖拓扑顺序，components.md §10.3「实时重算」）。 */
const derivedOutcomes = computed<Record<string, DerivedOutcome>>(() =>
  computeDerivedValues(allFields.value, props.modelValue),
);

function derivedOutcomeOf(field: RendererField): DerivedOutcome | undefined {
  return derivedOutcomes.value[field.key];
}

/**
 * 派生同步（与后端写入闸门同口径，tech-plan §6「前端实时显示，后端保存时再算一遍」）：
 * ok → 计算值覆盖（「来源齐全后以计算值为准」，来源补齐即取代手工值并清 manual 标记）；
 * missing → 留空并提示原因，仅手工录入的值（meta.manual）保留，旧计算值清空；
 * invalid → 不允许手工值（清空，validate 仍兜底报硬错误——应先修正来源字段）。
 */
watch(
  () => [props.modelValue, props.context] as const,
  () => {
    if (isReadonly.value) return;
    syncDerivedValues();
  },
  { immediate: true, deep: true },
);

function syncDerivedValues(): void {
  const base = props.meta ?? {};
  const data: Record<string, unknown> = { ...props.modelValue };
  const meta: Record<string, FieldMetaValue | undefined> = { ...base };
  let dataChanged = false;
  let metaChanged = false;
  for (const [key, outcome] of Object.entries(derivedOutcomes.value)) {
    const current = data[key];
    if (outcome.status === 'ok') {
      if (!Object.is(current, outcome.value)) {
        data[key] = outcome.value;
        dataChanged = true;
      }
      if (base[key]?.manual) {
        meta[key] = { ...base[key], manual: undefined };
        metaChanged = true;
      }
    } else if (isDerivedManualEditable(outcome)) {
      if (!isEmptyFormValue(current) && !base[key]?.manual) {
        data[key] = null; // 旧计算值清空（留空并提示原因）
        dataChanged = true;
      }
    } else if (!isEmptyFormValue(current)) {
      delete data[key]; // 结果无效不允许手工值（后端 42200 同口径）
      dataChanged = true;
    }
  }
  if (dataChanged) emit('update:modelValue', data);
  if (metaChanged) emit('update:meta', meta);
}

/** 手工录入控件类型（来源缺失时放开编辑）：DURATION_YM 文本，其余数值。 */
function derivedManualInputKind(field: RendererField): 'number' | 'text' {
  return derivedManualKind(field.derived?.fn ?? '');
}

/** 来源缺失的手工录入：值入 data、标 meta.manual（A-FLD-005「标记手工录入」）。 */
function setDerivedManual(field: RendererField, value: unknown): void {
  setField(field.key, value);
  const base = props.meta ?? {};
  if (!base[field.key]?.manual) {
    emit('update:meta', {
      ...base,
      [field.key]: { ...base[field.key], manual: true },
    });
  }
}

/** 派生留空原因（缺少身高 / 出院日期早于手术日期……，与后端逐字一致）。 */
function derivedHintOf(field: RendererField): string {
  return derivedOutcomeOf(field)?.message ?? '';
}

// ---------------------------------------------------------------- 校验（E11-T04 + C-FLD-004）

const validation = computed(() => {
  const base = validateFormData(
    allFields.value,
    props.schema.rules ?? [],
    props.modelValue,
    props.context,
  );
  const hardErrors = [
    ...base.errors,
    ...missingDetailIssues(
      allFields.value,
      props.modelValue,
      props.meta,
      props.context,
    ),
    ...derivedManualIssues(),
  ];
  return { hardErrors, warnings: base.warnings };
});

/** 结果无效的派生字段不允许手工值（与后端写入闸门 42200 rule=derived 同口径；同步已清空则不报）。 */
function derivedManualIssues(): FieldIssue[] {
  const issues: FieldIssue[] = [];
  for (const field of allFields.value) {
    if (kindOf(field) !== 'derived') continue;
    const outcome = derivedOutcomeOf(field);
    if (!outcome || isDerivedManualEditable(outcome)) continue;
    if (!isEmptyFormValue(props.modelValue[field.key])) {
      issues.push({
        key: field.key,
        categoryKey: field.categoryKey,
        rule: 'derived',
        message: outcome.message ?? '',
      });
    }
  }
  return issues;
}

const errorByKey = computed(
  () => new Map(validation.value.hardErrors.map((issue) => [issue.key, issue])),
);
const warningByKey = computed(
  () => new Map(validation.value.warnings.map((issue) => [issue.key, issue])),
);

function errorOf(field: RendererField): FieldIssue | undefined {
  return errorByKey.value.get(field.key);
}

function warningOf(field: RendererField): FieldIssue | undefined {
  return warningByKey.value.get(field.key);
}

function showError(field: RendererField): boolean {
  return props.showErrors && errorByKey.value.has(field.key);
}

/** 提交闸门：硬错误（含必填缺失与未填「其他」说明）非空则不能提交；结果同时 emit。 */
async function validate(): Promise<RendererValidateResult> {
  const { hardErrors, warnings } = validation.value;
  const result: RendererValidateResult = {
    ok: hardErrors.length === 0,
    missingRequired: hardErrors.filter((issue) => issue.rule === 'required'),
    hardErrors,
    warnings,
  };
  emit('validate', result);
  return result;
}

// ---------------------------------------------------------------- 带入上次值（A-FU-015 / C-FU-016）

const carryPlanVisible = computed(
  () =>
    !isReadonly.value &&
    canCarryOver(
      allFields.value,
      props.modelValue,
      props.context,
      props.previous,
    ),
);

const carryText = computed(() =>
  props.previous ? carryOverButtonText(props.previous) : '带入上次值',
);

function doCarryOver(): void {
  if (!props.previous) return;
  const plan = carryOverPlan(
    allFields.value,
    props.modelValue,
    props.context,
    props.previous,
  );
  if (plan.length === 0) return;
  const keys = plan.map((item) => item.key);
  carriedKeys.value = new Set(keys);
  emit('update:modelValue', applyCarryOver(props.modelValue, plan));
  emit('carryOver', keys);
}

function carriedOf(field: RendererField): boolean {
  return carriedKeys.value.has(field.key);
}

function fromReportOf(field: RendererField): boolean {
  return Boolean(props.meta?.[field.key]?.fromReport);
}

// ---------------------------------------------------------------- 展示文本与 chip

/** 静态展示（只读视图 / 派生计算值 / 敏感）与编辑控件二选一；派生在来源缺失时放开手工录入。 */
function isStaticDisplay(field: RendererField): boolean {
  const kind = kindOf(field);
  if (kind === 'sensitive') return true;
  if (kind === 'derived') {
    const outcome = derivedOutcomeOf(field);
    return isReadonly.value || !outcome || !isDerivedManualEditable(outcome);
  }
  return isReadonly.value;
}

/** 只读展示值：敏感显示脱敏符号；空值「未填写」（次要色）。 */
function displayTextOf(field: RendererField): string {
  if (kindOf(field) === 'sensitive') return '••••••';
  return readonlyFieldValue(field, props.modelValue[field.key]);
}

function displayCls(field: RendererField): string {
  const empty =
    kindOf(field) !== 'sensitive' && displayTextOf(field) === '未填写';
  return empty ? 'field-display field-display-empty' : 'field-display';
}

/** 数值字段单位（框外，A-FLD-003）；非数值字段无单位。 */
function unitOf(field: RendererField): string {
  const kind = kindOf(field);
  if (kind !== 'number' && kind !== 'pair-number') return '';
  return field.unit ?? '';
}

function previousTextOf(field: RendererField): string {
  if (!props.previous) return '';
  return previousValueText(field, props.previous.values[field.key]);
}

/** 「其他」说明短文本是否显示（选中了需说明选项）。 */
function needsDetailInput(field: RendererField): boolean {
  return (
    selectedNeedsDetailCodes(field, props.modelValue[field.key]).length > 0
  );
}

// ---------------------------------------------------------------- 下次随访时间（E21-T08 / C-FU-005）

/** 「下次随访时间」字段（按字段编码识别；props.nextVisit 为 null 整体不生效，A / B 模板）。 */
function isNextVisitField(field: RendererField): boolean {
  return props.nextVisit !== null && field.code === NEXT_VISIT_FIELD_CODE;
}

/** 间隔快捷选项（值 = 本次随访日期 ?? 今天 + N 个月；月末与后端同口径）。 */
function nextVisitChips(): { label: string; value: string }[] {
  return props.nextVisit ? nextVisitQuickOptions(props.nextVisit) : [];
}

/** 字段旁提示：窗口预览（已填）与页面补充提示（补录「只保存」等）。 */
function nextVisitHint(field: RendererField): string {
  if (!props.nextVisit) return '';
  return nextVisitHintOf(
    props.nextVisit,
    !isEmptyFormValue(props.modelValue[field.key]),
  );
}

// ---------------------------------------------------------------- 已填 / 应填（E11-T11 / A-PAT-021、C-PAT-018，口径 A-PAT-019）

/** 分类进度（A-PAT-019 口径：必填字段、按条件实际显示的才计入；与后端 FormProgressEngine 同口径）。 */
const categoryProgress = computed<CategoryProgress[]>(() =>
  computeCategoryProgress(
    allFields.value,
    categories.value.map(({ key, label, order }) => ({ key, label, order })),
    props.modelValue,
    props.context,
  ),
);

/** 上抛给 CategoryBar（E13-T11）；数值未变化不重复上抛（逐键输入时 filled 不变则不打扰）。 */
let lastProgressJson = '';
watch(
  categoryProgress,
  (items) => {
    const next = JSON.stringify(items);
    if (next === lastProgressJson) return;
    lastProgressJson = next;
    emit('progress', items);
  },
  { immediate: true },
);

// ---------------------------------------------------------------- 对比标红（E17-T03 / A-FU-027、C-FU-011）

/** 序数表（ORDINAL_RISE 用）：模板 optionSet.ordinal；无序数字段不进表。 */
const compareOrdinals = computed<OrdinalMap>(() => {
  const map: OrdinalMap = {};
  for (const field of allFields.value) {
    for (const option of field.optionSet ?? []) {
      if (option.ordinal !== null && option.ordinal !== undefined) {
        (map[field.key] ??= {})[String(option.code)] = option.ordinal;
      }
    }
  }
  return map;
});

/** 实时求值（与后端 CompareEvaluator 共享用例锁定一致）；只读视图不标红（持久化展示随 E17-T04）。 */
const compareHits = computed<CompareHit[]>(() => {
  if (!props.highlightEnabled || isReadonly.value) return [];
  const rules: CompareRuleInput[] = [];
  for (const rule of props.schema.compareRules ?? []) {
    if (!rule.enabled) continue;
    const input = toCompareRuleInput(rule);
    if (input) rules.push(input);
  }
  if (rules.length === 0) return [];
  return evaluateCompareRules(
    rules,
    props.modelValue,
    props.previous
      ? {
          date: props.previous.date,
          kind: props.previous.kind,
          values: props.previous.values,
        }
      : null,
    compareOrdinals.value,
  );
});

const hitsByKey = computed(() => {
  const group = new Map<string, CompareHit[]>();
  for (const hit of compareHits.value) {
    const list = group.get(hit.key) ?? [];
    list.push(hit);
    group.set(hit.key, list);
  }
  return group;
});

/** 分组标红数（分组标题 ▲n；锚点 / CategoryBar 由页面上抛 highlight 后并入）。 */
const hitCountByCategory = computed(() => {
  const count = new Map<string, number>();
  for (const hit of compareHits.value) {
    count.set(hit.categoryKey, (count.get(hit.categoryKey) ?? 0) + 1);
  }
  return count;
});

function hitsOf(field: RendererField): CompareHit[] {
  return hitsByKey.value.get(field.key) ?? [];
}

function hitCountOf(categoryKey: string): number {
  return hitCountByCategory.value.get(categoryKey) ?? 0;
}

/** 对比值引用名：上一次随访 → 上次；第 1 年基线回落 → 出院前（Q41 出院前超声）。 */
const previousRefLabel = computed(() =>
  props.previous?.kind === 'BASELINE' ? '出院前' : '上次',
);

/**
 * 标红字段旁的可见文本（验收「标红字段旁显示对比值与变化量」）：
 * 变化量文案 + （对比值）；固定阈值规则无变化量，只留 ▲ 与 tooltip 规则说明。
 */
function highlightTextOf(field: RendererField): string {
  return hitsOf(field)
    .map((hit) => {
      const parts: string[] = [];
      if (hit.changeText) parts.push(hit.changeText);
      const previousText = formatFieldValue(field, hit.previousValue);
      if (previousText)
        parts.push(`（${previousRefLabel.value} ${previousText}）`);
      return parts.join('');
    })
    .filter((part) => part !== '')
    .join('；');
}

/** 规则说明（tooltip）：命中的规则文案逐条拼接（悬停显示规则与阈值来源，design-system §14）。 */
function highlightTooltipOf(field: RendererField): string {
  return hitsOf(field)
    .map((hit) => hit.message)
    .join('；');
}

/** 上抛 highlight（页面给 CategoryBar 并入标红数）；命中未变化不重复上抛；开关关闭不上抛。 */
let lastHighlightJson = '';
watch(
  compareHits,
  (hits) => {
    if (!props.highlightEnabled) return;
    const next = JSON.stringify(hits);
    if (next === lastHighlightJson) return;
    lastHighlightJson = next;
    emit('highlight', hits);
  },
  { immediate: true },
);

// ---------------------------------------------------------------- 暴露

/** 每个分类 / 分节的稳定锚点键与模板版本（components.md §10.3 锚点；报告单位置 E16-T13 用）。 */
function anchors(): CategoryAnchor[] {
  return categoryAnchors(props.schema);
}

function scrollTo(categoryKey: string): void {
  root.value?.ownerDocument
    ?.getElementById(anchorId(categoryKey))
    ?.scrollIntoView({
      behavior: 'smooth',
      block: 'start',
    });
}

function focusFirstError(): void {
  const target = root.value?.querySelector<HTMLElement>(
    '.field-has-error input, .field-has-error textarea, .field-has-error .field-display',
  );
  target?.focus();
}

defineExpose({ anchors, focusFirstError, scrollTo, validate });
</script>

<template>
  <div
    ref="root"
    class="form-renderer crdp-form"
    :class="`form-${deviceKind}`"
    :style="cssVars"
    @keydown.enter="onKeydownEnter"
  >
    <div v-if="carryPlanVisible" class="carry-bar">
      <button
        class="carry-button"
        data-testid="carry-over-button"
        type="button"
        @click="doCarryOver"
      >
        {{ carryText }}
      </button>
      <span class="carry-hint">带入后可修改，带入的值以浅色标出</span>
    </div>

    <section
      v-for="category in categories"
      :id="anchorId(category.key)"
      :key="category.key"
      class="form-category"
      data-testid="form-category"
    >
      <h4 class="category-title">
        {{ category.label }}
        <!-- 对比标红数（E17-T03 / A-FU-027）：分组标题 ▲n，红色（design-system §14） -->
        <span
          v-if="hitCountOf(category.key) > 0"
          :data-testid="`category-highlight-${category.key}`"
          class="category-highlight"
        >
          ▲{{ hitCountOf(category.key) }}
        </span>
      </h4>
      <div class="category-fields" :class="{ 'single-column': singleColumn }">
        <div
          v-for="field in visibleFieldsOf(category)"
          :key="field.key"
          class="form-field"
          :class="{
            'crdp-field-flag': hitsOf(field).length > 0,
            'field-carried': carriedOf(field),
            'field-from-report': fromReportOf(field),
            'field-full-row': isFullRow(field),
            'field-has-error': showError(field),
            'field-vertical': resolvedLayout === 'vertical',
          }"
          :data-field-key="field.key"
          data-testid="form-field"
          @focusout="onFieldFocusOut($event, field.key)"
        >
          <label :class="labelCls(field)">{{ field.label }}：</label>

          <div class="field-body">
            <!-- 静态展示：只读视图 / 派生（自动计算只读）/ 敏感（脱敏只读） -->
            <span
              v-if="isStaticDisplay(field)"
              :class="displayCls(field)"
              :data-testid="`field-display-${field.key}`"
              v-text="displayTextOf(field)"
            ></span>

            <!-- 编辑控件（字段、选项、宽度全部来自模板） -->
            <template v-else>
              <RadioGroup
                v-if="kindOf(field) === 'radio'"
                :options="radioOptions(field)"
                option-type="button"
                :value="valueOf(field)"
                @update:value="(value) => setField(field.key, value)"
              />
              <Select
                v-else-if="kindOf(field) === 'select'"
                :options="radioOptions(field)"
                :style="fieldControlStyle(field)"
                :value="valueOf(field)"
                @update:value="(value) => setField(field.key, value)"
              />
              <CheckboxGroup
                v-else-if="kindOf(field) === 'multiselect'"
                :value="normalizeMultiselectValue(valueOf(field))"
                @update:value="(next) => onMultiselectChange(field, next)"
              >
                <Checkbox
                  v-for="option in radioOptions(field)"
                  :key="String(option.value)"
                  :disabled="
                    isOptionLocked(field, valueOf(field), Number(option.value))
                  "
                  :value="option.value"
                >
                  {{ option.label }}
                </Checkbox>
              </CheckboxGroup>
              <InputNumber
                v-else-if="kindOf(field) === 'number'"
                :controls="false"
                inputmode="decimal"
                :precision="field.decimals ?? undefined"
                :style="fieldControlStyle(field)"
                :value="
                  typeof valueOf(field) === 'number'
                    ? valueOf(field)
                    : undefined
                "
                @update:value="(value) => setField(field.key, value)"
              />
              <div
                v-else-if="kindOf(field) === 'pair-number'"
                class="pair-number"
              >
                <InputNumber
                  :controls="false"
                  inputmode="decimal"
                  placeholder="长"
                  :style="fieldControlStyle(field)"
                  :value="
                    (valueOf(field) as Record<string, number> | undefined)
                      ?.length
                  "
                  @update:value="
                    (value) =>
                      setField(field.key, {
                        ...(valueOf(field) as object),
                        length: value,
                      })
                  "
                />
                <span class="pair-separator">×</span>
                <InputNumber
                  :controls="false"
                  inputmode="decimal"
                  placeholder="宽"
                  :style="fieldControlStyle(field)"
                  :value="
                    (valueOf(field) as Record<string, number> | undefined)
                      ?.width
                  "
                  @update:value="
                    (value) =>
                      setField(field.key, {
                        ...(valueOf(field) as object),
                        width: value,
                      })
                  "
                />
              </div>
              <DatePicker
                v-else-if="kindOf(field) === 'date'"
                :placeholder="
                  isNextVisitField(field) ? NEXT_VISIT_PLACEHOLDER : undefined
                "
                :style="fieldControlStyle(field)"
                :value="
                  typeof valueOf(field) === 'string'
                    ? valueOf(field)
                    : undefined
                "
                value-format="YYYY-MM-DD"
                @update:value="(value) => setField(field.key, value)"
              />
              <DatePicker
                v-else-if="kindOf(field) === 'datetime'"
                :show-time="true"
                :style="fieldControlStyle(field)"
                :value="
                  typeof valueOf(field) === 'string'
                    ? valueOf(field)
                    : undefined
                "
                value-format="YYYY-MM-DDTHH:mm"
                @update:value="(value) => setField(field.key, value)"
              />
              <Textarea
                v-else-if="kindOf(field) === 'textarea'"
                :rows="2"
                :value="
                  typeof valueOf(field) === 'string' ? valueOf(field) : ''
                "
                @update:value="(value) => setField(field.key, value)"
              />
              <!-- 派生来源缺失时的手工录入（A-FLD-005：多见于历史数据；标 meta.manual） -->
              <InputNumber
                v-else-if="
                  kindOf(field) === 'derived' &&
                  derivedManualInputKind(field) === 'number'
                "
                :controls="false"
                inputmode="decimal"
                :style="fieldControlStyle(field)"
                :value="
                  typeof valueOf(field) === 'number'
                    ? valueOf(field)
                    : undefined
                "
                @update:value="(value) => setDerivedManual(field, value)"
              />
              <Input
                v-else-if="kindOf(field) === 'derived'"
                :style="fieldControlStyle(field)"
                :value="
                  typeof valueOf(field) === 'string' ? valueOf(field) : ''
                "
                @update:value="
                  (value) => setDerivedManual(field, String(value))
                "
              />
              <Input
                v-else
                :style="fieldControlStyle(field)"
                :value="
                  typeof valueOf(field) === 'string' ? valueOf(field) : ''
                "
                @update:value="(value) => setField(field.key, value)"
              />

              <!-- 单位放框外（A-FLD-003；空单位不占位） -->
              <span
                v-if="unitOf(field)"
                class="field-unit"
                v-text="unitOf(field)"
              ></span>

              <!-- E21-T08 / C-FU-005：下次随访时间的间隔快捷（+N 个月，只编辑态）与
                   窗口预览 / 补充提示（pages.md §5.1） -->
              <template v-if="isNextVisitField(field)">
                <span v-if="!isReadonly" class="next-visit-quick">
                  <button
                    v-for="option in nextVisitChips()"
                    :key="option.label"
                    class="quick-chip"
                    :data-testid="`next-visit-quick-${option.label}`"
                    type="button"
                    @click="setField(field.key, option.value)"
                  >
                    {{ option.label }}
                  </button>
                </span>
                <span
                  v-if="nextVisitHint(field)"
                  :data-testid="`next-visit-hint-${field.key}`"
                  class="next-visit-hint"
                >
                  {{ nextVisitHint(field) }}
                </span>
              </template>

              <!-- 「其他」补充说明：写入字段备注（data_meta.note，Q37） -->
              <Input
                v-if="
                  kindOf(field) === 'multiselect' && needsDetailInput(field)
                "
                class="option-detail"
                data-role="option-detail"
                placeholder="选“其他”须填写说明"
                :value="meta?.[field.key]?.note ?? ''"
                @update:value="(value) => onDetailInput(field, String(value))"
              />
            </template>

            <!-- chip：带入 / 报告单带入 / 派生 / 敏感（文案见常量） -->
            <em
              v-if="carriedOf(field)"
              class="chip carried"
              v-text="CHIP_CARRIED"
            ></em>
            <em
              v-if="fromReportOf(field)"
              class="chip"
              v-text="CHIP_FROM_REPORT"
            ></em>
            <em
              v-if="kindOf(field) === 'derived'"
              class="chip"
              v-text="CHIP_DERIVED"
            ></em>
            <em
              v-if="kindOf(field) === 'sensitive'"
              class="chip"
              v-text="CHIP_SENSITIVE"
            ></em>

            <!-- 上次值提示（12px 次要色） -->
            <div
              v-if="!isReadonly && previousTextOf(field)"
              class="previous-value"
            >
              {{ previousTextOf(field) }}
            </div>

            <!-- 对比标红（E17-T03 / A-FU-027）：▲ + 变化量 + 对比值；悬停显示规则说明 -->
            <Tooltip
              v-if="hitsOf(field).length > 0"
              :title="highlightTooltipOf(field)"
            >
              <div
                :data-testid="`field-highlight-${field.key}`"
                class="field-highlight"
              >
                <span class="highlight-flag">▲</span>
                <span v-if="highlightTextOf(field)" class="highlight-text">
                  {{ highlightTextOf(field) }}
                </span>
              </div>
            </Tooltip>

            <!-- 派生留空原因（缺少身高 / 出院日期早于手术日期……，与后端逐字一致） -->
            <div
              v-if="kindOf(field) === 'derived' && derivedHintOf(field)"
              :data-testid="`derived-hint-${field.key}`"
              class="derived-hint"
            >
              {{ derivedHintOf(field) }}
            </div>
            <!-- 软范围 / SOFT 规则：橙色提示，不拦截 -->
            <div v-if="warningOf(field)" class="field-warning">
              {{ warningOf(field)?.message }}
            </div>
            <!-- 硬错误：提交校验后标红（必填 / 硬范围 / HARD 规则 / 未填「其他」说明） -->
            <div v-if="showError(field)" class="field-error">
              {{ errorOf(field)?.message }}
            </div>

            <div v-if="field.help" class="field-help">{{ field.help }}</div>
          </div>
        </div>
      </div>
      <!-- E16-T14 分类末尾作用域插槽（category { key, label }）：随访分组末尾的
           附件缩略图条（InlineAttachmentStrip）等位置级内容由调用方注入 -->
      <slot :category="category" name="category-end" />
    </section>
  </div>
</template>

<style scoped>
.form-renderer {
  font-size: 14px;
  color: #262626;
}

.carry-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: calc(var(--form-gutter) / 2);
}

.carry-button {
  padding: 4px 12px;
  font-size: 14px;
  color: var(--form-carry-border);
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--form-carry-border);
  border-radius: 4px;
}

.carry-hint {
  font-size: 12px;
  color: var(--form-text-secondary);
}

/* E21-T08 / C-FU-005：下次随访时间的间隔快捷与提示（pages.md §5.1） */
.next-visit-quick {
  display: inline-flex;
  gap: 4px;
  margin-left: 4px;
}

.quick-chip {
  padding: 2px 8px;
  font-size: 12px;
  color: var(--form-carry-border);
  cursor: pointer;
  background: #fff;
  border: 1px solid var(--form-carry-border);
  border-radius: 4px;
}

.next-visit-hint {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: var(--form-text-secondary);
}

.form-category {
  margin-bottom: var(--form-gutter);
}

.category-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #262626;
}

.category-fields {
  display: flex;
  flex-wrap: wrap;
  gap: var(--form-gutter);
}

/* 平板 / 手机：字段单列（每字段占满一行，pages.md §5.2） */
.category-fields.single-column .form-field {
  flex-basis: 100%;
}

.form-field {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: flex-start;
  padding: 2px 4px;
  border-radius: 4px;
}

.form-field.field-full-row {
  flex-basis: 100%;
}

.form-field.field-vertical {
  flex-direction: column;
}

.field-label {
  flex: none;
  width: var(--form-label-width, 140px);
  text-align: right;
  word-break: break-all;
}

/* 必填 * ：CSS 伪元素渲染，避免模板行超宽 */
.field-label.required::before {
  margin-right: 2px;
  color: var(--form-error);
  content: '*';
}

.field-vertical .field-label {
  width: auto;
  text-align: left;
}

.field-body {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 4px 8px;
  align-items: center;
  min-width: 0;
}

.field-vertical .field-body {
  width: 100%;
}

.field-display {
  color: #262626;
}

.field-display-empty {
  color: var(--form-text-secondary);
}

.field-unit {
  font-size: 12px;
  color: var(--form-text-secondary);
}

.pair-number {
  display: flex;
  gap: 4px;
  align-items: center;
}

/* 手机：长 × 宽占满一行、两框均分（inputmode=decimal 弹数字键盘） */
.form-mobile .pair-number {
  width: 100%;
}

.form-mobile .pair-number :deep(.ant-input-number) {
  flex: 1 1 0;
  min-width: 0;
}

.pair-separator {
  color: var(--form-text-secondary);
}

.option-detail {
  width: var(--form-field-short-text);
}

/* 手机：「其他」补充说明占满宽（与输入框占满宽同口径） */
.form-mobile .option-detail {
  width: 100%;
}

.previous-value {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--form-text-secondary);
}

/* 分组标题标红数（E17-T03）：红色 ▲n（design-system §14） */
.category-highlight {
  margin-left: 4px;
  font-size: 12px;
  color: var(--form-error);
}

/* 对比标红字段旁提示（E17-T03 / A-FU-027）：▲ + 变化量 + 对比值；红底由
   globalCss .crdp-field-flag 提供输入框背景，这里只管文案行 */
.field-highlight {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  font-size: 12px;
  color: var(--form-error);
  cursor: help;
}

.field-highlight .highlight-flag {
  font-weight: 600;
}

.field-warning {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--form-warning);
}

/* 派生留空原因（E11-T05）：橙色提示，不拦截（与软提示同色系、独立样式便于测试定位） */
.derived-hint {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--form-warning);
}

.field-error {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--form-error);
}

.field-has-error :deep(.ant-input-number),
.field-has-error :deep(.ant-select-selector),
.field-has-error :deep(.ant-input) {
  border-color: var(--form-error);
}

.field-help {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--form-text-secondary);
}

.chip {
  padding: 0 6px;
  font-size: 12px;
  font-style: normal;
  line-height: 18px;
  color: var(--form-text-secondary);
  background: var(--form-carry-bg);
  border-radius: 4px;
}

.chip.carried {
  color: var(--form-carry-border);
}

/* 带入上次值（C-FU-016）与报告单带入：浅色底标出（chip 文案区分来源） */
.field-carried,
.field-from-report {
  background: var(--form-carry-bg);
}
</style>
