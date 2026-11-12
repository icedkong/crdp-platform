<script lang="ts" setup>
import type {
  ReportDigitizeChangedView,
  ReportRowDigitizeStatus,
} from '@crdp/api';

import { computed, reactive, ref, watch } from 'vue';

import { DIGITIZE_CHANGE_OPTIONS, digitizeChangeError } from '@crdp/api';
import {
  Form,
  FormItem,
  message,
  Modal,
  RadioButton,
  RadioGroup,
  Textarea,
} from 'ant-design-vue';

import { changeReportDigitizeStatus } from '#/api/core/report';

/**
 * E21-T15 / C-SCAN-011（pages.md §6.4 报告单列表「改状态」；接口 POST
 * /followup/reports/{id}/digitize-status）：手工修改电子化状态——待录入 / 已电子化 /
 * 无需录入，改为「无需录入」须填原因（40001，前端先拦、后端兜底）。
 *
 * 每次修改后端写 RPT_DIGITIZE_CHANGE 操作日志（谁、何时、前后值、原因）并推进
 * version（验收「状态可手工修改并留痕」）。调用方在 scan_progress 开关开启且有
 * followup:scan:upload 权限时才显示入口（后端 40301 / 40300 兜底，AGENTS §5.7 / §5.9）。
 */
const props = defineProps<{
  /** 目标报告单（null = 关闭态占位；label 为弹窗标题显示名）。 */
  report: null | {
    digitizeStatus: null | ReportRowDigitizeStatus;
    id: string;
    label: string;
  };
}>();

const emit = defineEmits<{
  (event: 'success', view: ReportDigitizeChangedView): void;
}>();

const open = defineModel<boolean>('open', { default: false });

const form = reactive<{
  reason: string;
  status: null | ReportRowDigitizeStatus;
}>({
  reason: '',
  status: null,
});
const error = ref<null | string>(null);
const submitting = ref(false);

/** 改为「无需录入」须填原因（C-SCAN-011）。 */
const reasonRequired = computed(() => form.status === 'NO_ENTRY');

watch(open, (value) => {
  if (!value || !props.report) return;
  form.status = props.report.digitizeStatus;
  form.reason = '';
  error.value = null;
});

function handleSubmit() {
  const report = props.report;
  if (!report || submitting.value) return;
  const problem = digitizeChangeError(
    report.digitizeStatus,
    form.status,
    form.reason,
  );
  if (problem) {
    error.value = problem; // 未选 / 无变化 / 无需录入缺原因 / 超 255 字，后端同口径兜底
    return;
  }
  error.value = null;
  submitting.value = true;
  changeReportDigitizeStatus(report.id, {
    reason: form.reason.trim() || null,
    status: form.status as ReportRowDigitizeStatus,
  })
    .then((view) => {
      message.success('电子化状态已更新（已记入操作日志）');
      open.value = false;
      emit('success', view);
    })
    .catch(() => {
      // 提示由统一拦截器弹出（如「改为『无需录入』须填写原因」「状态未变化」）
    })
    .finally(() => {
      submitting.value = false;
    });
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    :title="`修改电子化状态 · ${report?.label ?? ''}`"
    :width="520"
    cancel-text="取消"
    ok-text="保存"
    @cancel="open = false"
    @ok="handleSubmit"
  >
    <p class="hint">
      状态改为「无需录入」须填写原因；每次修改都会记入操作日志（谁、何时、前后值）。
    </p>
    <Form layout="vertical" @submit.prevent>
      <FormItem label="电子化状态">
        <RadioGroup
          v-model:value="form.status"
          data-testid="digitize-status-options"
        >
          <RadioButton
            v-for="item in DIGITIZE_CHANGE_OPTIONS"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </RadioButton>
        </RadioGroup>
      </FormItem>
      <FormItem :label="reasonRequired ? '原因（必填）' : '原因（选填）'">
        <Textarea
          v-model:value="form.reason"
          data-testid="digitize-reason-input"
          :maxlength="255"
          :placeholder="
            reasonRequired ? '必填，如：检验类资料，无需转录（示例）' : '选填'
          "
          :rows="3"
          show-count
        />
      </FormItem>
    </Form>
    <p v-if="error" class="error" data-testid="digitize-status-error">
      {{ error }}
    </p>
  </Modal>
</template>

<style scoped>
.hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.error {
  margin: 4px 0 0;
  font-size: 12px;
  color: #ff4d4f;
}
</style>
