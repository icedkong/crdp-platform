<script lang="ts" setup>
import { ref, watch } from 'vue';

import { baselineReasonError } from '@crdp/api';
import { Modal, Textarea } from 'ant-design-vue';

/**
 * E13-T02 / A-PAT-006（components.md §13 ReasonModal：520 宽、原因必填，
 * placeholder「必填，如：抄录错误」）：需要填原因的操作的通用弹窗。
 * 首个调用方为「解锁基线」（有 followup:patient:lock 权限并填写原因后解锁）；
 * 删除、修改已提交、停止随访等后续复用同一组件（各操作自己调接口）。
 *
 * 组件只做原因校验（必填、≤255 字）：确认时 emit('confirm', reason)，
 * 由父组件调接口，成功后自行把 v-model:open 置 false（失败保持打开可改原因）。
 */
const props = defineProps<{
  /** 确认按钮文案（如「解锁」「停止随访」）。 */
  confirmText?: string;
  /** 弹窗副标题（脱敏姓名等，不出现明文）。 */
  label?: null | string;
  /** 弹窗说明（为什么需要原因 / 操作后果等）。 */
  note?: null | string;
  /** 原因输入框 placeholder（缺省「必填，如：抄录错误」）。 */
  placeholder?: string;
}>();

const emit = defineEmits<{
  /** 确认（原因已校验必填、去首尾空白、≤255 字）；关闭由父组件控制。 */
  confirm: [reason: string];
}>();

const open = defineModel<boolean>('open', { default: false });

const reason = ref('');
const error = ref<null | string>(null);

watch(open, (value) => {
  if (value) {
    reason.value = '';
    error.value = null;
  }
});

function handleConfirm() {
  const problem = baselineReasonError(reason.value);
  if (problem) {
    error.value = problem;
    return;
  }
  error.value = null;
  emit('confirm', reason.value.trim());
}
</script>

<template>
  <Modal
    :ok-text="props.confirmText ?? '确认'"
    :open="open"
    :title="props.label ? `填写原因 · ${props.label}` : '填写原因'"
    :width="520"
    cancel-text="取消"
    @cancel="open = false"
    @ok="handleConfirm"
  >
    <p v-if="props.note" class="note">{{ props.note }}</p>
    <Textarea
      v-model:value="reason"
      :maxlength="255"
      :placeholder="props.placeholder ?? '必填，如：抄录错误'"
      :rows="3"
      data-testid="reason-input"
      show-count
    />
    <p v-if="error" class="error" data-testid="reason-error">{{ error }}</p>
  </Modal>
</template>

<style scoped>
.note {
  margin: 0 0 8px;
  font-size: 13px;
  color: #595959;
}

.error {
  margin: 8px 0 0;
  font-size: 12px;
  color: #ff4d4f;
}
</style>
