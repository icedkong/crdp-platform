<script lang="ts" setup>
import type { AttachmentNavItem, ReadonlyCategory } from '#/reference/types';

import { colors, sizes } from '@crdp/tokens';

import InlineAttachmentStrip from './inline-attachment-strip.vue';

defineProps<{
  attachments: AttachmentNavItem[];
  canUpload?: boolean;
  categories: ReadonlyCategory[];
}>();

const emit = defineEmits<{
  open: [reportId: string];
  upload: [anchorKey: string];
}>();

function linked(items: AttachmentNavItem[], key: string) {
  return items.filter((item) =>
    item.locations.some((location) => location.anchorKey === key),
  );
}
</script>

<template>
  <section
    v-for="category in categories"
    :id="`crdp-anchor-${category.key}`"
    :key="category.key"
    class="category"
  >
    <h3>
      {{ category.label }}
      <span v-if="linked(attachments, category.key).length > 0">
        📎 {{ linked(attachments, category.key).length }}
      </span>
    </h3>
    <div v-for="field in category.fields" :key="field.label" class="field">
      <span class="label" :style="{ width: `${sizes.detailLabelWidth}px` }">
        {{ field.label }}
      </span>
      <span
        :style="{ color: field.value ? colors.text : colors.textSecondary }"
      >
        {{ field.value || '未填写' }}
      </span>
      <em v-if="field.derived" class="chip">自动计算 · 只读</em>
      <em v-if="field.sensitive" class="chip">敏感 · 默认脱敏</em>
    </div>
    <InlineAttachmentStrip
      :anchor-key="category.key"
      :can-upload="canUpload"
      :items="linked(attachments, category.key)"
      @open="emit('open', $event)"
      @upload="emit('upload', category.key)"
    />
  </section>
</template>

<style scoped>
.category {
  padding: 8px 4px 16px;
}

h3 {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.field {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}

.label {
  flex: none;
  color: #262626;
  text-align: right;
}

.chip {
  padding: 0 6px;
  font-size: 12px;
  font-style: normal;
  color: #8c8c8c;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}
</style>
