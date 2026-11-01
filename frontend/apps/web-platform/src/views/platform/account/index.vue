<script lang="ts" setup>
import type { PlatformAccountView } from '@crdp/api';

import { onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { StatusTag } from '@crdp/status-tag';
import { breakpoints } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  message,
  Modal,
  Result,
  Space,
  Table,
} from 'ant-design-vue';

import {
  listPlatformAccounts,
  resetPlatformAccountPassword,
  unlockPlatformAccount,
} from '#/api/core/platform-account';
import { usePermission } from '#/reference/use-permission';

import {
  accountTimeText,
  phoneText,
  platformAccountStatusText,
  resetResultLines,
  roleNamesText,
} from './account-logic';
import EditDialog from './edit-dialog.vue';

defineOptions({ name: 'PlatformAccount' });

const router = useRouter();
const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:account:edit');
const canReset = hasPermission('platform:account:reset');

const isMobile = ref(false);
const loading = ref(false);
const items = ref<PlatformAccountView[]>([]);
const errorMessage = ref('');

const editOpen = ref(false);
const editTarget = ref<null | PlatformAccountView>(null);

// 重置密码：二次确认 → 一次性临时密码展示（确认已保存才关闭）
const resetConfirm = ref<null | PlatformAccountView>(null);
const resetResult = ref<null | { lines: string[]; temporary: string }>(null);
const resetting = ref(false);

const columns = [
  { dataIndex: 'username', title: '账号' },
  { dataIndex: 'displayName', title: '姓名' },
  { dataIndex: 'phoneMasked', title: '手机号', width: 140 },
  { dataIndex: 'roleNames', title: '平台角色' },
  { dataIndex: 'status', title: '状态', width: 96 },
  { dataIndex: 'createdAt', title: '创建时间', width: 150 },
  {
    dataIndex: 'action',
    fixed: 'right' as const,
    title: '操作',
    width: canEdit ? 200 : 120,
  },
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
    items.value = await listPlatformAccounts();
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

function openEdit(row: PlatformAccountView) {
  editTarget.value = row;
  editOpen.value = true;
}

async function confirmReset() {
  if (!resetConfirm.value) return;
  resetting.value = true;
  try {
    const result = await resetPlatformAccountPassword(resetConfirm.value.id);
    resetResult.value = {
      lines: resetResultLines(result.temporaryPassword),
      temporary: result.temporaryPassword,
    };
  } catch (error) {
    message.error(
      `重置失败：${error instanceof Error ? error.message : String(error)}`,
    );
  } finally {
    resetting.value = false;
  }
}

function closeReset() {
  resetConfirm.value = null;
  resetResult.value = null;
}

async function unlock(row: PlatformAccountView) {
  try {
    await unlockPlatformAccount(row.id);
    message.success(`已解除 ${row.username} 的登录锁定`);
  } catch (error) {
    message.error(
      `解锁失败：${error instanceof Error ? error.message : String(error)}`,
    );
  }
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
    title="平台账号"
    description="平台账号存平台库、只能授予平台角色；强制绑定手机号（双因素认证）。医院账号在各自租户库维护，两个登录入口分立。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <span class="hint">
          平台管理员至少保留 2
          个启用账号；停用账号的在线会话在下一次请求时失效。
        </span>
        <Button
          v-if="canEdit"
          type="primary"
          data-testid="account-create"
          @click="openCreate"
        >
          新建账号
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
        :row-key="(row: PlatformAccountView) => row.id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <span v-if="column.dataIndex === 'username'" class="code">
            {{ record.username }}
          </span>
          <span v-else-if="column.dataIndex === 'phoneMasked'">
            {{ phoneText(record as PlatformAccountView) }}
          </span>
          <span v-else-if="column.dataIndex === 'roleNames'">
            {{ roleNamesText(record as PlatformAccountView) }}
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="platformAccountStatusText(record.status)"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'createdAt'">
            {{ accountTimeText(record.createdAt) }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button
              v-if="canEdit"
              size="small"
              type="link"
              :data-testid="`account-edit-${record.username}`"
              @click="openEdit(record as PlatformAccountView)"
            >
              维护
            </Button>
            <Button
              v-if="canReset"
              size="small"
              type="link"
              :data-testid="`account-reset-${record.username}`"
              @click="resetConfirm = record as PlatformAccountView"
            >
              重置密码
            </Button>
            <Button
              v-if="canReset"
              size="small"
              type="link"
              @click="unlock(record as PlatformAccountView)"
            >
              解锁
            </Button>
          </Space>
        </template>
      </Table>
    </Card>

    <EditDialog v-model:open="editOpen" :account="editTarget" @done="load" />

    <Modal
      :open="resetConfirm !== null && resetResult === null"
      :confirm-loading="resetting"
      :title="`重置密码：${resetConfirm?.username ?? ''}`"
      ok-text="确认重置"
      cancel-text="取消"
      ok-type="danger"
      @ok="confirmReset"
      @cancel="resetConfirm = null"
    >
      <p>将为该平台账号生成一次性临时密码：</p>
      <ul class="reset-notes">
        <li>该账号的全部在线会话立即失效；</li>
        <li>登录锁定一并解除；</li>
        <li>下次登录用临时密码，登录后强制修改密码。</li>
      </ul>
    </Modal>

    <Modal
      :open="resetResult !== null"
      title="临时密码（只显示一次）"
      :width="480"
      :footer="null"
      @cancel="closeReset"
    >
      <ul class="reset-notes" data-testid="reset-result">
        <li v-for="line in resetResult?.lines ?? []" :key="line">
          {{ line }}
        </li>
      </ul>
      <div class="reset-actions">
        <Button type="primary" @click="closeReset">我已保存</Button>
      </div>
    </Modal>
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

.reset-notes {
  padding-left: 18px;
}

.reset-actions {
  margin-top: 16px;
  text-align: right;
}
</style>
