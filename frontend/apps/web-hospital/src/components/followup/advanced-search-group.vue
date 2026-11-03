<script lang="ts" setup>
/**
 * E13-T06 / A-PAT-014 · 高级检索条件组（AdvancedSearch 弹窗内的递归组，components.md §11.2）：
 * 逐行「字段 / 运算符 / 条件值 / −」，行内可拖动排序（原生 HTML5 DnD，避免引新依赖）；
 * 组头「并且 / 或者」切换组内逻辑；可加条件行与嵌套条件组（后端限三层）。
 * 字段只能从目录（字段字典生成）中选择——不允许输入原始 SQL（A-NFR-009）。
 */
import type {
  AdvancedSearchField,
  ConditionGroup,
  ConditionRow,
} from '@crdp/api';

import { computed, ref } from 'vue';

import {
  isConditionGroup,
  OPERATOR_LABELS,
  withGroupAdded,
  withItemMoved,
  withItemRemoved,
  withItemReplaced,
  withRowAdded,
} from '@crdp/api';
import { colors } from '@crdp/tokens';
import {
  Button,
  DatePicker,
  Input,
  InputNumber,
  Select,
  Space,
} from 'ant-design-vue';

const props = // 递归渲染子组：SFC 按文件名隐式自引用（无需 import，oxlint 禁止 self-import）
  defineProps<{
    /** 字段目录（已按 mobileCommonOnly 过滤后的可选项）。 */
    fields: AdvancedSearchField[];
    /** 当前组（不可变更新，update:group 事件上抛）。 */
    group: ConditionGroup;
    /** 嵌套组带边框并可整组删除。 */
    nested?: boolean;
  }>();

const emit = defineEmits<{
  (e: 'update:group', group: ConditionGroup): void;
  (e: 'remove'): void;
}>();

const RangePicker = DatePicker.RangePicker;

function update(next: ConditionGroup) {
  emit('update:group', next);
}

function fieldOf(row: ConditionRow): AdvancedSearchField | undefined {
  return props.fields.find((item) => item.field === row.field);
}

function replaceItem(
  index: number,
  replacer: (
    item: ConditionGroup | ConditionRow,
  ) => ConditionGroup | ConditionRow,
) {
  update(withItemReplaced(props.group, [index], replacer));
}

function onRowChanged(index: number, patch: Partial<ConditionRow>) {
  replaceItem(index, (item) =>
    isConditionGroup(item) ? item : { ...item, ...patch },
  );
}

/** 换字段：运算符切到新字段首个可用项，取值清空（取值形态可能变化）。 */
function onFieldChange(index: number, fieldCode: string) {
  const next = props.fields.find((item) => item.field === fieldCode);
  replaceItem(index, (item) =>
    isConditionGroup(item)
      ? item
      : { field: fieldCode, op: next?.ops[0] ?? 'EQ', value: null },
  );
}

/** 换运算符：取值形态变化时清空。 */
function onOpChange(index: number, op: string) {
  replaceItem(index, (item) =>
    isConditionGroup(item) ? item : { ...item, op, value: null },
  );
}

// ---------------------------------------------------------------- 取值控件

function rowValue(row: ConditionRow) {
  return row.value ?? null;
}

function setRowValue(index: number, value: unknown) {
  onRowChanged(index, { value: value as ConditionRow['value'] });
}

/** 日期控件取值（DatePicker 只吃 string | undefined）。 */
function dateValue(row: ConditionRow): string | undefined {
  return typeof row.value === 'string' ? row.value : undefined;
}

/** 文本控件取值。 */
function textValue(row: ConditionRow): string {
  return typeof row.value === 'string' ? row.value : '';
}

function betweenValue(row: ConditionRow): [string, string] | undefined {
  if (Array.isArray(row.value) && row.value.length === 2) {
    return [String(row.value[0]), String(row.value[1])];
  }
  return undefined;
}

// ---------------------------------------------------------------- 拖动排序（原生 DnD）

const dragIndex = ref(-1);

function onDragStart(index: number) {
  dragIndex.value = index;
}

function onDrop(index: number) {
  if (dragIndex.value >= 0 && dragIndex.value !== index) {
    update(withItemMoved(props.group, dragIndex.value, index));
  }
  dragIndex.value = -1;
}

// ---------------------------------------------------------------- 选项

const fieldOptions = computed(() =>
  props.fields.map((item) => ({
    label: item.categoryLabel
      ? `${item.categoryLabel} / ${item.label}`
      : item.label,
    value: item.field,
  })),
);

function operatorOptions(row: ConditionRow) {
  const field = fieldOf(row);
  return (field?.ops ?? []).map((op) => ({
    label: OPERATOR_LABELS[op] ?? op,
    value: op,
  }));
}

const optionValues = (row: ConditionRow) =>
  (fieldOf(row)?.options ?? []).map((option) => ({
    label: option.label,
    value: option.value,
  }));

