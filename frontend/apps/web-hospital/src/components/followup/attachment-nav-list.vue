<script lang="ts" setup>
import type {
  AttachmentGroup,
  AttachmentNavItem,
  ReportLocation,
} from '#/reference/types';

import { StatusTag } from '@crdp/status-tag';
import { colors } from '@crdp/tokens';
import { Button, Empty, Tag } from 'ant-design-vue';

defineProps<{
  canUpload?: boolean;
  emptyText: string;
  groups: AttachmentGroup[];
}>();

const emit = defineEmits<{
  locate: [location: ReportLocation];
  open: [reportId: string];
  upload: [];
}>();

function openItem(item: AttachmentNavItem) {
  const location = item.locations[0];
  if (location) emit('locate', location);
  else emit('open', item.reportId);
}

function chip(location: ReportLocation) {
  return location.anchorLabel || location.recordLabel || '整条记录';
}
</script>

<template>
  <Empty v-if="groups.length === 0" :description="emptyText">
    <Button v-if="canUpload" type="primary" @click="emit('upload')">
      上传
    </Button>
  </Empty>
  <div v-for="group in groups" :key="group.key" class="group">
    <div
      class="group-title"
      :style="{ color: group.warning ? colors.warning : undefined }"
    >
      <span>{{ group.title }}</span>
      <Button
        v-if="group.warning && canUpload"
        size="small"
        type="link"
        @click="emit('upload')"
      >
        指定位置
      </Button>
    </div>
    <template v-if="group.children?.length">
      <div v-for="child in group.children" :key="child.key" class="child">
        <div class="group-title">{{ child.title }}</div>
        <div v-for="item in child.items" :key="item.reportId" class="item">
          <button class="name" type="button" @click="openItem(item)">
            <img v-if="item.thumbnailUrl" alt="" :src="item.thumbnailUrl" />
            <span>{{ item.name }}</span>
            <StatusTag
              v-if="item.digitizeStatus"
              :status="item.digitizeStatus"
            />
          </button>
          <div class="locs">
            <Tag
              v-for="(location, index) in item.locations"
              :key="`${item.reportId}-${index}`"
              class="loc"
              @click="emit('locate', location)"
            >
              {{ chip(location) }}
            </Tag>
          </div>
        </div>
      </div>
    </template>
    <div v-for="item in group.items" :key="item.reportId" class="item">
      <button class="name" type="button" @click="openItem(item)">
        <img v-if="item.thumbnailUrl" alt="" :src="item.thumbnailUrl" />
        <span>{{ item.name }}</span>
        <StatusTag v-if="item.digitizeStatus" :status="item.digitizeStatus" />
      </button>
      <div class="locs">
        <Tag
          v-for="(location, index) in item.locations"
          :key="`${item.reportId}-${index}`"
          class="loc"
          @click="emit('locate', location)"
        >
          {{ chip(location) }}
        </Tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.group {
  margin-bottom: 12px;
}

.group-title {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}

.child {
  margin-left: 8px;
}

.item {
  margin: 6px 0 6px 8px;
}

.name {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 0;
  color: #262626;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.name img {
  width: 28px;
  height: 28px;
  object-fit: cover;
  border-radius: 4px;
}

.loc {
  margin-top: 4px;
  cursor: pointer;
}
</style>
