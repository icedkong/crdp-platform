<script lang="ts" setup>
import { reactive, ref, watch } from 'vue';

import {
  isReauthCancelled,
  PATIENT_DELETE_REASON_MAX,
  validatePatientDeleteReason,
} from '@crdp/api';
import { Form, FormItem, message, Modal, Textarea } from 'ant-design-vue';

import { deletePatient } from '#/api/core/patient-export';
import ReauthModal from '#/components/reauth-modal.vue';

/**
 * E13-T09 / A-PAT-012、A-NFR-008（pages.md §3 行操作「删除」：红色 + 确认 + 原因；
 * components.md §13 ReasonModal 口径）：软删除患者——只隐藏并释放病案号，数据长期保留，
 * 可由租户管理员恢复。敏感操作：followup:patient:delete + 二次认证（E04-T08）+ 原因必填。
 */
const props = defineProps<{
  /** 弹窗副标题（脱敏姓名等，不出现明文）。 */
  label?: null | string;
  patientId: number | string;
}>();

const emit = defineEmits<{ success: [patientId: string] }>();

const open = defineModel<boolean>('open', { default: false });

const reauthModal = ref<InstanceType<typeof ReauthModal>>();
const form = reactive({ reason: '' });
const error = ref('');
const submitting = ref(false);

async function handleSubmit() {
  const invalid = validatePatientDeleteReason(form.reason);
  if (invalid) {
    error.value = invalid;
    return;
  }
  error.value = '';
  submitting.value = true;
  try {
    const done = await reauthModal.value?.run((headers) =>
      deletePatient(props.patientId, form.reason.trim(), headers),
    );
    if (!done) {
      return;
    }
    message.success(
      '已删除（软删除）：数据保留，病案号可复用，租户管理员可恢复',
    );
    open.value = false;
    emit('success', String(props.patientId));
  } catch (error_) {
    if (isReauthCancelled(error_)) {
      return;
    }
    // 后端 message（如「患者已被删除，无需重复操作」）优先展示
    message.error((error_ as { message?: string }).message ?? '删除失败');
  } finally {
    submitting.value = false;
  }
}

watch(open, (value) => {
  if (value) {
    form.reason = '';
    error.value = '';
  }
});
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    ok-text="删除（需身份验证）"
    :ok-button-props="{ danger: true }"
    cancel-text="取消"
    title="删除患者"
    :width="520"
    @cancel="open = false"
    @ok="handleSubmit"
  >
    <p v-if="label" class="hint">{{ label }}</p>
    <p class="warn">
      删除为软删除：数据长期保留（A-NFR-008），病案号释放后可重新登记，租户管理员可恢复。
    </p>
    <Form layout="vertical" @submit.prevent>
      <FormItem
        label="删除原因"
        required
        :validate-status="error ? 'error' : ''"
        :help="error"
      >
        <Textarea
          v-model:value="form.reason"
          :maxlength="PATIENT_DELETE_REASON_MAX"
          :rows="3"
          show-count
        />
      </FormItem>
    </Form>
    <ReauthModal ref="reauthModal" />
  </Modal>
</template>

<style scoped>
.hint {
  color: rgb(0 0 0 / 65%);
}

.warn {
  font-size: 12px;
  color: #fa8c16;
}
</style>
