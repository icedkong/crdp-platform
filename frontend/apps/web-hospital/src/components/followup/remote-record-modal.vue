<script lang="ts" setup>
import { computed, ref, watch } from 'vue';

import { message, Modal } from 'ant-design-vue';
import dayjs from 'dayjs';

import { checkRecordDuplicate, createBackfillRecord } from '#/api/core/task';
import {
  defaultUnplannedDate,
  isUnplannedDateDisabled,
  unplannedDateError,
} from '#/views/followup/task/temp-task-logic';

/**
 * E21-T12 / C-FU-025 · 新建远程监测记录弹窗（患者详情「随访记录」页签工具栏入口，
 * 随功能开关 remote_monitor_record 显隐）：远程监测传输的结果用同一张程控表单人工
 * 录入（首期不对接厂商远程监测平台，附录 B）；POST /patients/{id}/records
 * （source=REMOTE，当天可录、不能晚于今天、须患者在随访中）→ 新标签页打开随访表单
 * （recordId 路由），提交后时间轴单独成「远程监测」节点，与门诊程控记录区分。
 * 随访方式缺省带入「远程监测」（entryDefaults.visitMethod，录入中可改选）。
 */
const props = defineProps<{
  open: boolean;
  patientId: string;
}>();

const emit = defineEmits<{
  (event: 'created', recordId: string): void;
  (event: 'update:open', value: boolean): void;
}>();

const visitDate = ref<dayjs.Dayjs | null>(null);
const submitting = ref(false);

watch(
  () => props.open,
  (open) => {
    if (open) visitDate.value = dayjs(defaultUnplannedDate());
  },
);

const dateError = computed(() =>
  visitDate.value
    ? unplannedDateError(visitDate.value.format('YYYY-MM-DD'))
    : '',
);

/** 未来日期不可选（远程传输收到当天即可录，与计划外同口径）。 */
function disabledDate(current: dayjs.Dayjs) {
  return isUnplannedDateDisabled(current);
}

async function handleOk() {
  if (!visitDate.value || dateError.value) {
    message.warning(dateError.value || '请填写随访日期');
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
      source: 'REMOTE',
      visitDate: date,
    });
    message.success('已创建远程监测记录，正在打开随访表单');
    emit('created', created.recordId);
    emit('update:open', false);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '创建远程监测记录失败，请稍后重试');
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
    title="新建远程监测记录（C-FU-025）"
    @cancel="handleCancel"
    @ok="handleOk"
  >
    <a-form layout="vertical">
      <a-form-item
        help="远程监测传输的结果人工录入；随访方式带入「远程监测」"
        label="随访日期"
        required
      >
        <a-date-picker
          v-model:value="visitDate"
          :disabled-date="disabledDate"
          style="width: 100%"
          value-format="YYYY-MM-DD"
        />
      </a-form-item>
      <div class="hint">
        远程监测记录：同一表单人工录入（首期不对接厂商远程监测平台）；时间轴单独标识，与门诊程控记录区分。
      </div>
    </a-form>
  </a-modal>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: #8c8c8c;
}
</style>
