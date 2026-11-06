<script lang="ts" setup>
import type {
  PlatformAccountView,
  PlatformResponsibleOwner,
  TenantListItem,
} from '@crdp/api';

import { ref, watch } from 'vue';

import {
  Alert,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  List,
  ListItem,
  Modal,
  Select,
  Space,
  Tag,
} from 'ant-design-vue';

import {
  listResponsibleTenants,
  saveResponsibleTenants,
} from '#/api/core/platform-account';
import { listResponsibleOwners, listTenants } from '#/api/core/tenant';

/**
 * 账号负责租户（E20-T01 / PLT-VIEW-006 · pages.md §11 平台账号「负责租户多选」）：
 * 为账号多选负责的租户（整体替换）；变更即时生效——该账号进行中的视角会话立即结束。
 * 支持按租户反查负责人（platform-spec §8「平台账号管理 · 负责租户」）。
 * 权限：维护需 platform:view:grant:edit（按钮在列表页按权限显隐），反查只需登录查看。
 */
const props = defineProps<{
  account: null | PlatformAccountView;
  open: boolean;
}>();

const emit = defineEmits<{
  (e: 'done'): void;
  (e: 'update:open', open: boolean): void;
}>();

const tenantOptions = ref<{ label: string; value: number }[]>([]);
const selected = ref<number[]>([]);
const loading = ref(false);
const submitting = ref(false);
const loadError = ref('');
const submitError = ref('');

// 按租户反查负责人
const ownerTenantId = ref<number | undefined>(undefined);
const owners = ref<PlatformResponsibleOwner[]>([]);
const ownersLoading = ref(false);

async function loadOptions() {
  try {
    const page = await listTenants({ pageNo: 1, pageSize: 100 });
    tenantOptions.value = page.items.map((tenant: TenantListItem) => ({
      label: `${tenant.name}（${tenant.code}）`,
      value: tenant.id,
    }));
  } catch (error) {
    tenantOptions.value = [];
    loadError.value = error instanceof Error ? error.message : String(error);
  }
}

async function loadSelected(accountId: number) {
  const tenants = await listResponsibleTenants(accountId);
  selected.value = tenants.map((tenant) => tenant.tenantId);
}

async function lookupOwners() {
  if (!ownerTenantId.value) {
    owners.value = [];
    return;
  }
  ownersLoading.value = true;
  try {
    owners.value = await listResponsibleOwners(ownerTenantId.value);
  } catch (error) {
    owners.value = [];
    loadError.value = error instanceof Error ? error.message : String(error);
  } finally {
    ownersLoading.value = false;
  }
}

async function submit() {
  if (!props.account) return;
  submitting.value = true;
  submitError.value = '';
  try {
    await saveResponsibleTenants(props.account.id, selected.value);
    emit('update:open', false);
    emit('done');
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}

watch(
  () => props.open,
  async (open) => {
    if (!open || !props.account) return;
    loadError.value = '';
    submitError.value = '';
    owners.value = [];
    ownerTenantId.value = undefined;
    loading.value = true;
    try {
      await Promise.all([loadOptions(), loadSelected(props.account.id)]);
    } catch (error) {
      loadError.value = error instanceof Error ? error.message : String(error);
    } finally {
      loading.value = false;
    }
  },
);
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    :title="`负责租户：${account?.username ?? ''}`"
    :width="620"
    ok-text="保存"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Alert
      v-if="loadError"
      :message="`加载失败：${loadError}`"
      show-icon
      type="error"
      class="mb-2"
    />
    <Form layout="vertical" class="crdp-form">
      <FormItem label="负责的租户（多选）">
        <Select
          v-model:value="selected"
          :options="tenantOptions"
          :loading="loading"
          allow-clear
          mode="multiple"
          placeholder="选择该账号负责的租户"
          show-search
          option-filter-prop="label"
        />
        <p class="hint">
          租户范围为「负责的租户」的角色只能进入这些租户；负责关系变更即时生效，进行中的视角会话立即结束。
        </p>
      </FormItem>
    </Form>

    <Descriptions :column="1" bordered size="small" class="mt-2">
      <DescriptionsItem label="按租户反查负责人">
        <Select
          v-model:value="ownerTenantId"
          :options="tenantOptions"
          allow-clear
          placeholder="选择租户查看负责人"
          show-search
          option-filter-prop="label"
          style="width: 320px"
          @change="lookupOwners"
        />
        <List
          v-if="owners.length > 0"
          :loading="ownersLoading"
          size="small"
          :data-source="owners"
        >
          <template #renderItem="{ item }">
            <ListItem>
              <Space>
                <span>{{ item.displayName }}</span>
                <span class="code">{{ item.username }}</span>
                <Tag v-if="item.status === 'DISABLED'" color="default">
                  已停用
                </Tag>
              </Space>
            </ListItem>
          </template>
        </List>
        <p v-else-if="ownerTenantId" class="hint">该租户暂无负责人。</p>
      </DescriptionsItem>
    </Descriptions>

    <Alert
      v-if="submitError"
      :message="`保存失败：${submitError}`"
      show-icon
      type="error"
      class="mt-2"
    />
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}

.hint {
  margin-bottom: 4px;
  font-size: 13px;
  color: #86909c;
}
</style>
