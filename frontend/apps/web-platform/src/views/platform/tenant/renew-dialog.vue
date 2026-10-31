<script lang="ts" setup>
import type {
  TenantListItem,
  TenantStatus,
  TenantStatusImpact,
} from '@crdp/api';

import { computed, reactive, ref, watch } from 'vue';

import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  DatePicker,
  Form,
  FormItem,
  Input,
  Modal,
  Radio,
  RadioGroup,
  Spin,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { applyTenantExpiry, tenantStatusImpact } from '#/api/core/tenant';

import {
  EMPTY_EXPIRY_FORM,
  impactRows,
  renewalConfirmText,
  statusLabel,
  toExpireRequest,
  toExpiryFormModel,
  validateExpiryForm,
  validateReason,
} from './lifecycle';

/**
 * 续期对话框（PLT-TEN-013 · pages.md §11）：改到期日或转长期有效 + 原因，二次确认展示影响；
 * 宽限期 / 停用中的租户续期后立即恢复启用。提醒节点与宽限期的完整维护在详情「到期设置」页签。
 */
const props = defineProps<{
  open: boolean;
  status: TenantStatus;
  tenant: null | Pick<
    TenantListItem,
    'expireAt' | 'expireType' | 'id' | 'name'
  >;
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

const form = reactive({ ...EMPTY_EXPIRY_FORM });
const errors = computed(() => validateExpiryForm(form));
const rows = computed(() => impactRows(impact.value));
const okDisabled = computed(
  () =>
    Boolean(reasonError.value) ||
    !reason.value.trim() ||
    Object.keys(errors.value).length > 0,
);

watch(
  () => props.open,
  (open) => {
    if (!open || !props.tenant) return;
    reason.value = '';
    reasonError.value = '';
    actionError.value = '';
    // 续期默认在现值上改：有到期日沿用原日期，长期有效保持长期
    Object.assign(
      form,
      toExpiryFormModel({
        expireAt: props.tenant.expireAt,
        expireType: props.tenant.expireType,
        graceDays: null,
        graceReadOnly: false,
        remindDays: null,
      }),
    );
    form.expireAt = form.expireAt || '';
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

function onExpireChange(_date: unknown, date: null | string | string[]) {
  form.expireAt = Array.isArray(date) ? '' : (date ?? '');
}

async function submit() {
  if (!props.tenant || okDisabled.value) return;
  submitting.value = true;
  actionError.value = '';
  try {
    await applyTenantExpiry(
      props.tenant.id,
      toExpireRequest(form, reason.value),
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
    :ok-button-props="{ disabled: okDisabled }"
    :title="`续期：${tenant?.name ?? ''}`"
    :width="520"
    ok-text="确认续期"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Alert
      :message="renewalConfirmText(status)"
      show-icon
      type="warning"
      class="mb-4"
    />
    <Spin :spinning="impactLoading">
      <Form layout="vertical" class="crdp-form">
        <FormItem label="当前状态">
          <StatusTag :status="statusLabel(status)" />
          <span v-if="impact" class="impact-text">
            在线用户 {{ rows.online }} · 进行中任务 {{ rows.pending }}
          </span>
        </FormItem>
        <FormItem label="到期类型" required>
          <RadioGroup v-model:value="form.expireType">
            <Radio value="FIXED">有到期日</Radio>
            <Radio value="LONG_TERM">长期有效</Radio>
          </RadioGroup>
        </FormItem>
        <FormItem
          v-if="form.expireType === 'FIXED'"
          label="新的到期日"
          required
          :validate-status="errors.expireAt ? 'error' : undefined"
          :help="errors.expireAt"
        >
          <DatePicker
            :value="form.expireAt ? dayjs(form.expireAt) : undefined"
            placeholder="选择日期"
            style="width: 100%"
            value-format="YYYY-MM-DD"
            @change="onExpireChange"
          />
        </FormItem>
        <FormItem
          label="续期原因"
          required
          :validate-status="reasonError ? 'error' : undefined"
          :help="reasonError"
        >
          <Input
            v-model:value="reason"
            :maxlength="200"
            placeholder="如：续费一年（示例）"
            show-count
            type="textarea"
            @change="onReasonChange"
          />
        </FormItem>
      </Form>
    </Spin>
    <Alert
      v-if="actionError"
      :message="`续期失败：${actionError}`"
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
