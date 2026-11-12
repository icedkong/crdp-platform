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
 * E15-T06 / C-FU-022、A-FU-013 · 新建计划外随访弹窗（患者详情头部入口，components.md §4）：
 * 症状就诊、程控调整等直接新建记录——随访日期当天可录、不能晚于今天；POST
 * /patients/{id}/records（source=UNPLANNED）→ 新标签页打开随访表单（recordId 路由）。
 * 计划外随访不影响后续节点（时间轴单独成节点、不作对比对象）；「随访时间点选『其他』」
 * 与「下次随访时间覆盖」是医院 C 表单字段与联动（随 E21），文案按 pages.md §5 横幅口径。
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

/** 未来日期不可选（与补录不同：计划外当天可录）。 */
function disabledDate(current: dayjs.Dayjs) {
  return isUnplannedDateDisabled(current);
}

async function handleOk() {
  if (!visitDate.value || dateError.value) {
    message.warning(dateError.value || '请填写随访日期');
    return;
  }
  // C-FU-024 重复记录提示（E21-T11）：同一患者同一随访日期已有记录（含草稿）先确认
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
      source: 'UNPLANNED',
      visitDate: date,
    });
    message.success('已创建计划外随访记录，正在打开随访表单');
    emit('created', created.recordId);
    emit('update:open', false);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '创建计划外随访失败，请稍后重试');
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
    title="新建计划外随访（C-FU-022）"
    @cancel="handleCancel"
    @ok="handleOk"
  >
    <a-form layout="vertical">
      <a-form-item
        help="症状就诊、程控调整等直接新建记录；不影响后续节点"
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
        计划外随访：随访时间点为“其他”，不影响后续节点（提交后记为计划外记录，不作对比对象）。
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
