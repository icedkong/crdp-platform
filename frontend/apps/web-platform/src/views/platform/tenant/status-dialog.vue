<script lang="ts" setup>
import type {
  TenantListItem,
  TenantStatus,
  TenantStatusAction,
  TenantStatusImpact,
} from '@crdp/api';

import { computed, ref, watch } from 'vue';

import { StatusTag } from '@crdp/status-tag';
import { Alert, Form, FormItem, Input, Modal, Spin } from 'ant-design-vue';

import { changeTenantStatus, tenantStatusImpact } from '#/api/core/tenant';

import {
  actionConfirmText,
  actionVerb,
  impactRows,
  statusLabel,
  validateReason,
} from './lifecycle';

/**
 * 状态变更对话框（PLT-TEN-010 · pages.md §11）：停用 / 启用（转正、恢复）二次确认 + 原因，
 * 展示影响（在线用户数、进行中任务）。非法流转由后端按状态图 40901 拒绝，前端按钮只按同一规则显隐。
 */
const props = defineProps<{
  action: TenantStatusAction;
  open: boolean;
  status: TenantStatus;
  tenant: null | Pick<TenantListItem, 'code' | 'id' | 'name'>;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'done'): void;
}>();

const reason = ref('');
const reasonError = ref('');
const submitting = ref(false);
const actionError = ref('');
const impact = ref<null | TenantStatusImpact>(null);
const impactLoading = ref(false);

const verb = computed(() => actionVerb(props.action, props.status));
const confirmText = computed(() =>
  actionConfirmText(props.action, props.status),
);
const rows = computed(() => impactRows(impact.value));
const okDisabled = computed(
  () => Boolean(reasonError.value) || !reason.value.trim(),
);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    reason.value = '';
    reasonError.value = '';
    actionError.value = '';
    if (!props.tenant) return;
    impactLoading.value = true;
    impact.value = null;
    tenantStatusImpact(props.tenant.id)
      .then((data) => {
        impact.value = data;
      })
      .catch(() => {
        impact.value = null;
      })
      .finally(() => {
        impactLoading.value = false;
      });
  },
);

function onReasonChange() {
  reasonError.value = validateReason(reason.value) ?? '';
}

async function submit() {
  if (!props.tenant || okDisabled.value) return;
  submitting.value = true;
  actionError.value = '';
  try {
    await changeTenantStatus(
      props.tenant.id,
      props.action,
      reason.value.trim(),
    );
    emit('update:open', false);
    emit('done');
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :confirm-loading="submitting"
    :ok-button-props="{ danger: action === 'DISABLE', disabled: okDisabled }"
    :title="`${verb}：${tenant?.name ?? ''}`"
    :width="520"
    ok-text="确认"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Alert :message="confirmText" show-icon type="warning" class="mb-4" />
    <Spin :spinning="impactLoading">
      <Form layout="vertical" class="crdp-form">
        <FormItem label="当前状态">
          <StatusTag :status="statusLabel(status)" />
          <span v-if="impact" class="impact-text">
            在线用户 {{ rows.online }} · 进行中任务 {{ rows.pending }}
          </span>
        </FormItem>
        <FormItem
          label="变更原因"
          required
          :validate-status="reasonError ? 'error' : undefined"
          :help="reasonError"
        >
          <Input
            v-model:value="reason"
            :maxlength="200"
            placeholder="如：正式签约、欠费停用（示例）"
            show-count
            type="textarea"
            @change="onReasonChange"
          />
        </FormItem>
      </Form>
    </Spin>
    <Alert
      v-if="actionError"
      :message="`${verb}失败：${actionError}`"
      show-icon
      type="error"
      class="mt-2"
    />
  </Modal>
</template>

<style scoped>
.impact-text {
  margin-left: 12px;
  color: #4e5969;
}
</style>
