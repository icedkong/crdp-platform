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
 * - Enter 不提交（本组件只阻止默认行为；Ctrl/⌘+S、Ctrl/⌘+Enter 由页面监听）。
 * 不在本组件（后续任务）：派生重算（E11-T05）、对比标红（E17）、已填 / 应填（E11-T11）、
 * 附件条（E16）、自动保存（E25）、平板 / 手机深度响应式（E24-T02）。
 */
import type { FormSchema } from '@crdp/api';
import type { FieldIssue } from '@crdp/rules';

import type { PreviousRecord } from './carry-over';
import type { ChoiceOption, RendererCategory, RendererField } from './layout';
import type { FieldMetaValue } from './multiselect';
import type { RendererValidateResult } from './types';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import {
  isFieldVisible,
  isRequiredField,
  stripHiddenFieldValues,
  validateFormData,
} from '@crdp/rules';
import { breakpoints, colors, sizes } from '@crdp/tokens';
import {
  Checkbox,
  CheckboxGroup,
  DatePicker,
  Input,
  InputNumber,
  RadioGroup,
  Select,
  Textarea,
} from 'ant-design-vue';

import {
  applyCarryOver,
  canCarryOver,
  carryOverButtonText,
  carryOverPlan,
} from './carry-over';
import { previousValueText, readonlyFieldValue } from './format-value';
import {
  anchorId,
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

const props = withDefaults(
  defineProps<{
    /** 条件上下文（如医院 C 的 device.*，E21-T03）。 */
    context?: null | Record<string, unknown>;
    /** 布局：auto 桌面 140 / 平板 120 / 手机标签在上；horizontal 固定横向；vertical 标签在上。 */
    layout?: 'auto' | 'horizontal' | 'vertical';
    /** 字段附加信息（data_meta）：manual / note（「其他」说明）/ fromReport（报告单带入）。 */
    meta?: null | Record<string, FieldMetaValue | undefined>;
    /** edit 录入 / readonly 只读（空值显示「未填写」）。 */
    mode?: 'edit' | 'readonly';
    /** 表单数据（键 = 稳定键；v-model）。 */
    modelValue: Record<string, unknown>;
    /** 上一次记录（带入上次值 / 「上次 xx」提示；规则参数决定取法，后端给）。 */
    previous?: null | PreviousRecord;
    /** 表单模板（FormSchema，字段 / 分组 / 选项 / 条件 / 校验全部来自它）。 */
    schema: FormSchema;
    /** 提交校验后为 true：显示必填 / 硬错误标红。 */
    showErrors?: boolean;
  }>(),
  {
    context: undefined,
    layout: 'auto',
    meta: undefined,
    mode: 'edit',
    previous: null,
    showErrors: false,
  },
);
const emit = defineEmits<{
  carryOver: [keys: string[]];
  clearedHidden: [keys: string[]];
  fieldBlur: [key: string];
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

/** 断点（tokens.breakpoints）：auto 布局用；监听 window.matchMedia。 */
const deviceKind = ref<'desktop' | 'mobile' | 'tablet'>('desktop');
let mediaMobile: MediaQueryList | undefined;
let mediaTablet: MediaQueryList | undefined;

function syncDevice(): void {
  if (mediaMobile?.matches) {
    deviceKind.value = 'mobile';
  } else if (mediaTablet?.matches) {
    deviceKind.value = 'tablet';
  } else {
    deviceKind.value = 'desktop';
  }
}

onMounted(() => {
  if (typeof window.matchMedia !== 'function') return;
  mediaMobile = window.matchMedia(`(max-width: ${breakpoints.tablet - 1}px)`);
  mediaTablet = window.matchMedia(
    `(min-width: ${breakpoints.tablet}px) and (max-width: ${breakpoints.desktop - 1}px)`,
  );
  mediaMobile.addEventListener('change', syncDevice);
  mediaTablet.addEventListener('change', syncDevice);
  syncDevice();
});

onBeforeUnmount(() => {
  mediaMobile?.removeEventListener('change', syncDevice);
  mediaTablet?.removeEventListener('change', syncDevice);
});

/** 只读固定 120（detailLabelWidth）；auto 按断点；手机标签在上（E24-T02 做深度适配）。 */
const resolvedLayout = computed(() => {
  if (isReadonly.value) return 'horizontal';
  if (props.layout === 'vertical') return 'vertical';
  if (props.layout === 'horizontal') return 'horizontal';
  return deviceKind.value === 'mobile' ? 'vertical' : 'horizontal';
});

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

/** 控件宽度（按类型定宽，不随屏幕拉伸；长文本 / 选项组占满一行）。 */
function fieldControlStyle(field: RendererField): Record<string, string> {
  const kind = kindOf(field);
  if (kind === 'number') return { width: 'var(--form-field-number)' };
  if (kind === 'date' || kind === 'datetime')
    return { width: 'var(--form-field-date)' };
  if (kind === 'select') return { width: 'var(--form-field-select)' };
  if (kind === 'text') return { width: 'var(--form-field-short-text)' };
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
  ];
  return { hardErrors, warnings: base.warnings };
});

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

/** 静态展示（只读视图 / 派生 / 敏感）与编辑控件二选一。 */
function isStaticDisplay(field: RendererField): boolean {
  return (
    isReadonly.value ||
    kindOf(field) === 'derived' ||
    kindOf(field) === 'sensitive'
  );
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

// ---------------------------------------------------------------- 暴露

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

defineExpose({ focusFirstError, scrollTo, validate });
</script>

<template>
  <div
    ref="root"
    class="form-renderer"
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
      <h4 class="category-title">{{ category.label }}</h4>
      <div class="category-fields">
        <div
          v-for="field in visibleFieldsOf(category)"
          :key="field.key"
          class="form-field"
          :class="{
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

.pair-separator {
  color: var(--form-text-secondary);
}

.option-detail {
  width: var(--form-field-short-text);
}

.previous-value {
  flex-basis: 100%;
  font-size: 12px;
  color: var(--form-text-secondary);
}

.field-warning {
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
