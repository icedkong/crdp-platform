<script lang="ts" setup>
import type { LocationTreeNode, ReportLocationInput } from '#/reference/types';

import { ref, watch } from 'vue';

import { Button, Form, FormItem, Input, Modal, Select } from 'ant-design-vue';

import LocationPicker from './location-picker.vue';

const props = defineProps<{
  open: boolean;
  options: LocationTreeNode[];
  patientId: string;
  preset?: ReportLocationInput[];
}>();

const emit = defineEmits<{
  'update:open': [open: boolean];
}>();

const locations = ref<ReportLocationInput[]>([]);
const showError = ref(false);
const saved = ref(false);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    locations.value = props.preset ? [...props.preset] : [];
    showError.value = false;
    saved.value = false;
  },
);

function submit() {
  if (locations.value.length === 0) {
    showError.value = true;
    return;
  }
  saved.value = true;
}
</script>

<template>
  <Modal
    :footer="null"
    :open="open"
    title="添加报告单"
    :width="900"
    @update:open="emit('update:open', $event)"
  >
    <p class="fake">虚构样板。文件不会上传，也不会写入后端。</p>
    <Form v-if="!saved" layout="vertical">
      <FormItem label="类型">
        <Select
          :options="[{ label: '心脏超声（示例）', value: 'echo' }]"
          value="echo"
        />
      </FormItem>
      <FormItem label="名称">
        <Input value="超声示例-上传" />
      </FormItem>
      <FormItem label="报告时间">
        <Input value="2026-10-31" />
      </FormItem>
      <FormItem label="位置" required>
        <LocationPicker
          :options="options"
          :patient-id="patientId"
          :preset="preset"
          :show-error="showError"
          :value="locations"
          @update:value="locations = $event"
        />
      </FormItem>
      <FormItem label="附件">
        <Input disabled value="fake-report.jpg（示例，未选择真实文件）" />
      </FormItem>
      <div class="footer">
        <Button @click="emit('update:open', false)">取消</Button>
        <Button type="primary" @click="submit">确定</Button>
      </div>
    </Form>
    <p v-else>
      样板已校验通过：位置 {{ locations.length }} 个。没有调用上传接口。
    </p>
  </Modal>
</template>

<style scoped>
.fake {
  color: #8c8c8c;
}

.footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
