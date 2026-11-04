<script lang="ts" setup>
import type { BatchResult, DoctorOption } from '@crdp/api';

import { ref, watch } from 'vue';

import { doctorOptionLabel } from '@crdp/api';
import { Form, FormItem, message, Modal, Select } from 'ant-design-vue';

import { batchAssignDoctor, fetchDoctorOptions } from '#/api/core/patient';
import BatchResultPanel from '#/components/followup/batch-result-modal.vue';

/**
 * E13-T07 / A-PAT-017、C-PAT-016（pages.md §3 患者列表「批量指定责任医生」）：
 * 为选中患者批量指定责任医生——只能选该病区的医生（A-PAT-017）：候选为启用病区的
 * 启用医生（doctor-options，展示「姓名 · 病区」），跨病区患者由后端逐条校验跳过；
 * 结果逐条可查（tasks.csv 验收）。手机不提供批量操作（pages.md §3）。
 */
const props = defineProps<{
  /** 已选患者数（标题与提交校验）。 */
  count: number;
  patientIds: Array<number | string>;
}>();

const emit = defineEmits<{ success: [result: BatchResult] }>();

const open = defineModel<boolean>('open', { default: false });

const doctorOptions = ref<Array<{ label: string; value: string }>>([]);
const optionsLoading = ref(false);
const doctorId = ref<null | string>(null);
const submitting = ref(false);
const result = ref<BatchResult | null>(null);

watch(open, (value) => {
  if (!value) return;
  doctorId.value = null;
  result.value = null;
  if (doctorOptions.value.length === 0) {
    void loadOptions();
  }
});

async function loadOptions() {
  optionsLoading.value = true;
  try {
    const options = await fetchDoctorOptions();
    doctorOptions.value = options.doctors.map((doctor: DoctorOption) => ({
      label: doctorOptionLabel(doctor),
      value: doctor.id,
    }));
  } catch {
    doctorOptions.value = [];
  } finally {
    optionsLoading.value = false;
  }
}

async function handleSubmit() {
  if (!doctorId.value) {
    message.warning('请选择责任医生');
    return;
  }
  submitting.value = true;
  try {
    result.value = await batchAssignDoctor({
      doctorId: doctorId.value,
      patientIds: props.patientIds,
    });
    message.success(
      `批量指定完成：已指定 ${result.value.succeeded}、跳过 ${result.value.skipped}`,
    );
    emit('success', result.value);
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '批量指定责任医生失败，请稍后重试');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :footer="result ? null : undefined"
    :open="open"
    :title="
      result
        ? '批量指定责任医生 · 结果'
        : `批量指定责任医生（已选 ${count} 名患者）`
    "
    :width="720"
    @cancel="open = false"
    @ok="handleSubmit"
  >
    <template v-if="!result">
      <Form layout="vertical" @submit.prevent>
        <FormItem label="责任医生">
          <Select
            v-model:value="doctorId"
            :loading="optionsLoading"
            :options="doctorOptions"
            placeholder="选择医生（只能指定患者所在病区的医生，A-PAT-017）"
            show-search
            option-filter-prop="label"
          />
        </FormItem>
      </Form>
      <p class="hint">
        只能选患者所在病区的医生：跨病区选中的患者会在结果中逐条跳过；
        越范围患者同样逐条列明（不带摘要）。
      </p>
    </template>
    <BatchResultPanel v-else v-model:result="result" success-label="已指定" />
  </Modal>
</template>

<style scoped>
.hint {
  margin: 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
