<script lang="ts" setup>
import type { OnlineSessionView } from '@crdp/api';

import { onMounted, onUnmounted, ref } from 'vue';

import { colors } from '@crdp/tokens';
import { Alert, Button, Empty, Modal, Spin, Table, Tag } from 'ant-design-vue';

import { getMySessionsApi, revokeMySessionApi } from '#/api';

defineOptions({ name: 'ProfileSessions' });

/**
 * E06-T11 / PLT-ORG-011：本人在线会话（接口 E04-T06 /auth/sessions，只返回会话号不返回令牌）。
 * 可下线自己的其他会话；被下线令牌下次请求即 40100。
 */
const loading = ref(false);
const error = ref('');
const sessions = ref<OnlineSessionView[]>([]);
const revoking = ref('');
let refreshTimer: ReturnType<typeof setInterval> | undefined;

const columns = [
  { dataIndex: 'sessionId', title: '会话号', width: 200 },
  { dataIndex: 'ip', title: '登录 IP', width: 160 },
  { dataIndex: 'issuedAt', title: '登录时间', width: 220 },
  { dataIndex: 'lastActiveAt', title: '最后活跃', width: 220 },
  { dataIndex: 'actions', title: '操作', width: 120 },
];

async function load() {
  loading.value = true;
  error.value = '';
  try {
    sessions.value = await getMySessionsApi();
  } catch (error_) {
    error.value = error_ instanceof Error ? error_.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function confirmRevoke(record: OnlineSessionView) {
  Modal.confirm({
    title: '下线该会话？',
    content: `会话 ${record.sessionId}（登录 IP ${record.ip ?? '未知'}）将被下线，期间如仍在使用需重新登录。`,
    okText: '下线',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      revoking.value = record.sessionId;
      try {
        await revokeMySessionApi(record.sessionId);
        await load();
      } finally {
        revoking.value = '';
      }
    },
  });
}

onMounted(() => {
  load();
  // 30 秒轻量刷新活跃状态（在线会话是实时视图）
  refreshTimer = setInterval(load, 30_000);
});
onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer);
  }
});
</script>

<template>
  <div class="sessions" data-testid="profile-sessions">
    <Alert v-if="error" class="mb-3" :message="error" show-icon type="error">
      <template #action>
        <Button size="small" type="link" @click="load">重试</Button>
      </template>
    </Alert>
    <Spin :spinning="loading">
      <Empty
        v-if="!loading && sessions.length === 0"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无在线会话"
      />
      <Table
        v-else
        :columns="columns"
        :data-source="sessions"
        :pagination="false"
        row-key="sessionId"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'sessionId'">
            <span class="mono">{{ record.sessionId }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'actions'">
            <Tag v-if="record.current" color="processing">当前会话</Tag>
            <Button
              v-else
              danger
              size="small"
              type="link"
              :loading="revoking === record.sessionId"
              @click="confirmRevoke(record)"
            >
              下线
            </Button>
          </template>
        </template>
      </Table>
    </Spin>
    <p class="hint" :style="{ color: colors.textSecondary }">
      会话号由令牌单向推导，界面不展示令牌；时间为北京时间。
    </p>
  </div>
</template>

<style scoped>
.sessions {
  max-width: 860px;
}

.mono {
  font-family: monospace;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
}
</style>
