<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import { computed, ref, watch } from 'vue';

import { message, Modal } from 'ant-design-vue';
import dayjs from 'dayjs';

import { checkRecordDuplicate, createBackfillRecord } from '#/api/core/task';
import {
  backfillDateError,
  defaultBackfillDate,
  isBackfillDateDisabled,
} from '#/views/followup/patient/backfill-logic';

/**
 * E15-T07 / A-FU-014、C-FU-013 · 历史补录弹窗（患者详情「随访记录」页签入口）：
 * 填写实际随访日期（过去日期，今天及以后不可选）→ POST /patients/{id}/records
 * （source=BACKFILL）→ 新标签页打开随访表单（recordId 路由，pages.md §5）。
 * 补录建议随访时间点（C-FU-014）随 E23；E21-T11 起创建前查重复记录提示
 * （C-FU-024）：同一患者同一随访日期已有记录时先确认「确定继续？」再创建。
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
  // C-FU-024 重复记录提示：同一患者同一随访日期已有记录（含草稿）先确认
  const date = visitDate.value.format('YYYY-MM-DD');
  try {
    const view = await checkRecordDuplicate(props.patientId, date);
    if (view.duplicate) {
      Modal.confirm({
        title: '重复记录提示',
        content: `该患者在 ${date} 已有一条随访记录，确定继续？`,
        okText: '继续创建',
        cancelText: '取消',
        onOk: () => createRecord(date),
      });
      return;
    }
  } catch {
    // 提示接口失败不拦截创建（后端保存复算与提示各自兜底）
  }
  await createRecord(date);
}

async function createRecord(date: string) {
  submitting.value = true;
  try {
    const created = await createBackfillRecord(props.patientId, {
      source: 'BACKFILL',
      visitDate: date,
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
