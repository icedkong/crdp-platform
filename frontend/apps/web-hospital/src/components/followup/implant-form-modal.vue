<script lang="ts" setup>
import type {
  ImplantForm,
  ImplantFormErrors,
  ImplantOptions,
  ImplantRecordView,
} from '@crdp/api';

import { computed, reactive, ref, watch } from 'vue';

import {
  buildImplantPayload,
  buildImplantUpdatePayload,
  emptyImplantForm,
  implantEditReasonError,
  implantFormOf,
  validateImplantForm,
} from '@crdp/api';
import { Form, FormItem, message, Modal, Textarea } from 'ant-design-vue';

import {
  createPatientImplant,
  fetchImplantOptions,
  updatePatientImplant,
} from '#/api/core/implant';
import ImplantFields from '#/components/followup/implant-fields.vue';

/**
 * E21-T01 / C-PAT-004、C-PAT-005 · 植入记录新建 / 编辑弹窗（pages.md §4.4「操作：
 * …编辑」；新建承载首条植入与补录历史器械，在用器械的更换 / 升级 / 电极修正走
 * implant-replace-modal.vue，E21-T02）。字段组见 implant-fields.vue。保存带
 * baseVersion 乐观锁（40900 提示刷新重试）。编辑必须填写修改原因（C-PAT-011 /
 * UAT-SELF-01：字段级历史行带原因，与修改已提交随访记录同口径）。
 */
const props = defineProps<{
  open: boolean;
  patientId: string;
  /** 编辑对象（null = 新建）。 */
  record?: ImplantRecordView | null;
}>();

const emit = defineEmits<{
  (event: 'saved', record: ImplantRecordView): void;
  (event: 'update:open', value: boolean): void;
}>();

const form = reactive<ImplantForm>(emptyImplantForm());
const options = ref<ImplantOptions | null>(null);
const errors = ref<ImplantFormErrors>({});
const reason = ref('');
const reasonError = ref<null | string>(null);
const submitting = ref(false);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    Object.assign(
      form,
      props.record ? implantFormOf(props.record) : emptyImplantForm(),
    );
    form.leads = props.record ? implantFormOf(props.record).leads : [];
    reason.value = '';
    reasonError.value = null;
    errors.value = {};
    if (!options.value) {
      fetchImplantOptions()
        .then((data) => {
          options.value = data;
        })
        .catch(() => message.error('植入选项加载失败，请重试'));
    }
  },
  { immediate: true },
);

const isEdit = computed(() => !!props.record);

async function handleSave() {
  errors.value = validateImplantForm(form, options.value);
  if (isEdit.value) {
    reasonError.value = implantEditReasonError(reason.value);
  }
  if (Object.keys(errors.value).length > 0 || reasonError.value) {
    message.warning(reasonError.value || '请先补全植入记录必填项');
    return;
  }
  submitting.value = true;
  try {
    const saved = props.record
      ? await updatePatientImplant(
          props.record.id,
          buildImplantUpdatePayload(props.record.version, form, reason.value),
        )
      : await createPatientImplant(props.patientId, buildImplantPayload(form));
    message.success(props.record ? '植入记录已保存' : '植入记录已新建');
    emit('saved', saved);
    emit('update:open', false);
  } catch (error) {
    const body = error as { code?: number; message?: string };
    if (body?.code === 40_900) {
      message.error('该植入记录已被他人修改，请刷新后重试');
    } else {
      message.error(body?.message || '保存失败，请稍后重试');
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :title="
      isEdit ? `编辑植入记录（第 ${record?.implantNo} 次）` : '新建植入记录'
    "
    :width="860"
    :confirm-loading="submitting"
    ok-text="保存"
    cancel-text="取消"
    @ok="handleSave"
    @cancel="emit('update:open', false)"
  >
    <ImplantFields v-model:form="form" :errors="errors" :options="options" />
    <Form v-if="isEdit" layout="vertical" @submit.prevent>
      <FormItem label="修改原因（必填）">
        <Textarea
          v-model:value="reason"
          data-testid="implant-edit-reason"
          :maxlength="255"
          :rows="2"
          show-count
          placeholder="必填，如：型号抄录更正（示例）"
        />
      </FormItem>
    </Form>
    <p
      v-if="reasonError"
      class="reason-error"
      data-testid="implant-edit-reason-error"
    >
      {{ reasonError }}
    </p>
  </Modal>
</template>

<style scoped>
.reason-error {
  margin: 0 0 8px;
  font-size: 12px;
  color: #ff4d4f;
}
</style>
