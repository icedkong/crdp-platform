<script lang="ts" setup>
import type { AttachmentNavItem } from '#/reference/types';

import { computed } from 'vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

const props = defineProps<{
  anchorKey?: string;
  canUpload?: boolean;
  items: Array<
    Pick<
      AttachmentNavItem,
      | 'fileCount'
      | 'name'
      | 'reportDate'
      | 'reportId'
      | 'thumbnailUrl'
      | 'typeName'
    >
  >;
}>();

const emit = defineEmits<{
  open: [reportId: string];
  upload: [];
}>();

const { isMobile } = useBreakpoint();
const visible = computed(() => props.items.slice(0, 8));
const extra = computed(() =>
  Math.max(props.items.length - visible.value.length, 0),
);
const size = computed(() => (isMobile.value ? 56 : 64));
</script>

<template>
  <div v-if="items.length > 0 || canUpload" class="strip">
    <button
      v-for="item in visible"
      :key="item.reportId"
      class="thumb"
      :style="{ width: `${size}px`, height: `${size}px` }"
      :title="`${item.typeName} · ${item.name} · ${item.reportDate}`"
      type="button"
      @click="emit('open', item.reportId)"
    >
      <img
        v-if="item.thumbnailUrl"
        alt=""
        loading="lazy"
        :src="item.thumbnailUrl"
      />
      <span v-else>{{ item.typeName }}</span>
    </button>
    <span v-if="extra > 0" class="more">+{{ extra }}</span>
    <button v-if="canUpload" class="add" type="button" @click="emit('upload')">
      上传附件
    </button>
  </div>
</template>

<style scoped>
.strip {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
  overflow-x: auto;
}

.thumb,
.add {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  font-size: 12px;
  color: #8c8c8c;
  cursor: pointer;
  background: #fafafa;
  border: 1px solid #e9e9e9;
  border-radius: 4px;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.add {
  width: 72px;
  height: 64px;
  border-style: dashed;
}
</style>
