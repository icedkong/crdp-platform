<script lang="ts" setup>
import type { StatusTone } from '@crdp/tokens';

import { computed } from 'vue';

import { colors, statusTone } from '@crdp/tokens';
import { Badge, Tag, Tooltip } from 'ant-design-vue';

const props = withDefaults(
  defineProps<{
    count?: number;
    domain?: string;
    status?: string;
    tooltip?: string;
    variant?: 'dot' | 'tag';
  }>(),
  {
    count: undefined,
    domain: '',
    status: '',
    tooltip: '',
    variant: 'tag',
  },
);

const tone = computed<StatusTone>(() => {
  const found = statusTone[props.status];
  if (!found && import.meta.env.DEV) {
    console.warn(`未知状态：${props.status}`);
  }
  return found ?? 'default';
});

const text = computed(() =>
  props.count === undefined ? props.status : `${props.status} ${props.count}`,
);

const badgeStatus = computed(() => {
  if (tone.value === 'pending') return 'processing';
  return tone.value === 'default' ? 'default' : tone.value;
});
</script>

<template>
  <Tooltip :title="tooltip">
    <Tag
      v-if="tone === 'pending'"
      class="pending-tag"
      :style="{ color: colors.primary, borderColor: colors.primary }"
    >
      {{ text }}
    </Tag>
    <Badge v-else-if="variant === 'dot'" :status="badgeStatus" :text="text" />
    <Tag v-else :color="tone === 'default' ? undefined : tone">{{ text }}</Tag>
  </Tooltip>
</template>

<style scoped>
.pending-tag {
  background: #fff;
}
</style>
