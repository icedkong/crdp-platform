<script lang="ts" setup>
/**
 * E16-T04 / A-ATT-010、C-SCAN-013（pages.md §4.6）：报告单页签左侧双树浏览。
 * 「按类型 | 按时间」切换（Segmented）+ a-tree：按类型「类型 → 名称，带数量」、
 * 按时间「报告时间 → 名称，时间倒序」；分组默认展开；点节点选中 / 再点取消
 * （update:selectedKey 交给父级过滤列表）；切换树清空上一棵的选中。
 * 构建口径在 report-tree-logic.ts（同名报告单按报告时间区分，A-ATT-011）。
 */
import type { ReportRow } from '@crdp/api';

import type { ReportTreeMode } from './report-tree-logic';

import { computed, ref, watch } from 'vue';

import { sizes } from '@crdp/tokens';
import { Segmented, Tree } from 'ant-design-vue';

import { buildReportTree, groupTreeKeys } from './report-tree-logic';

const props = withDefaults(
  defineProps<{
    loading?: boolean;
    mode?: ReportTreeMode;
    reports: ReportRow[];
    selectedKey?: null | string;
  }>(),
  { loading: false, mode: 'type', selectedKey: null },
);

const emit = defineEmits<{
  'update:mode': [mode: ReportTreeMode];
  'update:selectedKey': [key: null | string];
}>();

const modeValue = computed<ReportTreeMode>(() => props.mode ?? 'type');
const treeData = computed(() =>
  buildReportTree(props.reports, modeValue.value),
);

// 分组默认全部展开（树数据变化后重置，保证切树 / 刷新后数量可见）
const expandedKeys = ref<string[]>([]);
watch(
  treeData,
  (nodes) => {
    expandedKeys.value = groupTreeKeys(nodes);
  },
  { immediate: true },
);

// 切换树时清空上一棵的选中键（键空间不同，列表回到全部）
watch(
  () => props.mode,
  () => {
    emit('update:selectedKey', null);
  },
);

function onSelect(keys: Array<number | string>) {
  // antd a-tree：再点已选中节点 = 取消选中（keys 为空）
  emit('update:selectedKey', keys.length === 0 ? null : String(keys[0]));
}
</script>

<template>
  <aside class="tree" data-testid="report-tree">
    <Segmented
      class="mode"
      :options="[
        { label: '按类型', value: 'type' },
        { label: '按时间', value: 'time' },
      ]"
      :value="modeValue"
      @update:value="emit('update:mode', $event as ReportTreeMode)"
    />
    <Tree
      v-if="reports.length > 0"
      block-node
      :expanded-keys="expandedKeys"
      :selected-keys="selectedKey ? [selectedKey] : []"
      :tree-data="treeData"
      @select="onSelect"
      @update:expanded-keys="expandedKeys = $event"
    />
    <p v-else class="empty">
      {{ loading ? '加载中…' : '暂无报告单' }}
    </p>
  </aside>
</template>

<style scoped>
.tree {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: v-bind('`${sizes.attachmentNavWidth}px`');
  max-height: 640px;
  padding: 8px;
  overflow: auto;
  background: #fff;
  border: 1px solid #e9e9e9;
  border-radius: v-bind('`${sizes.cardRadius}px`');
}

.mode {
  align-self: stretch;
  width: 100%;
}

.empty {
  margin: 8px 0;
  font-size: 12px;
  color: #8c8c8c;
  text-align: center;
}
</style>
