<script lang="ts" setup>
import type { PlatformRoleHolder, PlatformRoleView } from '@crdp/api';

import { ref, watch } from 'vue';

import { platformAccountStatusText } from '@crdp/api';
import { Alert, Modal, Table } from 'ant-design-vue';

import { listRoleHolders } from '#/api/core/role';

/** 查看持有人（platform-spec §1 主要页面；只读）。 */
const props = defineProps<{
  open: boolean;
  role: null | PlatformRoleView;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const loading = ref(false);
const holders = ref<PlatformRoleHolder[]>([]);
const errorMessage = ref('');

watch(
  () => props.open,
  async (open) => {
    if (!open || !props.role) return;
    loading.value = true;
    errorMessage.value = '';
    holders.value = [];
    try {
      holders.value = await listRoleHolders(props.role.id);
    } catch (error) {
      errorMessage.value =
        error instanceof Error ? error.message : String(error);
    } finally {
      loading.value = false;
    }
  },
);

const columns = [
  { dataIndex: 'username', title: '账号' },
  { dataIndex: 'displayName', title: '姓名' },
  { dataIndex: 'accountStatus', title: '账号状态', width: 110 },
];
</script>

<template>
  <Modal
    :open="open"
    :footer="null"
    :title="`持有人：${role?.name ?? ''}`"
    :width="520"
    @cancel="emit('close')"
  >
    <Alert
      v-if="errorMessage"
      :message="`加载失败：${errorMessage}`"
      show-icon
      type="error"
      class="mb-2"
    />
    <Table
      :columns="columns"
      :data-source="holders"
      :loading="loading"
      :locale="{
        emptyText: '该角色暂无持有人',
      }"
      :pagination="false"
      row-key="accountId"
      size="middle"
    >
      <template #bodyCell="{ column, record }">
        <span v-if="column.dataIndex === 'username'" class="code">
          {{ record.username }}
        </span>
        <span v-else-if="column.dataIndex === 'accountStatus'">
          {{ platformAccountStatusText(record.accountStatus) }}
        </span>
      </template>
    </Table>
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}
</style>
