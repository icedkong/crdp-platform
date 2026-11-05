<script lang="ts" setup>
import type { AutoSaveStatus } from '@crdp/form-engine';

import { computed } from 'vue';

import { autoSaveStatusText } from '@crdp/form-engine';
import { colors } from '@crdp/tokens';

/**
 * NFR-SAVE-001/002/003 · 自动保存状态条（components.md §13、pages.md §5 状态条）。
 * 文案照抄 design-system §13：已自动保存 HH:mm / 有改动待同步（每 2 分钟）/
 * 离线 · 改动存在本机草稿 / 版本冲突 · 自动保存已暂停；idle 不渲染。
 */
const props = defineProps<{
  /** HH:mm（useAutoSave lastSavedAt）。 */
  lastSavedAt?: null | string;
  /** useAutoSave status。 */
  status: AutoSaveStatus;
}>();

const text = computed(() =>
  autoSaveStatusText(props.status, props.lastSavedAt ?? null),
);

const tone = computed(() => {
  switch (props.status) {
    case 'conflict': {
      return colors.error;
    }
    case 'offline': {
      return colors.warning;
    }
    case 'saved': {
      return colors.success;
    }
    default: {
      return colors.textSecondary;
    }
  }
});
</script>

<template>
  <div
    v-if="text"
    class="form-status-bar"
    :class="`status-${status}`"
    :style="{ color: tone }"
    data-testid="form-status-bar"
  >
    <span class="dot" :style="{ background: tone }"></span>
    <span>{{ text }}</span>
  </div>
</template>

<style scoped>
.form-status-bar {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 12px;
  line-height: 20px;
}

.dot {
  flex: 0 0 auto;
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
</style>
