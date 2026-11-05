<script lang="ts" setup>
import type { AutoSaveConflict } from '@crdp/form-engine';

import { computed } from 'vue';

import { Alert, Button, Space } from 'ant-design-vue';

/**
 * NFR-SAVE-003 · 409 冲突条（components.md §13、pages.md §5.1 文案照抄）：
 * 「版本冲突：{更新人} 于 {时间} 修改了 {字段}」+「保留我的并覆盖」/「载入服务器版本」。
 * 不做字段级自动合并；选择由页面调 useAutoSave 的 keepMine / loadServer。
 */
const props = defineProps<{
  conflict: AutoSaveConflict;
  /** 处理中的动作（对应按钮 loading，另一个禁用）。 */
  pending?: 'keepMine' | 'loadServer' | null;
}>();

const emit = defineEmits<{
  keepMine: [];
  loadServer: [];
}>();

const updatedBy = computed(() => props.conflict.updatedBy || '他人');

/** 时间到分钟（YYYY-MM-DD HH:mm，本地时区口径）。 */
const updatedAt = computed(() => {
  const time = new Date(props.conflict.updatedAt);
  if (Number.isNaN(time.getTime())) return props.conflict.updatedAt;
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${time.getFullYear()}-${pad(time.getMonth() + 1)}-${pad(
    time.getDate(),
  )} ${pad(time.getHours())}:${pad(time.getMinutes())}`;
});

const message = computed(
  () =>
    `版本冲突：${updatedBy.value} 于 ${updatedAt.value} 修改了 ${props.conflict.fields.join('、')}`,
);
</script>

<template>
  <Alert
    class="conflict-banner"
    data-testid="conflict-banner"
    :message="message"
    show-icon
    type="error"
  >
    <template #action>
      <Space>
        <Button
          danger
          data-testid="conflict-keep-mine"
          :disabled="pending === 'loadServer'"
          :loading="pending === 'keepMine'"
          size="small"
          type="primary"
          @click="emit('keepMine')"
        >
          保留我的并覆盖
        </Button>
        <Button
          data-testid="conflict-load-server"
          :disabled="pending === 'keepMine'"
          :loading="pending === 'loadServer'"
          size="small"
          @click="emit('loadServer')"
        >
          载入服务器版本
        </Button>
      </Space>
    </template>
  </Alert>
</template>

<style scoped>
.conflict-banner {
  margin-bottom: 8px;
}
</style>
