<script lang="ts" setup>
import type { UserOptionView } from '@crdp/api';

import { onMounted, ref, watch } from 'vue';

import { userOptionLabel } from '@crdp/api';
import { Select } from 'ant-design-vue';

import { userOptions } from '#/api/core/user';

/**
 * E06-T09 / PLT-ORG-012：用户选择器（共用组件）。服务端搜索本院启用用户（keyword 命中
 * 账号 / 姓名 / 工号，登录即可调用），文案 `姓名（账号 · 科室）`；业务模块（责任医生、
 * 研究成员）与系统页（科室负责人、角色成员）共用。单选 / 多选由 multiple 决定。
 */
defineOptions({ name: 'UserSelector' });
const props = withDefaults(
  defineProps<{
    /** 过滤：主科室或负责科室命中（可选） */
    deptId?: null | number;
    /** 多选（研究成员等）；默认单选 */
    multiple?: boolean;
    /** 占位文案 */
    placeholder?: string;
    /** 搜索框为空时的初始候选是否加载（默认加载，便于直接点开就有人可选） */
    preload?: boolean;
    value?: null | number | number[];
  }>(),
  {
    deptId: null,
    multiple: false,
    placeholder: '输入姓名、账号或工号搜索本院用户',
    preload: true,
    value: null,
  },
);

const emit = defineEmits<{
  (e: 'change', value: null | number | number[]): void;
}>();

const options = ref<UserOptionView[]>([]);
const loading = ref(false);
let searchTimer: null | ReturnType<typeof setTimeout> = null;

async function search(keyword: string) {
  loading.value = true;
  try {
    options.value = await userOptions({
      deptId: props.deptId ?? undefined,
      keyword: keyword.trim() === '' ? undefined : keyword.trim(),
    });
  } catch {
    options.value = [];
    // 错误提示由统一拦截器弹出（含 traceId）
  } finally {
    loading.value = false;
  }
}

/** 输入停止 300ms 再查（选择器防抖，避免每个按键都打一次库）。 */
function onSearch(keyword: string) {
  if (searchTimer) {
    clearTimeout(searchTimer);
  }
  searchTimer = setTimeout(() => {
    void search(keyword);
  }, 300);
}

function onChange(value: unknown) {
  if (props.multiple) {
    emit('change', (value as number[]) ?? []);
    return;
  }
  emit('change', (value as number) ?? null);
}

onMounted(() => {
  if (props.preload) {
    void search('');
  }
});

watch(
  () => props.deptId,
  () => {
    void search('');
  },
);
</script>

<template>
  <Select
    :allow-clear="true"
    :filter-option="false"
    :loading="loading"
    :mode="multiple ? 'multiple' : undefined"
    :options="
      options.map((option) => ({
        label: userOptionLabel(option),
        value: option.id,
      }))
    "
    :placeholder="placeholder"
    :show-search="true"
    :value="value ?? (multiple ? [] : undefined)"
    style="width: 100%"
    @change="onChange"
    @search="onSearch"
  />
</template>
