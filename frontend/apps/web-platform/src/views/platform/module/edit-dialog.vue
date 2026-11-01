<script lang="ts" setup>
import type { ModuleCatalogItem } from '@crdp/api';

import { computed, ref, watch } from 'vue';

import {
  Alert,
  Form,
  FormItem,
  Input,
  Modal,
  RadioButton,
  RadioGroup,
  Textarea,
} from 'ant-design-vue';

import { updateModule } from '#/api/core/module';

import { toEditModel, toUpdateRequest, validateEditModel } from './catalog';

/**
 * 维护模块展示信息与上下架（PLT-MOD-002 · pages.md §11 模块目录）：只允许改名称、说明与上架状态；
 * 基础模块必选不可下架（前端禁用选项，后端 40901 兜底）。
 */
const props = defineProps<{
  canTakeOffShelf: boolean;
  moduleItem: ModuleCatalogItem | null;
  open: boolean;
}>();

const emit = defineEmits<{
  (e: 'update:open', open: boolean): void;
  (e: 'done'): void;
}>();

const form = ref({ description: '', name: '', status: 'ON' });
const errors = ref<string[]>([]);
const submitting = ref(false);
const submitError = ref('');

const title = computed(() => `维护模块：${props.moduleItem?.name ?? ''}`);
const okDisabled = computed(() => errors.value.length > 0);

watch(
  () => props.open,
  (open) => {
    if (!open || !props.moduleItem) return;
    form.value = toEditModel(props.moduleItem);
    errors.value = [];
    submitError.value = '';
  },
);

function onFormChange() {
  errors.value = validateEditModel(form.value);
}

async function submit() {
  if (!props.moduleItem || okDisabled.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    await updateModule(props.moduleItem.code, toUpdateRequest(form.value));
    emit('update:open', false);
    emit('done');
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :open="open"
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: okDisabled }"
    :title="title"
    :width="520"
    ok-text="确认"
    cancel-text="取消"
    @ok="submit"
    @cancel="emit('update:open', false)"
  >
    <Form layout="vertical" class="crdp-form">
      <FormItem label="模块编码">
        <span class="code">{{ moduleItem?.code }}</span>
        <span class="readonly-hint">（随发版注册，不可修改）</span>
      </FormItem>
      <FormItem
        label="名称"
        required
        :validate-status="errors.length > 0 ? 'error' : undefined"
        :help="errors.join('；') || undefined"
      >
        <Input
          v-model:value="form.name"
          :maxlength="64"
          placeholder="如：随访"
          show-count
          @change="onFormChange"
        />
      </FormItem>
      <FormItem label="说明">
        <Textarea
          v-model:value="form.description"
          :maxlength="255"
          placeholder="展示在模块目录的说明文字"
          show-count
          @change="onFormChange"
        />
      </FormItem>
      <FormItem label="上架状态">
        <RadioGroup v-model:value="form.status" @change="onFormChange">
          <RadioButton value="ON">上架</RadioButton>
          <RadioButton value="OFF" :disabled="!canTakeOffShelf">
            下架
          </RadioButton>
        </RadioGroup>
      </FormItem>
    </Form>
    <Alert
      v-if="form.status === 'OFF'"
      message="下架后该模块不能再被新开通；已开通租户不受影响（关闭模块走租户详情 · 模块）。"
      show-icon
      type="warning"
      class="mt-2"
    />
    <Alert
      v-if="!canTakeOffShelf"
      message="平台基础模块必选，不能下架。"
      show-icon
      type="info"
      class="mt-2"
    />
    <Alert
      v-if="submitError"
      :message="`维护失败：${submitError}`"
      show-icon
      type="error"
      class="mt-2"
    />
  </Modal>
</template>

<style scoped>
.code {
  font-family: monospace;
}

.readonly-hint {
  margin-left: 8px;
  color: #86909c;
}
</style>
