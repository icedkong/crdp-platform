<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import type { TaskDrawerFilterState } from '#/views/followup/task/responsive-logic';

import { sizes } from '@crdp/tokens';
import {
  Button,
  DatePicker,
  Drawer,
  Form,
  FormItem,
  Select,
} from 'ant-design-vue';

import { ASSIGNEE_FILTER_OPTIONS } from '#/views/followup/task/logic';

/**
 * E24-T05 / NFR-RESP-002 · 我的任务筛选抽屉（pages.md §7 手机：状态筛选用
 * 顶部分段切换、其余筛选进底部抽屉；design-system §7：底部抽屉 100% 宽只用于
 * 手机筛选等场景）。
 * - 状态筛选不进抽屉（顶部分段常驻可见）；抽屉只收 病区 / 计划日期 / 负责人；
 * - 输入改动以新状态对象上抛 update:filters（不直接改 props），页面回写筛选
 *   状态、「查询」才发请求——与桌面行内表单同一套「输入即回写、点查询才检索」
 *   行为（E24-T03 PatientFilterDrawer 同口径）。
 * 手机输入框高 36 / 字号 16 由 tokens globalCss 的 .crdp-form 手机媒体查询承担。
 */
const props = defineProps<{
  /** 页面筛选状态切片（改动经 update:filters 回写）。 */
  filters: TaskDrawerFilterState;
  open: boolean;
  /** 手机底部（100% 宽）；预留平板右侧（宽 480，design-system §7）。 */
  placement: 'bottom' | 'right';
  wardOptions: Array<{ label: string; value: number }>;
}>();

const emit = defineEmits<{
  (e: 'reset'): void;
  (e: 'search'): void;
  (e: 'update:filters', state: TaskDrawerFilterState): void;
  (e: 'update:open', open: boolean): void;
}>();

const RangePicker = DatePicker.RangePicker;

const drawerWidth = sizes.drawer.min;

/** 单字段改动 → 整份新筛选状态上抛（页面 Object.assign 回写 reactive 状态）。 */
function patch(partial: Partial<TaskDrawerFilterState>) {
  emit('update:filters', { ...props.filters, ...partial });
}

function close() {
  emit('update:open', false);
}

/** 查询：上抛检索并收起抽屉。 */
function onSearch() {
  emit('search');
  close();
}

function onReset() {
  emit('reset');
}

// antd 控件回调签名比业务类型宽（SelectValue / Dayjs 元组）；入参收窄在函数内做，
// 模板不写内联箭头（value-format=YYYY-MM-DD 时 RangePicker 回调即字符串元组）
function onWardChange(value: unknown) {
  patch({
    wardIds: Array.isArray(value) ? value.map(Number) : [],
  });
}

function onDueRangeChange(value: [Dayjs, Dayjs] | [string, string] | null) {
  // value-format=YYYY-MM-DD：运行时回调即字符串元组（Dayjs 形态仅在未设 value-format 时出现）
  patch({ dueRange: value as [string, string] | null });
}

function onAssigneeChange(value: unknown) {
  patch({ assignee: value === 'UNASSIGNED' ? 'UNASSIGNED' : 'ALL' });
}
</script>

<template>
  <Drawer
    class-name="crdp-form"
    :height="placement === 'bottom' ? '70vh' : undefined"
    :open="open"
    :placement="placement"
    :width="placement === 'right' ? drawerWidth : undefined"
    title="筛选"
    @close="close"
  >
    <Form layout="vertical" @submit.prevent>
      <FormItem label="病区">
        <Select
          :value="props.filters.wardIds"
          allow-clear
          data-testid="drawer-ward"
          :max-tag-count="2"
          mode="multiple"
          :options="props.wardOptions"
          placeholder="全部"
          @update:value="onWardChange"
        />
      </FormItem>
      <FormItem label="计划日期">
        <RangePicker
          :value="props.filters.dueRange ?? undefined"
          value-format="YYYY-MM-DD"
          @update:value="onDueRangeChange"
        />
      </FormItem>
      <FormItem label="负责人">
        <Select
          :value="props.filters.assignee"
          data-testid="drawer-assignee"
          :options="[...ASSIGNEE_FILTER_OPTIONS]"
          @update:value="onAssigneeChange"
        />
      </FormItem>
    </Form>
    <template #footer>
      <div class="drawer-actions">
        <Button data-testid="drawer-reset" @click="onReset">重置</Button>
        <Button data-testid="drawer-search" type="primary" @click="onSearch">
          查询
        </Button>
      </div>
    </template>
  </Drawer>
</template>

<style scoped>
/* 横向排列、查询靠右（触控 ≥ 40px 由 globalCss 承担） */
.drawer-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
