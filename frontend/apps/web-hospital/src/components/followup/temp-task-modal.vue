<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue';

import { isKeywordValid, TASK_TYPE_TEXT } from '@crdp/api';
import { message } from 'ant-design-vue';

import { searchPatients } from '#/api/core/patient';
import { createTempTask } from '#/api/core/task';
import {
  defaultTempTaskState,
  tempTaskFormError,
  tempTaskWindowText,
} from '#/views/followup/task/temp-task-logic';

/**
 * E15-T06 / A-FU-013 · 临时添加任务弹窗（pages.md §7 任务列表「+ 临时任务」与
 * 患者时间轴「添加任务」共用；A-FU-023「中间该子计划的任务列表，可临时添加任务」）：
 * 选患者（患者入口预置）→ 任务类型（FORM 填表随访 / REMINDER 站内提醒，A-FU-026）→
 * 计划日期 + 窗口宽度 → POST /followup/tasks。临时任务不占用年度任务（负数次数），
 * 负责人缺省当前用户；站内提醒类任务到点只提醒、无需填表（完成走「完成提醒」）。
 */
const props = defineProps<{
  open: boolean;
  /** 患者入口（时间轴）预置患者；任务列表入口为空，弹窗内检索选择。 */
  patientId?: null | string;
  /** 患者入口预置的患者显示名（脱敏）。 */
  patientLabel?: null | string;
}>();

const emit = defineEmits<{
  (event: 'created'): void;
  (event: 'update:open', value: boolean): void;
}>();

const state = reactive(defaultTempTaskState());
const keyword = ref('');
const searching = ref(false);
const options = ref<Array<{ label: string; value: string }>>([]);
const submitting = ref(false);

watch(
  () => props.open,
  (open) => {
    if (open) {
      Object.assign(state, defaultTempTaskState(props.patientId ?? null));
      keyword.value = '';
      options.value =
        props.patientId && props.patientLabel
          ? [{ label: props.patientLabel, value: props.patientId }]
          : [];
    }
  },
);

const formError = computed(() => tempTaskFormError(state));

/** 患者检索（姓名 / 病案号精确匹配，行内姓名脱敏；列表不提供逐行明文）。 */
async function handleSearch() {
  if (!isKeywordValid(keyword.value)) {
    message.warning('请输入患者姓名或病案号（≤64 字）');
    return;
  }
  searching.value = true;
  try {
    const page = await searchPatients({
      pageNo: 1,
      pageSize: 20,
      filters: {},
      keyword: keyword.value.trim(),
    });
    options.value = page.items.map((item) => ({
      label: `${item.name} · ${item.patientNo}`,
      value: String(item.id),
    }));
    if (options.value.length === 0)
      message.info('未找到患者，请确认姓名或病案号');
  } catch {
    message.error('患者检索失败，请稍后重试');
  } finally {
    searching.value = false;
  }
}

async function handleOk() {
  const error = formError.value;
  if (error) {
    message.warning(error);
    return;
  }
  submitting.value = true;
  try {
    await createTempTask({
      patientId: state.patientId as string,
      planDate: state.planDate as string,
      taskType: state.taskType,
      windowDays: state.windowDays,
      note: state.note.trim() || undefined,
    });
    message.success(
      state.taskType === 'REMINDER'
        ? '已添加临时站内提醒任务，到点提醒负责人（无需填表）'
        : '已添加临时任务，不占用年度任务',
    );
    emit('created');
    emit('update:open', false);
  } catch (error_) {
    const body = error_ as { message?: string };
    message.error(body?.message || '添加临时任务失败，请稍后重试');
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
    ok-text="添加任务"
    title="临时添加任务（A-FU-013，不占用年度任务）"
    @cancel="handleCancel"
    @ok="handleOk"
  >
    <a-form layout="vertical">
      <a-form-item label="患者" required>
        <a-select
          v-model:value="state.patientId"
          :disabled="!!props.patientId"
          :options="options"
          placeholder="输入姓名或病案号检索"
          show-search
        />
        <div v-if="!props.patientId" class="hint-row">
          <a-input-search
            v-model:value="keyword"
            :loading="searching"
            enter-button="检索"
            placeholder="姓名 / 病案号"
            style="max-width: 260px"
            @search="handleSearch"
          />
        </div>
      </a-form-item>
      <a-form-item label="任务类型" required>
        <a-radio-group v-model:value="state.taskType">
          <a-radio value="FORM">{{ TASK_TYPE_TEXT.FORM }}</a-radio>
          <a-radio value="REMINDER">{{ TASK_TYPE_TEXT.REMINDER }}</a-radio>
        </a-radio-group>
        <div class="hint-row">
          站内提醒类任务到点只提醒负责人、无需填表（A-FU-026），如「第 5
          年随访前核对联系方式」
        </div>
      </a-form-item>
      <a-form-item label="计划日期" required>
        <a-date-picker
          v-model:value="state.planDate"
          value-format="YYYY-MM-DD"
        />
      </a-form-item>
      <a-form-item
        :label="`窗口宽度（${tempTaskWindowText(state.windowDays)}）`"
      >
        <a-input-number
          v-model:value="state.windowDays"
          :max="365"
          :min="0"
          placeholder="7"
        />
        <span class="hint-row">窗口 = 计划日期 ± 宽度（0–365 天）</span>
      </a-form-item>
      <a-form-item label="备注">
        <a-input
          v-model:value="state.note"
          :maxlength="255"
          placeholder="如：再住院后加访（示例）"
        />
      </a-form-item>
      <div class="hint-row">
        任务创建即进入负责人待办（默认本人），不占用年度任务
      </div>
    </a-form>
  </a-modal>
</template>

<style scoped>
.hint-row {
  margin-top: 4px;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
