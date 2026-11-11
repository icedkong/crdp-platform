<script lang="ts" setup>
import type { HospitalAnnouncement } from '@crdp/api';

import { onUnmounted, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { recentAnnouncementCount } from '@crdp/api';
import { colors, timings } from '@crdp/tokens';
import { useIntervalFn } from '@vueuse/core';
import {
  Badge,
  Empty,
  message,
  Modal,
  Popover,
  Spin,
  Tooltip,
} from 'ant-design-vue';

import { fetchAnnouncements } from '#/api/core/announcement';

import { noticeTimeText } from '../notify/notice-bell-logic';

/**
 * E05-T11 / PLT-CFG-009：顶栏系统公告铃铛——平台发布的公告按范围（全部 / 指定租户）
 * 下发到本院，登录即可看（同个人中心一类，不占权限码）。角标 = 近 7 天发布的公告条数
 * （公告无已读跟踪，只提示「有新公告」）；点击条目弹出正文全文。
 * 顶栏视觉未在 components.md 细化，按 Ant Design Vue 默认样式与站内信铃铛同款实现——推断，待确认。
 */
defineOptions({ name: 'AnnouncementBell' });

/** 角标窗口：近 7 天发布的公告计入角标（tokens 无对应时长，语义常量放此处）。 */
const RECENT_WINDOW_MS = 7 * 24 * 60 * 60 * 1000;

const open = ref(false);
const loading = ref(false);
const announcements = ref<HospitalAnnouncement[]>([]);
const detailOpen = ref(false);
const detail = ref<HospitalAnnouncement | null>(null);

/** 颜色全部取自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = {
  '--announcement-border': colors.borderSecondary,
  '--announcement-hover': colors.tableHeader,
  '--announcement-icon': colors.primary,
  '--announcement-text': colors.text,
  '--announcement-text-secondary': colors.textSecondary,
} as const;

async function load() {
  loading.value = true;
  try {
    announcements.value = await fetchAnnouncements();
  } catch {
    message.error('公告加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

function onOpenChange(value: boolean) {
  open.value = value;
  if (value) {
    void load();
  }
}

function openDetail(item: HospitalAnnouncement) {
  detail.value = item;
  detailOpen.value = true;
}

const poll = useIntervalFn(load, timings.notifyPollMs, {
  immediate: true,
  immediateCallback: true,
});

onUnmounted(() => {
  poll.pause();
});
</script>

<template>
  <Popover
    :open="open"
    trigger="click"
    placement="bottomRight"
    :overlay-style="{ width: '360px' }"
    @open-change="onOpenChange"
  >
    <template #content>
      <div class="announcement-head">
        <span class="announcement-title">系统公告</span>
        <span class="announcement-source">平台发布</span>
      </div>
      <Spin :spinning="loading">
        <ul v-if="announcements.length > 0" class="announcement-list">
          <li
            v-for="item in announcements"
            :key="item.id"
            class="announcement-item"
            @click="openDetail(item)"
          >
            <div class="announcement-item__top">
              <span class="announcement-item__title">{{ item.title }}</span>
              <span class="announcement-item__time">
                {{ noticeTimeText(item.publishedAt) }}
              </span>
            </div>
            <div class="announcement-item__summary">
              {{ item.content }}
            </div>
            <div class="announcement-item__meta">
              <span class="announcement-item__jump">查看全文</span>
            </div>
          </li>
        </ul>
        <div v-else class="announcement-empty">暂无公告</div>
      </Spin>
    </template>
    <span :style="cssVars" class="announcement-bell">
      <Tooltip title="系统公告">
        <Badge
          :count="
            recentAnnouncementCount(announcements, Date.now(), RECENT_WINDOW_MS)
          "
          :offset="[-4, 4]"
          :overflow-count="99"
        >
          <span class="announcement-bell__icon">
            <IconifyIcon
              class="announcement-bell__svg"
              icon="ant-design:notification-outlined"
            />
          </span>
        </Badge>
      </Tooltip>
    </span>
  </Popover>

  <Modal
    v-model:open="detailOpen"
    :title="detail?.title ?? '系统公告'"
    :footer="null"
    :width="560"
  >
    <template v-if="detail">
      <p class="announcement-detail__meta">
        平台发布 · {{ noticeTimeText(detail.publishedAt) }}
      </p>
      <p class="announcement-detail__content">{{ detail.content }}</p>
    </template>
    <Empty
      v-else
      :image="Empty.PRESENTED_IMAGE_SIMPLE"
      description="暂无公告"
    />
  </Modal>
</template>

<style scoped>
.announcement-bell {
  display: inline-flex;
  align-items: center;
  height: 100%;
  margin-right: 8px;
  cursor: pointer;
}

.announcement-bell__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: var(--announcement-icon);
  border-radius: 6px;
}

.announcement-bell__icon:hover {
  background-color: var(--announcement-hover);
}

.announcement-bell__svg {
  width: 16px;
  height: 16px;
}

.announcement-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--announcement-border);
}

.announcement-title {
  font-weight: 600;
  color: var(--announcement-text);
}

.announcement-source {
  font-size: 12px;
  color: var(--announcement-text-secondary);
}

.announcement-list {
  max-height: 360px;
  padding: 0;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.announcement-item {
  padding: 10px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--announcement-border);
}

.announcement-item:last-child {
  border-bottom: none;
}

.announcement-item:hover {
  background-color: var(--announcement-hover);
}

.announcement-item__top {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.announcement-item__title {
  font-weight: 600;
  color: var(--announcement-text);
}

.announcement-item__time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--announcement-text-secondary);
}

.announcement-item__summary {
  display: -webkit-box;
  margin-top: 4px;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  color: var(--announcement-text-secondary);
  -webkit-box-orient: vertical;
}

.announcement-item__meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
}

.announcement-item__jump {
  font-size: 12px;
  color: var(--announcement-icon);
}

.announcement-empty {
  padding: 32px 0;
  color: var(--announcement-text-secondary);
  text-align: center;
}

.announcement-detail__meta {
  margin: 0 0 8px;
  color: var(--announcement-text-secondary);
}

.announcement-detail__content {
  margin: 0;
  white-space: pre-wrap;
}
</style>
