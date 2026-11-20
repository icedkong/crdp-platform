<script lang="ts" setup>
import type {
  ConfigApplyPreview,
  ConfigApplyResult,
  ConfigTemplateView,
  TenantListItem,
} from '@crdp/api';

import type { LoadedConfigPreview } from './apply';

import { computed, reactive, ref, watch } from 'vue';

import {
  Alert,
  Checkbox,
  Modal,
  Select,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  applyConfigTemplate,
  previewConfigTemplate,
} from '#/api/core/config-template';
import { listTenants } from '#/api/core/tenant';

import {
  applyResultSummaryText,
  canSubmitConfigPreview,
  IMPORT_RULE_NOTES,
  targetTenantOptions,
  toApplyFormModel,
  validateApplyModel,
} from './apply';

/**
 * 应用到租户对话框（E12-T04 / A-FLD-014，验收「导入前预览差异」）：
 * 选目标租户后按包内容与导入口径逐表预览将发生的差异，阅读并确认后才提交应用；
 * 应用结果回显实际的新增 / 覆盖 / 跳过 / 丢弃计数。
 */
const props = defineProps<{
  open: boolean;
  template: ConfigTemplateView | null;
}>();

const emit = defineEmits<{
  (e: 'done'): void;
  (e: 'update:open', open: boolean): void;
}>();

const form = reactive(toApplyFormModel());
const errors = computed(() =>
  validateApplyModel(form, props.template?.tenantId ?? null),
);
const submitting = ref(false);
const submitError = ref('');

// 预览数据：模板分表元信息（推演每张表的导入动作）+ 目标租户选项
const previewLoading = ref(false);
const previewFailed = ref(false);
const preview = ref<LoadedConfigPreview | null>(null);
const ready = computed(() =>
  canSubmitConfigPreview(form, preview.value, props.template?.id),
);
let previewSequence = 0;
const tenants = ref<TenantListItem[]>([]);
const tenantsLoading = ref(false);
const tenantsFailed = ref(false);
const options = computed(() =>
  targetTenantOptions(tenants.value, props.template?.tenantId ?? null),
);

// 应用结果（提交成功后切换到结果视图）
const result = ref<ConfigApplyResult | null>(null);

const previewColumns = [
  { dataIndex: 'table', title: '配置表' },
  { dataIndex: 'sourceRow', title: '源行序号' },
  { dataIndex: 'action', title: '动作' },
  { dataIndex: 'columns', title: '涉及字段' },
  { dataIndex: 'note', title: '说明' },
];
const actionText: Record<string, string> = {
  CONFLICT: '冲突',
  DROP: '丢弃',
  INSERT: '新增',
  KEEP: '保留',
  UPDATE: '更新',
};

const resultColumns = [
  { dataIndex: 'table', title: '配置表' },
  { dataIndex: 'inserted', title: '新增', width: 80, align: 'right' as const },
  { dataIndex: 'merged', title: '覆盖', width: 80, align: 'right' as const },
  { dataIndex: 'skipped', title: '跳过', width: 80, align: 'right' as const },
  { dataIndex: 'dropped', title: '丢弃', width: 80, align: 'right' as const },
  { dataIndex: 'note', title: '说明' },
];

async function loadPreview() {
  const sequence = ++previewSequence;
  const id = props.template?.id;
  const tenantId = form.tenantId;
  preview.value = null;
  form.confirmed = false;
  previewFailed.value = false;
  if (!id || tenantId === null || !props.open) {
    previewLoading.value = false;
    return;
  }
  previewLoading.value = true;
  try {
    const response = await previewConfigTemplate(id, tenantId);
    if (sequence === previewSequence)
      preview.value = { ...response, templateId: id, tenantId };
  } catch {
    if (sequence === previewSequence) previewFailed.value = true;
  } finally {
    if (sequence === previewSequence) previewLoading.value = false;
  }
}

watch(
  () => form.tenantId,
  () => {
    void loadPreview();
  },
);
watch(
  () => props.template?.id,
  () => {
    void loadPreview();
  },
);

async function loadTenants() {
  tenantsLoading.value = true;
  tenantsFailed.value = false;
  try {
    const page = await listTenants({ pageNo: 1, pageSize: 100 });
    tenants.value = page.items;
  } catch {
    tenantsFailed.value = true;
  } finally {
    tenantsLoading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    ++previewSequence;
    preview.value = null;
    previewLoading.value = false;
    if (!open) return;
    Object.assign(form, toApplyFormModel());
    submitError.value = '';
    result.value = null;

    if (tenants.value.length === 0 && !tenantsLoading.value) {
      void loadTenants();
    }
  },
);

function close() {
  emit('update:open', false);
}

function onOk() {
  if (result.value) {
    close();
    return;
  }
  void submit();
}

