<script lang="ts" setup>
import type { NoticeMessage } from '@crdp/api';

import { onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Bell } from '@vben/icons';

import { colors, timings } from '@crdp/tokens';
import { useIntervalFn } from '@vueuse/core';
import {
  Badge,
  Button,
  message,
  Popover,
  Spin,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import {
  fetchNoticeMessages,
  fetchNoticeUnreadCount,
  markAllNoticeMessagesRead,
  markNoticeMessageRead,
} from '#/api/core';

import {
  noticeJumpLocation,
  noticeTimeText,
  shouldMarkRead,
} from './notice-bell-logic';

/**
 * E09-T02 / A-NTF-002、C-NTF-002：顶栏站内信铃铛（notify.md §1：铃铛角标 60 秒轮询，
 * 顶栏视觉未在 components.md 细化，按 Ant Design Vue 默认样式实现——推断，待确认）。
 * 点击消息先标已读再跳到对应任务 / 患者 / 录入页（落点路由未开放时提示）；
 * 患者姓名是后端按接收人字段策略脱敏后的值（隐藏策略下整字段不出现）。
 */
defineOptions({ name: 'NoticeBell' });

const router = useRouter();

const PAGE_SIZE = 20;

const open = ref(false);
const loading = ref(false);
const unread = ref(0);
const messages = ref<NoticeMessage[]>([]);

/** 颜色全部取自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = {
  '--notice-border': colors.borderSecondary,
  '--notice-hover': colors.tableHeader,
  '--notice-jump': colors.primary,
  '--notice-text': colors.text,
  '--notice-text-secondary': colors.textSecondary,
} as const;

async function refreshUnread() {
  try {
    const count = await fetchNoticeUnreadCount();
    unread.value = count.unread;
  } catch {
    // 角标失败不打扰用户（网络抖动由下一次轮询兜底），不弹错误
  }
}

async function loadMessages() {
  loading.value = true;
  try {
    const page = await fetchNoticeMessages({ pageSize: PAGE_SIZE });
    messages.value = page.items;
  } catch {
    message.error('站内信加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

function onOpenChange(value: boolean) {
  open.value = value;
  if (value) {
    loadMessages();
    refreshUnread();
  }
}

/** 点击消息：未读先标已读，再按 target 跳转（验收：点击消息跳到对应任务）。 */
async function onClickMessage(item: NoticeMessage) {
  open.value = false;
  if (shouldMarkRead(item)) {
    item.read = true;
    unread.value = Math.max(0, unread.value - 1);
    try {
      await markNoticeMessageRead(item.id);
    } catch {
      // 40400 等失败回滚本地状态并提示，下次打开重新拉取
      item.read = false;
      unread.value += 1;
      message.error('操作失败，请稍后重试');
    }
  }
  const location = noticeJumpLocation(item, routeExists);
  if (location) {
    router.push(location);
    return;
  }
  if (item.target) {
    message.info('该消息对应的页面尚未开放');
  }
}

async function onMarkAllRead() {
  try {
    const result = await markAllNoticeMessagesRead();
    unread.value = 0;
    for (const item of messages.value) {
      item.read = true;
    }
    message.success(
      result.marked > 0
        ? `已将 ${result.marked} 条站内信标为已读`
        : '没有未读站内信',
    );
  } catch {
    message.error('操作失败，请稍后重试');
  }
}

/** 落点路由是否已注册（404 兜底不算——RECORD 隐藏路由随 E15 菜单下发）。 */
function routeExists(location: {
  path: string;
  query?: Record<string, string>;
}) {
  const resolved = router.resolve(location);
  const last = resolved.matched.at(-1);
  return Boolean(last) && last?.name !== 'FallbackNotFound';
}

const poll = useIntervalFn(refreshUnread, timings.notifyPollMs, {
  immediate: true,
  immediateCallback: true,
});

function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    refreshUnread();
  }
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisibilityChange);
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
      <div class="notice-head">
        <span class="notice-title">站内信</span>
        <Button
          size="small"
          type="link"
          :disabled="unread <= 0"
          @click="onMarkAllRead"
        >
          全部已读
        </Button>
      </div>
      <Spin :spinning="loading">
        <ul v-if="messages.length > 0" class="notice-list">
          <li
            v-for="item in messages"
            :key="item.id"
            class="notice-item"
            :class="{ 'notice-item--unread': !item.read }"
            @click="onClickMessage(item)"
          >
            <div class="notice-item__top">
              <span class="notice-item__title">{{ item.title }}</span>
              <span class="notice-item__time">
                {{ noticeTimeText(item.createdAt) }}
              </span>
            </div>
            <div v-if="item.summary" class="notice-item__summary">
              {{ item.summary }}
            </div>
            <div class="notice-item__meta">
              <Tag v-if="item.patientName" class="notice-item__patient">
                {{ item.patientName }}
              </Tag>
              <span v-if="item.target" class="notice-item__jump">点击进入</span>
            </div>
          </li>
        </ul>
        <div v-else class="notice-empty">暂无站内信</div>
      </Spin>
    </template>
    <span :style="cssVars" class="notice-bell">
      <Tooltip title="站内信">
        <Badge :count="unread" :offset="[-4, 4]" :overflow-count="99">
          <span class="notice-bell__icon">
            <Bell class="notice-bell__svg" />
          </span>
        </Badge>
      </Tooltip>
    </span>
  </Popover>
</template>

<style scoped>
.notice-bell {
  display: inline-flex;
  align-items: center;
  height: 100%;
  margin-right: 8px;
  cursor: pointer;
}

.notice-bell__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: var(--notice-text);
  border-radius: 6px;
}

.notice-bell__icon:hover {
  background-color: var(--notice-hover);
}

.notice-bell__svg {
  width: 16px;
  height: 16px;
}

.notice-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--notice-border);
}

.notice-title {
  font-weight: 600;
}

.notice-list {
  max-height: 360px;
  padding: 0;
  margin: 0;
  overflow-y: auto;
  list-style: none;
}

.notice-item {
  padding: 10px 12px;
  cursor: pointer;
  border-bottom: 1px solid var(--notice-border);
}

.notice-item:last-child {
  border-bottom: none;
}

.notice-item:hover {
  background-color: var(--notice-hover);
}

.notice-item--unread .notice-item__title {
  font-weight: 600;
}

.notice-item__top {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.notice-item__time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--notice-text-secondary);
}

.notice-item__summary {
  display: -webkit-box;
  margin-top: 4px;
  overflow: hidden;
  -webkit-line-clamp: 2;
  font-size: 12px;
  color: var(--notice-text-secondary);
  -webkit-box-orient: vertical;
}

.notice-item__meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
}

.notice-item__patient {
  margin-inline-end: 0;
}

.notice-item__jump {
  font-size: 12px;
  color: var(--notice-jump);
}

.notice-empty {
  padding: 32px 0;
  color: var(--notice-text-secondary);
  text-align: center;
}
</style>
