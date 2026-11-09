<script lang="ts" setup>
import type { PatientFilterState } from '@crdp/api';

import { sizes } from '@crdp/tokens';
import {
  Button,
  DatePicker,
  Drawer,
  Form,
  FormItem,
  Select,
} from 'ant-design-vue';

/**
 * E24-T03 / NFR-RESP-002 · 患者列表筛选抽屉（pages.md §3：平板「筛选收进抽屉」
 * components.md §11.1：平板右侧、手机底部；design-system §7：底部抽屉 100% 宽）。
 * - 检索词（姓名 / 编号）常驻页面顶栏输入框，不在抽屉里重复；
 * - 输入改动以新状态对象上抛 update:filters（不直接改 props），页面回写筛选状态、
 *   「查询」才发请求——与桌面行内表单同一套「输入即回写、点查询才检索」行为；
 * - 「高级检索」收起本抽屉并上抛 open-advanced（手机形态由 AdvancedSearch
 *   mobileCommonOnly 切底部抽屉，只留常用条件）。
 * 手机输入框高 36 / 字号 16 由 tokens globalCss 的 .crdp-form 手机媒体查询承担
 * （E24-T09 走查修正：ant-design-vue 4 Drawer 无 class-name 属性，须用
 * root-class-name 落到 .ant-drawer 根，globalCss 的后代选择器才能命中抽屉内控件）。
 */
const props = defineProps<{
  /** 页面筛选状态（改动经 update:filters 回写）。 */
  filters: PatientFilterState;
  open: boolean;
  /** 平板右侧（宽 480，design-system §7 右侧抽屉下限）/ 手机底部（100% 宽）。 */
  placement: 'bottom' | 'right';
  statusOptions: Array<{ label: string; value: string }>;
  wardOptions: Array<{ label: string; value: number }>;
}>();

const emit = defineEmits<{
  (e: 'openAdvanced'): void;
  (e: 'reset'): void;
  (e: 'search'): void;
  (e: 'update:filters', state: PatientFilterState): void;
  (e: 'update:open', open: boolean): void;
}>();

const RangePicker = DatePicker.RangePicker;

const drawerWidth = sizes.drawer.min;

/** 单字段改动 → 整份新筛选状态上抛（页面 Object.assign 回写 reactive 状态）。 */
function patch(partial: Partial<PatientFilterState>) {
  emit('update:filters', { ...props.filters, ...partial });
}

function close() {
  emit('update:open', false);
}

/** 查询：上抛检索（词长校验在页面 handleSearch 统一做）并收起抽屉。 */
function onSearch() {
  emit('search');
  close();
}

function onReset() {
  emit('reset');
}

/** 高级检索：收起筛选抽屉再打开高级检索（同屏只保留一个抽屉）。 */
function onOpenAdvanced() {
  close();
  emit('openAdvanced');
}
</script>

<template>
  <Drawer
    root-class-name="crdp-form"
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
          :value="props.filters.wardId"
          allow-clear
          data-testid="drawer-ward"
          :options="props.wardOptions"
          placeholder="全部"
          @update:value="
            (value: null | number | undefined) => patch({ wardId: value })
          "
        />
      </FormItem>
      <FormItem label="随访状态">
        <Select
          :value="props.filters.status"
          allow-clear
          data-testid="drawer-status"
          :options="props.statusOptions"
          placeholder="全部"
          @update:value="(value: null | string) => patch({ status: value })"
        />
      </FormItem>
      <FormItem label="出院日期">
        <RangePicker
          :value="props.filters.dischargeRange"
          value-format="YYYY-MM-DD"
          @update:value="
            (value: [string, string] | null) => patch({ dischargeRange: value })
          "
        />
      </FormItem>
      <FormItem label="下次随访">
        <RangePicker
          :value="props.filters.nextVisitRange"
          value-format="YYYY-MM-DD"
          @update:value="
            (value: [string, string] | null) => patch({ nextVisitRange: value })
          "
        />
      </FormItem>
    </Form>
    <template #footer>
      <div class="drawer-actions">
        <Button data-testid="drawer-reset" @click="onReset">重置</Button>
        <Button
          data-testid="drawer-advanced"
          ghost
          type="primary"
          @click="onOpenAdvanced"
        >
          高级检索
        </Button>
        <!-- 「我的筛选」下拉由页面塞进来（A-PAT-015；与桌面行内表单同一份标记） -->
        <slot name="actions" />
        <Button data-testid="drawer-search" type="primary" @click="onSearch">
          查询
        </Button>
      </div>
    </template>
  </Drawer>
</template>

<style scoped>
/* 底部抽屉里按钮纵向排开的场景不存在：横向排列、查询靠右（触控 ≥ 40px 由 globalCss 承担） */
.drawer-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
