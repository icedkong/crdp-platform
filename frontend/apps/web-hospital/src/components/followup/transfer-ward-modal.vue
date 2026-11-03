<script lang="ts" setup>
import type {
  PatientTransferredResult,
  TransferWardFormState,
} from '@crdp/api';

import { computed, reactive, ref, watch } from 'vue';

import {
  buildTransferPayload,
  emptyTransferForm,
  transferTargetOptions,
  validateTransferForm,
} from '@crdp/api';
import {
  Form,
  FormItem,
  message,
  Modal,
  Select,
  Textarea,
} from 'ant-design-vue';

import {
  fetchPatientFilterOptions,
  transferPatientWard,
} from '#/api/core/patient';

/**
 * E13-T08 / A-WARD-005（components.md §13 ReasonModal 口径：520 宽、原因必填）：
 * 选择目标病区 + 填写原因 + 确认。转病区只改患者归属列，任务与附件随患者迁移；
 * 原病区医生立即失去访问权（后端数据范围承担）——成功后由调用方刷新列表 / 离开
 * 患者页感知（操作者本人可能已无该患者可见范围）。
 *
 * 目标候选复用筛选候选接口（启用病区，权限 followup:patient:view，与列表页同源），
 * 排除当前病区（已在的病区无需转入）。
 */
const props = defineProps<{
  /** 当前病区 id（候选排除；患者详情传 overview 所属病区缺省时可为空）。 */
  currentWardId?: null | number;
  /** 弹窗副标题（脱敏姓名等，不出现明文）。 */
  label?: null | string;
  patientId: number | string;
}>();

const emit = defineEmits<{
  success: [result: PatientTransferredResult];
}>();

const open = defineModel<boolean>('open', { default: false });

const wardOptions = ref<Array<{ label: string; value: number }>>([]);
const optionsLoading = ref(false);
const submitting = ref(false);
const form = reactive<TransferWardFormState>(emptyTransferForm());
const errors = ref<Partial<Record<keyof TransferWardFormState, string>>>({});

const targetOptions = computed(() =>
  transferTargetOptions(wardOptions.value, props.currentWardId ?? null),
);

watch(open, (value) => {
  if (!value) return;
  Object.assign(form, emptyTransferForm());
  errors.value = {};
  if (wardOptions.value.length === 0) {
    void loadOptions();
  }
});

async function loadOptions() {
  optionsLoading.value = true;
  try {
    const options = await fetchPatientFilterOptions();
    wardOptions.value = options.wards.map((item) => ({
      label: item.name,
      value: item.id,
    }));
  } catch {
    // 候选加载失败不阻断弹窗：下拉为空提示重试，仍可关闭
    wardOptions.value = [];
  } finally {
    optionsLoading.value = false;
  }
}

async function handleSubmit() {
  errors.value = validateTransferForm(form);
  if (Object.keys(errors.value).length > 0) return;
  const payload = buildTransferPayload(form);
  if (!payload) return;
  submitting.value = true;
  try {
    const result = await transferPatientWard(props.patientId, payload);
    message.success(
      `已转入「${result.wardName}」，原病区医生不再可见该患者（A-WARD-005）`,
    );
    open.value = false;
    emit('success', result);
  } catch (error) {
    // 后端 message（如「患者已在该病区，无需转病区」「目标病区已停用，不能转入」）优先展示
    const body = error as { message?: string };
    message.error(body?.message || '转病区失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    title="转病区"
    :width="520"
    @cancel="open = false"
    @ok="handleSubmit"
  >
    <p v-if="label" class="hint">{{ label }}</p>
    <Form layout="vertical" @submit.prevent>
      <FormItem
        :validate-status="errors.wardId ? 'error' : undefined"
        label="目标病区"
      >
        <Select
          v-model:value="form.wardId"
          :loading="optionsLoading"
          :options="targetOptions"
          placeholder="选择要转入的病区 / 中心（启用项）"
          show-search
          option-filter-prop="label"
        />
        <template v-if="errors.wardId" #extra>{{ errors.wardId }}</template>
      </FormItem>
      <FormItem
        :validate-status="errors.reason ? 'error' : undefined"
        label="转病区原因"
      >
        <Textarea
          v-model:value="form.reason"
          :maxlength="255"
          placeholder="必填，如：患者术后转入本病区"
          :rows="3"
          show-count
        />
        <template v-if="errors.reason" #extra>{{ errors.reason }}</template>
      </FormItem>
    </Form>
    <p class="hint">
      转病区后任务与附件随患者迁移，原病区医生立即失去该患者的访问权；历史可在患者详情查看。
    </p>
  </Modal>
</template>

<style scoped>
.hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
