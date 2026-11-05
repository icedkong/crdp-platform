<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import { computed, ref, watch } from 'vue';

import { message } from 'ant-design-vue';
import dayjs from 'dayjs';

import { createBackfillRecord } from '#/api/core/task';
import {
  backfillDateError,
  defaultBackfillDate,
  isBackfillDateDisabled,
} from '#/views/followup/patient/backfill-logic';

/**
 * E15-T07 / A-FU-014、C-FU-013 · 历史补录弹窗（患者详情「随访记录」页签入口）：
 * 填写实际随访日期（过去日期，今天及以后不可选）→ POST /patients/{id}/records
 * （source=BACKFILL）→ 新标签页打开随访表单（recordId 路由，pages.md §5）。
 * 补录建议随访时间点（C-FU-014）与重复记录提示（C-FU-024）随 C 医院接入（E21 / E23）。
 */
const props = defineProps<{
  open: boolean;
  patientId: string;
}>();

const emit = defineEmits<{
  (event: 'created', recordId: string): void;
  (event: 'update:open', value: boolean): void;
}>();

const visitDate = ref<Dayjs | null>(null);
const submitting = ref(false);

watch(
  () => props.open,
  (open) => {
    if (open) visitDate.value = dayjs(defaultBackfillDate());
  },
);

const dateError = computed(() =>
  visitDate.value
    ? backfillDateError(visitDate.value.format('YYYY-MM-DD'))
    : '',
);

async function handleOk() {
  if (!visitDate.value || dateError.value) {
    message.warning(dateError.value || '请填写实际随访日期');
    return;
  }
  submitting.value = true;
  try {
    const created = await createBackfillRecord(props.patientId, {
      source: 'BACKFILL',
      visitDate: visitDate.value.format('YYYY-MM-DD'),
    });
    message.success('已创建补录记录，正在打开随访表单');
    emit('created', created.recordId);
    emit('update:open', false);
  } catch (error) {
    message.error('创建补录记录失败，请稍后重试');
    console.error(error);
  } finally {
    submitting.value = false;
  }
}

function handleCancel() {
  emit('update:open', false);
}
</script>

<template>
  <a-modal
    :confirm-loading="submitting"
    :open="open"
    ok-text="创建并录入"
    title="历史补录（A-FU-014）"
    :width="480"
    @cancel="handleCancel"
    @ok="handleOk"
  >
    <p class="hint">
      为过去日期补录随访记录：不需要任务、不受窗口限制，记录标记为补录。创建后在新标签页打开随访表单填写并提交。
    </p>
    <a-form-item
      :validate-status="dateError ? 'error' : ''"
      :help="dateError"
      label="实际随访日期"
      required
    >
      <a-date-picker
        v-model:value="visitDate"
        :disabled-date="isBackfillDateDisabled"
        placeholder="选择过去的随访日期"
        style="width: 100%"
      />
    </a-form-item>
  </a-modal>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
