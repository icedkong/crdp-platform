<script lang="ts" setup>
import type { TenantQuotaView } from '@crdp/api';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Form,
  FormItem,
  InputNumber,
  Modal,
  Switch,
} from 'ant-design-vue';

import { tenantQuota, updateTenantQuota } from '#/api/core/tenant';
import { usePermission } from '#/reference/use-permission';

import {
  EMPTY_QUOTA_FORM,
  quotaAlerts,
  storageUsageText,
  toQuotaFormModel,
  toQuotaRequest,
  userUsageText,
  validateQuotaForm,
} from './quota';

/**
 * 配额页签（E05-T09 / PLT-TEN-014）：上限与实时用量展示、80% 预警与满额横幅、
 * 维护弹窗（用户数整数 / 存储 GB，关掉开关 = 不限）。上限不强制高于当前用量：调低只挡新增。
 */
defineOptions({ name: 'PlatformTenantQuotaTab' });

const props = defineProps<{ tenantId: number }>();

const { hasPermission } = usePermission();
const canEdit = hasPermission('platform:tenant:edit');

const quota = ref<null | TenantQuotaView>(null);
const loadError = ref('');
const editOpen = ref(false);
const saving = ref(false);
const saveError = ref('');
const form = reactive({ ...EMPTY_QUOTA_FORM });
const errors = computed(() => validateQuotaForm(form));
const alerts = computed(() => (quota.value ? quotaAlerts(quota.value) : []));

async function load() {
  loadError.value = '';
  try {
    quota.value = await tenantQuota(props.tenantId);
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : String(error);
  }
}

function openEdit() {
  if (!quota.value) return;
  Object.assign(form, toQuotaFormModel(quota.value));
  saveError.value = '';
  editOpen.value = true;
}

async function save() {
  if (Object.keys(errors.value).length > 0) return;
  saving.value = true;
  saveError.value = '';
  try {
    quota.value = await updateTenantQuota(props.tenantId, toQuotaRequest(form));
    editOpen.value = false;
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : String(error);
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div>
    <Alert
      v-if="loadError"
      :message="`加载失败：${loadError}`"
      show-icon
      type="error"
      class="mb-4"
    />
    <template v-else-if="quota">
      <Alert
        v-for="item in alerts"
        :key="item.kind"
        :message="item.text"
        show-icon
        :type="item.level"
        class="mb-4"
        :data-testid="`quota-alert-${item.kind}`"
      />
      <div class="toolbar">
        <Button
          v-if="canEdit"
          type="primary"
          data-testid="edit-quota"
          @click="openEdit"
        >
          编辑配额
        </Button>
      </div>
      <Descriptions bordered :column="2" size="middle">
        <DescriptionsItem label="用户数（启用中 / 上限）">
          {{ userUsageText(quota) }}
        </DescriptionsItem>
        <DescriptionsItem label="存储用量 / 上限">
          {{ storageUsageText(quota) }}
        </DescriptionsItem>
        <DescriptionsItem label="配额规则" :span="2">
          默认不限；达到用户数上限后新建与启用用户被拒绝（PLT-ORG-010），达到存储上限后上传被拒绝；用量达上限
          80% 预警。调低上限不回收既有账号与文件，只挡新增。
        </DescriptionsItem>
      </Descriptions>
    </template>

    <!-- 维护弹窗：关闭「限制」开关 = 不限（提交 null 清除上限） -->
    <Modal
      v-model:open="editOpen"
      :confirm-loading="saving"
      title="编辑配额"
      :width="560"
      :ok-button-props="{ disabled: Object.keys(errors).length > 0 }"
      ok-text="保存"
      cancel-text="取消"
      @ok="save"
    >
      <Alert
        v-if="saveError"
        :message="`保存失败：${saveError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Form layout="vertical" class="crdp-form">
        <FormItem label="用户数上限">
          <Switch v-model:checked="form.userLimited" />
          <span class="switch-hint">
            {{ form.userLimited ? '限制启用中用户数' : '不限' }}
          </span>
        </FormItem>
        <FormItem
          v-if="form.userLimited"
          label="用户数上限（启用中）"
          required
          :validate-status="errors.userLimit ? 'error' : undefined"
          :help="errors.userLimit"
        >
          <InputNumber
            v-model:value="form.userLimit"
            :min="1"
            :precision="0"
            placeholder="如：50"
            style="width: 100%"
          />
        </FormItem>
        <FormItem label="存储上限">
          <Switch v-model:checked="form.storageLimited" />
          <span class="switch-hint">
            {{ form.storageLimited ? '限制存储总量' : '不限' }}
          </span>
        </FormItem>
        <FormItem
          v-if="form.storageLimited"
          label="存储上限（GB）"
          required
          :validate-status="errors.storageLimitGb ? 'error' : undefined"
          :help="errors.storageLimitGb"
        >
          <InputNumber
            v-model:value="form.storageLimitGb"
            :min="0.1"
            :step="1"
            placeholder="如：100"
            style="width: 100%"
          />
        </FormItem>
      </Form>
    </Modal>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.switch-hint {
  margin-left: 12px;
  color: #4e5969;
}
</style>