/** 颜色全部取自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = {
  '--adv-border-secondary': colors.borderSecondary,
  '--adv-text-secondary': colors.textSecondary,
};

const logicOptions = [
  { label: '并且', value: 'and' },
  { label: '或者', value: 'or' },
];

function addRow() {
  const first = props.fields[0];
  update(
    withRowAdded(props.group, {
      field: first?.field ?? '',
      op: first?.ops[0] ?? 'EQ',
      value: null,
    }),
  );
}
</script>

<template>
  <div
    :class="{ 'condition-group--nested': nested }"
    class="condition-group"
    :style="cssVars"
  >
    <div class="group-head">
      <Select
        :value="group.logic"
        :options="logicOptions"
        size="small"
        :style="{ width: '88px' }"
        @change="
          (value: unknown) =>
            update({ ...group, logic: value === 'or' ? 'or' : 'and' })
        "
      />
      <Button
        :disabled="fields.length === 0"
        size="small"
        type="dashed"
        @click="addRow"
      >
        + 条件
      </Button>
      <Button size="small" type="dashed" @click="update(withGroupAdded(group))">
        + 条件组
      </Button>
      <Button
        v-if="nested"
        danger
        size="small"
        type="text"
        @click="emit('remove')"
      >
        删除组
      </Button>
    </div>

    <div
      v-for="(item, index) in group.items"
      :key="index"
      class="condition-item"
      draggable="true"
      @dragover.prevent
      @dragstart="onDragStart(index)"
      @drop.prevent="onDrop(index)"
      @dragend="dragIndex = -1"
    >
      <template v-if="!isConditionGroup(item)">
        <Select
          :value="item.field"
          show-search
          :options="fieldOptions"
          placeholder="选择字段"
          :style="{ width: '220px' }"
          @change="(value: unknown) => onFieldChange(index, String(value))"
        />
        <Select
          :value="item.op"
          :options="operatorOptions(item)"
          :style="{ width: '104px' }"
          @change="(value: unknown) => onOpChange(index, String(value))"
        />

        <!-- 取值控件按字段形态与运算符（components.md §11.2；目录 ops 决定可用项） -->
        <template v-if="item.op !== 'IS_NULL' && item.op !== 'NOT_NULL'">
          <template v-if="fieldOf(item)?.type === 'DATE'">
            <RangePicker
              v-if="item.op === 'BETWEEN'"
              :value="betweenValue(item)"
              value-format="YYYY-MM-DD"
              :style="{ width: '240px' }"
              @change="
                (_dates: unknown, dateStrings: [string, string]) =>
                  setRowValue(index, dateStrings)
              "
            />
            <DatePicker
              v-else
              :value="dateValue(item)"
              value-format="YYYY-MM-DD"
              :style="{ width: '160px' }"
              @change="
                (_date: unknown, dateString: string) =>
                  setRowValue(index, dateString)
              "
            />
          </template>

          <Space v-else-if="item.op === 'BETWEEN'" :size="4">
            <InputNumber
              :value="
                Array.isArray(item.value)
                  ? (item.value[0] as number)
                  : undefined
              "
              @change="
                (value: unknown) =>
                  setRowValue(index, [
                    value,
                    Array.isArray(item.value) ? item.value[1] : null,
                  ])
              "
            />
            <span class="between-sep">~</span>
            <InputNumber
              :value="
                Array.isArray(item.value)
                  ? (item.value[1] as number)
                  : undefined
              "
              @change="
                (value: unknown) =>
                  setRowValue(index, [
                    Array.isArray(item.value) ? item.value[0] : null,
                    value,
                  ])
              "
            />
          </Space>

          <Select
            v-else-if="fieldOf(item)?.type === 'OPTION'"
            :value="rowValue(item) ?? undefined"
            :options="optionValues(item)"
            allow-clear
            placeholder="取值"
            :style="{ width: '160px' }"
            @change="(value: unknown) => setRowValue(index, value)"
          />

          <Input
            v-else
            :value="textValue(item)"
            allow-clear
            placeholder="取值"
            :style="{ width: '180px' }"
            @change="
              (event: Event) =>
                setRowValue(index, (event.target as HTMLInputElement).value)
            "
          />
        </template>
        <span v-else class="no-value">—</span>

        <Button
          danger
          size="small"
          type="text"
          @click="update(withItemRemoved(group, [index]))"
        >
          −
        </Button>
      </template>

      <AdvancedSearchGroup
        v-else
        :group="item"
        :fields="fields"
        nested
        @remove="update(withItemRemoved(group, [index]))"
        @update:group="(next) => replaceItem(index, () => next)"
      />
    </div>

    <div v-if="group.items.length === 0" class="group-empty">
      尚无条件，点「+ 条件」添加
    </div>
  </div>
</template>

<style scoped>
.condition-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.condition-group--nested {
  padding: 8px;
  border: 1px solid var(--adv-border-secondary);
  border-radius: 6px;
}

.group-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.condition-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 2px 4px;
  cursor: grab;
}

.condition-item:active {
  cursor: grabbing;
}

.no-value {
  width: 160px;
  color: var(--adv-text-secondary);
  text-align: center;
}

.between-sep {
  color: var(--adv-text-secondary);
}

.group-empty {
  font-size: 12px;
  color: var(--adv-text-secondary);
}
</style>