async function submit() {
  const id = props.template?.id;
  const current = preview.value;
  if (
    !id ||
    errors.value.length > 0 ||
    submitting.value ||
    previewLoading.value ||
    !current ||
    !ready.value
  )
    return;
  submitting.value = true;
  submitError.value = '';
  try {
    result.value = await applyConfigTemplate(
      id,
      form.tenantId as number,
      current.planHash,
    );
    emit('done');
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : String(error);
    await loadPreview();
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :open="open"
    :ok-button-props="{
      disabled:
        !result &&
        (errors.length > 0 || previewFailed || previewLoading || !ready),
    }"
    :ok-text="result ? '关闭' : '确认应用'"
    :title="result ? '应用结果' : '应用到租户（导入前预览）'"
    :width="880"
    @cancel="close"
    @ok="onOk"
  >
    <template v-if="!result">
      <Alert
        :message="`将把「${template?.name ?? ''}」（${template?.tenantName ?? ''} v${template?.version ?? ''}）的配置导入目标租户。`"
        show-icon
        type="info"
        class="mb-4"
      />

      <Spin :spinning="previewLoading">
        <div class="field-label">目标租户</div>
        <Select
          v-model:value="form.tenantId"
          :disabled="tenantsLoading || submitting"
          :loading="tenantsLoading"
          :options="options"
          placeholder="选择要应用配置的租户（不能是源租户）"
          show-search
          option-filter-prop="label"
          style="width: 100%"
        />
        <div v-if="tenantsFailed" class="field-error">
          租户列表加载失败，
          <a @click="loadTenants">重试</a>
        </div>

        <Alert
          v-if="previewFailed"
          class="mt-4"
          message="导入预览加载失败，预览不可用时不允许应用。"
          show-icon
          type="error"
        >
          <template #description>
            <a @click="loadPreview">重试</a>
          </template>
        </Alert>
        <Alert
          v-if="!form.tenantId"
          class="mt-4"
          message="请选择目标租户后生成实际差异预览。"
          show-icon
          type="info"
        />
        <template v-else-if="preview">
          <Alert
            class="mt-4"
            :message="
              preview.applicable
                ? '已读取目标租户，以下为实际差异；计划变更导入为未发布的新版本。'
                : '存在冲突，请处理后重新预览。'
            "
            show-icon
            type="warning"
          />
          <div class="field-label mt-4">分表预计变更</div>
          <Table
            :columns="resultColumns"
            :data-source="preview.tables"
            :pagination="false"
            :row-key="(row: ConfigApplyPreview['tables'][number]) => row.table"
            :scroll="{ y: 200 }"
            size="small"
          />
          <div class="field-label mt-4">行级差异</div>
          <Table
            class="mt-2"
            :columns="previewColumns"
            :data-source="preview.changes"
            :loading="previewLoading"
            :pagination="false"
            :row-key="
              (row: ConfigApplyPreview['changes'][number]) =>
                `${row.table}:${row.sourceRow}`
            "
            :scroll="{ y: 260 }"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'action'">
                <Tag
                  :color="record.action === 'CONFLICT' ? 'error' : 'default'"
                >
                  {{ actionText[record.action] ?? record.action }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'columns'">
                {{ record.columns.join('、') || '—' }}
              </template>
            </template>
          </Table>
        </template>

        <div class="field-label mt-4">导入口径（应用前必读）</div>
        <ul class="rule-notes">
          <li v-for="note in IMPORT_RULE_NOTES" :key="note">{{ note }}</li>
        </ul>
        <Checkbox
          v-model:checked="form.confirmed"
          :disabled="!preview?.applicable || previewLoading || submitting"
          class="mt-2"
        >
          我已阅读目标租户的实际差异，并确认按该计划应用
        </Checkbox>
      </Spin>
      <Alert
        v-if="submitError"
        :message="`应用失败：${submitError}`"
        show-icon
        type="error"
        class="mt-4"
      />
    </template>

    <template v-else>
      <Alert
        :message="applyResultSummaryText(result)"
        show-icon
        type="success"
        class="mb-4"
      />
      <p class="result-meta">
        已应用到 {{ result.tenantName }}（{{ result.tenantCode }}）· 应用时间
        {{ result.appliedAt.slice(0, 16).replace('T', ' ') }}
      </p>
      <Table
        :columns="resultColumns"
        :data-source="result.tables"
        :pagination="false"
        :row-key="(row: ConfigApplyResult['tables'][number]) => row.table"
        :scroll="{ y: 320 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'note'">
            {{ record.note ?? '—' }}
          </template>
        </template>
      </Table>
    </template>
  </Modal>
</template>

<style scoped>
.field-label {
  margin-bottom: 8px;
  font-weight: 500;
  color: rgb(0 0 0 / 88%);
}

.field-error {
  margin-top: 4px;
  font-size: 12px;
  color: #ff4d4f;
}

.rule-notes {
  max-height: 150px;
  padding-left: 18px;
  margin: 0;
  overflow-y: auto;
  font-size: 12px;
  line-height: 22px;
  color: rgb(0 0 0 / 65%);
}

.row-skip {
  color: rgb(0 0 0 / 45%);
}

.result-meta {
  margin: 0 0 8px;
  color: rgb(0 0 0 / 65%);
}
</style>
