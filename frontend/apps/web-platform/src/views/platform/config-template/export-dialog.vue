<script lang="ts" setup>
import { computed, reactive, ref, watch } from 'vue';

import { Alert, Form, FormItem, Input, Modal, Select } from 'ant-design-vue';

import { exportConfigTemplate } from '#/api/core/config-template';
import { listTenants } from '#/api/core/tenant';

import {
  TEMPLATE_NAME_MAX,
  toExportFormModel,
  toExportRequest,
  validateExportModel,
} from './list';

/**
 * 导出对话框（E12-T01 接口 / E12-T04 页面）：选源租户、可命名模板。
 * 只导出配置表注册表标注的配置（不含患者数据）；含敏感信息时后端 40901 拦截。
 */
const props = defineProps<{ open: boolean }>();

const emit = defineEmits<{
  (e: 'done'): void;
  (e: 'update:open', open: boolean): void;
}>();

const form = reactive(toExportFormModel());
const errors = computed(() => validateExportModel(form));
const submitting = ref(false);
const submitError = ref('');
const tenantsLoading = ref(false);
const tenantsFailed = ref(false);
const options = ref<Array<{ label: string; value: number }>>([]);

async function loadTenants() {
  tenantsLoading.value = true;
  tenantsFailed.value = false;
  try {
    const page = await listTenants({ pageNo: 1, pageSize: 100 });
    options.value = page.items.map((tenant) => ({
      label: `${tenant.name}（${tenant.code}）`,
      value: tenant.id,
    }));
  } catch {
    tenantsFailed.value = true;
  } finally {
    tenantsLoading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    Object.assign(form, toExportFormModel());
    submitError.value = '';
    if (options.value.length === 0 && !tenantsLoading.value) {
      void loadTenants();
    }
  },
);

function close() {
  emit('update:open', false);
}

async function submit() {
  if (errors.value.length > 0 || submitting.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    await exportConfigTemplate(toExportRequest(form));
    close();
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
    :confirm-loading="submitting"
    :open="open"
    :ok-button-props="{ disabled: errors.length > 0 }"
    title="导出配置模板"
    @cancel="close"
    @ok="submit"
  >
    <Alert
      message="只导出配置（字段字典与表单、字典、角色与授权、通知、计划模板等），不含任何患者数据与用户账号；术者、病区 / 中心只复制结构。含敏感信息的包会被拦截。"
      show-icon
      type="info"
      class="mb-4"
    />
    <Form layout="vertical">
      <FormItem label="源租户" required>
        <Select
          v-model:value="form.tenantId"
          :loading="tenantsLoading"
          :options="options"
          placeholder="选择要导出配置的租户"
          show-search
          option-filter-prop="label"
        />
        <div v-if="tenantsFailed" class="field-error">
          租户列表加载失败，
          <a @click="loadTenants">重试</a>
        </div>
      </FormItem>
      <FormItem label="模板名称">
        <Input
          v-model:value="form.name"
          :maxlength="TEMPLATE_NAME_MAX"
          placeholder="缺省为「{租户名} 配置模板」"
          allow-clear
        />
      </FormItem>
    </Form>
    <Alert
      v-if="submitError"
      :message="`导出失败：${submitError}`"
      show-icon
      type="error"
      class="mb-4"
    />
  </Modal>
</template>

<style scoped>
.field-error {
  margin-top: 4px;
  font-size: 12px;
  color: #ff4d4f;
}
</style>
