<script lang="ts" setup>
import type { LoginLogView } from '@crdp/api';

import { onMounted, ref } from 'vue';

import { loginLogResultText } from '@crdp/api';
import { colors } from '@crdp/tokens';
import { Alert, Button, Empty, Spin, Table, Tag } from 'ant-design-vue';

import { getLoginLogs } from '#/api/core/profile';

defineOptions({ name: 'ProfileLoginLogs' });

/**
 * E06-T11 / PLT-ORG-011：本人登录记录（最近 20 条：结果与时间；后端不含 IP）。
 * 只展示本人记录，失败原因代码不对外展示（防探测口径与后端一致）。
 */
const loading = ref(false);
const error = ref('');
const logs = ref<LoginLogView[]>([]);

const columns = [
  { dataIndex: 'createdAt', title: '时间', width: 220 },
  { dataIndex: 'result', title: '结果', width: 120 },
];

async function load() {
  loading.value = true;
  error.value = '';
  try {
    logs.value = await getLoginLogs();
  } catch (error_) {
    error.value = error_ instanceof Error ? error_.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="logs" data-testid="profile-login-logs">
    <Alert v-if="error" class="mb-3" :message="error" show-icon type="error">
      <template #action>
        <Button size="small" type="link" @click="load">重试</Button>
      </template>
    </Alert>
    <Spin :spinning="loading">
      <Empty
        v-if="!loading && logs.length === 0"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无登录记录"
      />
      <Table
        v-else
        :columns="columns"
        :data-source="logs"
        :pagination="false"
        row-key="createdAt"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'result'">
            <Tag :color="record.success ? 'success' : 'error'">
              {{ loginLogResultText(record) }}
            </Tag>
          </template>
        </template>
      </Table>
    </Spin>
    <p class="hint" :style="{ color: colors.textSecondary }">
      只保留最近 20 条；时间为北京时间。
    </p>
  </div>
</template>

<style scoped>
.logs {
  max-width: 640px;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
}
</style>
