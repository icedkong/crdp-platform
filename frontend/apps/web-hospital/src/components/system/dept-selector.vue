<script lang="ts" setup>
import type { DeptNodeView, OrgTreeView } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';

import { TreeSelect } from 'ant-design-vue';

import { orgTree } from '#/api/core/org';

/**
 * E06-T09 / PLT-ORG-012：科室选择器（共用组件）。树形选择本院科室，**只列启用中的科室**
 * （platform-spec §6 业务规则「停用科室不出现在选择器中」）；院区为不可选分组节点。
 * 单选 / 多选由 multiple 决定；业务模块（研究项目科室、责任科室）与系统页共用。
 */
defineOptions({ name: 'DeptSelector' });
const props = withDefaults(
  defineProps<{
    /** 多选（负责科室等）；默认单选 */
    multiple?: boolean;
    /** 占位文案 */
    placeholder?: string;
    value?: null | number | number[];
  }>(),
  { multiple: false, placeholder: '选择科室', value: null },
);

const emit = defineEmits<{
  (e: 'change', value: null | number | number[]): void;
}>();

const tree = ref<OrgTreeView>({ orgs: [], singleOrg: false });
const loaded = ref(false);

async function load() {
  try {
    tree.value = await orgTree();
  } catch {
    // 错误提示由统一拦截器弹出（含 traceId）；保留空树便于重试
  } finally {
    loaded.value = true;
  }
}

onMounted(() => {
  void load();
});

interface DeptTreeNode {
  children?: DeptTreeNode[];
  selectable?: boolean;
  title: string;
  value: number;
}

/** 启用科室 → 树节点（停用科室不出现，PLT-ORG-001 业务规则）。 */
const treeData = computed<DeptTreeNode[]>(() =>
  tree.value.orgs.map((org) => ({
    children: enabledDeptNodes(org.depts),
    selectable: false,
    title: org.name,
    value: -org.id,
  })),
);

function enabledDeptNodes(depts: DeptNodeView[]): DeptTreeNode[] {
  return depts
    .filter((dept) => dept.status === 'ENABLED')
    .map((dept) => ({
      children: enabledDeptNodes(dept.children),
      title: dept.name,
      value: dept.id,
    }));
}

function onChange(value: unknown) {
  if (props.multiple) {
    emit('change', (value as number[]) ?? []);
    return;
  }
  emit('change', (value as number) ?? null);
}
</script>

<template>
  <TreeSelect
    :allow-clear="true"
    :loading="!loaded"
    :placeholder="placeholder"
    :tree-checkable="multiple"
    :tree-data="treeData"
    :tree-default-expand-all="true"
    :value="value ?? (multiple ? [] : undefined)"
    style="width: 100%"
    @change="onChange"
  />
</template>
