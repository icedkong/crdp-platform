<script lang="ts" setup>
import type {
  AttachmentGroup,
  AttachmentNavItem,
  ReportLocation,
} from '#/reference/types';

import { StatusTag } from '@crdp/status-tag';
import { colors } from '@crdp/tokens';
import { Button, Dropdown, Empty, Menu, MenuItem, Tag } from 'ant-design-vue';

/**
 * E16-T14 / A-PAT-020、C-PAT-017（components.md §5 AttachmentNavList）：左栏导航
 * 列表体——类型分组带数量、每份报告单只出现一次且下方列出全部位置标签、缩略图与
 * 电子化状态随行；「未指定位置」警示组每份带「指定位置」入口（有上传权限才显示，
 * emit edit-location(reportId)，E16-T13 修改位置接口补指定）；条目 ⋮ 菜单：
 * 预览（open）、修改位置（edit-location，followup:scan:upload——逐文件下载 /
 * 删除需报告单文件清单接口，随后续任务开放）。
 */
defineProps<{
  canUpload?: boolean;
  emptyText: string;
  groups: AttachmentGroup[];
}>();

const emit = defineEmits<{
  editLocation: [reportId: string];
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
    </div>
    <template v-if="group.children?.length">
      <div v-for="child in group.children" :key="child.key" class="child">
        <div class="group-title">{{ child.title }}</div>
        <div v-for="item in child.items" :key="item.reportId" class="item">
          <div class="row">
            <button class="name" type="button" @click="openItem(item)">
              <img
                v-if="item.thumbnailUrl"
                alt=""
                loading="lazy"
                :src="item.thumbnailUrl"
              />
              <span>{{ item.name }}</span>
              <StatusTag
                v-if="item.digitizeStatus"
                :status="item.digitizeStatus"
              />
            </button>
            <Dropdown trigger="click">
              <Button class="more" size="small" type="text">⋮</Button>
              <template #overlay>
                <Menu>
                  <MenuItem key="preview" @click="emit('open', item.reportId)">
                    预览
                  </MenuItem>
                  <MenuItem
                    v-if="canUpload"
                    key="edit-location"
                    @click="emit('editLocation', item.reportId)"
                  >
                    修改位置
                  </MenuItem>
                </Menu>
              </template>
            </Dropdown>
          </div>
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
      <div class="row">
        <button class="name" type="button" @click="openItem(item)">
          <img
            v-if="item.thumbnailUrl"
            alt=""
            loading="lazy"
            :src="item.thumbnailUrl"
          />
          <span>{{ item.name }}</span>
          <StatusTag v-if="item.digitizeStatus" :status="item.digitizeStatus" />
        </button>
        <!-- E16-T14 ⋮ 菜单：预览 / 修改位置（下载与逐文件删除需报告单文件清单接口） -->
        <Dropdown trigger="click">
          <Button class="more" size="small" type="text">⋮</Button>
          <template #overlay>
            <Menu>
              <MenuItem key="preview" @click="emit('open', item.reportId)">
                预览
              </MenuItem>
              <MenuItem
                v-if="canUpload"
                key="edit-location"
                @click="emit('editLocation', item.reportId)"
              >
                修改位置
              </MenuItem>
            </Menu>
          </template>
        </Dropdown>
      </div>
      <div class="locs">
        <Tag
          v-for="(location, index) in item.locations"
          :key="`${item.reportId}-${index}`"
          class="loc"
          @click="emit('locate', location)"
        >
          {{ chip(location) }}
        </Tag>
        <!-- E16-T14 未指定位置组：经 E16-T13 add 补指定（A-ATT-020 / C-SCAN-020） -->
        <Button
          v-if="group.warning && canUpload"
          class="assign"
          size="small"
          type="link"
          @click="emit('editLocation', item.reportId)"
        >
          指定位置
        </Button>
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

.row {
  display: flex;
  gap: 4px;
  align-items: center;
}

.more {
  flex: none;
  color: #8c8c8c;
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

.locs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
  margin-top: 4px;
}

.assign {
  padding: 0;
}
</style>
