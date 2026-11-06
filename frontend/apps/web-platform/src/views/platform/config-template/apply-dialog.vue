<script lang="ts" setup>
import type {
  ConfigApplyResult,
  ConfigTableMeta,
  ConfigTemplateView,
  TenantListItem,
} from '@crdp/api';

import type { ImportPreviewSummary, TableImportPreview } from './apply';

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
  configTemplateDetail,
} from '#/api/core/config-template';
import { listTenants } from '#/api/core/tenant';

import {
  applyResultSummaryText,
  IMPORT_RULE_NOTES,
  previewSummary,
  previewSummaryText,
  tablePreviews,
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
const previews = ref<TableImportPreview[]>([]);
const summary = ref<ImportPreviewSummary>({
  importRows: 0,
  importTables: 0,
  skipTables: 0,
});
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
  { dataIndex: 'category', title: '类别' },
  { dataIndex: 'modeText', title: '复制方式', width: 110 },
  {
    dataIndex: 'packageRows',
    title: '包内行数',
    width: 96,
    align: 'right' as const,
  },
  { dataIndex: 'actionText', title: '导入时会发生什么' },
];

const resultColumns = [
  { dataIndex: 'table', title: '配置表' },
  { dataIndex: 'inserted', title: '新增', width: 80, align: 'right' as const },
  { dataIndex: 'merged', title: '覆盖', width: 80, align: 'right' as const },
  { dataIndex: 'skipped', title: '跳过', width: 80, align: 'right' as const },
  { dataIndex: 'dropped', title: '丢弃', width: 80, align: 'right' as const },
  { dataIndex: 'note', title: '说明' },
];

async function loadPreview() {
  const id = props.template?.id;
  if (!id) return;
  previewLoading.value = true;
  previewFailed.value = false;
  try {
    const detail = await configTemplateDetail(id);
    const tables: ConfigTableMeta[] = detail.tables;
    previews.value = tablePreviews(tables);
    summary.value = previewSummary(tables);
  } catch {
    previewFailed.value = true;
  } finally {
    previewLoading.value = false;
  }
}

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
    if (!open) return;
    Object.assign(form, toApplyFormModel());
    submitError.value = '';
    result.value = null;
    previews.value = [];
    summary.value = { importRows: 0, importTables: 0, skipTables: 0 };
    void loadPreview();
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
  if (!id || errors.value.length > 0 || submitting.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    result.value = await applyConfigTemplate(id, form.tenantId as number);
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
    :ok-button-props="{
      disabled: !result && (errors.length > 0 || previewFailed),
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
          :disabled="tenantsLoading"
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
        <template v-else>
          <Alert
            class="mt-4"
            :message="previewSummaryText(summary)"
            show-icon
            type="warning"
          />
          <Table
            class="mt-2"
            :columns="previewColumns"
            :data-source="previews"
            :loading="previewLoading"
            :pagination="false"
            :row-key="(row: TableImportPreview) => row.table"
            :scroll="{ y: 260 }"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'table'">
                <span :class="{ 'row-skip': record.tone === 'skip' }">
                  {{ record.table }}
                </span>
              </template>
              <template v-else-if="column.dataIndex === 'modeText'">
                <Tag v-if="record.mode === 'STRUCTURE_ONLY'" color="default">
                  {{ record.modeText }}
                </Tag>
                <Tag v-else color="blue">{{ record.modeText }}</Tag>
              </template>
              <template v-else-if="column.dataIndex === 'actionText'">
                <span :class="{ 'row-skip': record.tone === 'skip' }">
                  {{ record.actionText }}
                </span>
              </template>
            </template>
          </Table>
        </template>

        <div class="field-label mt-4">导入口径（应用前必读）</div>
        <ul class="rule-notes">
          <li v-for="note in IMPORT_RULE_NOTES" :key="note">{{ note }}</li>
        </ul>
        <Checkbox v-model:checked="form.confirmed" class="mt-2">
          我已阅读导入预览，了解目标租户中与模板自然键相同的配置将被覆盖
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
