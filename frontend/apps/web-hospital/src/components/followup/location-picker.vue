<script lang="ts" setup>
import type { ReportLocationInput, ReportLocationNode } from '@crdp/api';

import { computed } from 'vue';

import { colors } from '@crdp/tokens';
import { Drawer, Tree, TreeSelect } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

/**
 * E16-T02 / A-ATT-002、C-SCAN-010（components.md §8）：报告单位置选择——
 * 必填、可多选、至少 1 个；勾分组不自动勾父节点（整条 vs 分组由用户决定），不支持字段级。
 * 桌面 a-tree-select（SHOW_ALL、check-strictly），手机底部抽屉内 a-tree（全屏高 80vh）。
 */
const props = defineProps<{
  options: ReportLocationNode[];
  patientId: string;
  preset?: ReportLocationInput[];
  showError?: boolean;
  value: ReportLocationInput[];
}>();

const emit = defineEmits<{
  'update:value': [value: ReportLocationInput[]];
}>();

const { isMobile } = useBreakpoint();
const mobileOpen = defineModel<boolean>('mobileOpen', { default: false });

const payloadByKey = computed(() => {
  const map = new Map<string, ReportLocationInput>();
  const walk = (nodes: ReportLocationNode[]) => {
    for (const node of nodes) {
      if (node.value) map.set(node.key, node.value);
      if (node.children) walk(node.children);
    }
  };
  walk(props.options);
  return map;
});

/** 树渲染结构：分组节点不可选也不可勾（selectable=false 且 disabled），叶子可勾。 */
const treeData = computed(() => {
  const mapNode = (node: ReportLocationNode): Record<string, unknown> => ({
    children: node.children?.map((child) => mapNode(child)),
    disabled: !node.selectable && !node.children?.length,
    key: node.key,
    selectable: node.selectable,
    title: node.title,
    value: node.key,
  });
  return props.options.map((node) => mapNode(node));
});

/** 值 → 选中键：按位置四元组（类型 / 记录 / 锚点类型 / 锚点键）对齐，与后端去重键同口径。 */
const selectedKeys = computed(() => {
  const keys: string[] = [];
  for (const [key, payload] of payloadByKey.value) {
    const hit = props.value.some((item) => sameLocation(item, payload));
    if (hit) keys.push(key);
  }
  return keys;
});

const checkedKeysForTree = computed(() => selectedKeys.value);

function sameLocation(a: ReportLocationInput, b: ReportLocationInput) {
  return (
    a.linkType === b.linkType &&
    (a.linkRecordId ?? '') === (b.linkRecordId ?? '') &&
    (a.anchorType ?? '') === (b.anchorType ?? '') &&
    (a.anchorKey ?? '') === (b.anchorKey ?? '')
  );
}

function emitKeys(keys: Array<string | { value?: string }>) {
  const next = keys
    .map((item) =>
      typeof item === 'string' ? item : (item as { value?: string })?.value,
    )
    .map((key) => (key ? payloadByKey.value.get(key) : undefined))
    .filter(Boolean) as ReportLocationInput[];
  emit('update:value', next);
}

function onChange(raw: unknown) {
  const keys = Array.isArray(raw) ? raw : [];
  emitKeys(keys as Array<string | { value?: string }>);
}

/** 手机抽屉内 a-tree（check 严格模式，勾选即多选；父子不联动）。 */
function onTreeCheck(keys: unknown) {
  const list = Array.isArray(keys) ? (keys as string[]) : (keys?.checked ?? []);
  emitKeys(list.map((key) => ({ value: key })));
}

const invalid = computed(() => props.showError && props.value.length === 0);
</script>

<template>
  <div :data-patient-id="patientId" data-testid="location-picker">
    <template v-if="!isMobile">
      <TreeSelect
        allow-clear
        class="picker"
        :max-tag-count="2"
        placeholder="请选择位置，至少 1 个"
        show-checked-strategy="SHOW_ALL"
        :tree-check-strictly="true"
        tree-checkable
        tree-default-expand-all
        :tree-data="treeData"
        :value="selectedKeys"
        @update:value="onChange"
      />
    </template>
    <template v-else>
      <button class="mobile-entry" type="button" @click="mobileOpen = true">
        {{
          value.length > 0
            ? `已选 ${value.length} 个位置`
            : '请选择位置，至少 1 个'
        }}
      </button>
      <Drawer
        :open="mobileOpen"
        placement="bottom"
        title="选择位置（可多选）"
        @update:open="mobileOpen = $event"
      >
        <Tree
          check-strictly
          checkable
          default-expand-all
          :checked-keys="checkedKeysForTree"
          :field-names="{ children: 'children', key: 'key', title: 'title' }"
          :tree-data="treeData"
          @check="onTreeCheck"
        />
      </Drawer>
    </template>
    <p v-if="invalid" class="error" :style="{ color: colors.error }">
      请至少选择 1 个位置
    </p>
    <p v-else-if="preset?.length" class="hint">已按进入位置预选，可以再加。</p>
  </div>
</template>

<style scoped>
.picker {
  width: 100%;
}

.hint,
.error {
  margin: 4px 0 0;
  font-size: 12px;
}

.hint {
  color: #8c8c8c;
}

.mobile-entry {
  width: 100%;
  min-height: 32px;
  padding: 4px 11px;
  font-size: 14px;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
}
</style>
