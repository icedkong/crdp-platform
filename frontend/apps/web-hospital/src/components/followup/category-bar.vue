<script lang="ts" setup>
import type { CategoryProgress } from '#/reference/types';

import { computed } from 'vue';

import { colors } from '@crdp/tokens';
import { Select } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

const props = withDefaults(
  defineProps<{
    activeKey?: string;
    items?: CategoryProgress[];
    mode?: 'auto' | 'bar' | 'select';
    showHighlightCount?: boolean;
  }>(),
  {
    activeKey: '',
    items: () => [],
    mode: 'auto',
    showHighlightCount: false,
  },
);

const emit = defineEmits<{
  jump: [key: string];
  'update:activeKey': [key: string];
}>();

const { isMobile } = useBreakpoint();
const asSelect = computed(
  () => props.mode === 'select' || (props.mode === 'auto' && isMobile.value),
);

function pick(key: string) {
  emit('update:activeKey', key);
  emit('jump', key);
}

const options = computed(() =>
  props.items.map((item) => ({
    label: `${item.label} ${item.filled}/${item.total}`,
    value: item.key,
  })),
);
</script>

<template>
  <div v-if="asSelect" class="bar" data-testid="category-select">
    <Select
      :options="options"
      placeholder="跳到分组"
      :value="activeKey"
      @update:value="pick(String($event))"
    />
  </div>
  <div v-else class="bar chips" data-testid="category-bar">
    <button
      v-for="item in items"
      :key="item.key"
      class="chip"
      :class="{ active: item.key === activeKey }"
      type="button"
      @click="pick(item.key)"
    >
      <span>{{ item.label }}</span>
      <span
        class="count"
        :style="{
          color: item.filled < item.total ? colors.warning : undefined,
        }"
      >
        {{ item.filled }}/{{ item.total }}
      </span>
      <span v-if="showHighlightCount && item.highlightCount" class="flag">
        ▲{{ item.highlightCount }}
      </span>
      <span v-if="item.attachmentCount">📎 {{ item.attachmentCount }}</span>
    </button>
  </div>
</template>

<style scoped>
.bar {
  position: sticky;
  top: 0;
  z-index: 5;
  background: #fff;
}

.chips {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  overflow-x: auto;
}

.chips::-webkit-scrollbar {
  display: none;
}

.chip {
  display: inline-flex;
  flex: none;
  gap: 6px;
  align-items: center;
  padding: 4px 10px;
  font-size: 14px;
  color: #262626;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}

.chip.active {
  background: #e6f0ff;
  border-color: #3a75ff;
}

.flag {
  color: #ff4d4f;
}
</style>
