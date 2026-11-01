<script lang="ts" setup>
import type { PlatformRoleView } from '@crdp/api';

import { onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { StatusTag } from '@crdp/status-tag';
import { breakpoints } from '@crdp/tokens';
import { Alert, Button, Card, Result, Space, Table, Tag } from 'ant-design-vue';

import { listRoles } from '#/api/core/role';
import { usePermission } from '#/reference/use-permission';

import EditDialog from './edit-dialog.vue';
import HoldersDialog from './holders-dialog.vue';
import {
  platformRoleStatusText,
  roleTimeText,
  roleViewSummaryText,
} from './logic';

defineOptions({ name: 'PlatformRole' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:role:edit');

const isMobile = ref(false);
const loading = ref(false);
const items = ref<PlatformRoleView[]>([]);
const errorMessage = ref('');

// 编辑对话框（新建自定义角色 / 维护既有角色）
const editOpen = ref(false);
const editTarget = ref<null | PlatformRoleView>(null);

// 持有人（platform-spec §1 主要页面「查看持有人」）
const holdersTarget = ref<null | PlatformRoleView>(null);

const columns = [
  { dataIndex: 'code', title: '编码', width: 170 },
  { dataIndex: 'name', title: '名称', width: 130 },
  { dataIndex: 'builtIn', title: '预置', width: 72 },
  { dataIndex: 'view', title: '租户视角权限' },
  { dataIndex: 'holderCount', title: '持有人', width: 88 },
  { dataIndex: 'status', title: '状态', width: 96 },
  { dataIndex: 'updatedAt', title: '更新时间', width: 150 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 130 },
];

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    items.value = await listRoles();
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editTarget.value = null;
  editOpen.value = true;
}

function openEdit(row: PlatformRoleView) {
  editTarget.value = row;
  editOpen.value = true;
}

function onEditDone() {
  void load();
}

onMounted(() => {
  read();
  window.addEventListener('resize', read);
  void load();
});
onUnmounted(() => window.removeEventListener('resize', read));
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="平台角色"
    description="平台预置角色（平台管理员、实施人员、运维人员、客服支持）与自定义角色：授权平台端菜单、配置租户视角权限；预置角色不可删除，权限可调整。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <span class="hint">
          角色停用或授权变化后，持有人的在线会话在下一次请求时即按新权限生效。
        </span>
        <Button
          v-if="canEdit"
          type="primary"
          data-testid="role-create"
          @click="openCreate"
        >
          新建角色
        </Button>
      </div>

      <Alert
        v-if="errorMessage"
        :message="`加载失败：${errorMessage}`"
        show-icon
        type="error"
        class="mb-4"
      />

      <Table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        :pagination="false"
        :row-key="(row: PlatformRoleView) => row.id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'code'" class="code">
            {{ record.code }}
          </span>
          <template v-else-if="column.dataIndex === 'name'">
            {{ record.name }}
          </template>
          <span v-else-if="column.dataIndex === 'builtIn'">
            {{ record.builtIn ? '是' : '否' }}
          </span>
          <span v-else-if="column.dataIndex === 'view'" class="view-summary">
            {{ roleViewSummaryText(record as PlatformRoleView) }}
          </span>
          <span v-else-if="column.dataIndex === 'holderCount'">
            {{ record.holderCount ?? 0 }}
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="platformRoleStatusText(record.status)"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'updatedAt'">
            {{ roleTimeText(record.updatedAt) }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button
              size="small"
              type="link"
              :data-testid="`role-holders-${record.code}`"
              @click="holdersTarget = record as PlatformRoleView"
            >
              持有人
            </Button>
            <Button
              v-if="canEdit"
              size="small"
              type="link"
              :data-testid="`role-edit-${record.code}`"
              @click="openEdit(record as PlatformRoleView)"
            >
              维护
            </Button>
          </Space>
        </template>
      </Table>

      <div class="legend">
        <Tag>预置角色不可删除</Tag>
        <Tag>权限可由平台管理员调整</Tag>
        <Tag>「指定租户」清单由租户视角任务（E20）提供</Tag>
      </div>
    </Card>

    <EditDialog v-model:open="editOpen" :role="editTarget" @done="onEditDone" />
    <HoldersDialog
      :open="holdersTarget !== null"
      :role="holdersTarget"
      @close="holdersTarget = null"
    />
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.hint {
  font-size: 13px;
  color: #86909c;
}

.code {
  font-family: monospace;
}

.view-summary {
  font-size: 13px;
}

.legend {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
