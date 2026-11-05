<script lang="ts" setup>
import type { TaskAssignItem, TaskAssignResult } from '@crdp/api';

import { ref, watch } from 'vue';

import {
  doctorOptionLabel,
  taskAssignItemLabel,
  taskAssignOutcomeText,
  taskAssignSummary,
} from '@crdp/api';
import {
  Form,
  FormItem,
  message,
  Modal,
  Select,
  Table,
  Tag,
  Textarea,
} from 'ant-design-vue';

import { fetchDoctorOptions } from '#/api/core/patient';
import { batchAssignTasks } from '#/api/core/task';

/**
 * E15-T03 / A-FU-009（pages.md §7 任务列表「改派」与批量改派）：为选中任务改派
 * 新负责人——候选为启用病区的启用用户（doctor-options，「姓名 · 病区」），只能选
 * 患者所在病区的人（跨病区行由后端逐条 DOCTOR_NOT_IN_WARD 跳过）；改派原因可选。
 * 改派成功后新负责人收「任务改派」站内信（tasks.csv 验收）；结果逐条可查。
 * 单个改派 = 传一条 taskIds（与批量共用 batch-assign 入口）。
 */
const props = defineProps<{
  /** 已选任务数（标题与提交校验）。 */
  count: number;
  taskIds: Array<number | string>;
}>();

const emit = defineEmits<{ success: [result: TaskAssignResult] }>();

const open = defineModel<boolean>('open', { default: false });

const assigneeOptions = ref<Array<{ label: string; value: string }>>([]);
const optionsLoading = ref(false);
const assigneeId = ref<null | string>(null);
const reason = ref('');
const submitting = ref(false);
const result = ref<null | TaskAssignResult>(null);

watch(open, (value) => {
  if (!value) return;
  assigneeId.value = null;
  reason.value = '';
  result.value = null;
  if (assigneeOptions.value.length === 0) {
    void loadOptions();
  }
});

async function loadOptions() {
  optionsLoading.value = true;
  try {
    const options = await fetchDoctorOptions();
    assigneeOptions.value = options.doctors.map((doctor) => ({
      label: doctorOptionLabel(doctor),
      value: doctor.id,
    }));
  } catch {
    assigneeOptions.value = [];
  } finally {
    optionsLoading.value = false;
  }
}

async function handleSubmit() {
  if (!assigneeId.value) {
    message.warning('请选择新负责人');
    return;
  }
  submitting.value = true;
  try {
    result.value = await batchAssignTasks({
      assigneeId: assigneeId.value,
      reason: reason.value.trim() || undefined,
      taskIds: props.taskIds,
    });
    message.success(taskAssignSummary(result.value));
    emit('success', result.value);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '改派失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}

const columns = [
  { dataIndex: 'label', title: '任务' },
  { dataIndex: 'outcome', title: '结果', width: 200 },
];

function dataSource(items: TaskAssignItem[]) {
  return items.map((item) => ({
    key: item.taskId,
    label: taskAssignItemLabel(item),
    outcome: taskAssignOutcomeText(item),
    skipped: item.outcome === 'SKIP',
  }));
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :footer="result ? null : undefined"
    :open="open"
    :title="result ? '改派 · 结果' : `改派（已选 ${count} 条任务）`"
    :width="720"
    @cancel="open = false"
    @ok="handleSubmit"
  >
    <template v-if="!result">
      <Form layout="vertical" @submit.prevent>
        <FormItem label="新负责人">
          <Select
            v-model:value="assigneeId"
            :loading="optionsLoading"
            :options="assigneeOptions"
            option-filter-prop="label"
            placeholder="选择负责人（只能选患者所在病区的人，A-FU-009）"
            show-search
          />
        </FormItem>
        <FormItem help="可选；会展示给新负责人（站内信摘要），不超过 255 字">
          <Textarea
            v-model:value="reason"
            :maxlength="255"
            :rows="2"
            placeholder="改派原因（可选），如：医生休假（示例）"
          />
        </FormItem>
      </Form>
      <p class="hint">
        只能选患者所在病区的负责人：跨病区与已完成的任务会在结果中逐条跳过；
        越范围任务同样逐条列明（不带摘要）。改派成功后新负责人收到站内信通知。
      </p>
    </template>
    <template v-else>
      <p class="summary" data-testid="task-assign-summary">
        {{ taskAssignSummary(result) }}
      </p>
      <Table
        :columns="columns"
        :data-source="dataSource(result.items)"
        :pagination="false"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'outcome'">
            <Tag v-if="record.skipped" color="warning">
              {{ record.outcome }}
            </Tag>
            <Tag v-else color="success">{{ record.outcome }}</Tag>
          </template>
        </template>
      </Table>
    </template>
  </Modal>
</template>

<style scoped>
.hint {
  margin: 0;
  font-size: 12px;
  color: #8c8c8c;
}

.summary {
  margin: 0 0 8px;
  font-weight: 600;
}
</style>
