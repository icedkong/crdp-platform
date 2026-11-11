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
  implantFormOf,
  validateImplantForm,
} from '@crdp/api';
import { message, Modal, Tooltip } from 'ant-design-vue';

import {
  createPatientImplant,
  fetchImplantOptions,
  updatePatientImplant,
} from '#/api/core/implant';
import ImplantFields from '#/components/followup/implant-fields.vue';

/**
 * E21-T01 / C-PAT-004、C-PAT-005 · 植入记录新建 / 编辑弹窗（pages.md §4.4
 * 「操作：更换 / 升级、电极修正、编辑」中的「编辑」与再次植入入口；更换 / 升级与
 * 电极修正专用流程随 E21-T02）。字段组见 implant-fields.vue。保存带 baseVersion
 * 乐观锁（40900 提示刷新重试）。
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
  if (Object.keys(errors.value).length > 0) {
    message.warning('请先补全植入记录必填项');
    return;
  }
  submitting.value = true;
  try {
    const saved = props.record
      ? await updatePatientImplant(
          props.record.id,
          buildImplantUpdatePayload(props.record.version, form),
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
    <Tooltip title="更换 / 升级与电极修正流程随 E21-T02 开放">
      <div class="footnote">更换 / 升级、电极修正入口随后续版本开放</div>
    </Tooltip>
  </Modal>
</template>

<style scoped>
.footnote {
  margin-top: 12px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
