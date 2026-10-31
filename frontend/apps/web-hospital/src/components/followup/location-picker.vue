<script lang="ts" setup>
import type { LocationTreeNode, ReportLocationInput } from '#/reference/types';

import { computed } from 'vue';

import { colors } from '@crdp/tokens';
import { TreeSelect } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

const props = defineProps<{
  options: LocationTreeNode[];
  patientId: string;
  preset?: ReportLocationInput[];
  showError?: boolean;
  value: ReportLocationInput[];
}>();

const emit = defineEmits<{
  'update:value': [value: ReportLocationInput[]];
}>();

const { isMobile } = useBreakpoint();

const payloadByKey = computed(() => {
  const map = new Map<string, ReportLocationInput>();
  const walk = (nodes: LocationTreeNode[]) => {
    for (const node of nodes) {
      if (node.value) map.set(node.key, node.value);
      if (node.children) walk(node.children);
    }
  };
  walk(props.options);
  return map;
});

const treeData = computed(() => {
  const mapNode = (node: LocationTreeNode): Record<string, unknown> => ({
    children: node.children?.map((child) => mapNode(child)),
    disabled: !node.selectable && !node.children?.length,
    key: node.key,
    selectable: node.selectable,
    title: node.title,
    value: node.key,
  });
  return props.options.map((node) => mapNode(node));
});

const selectedKeys = computed(() => {
  const keys: string[] = [];
  for (const [key, payload] of payloadByKey.value) {
    const hit = props.value.some(
      (item) =>
        item.linkType === payload.linkType &&
        item.linkRecordId === payload.linkRecordId &&
        item.anchorKey === payload.anchorKey &&
        item.anchorType === payload.anchorType,
    );
    if (hit) keys.push(key);
  }
  return keys;
});

function onChange(raw: unknown) {
  const keys = Array.isArray(raw)
    ? raw.map((item) =>
        typeof item === 'string' ? item : (item as { value?: string })?.value,
      )
    : [];
  const next = keys
    .map((key) => (key ? payloadByKey.value.get(key) : undefined))
    .filter(Boolean);
  emit('update:value', next);
}

const invalid = computed(() => props.showError && props.value.length === 0);
</script>

<template>
  <div :data-patient-id="patientId" data-testid="location-picker">
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
    <p v-if="isMobile" class="hint">手机上点开后在下拉树里多选。</p>
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
</style>
