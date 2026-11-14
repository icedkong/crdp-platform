<script lang="ts" setup>
import type {
  ReportBatchCreatedView,
  ReportCatalogName,
  ReportCatalogType,
  ReportLocationInput,
  ReportLocationNode,
} from '@crdp/api';
import type { Dayjs } from 'dayjs';

import type { BatchFileRow } from './batch-upload-logic';

import { computed, ref, watch } from 'vue';

import {
  Button,
  DatePicker,
  Form,
  FormItem,
  message,
  Modal,
  RadioButton,
  RadioGroup,
  Select,
  Table,
  Upload,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { fetchUploadPolicy, uploadFile } from '#/api/core/file';
import {
  batchCreateReports,
  fetchCatalogReportNames,
  fetchCatalogReportTypes,
  fetchReportLocations,
} from '#/api/core/report';
import { usePermission } from '#/reference/use-permission';
import { useWatermarkViewer } from '#/reference/use-watermark-viewer';

import {
  acceptBatchFiles,
  batchFormHasError,
  batchItemErrors,
  batchUploadRowError,
  buildBatchPayload,
  validateBatchForm,
} from './batch-upload-logic';
import { downloadFileById } from './file-download';
import LocationPicker from './location-picker.vue';
import { initialLocations, sameReportLocation } from './report-dialog-logic';
import WatermarkPreview from './watermark-preview.vue';

/**
 * E16-T03 / A-ATT-014、C-SCAN-007（pages.md §6.3 批量上传，仅桌面 / 平板）：
 * 拖入 / 选择多个文件（一次 ≤ 50 个，合并为一份时 ≤ 每份上限，A-ATT-004）→ 逐个上传显示
 * 进度、失败可重试 → 统一设置类型、名称、报告时间与位置（至少 1 个）→ 模式二选一：
 * 每个文件一份（可逐份调整名称 / 报告时间 / 位置）或合并为一份多页资料 → 提交。
 * 整批一个事务（后端），失败后可整批重试（文件已上传、fileId 仍有效）。
 */
const props = defineProps<{
  open: boolean;
  patientId: string;
  /** 从基线分类 / 随访记录内打开时预选该位置（与添加报告单同口径） */
  preset?: ReportLocationInput[];
}>();

const emit = defineEmits<{
  created: [view: ReportBatchCreatedView];
  'update:open': [open: boolean];
}>();

const types = ref<ReportCatalogType[]>([]);
const typesLoading = ref(false);
const names = ref<ReportCatalogName[]>([]);
const namesLoading = ref(false);
const treeOptions = ref<ReportLocationNode[]>([]);
const treeLoading = ref(false);
const maxFilesPerReport = ref(0);

const rows = ref<BatchFileRow[]>([]);
const mode = ref<'MERGE' | 'ONE_PER_FILE'>('ONE_PER_FILE');
const typeCode = ref<null | string>(null);
const nameId = ref<null | string>(null);
const reportDate = ref<Dayjs | null>(null);
const locations = ref<ReportLocationInput[]>([]);
const expandedUid = ref<null | string>(null);
/** 行 → 原始 File（上传与重试用；对话框关闭即弃）。 */
const pendingFiles = new Map<string, File>();
const uploadingAny = ref(false);
const submitting = ref(false);

// ------------------------------ E16-T06 全屏查看器（A-ATT-005 / C-SCAN-006 旋转）

const { hasPermission } = usePermission();
const watermarkViewer = useWatermarkViewer();
const canDownloadFile = computed(() => hasPermission('followup:scan:download'));
const previewOpen = ref(false);
const previewIndex = ref(0);

/** 已上传完成的行 → 查看器文件清单（按提交顺序）。 */
const previewFiles = computed(() =>
  rows.value
    .filter((row) => row.status === 'done' && row.fileId)
    .map((row) => ({
      fileId: row.fileId as string,
      name: row.name,
    })),
);

/** 「预览」→ 全屏查看器打开该行文件（水印不可关闭；下载按 followup:scan:download）。 */
function openFilePreview(row: BatchFileRow) {
  const index = previewFiles.value.findIndex(
    (item) => item.fileId === row.fileId,
  );
  if (index === -1) return;
  previewIndex.value = index;
  previewOpen.value = true;
}

async function handlePreviewDownload(fileId: number | string) {
  const ok = await downloadFileById(fileId);
  if (!ok) {
    message.error('下载失败，请稍后重试');
  }
}
const showErrors = ref(false);

const currentType = computed(
  () => types.value.find((item) => item.code === typeCode.value) ?? null,
);

const filesReady = computed(
  () =>
    rows.value.length > 0 &&
    rows.value.every((row) => row.status === 'done' && row.fileId),
);

const errors = computed(() =>
  validateBatchForm({
    fileCount: rows.value.length,
    filesReady: filesReady.value,
    locations: locations.value,
    maxFilesPerReport: maxFilesPerReport.value,
    mode: mode.value,
    nameId: nameId.value,
    nameRequired: currentType.value?.nameRequired ?? true,
    reportDate: reportDate.value ? 'set' : null,
    typeCode: typeCode.value,
  }),
);

const itemErrors = computed(() =>
  batchItemErrors(
    rows.value,
    mode.value,
    currentType.value?.nameRequired ?? true,
    {
      nameId: nameId.value,
      reportDate: reportDate.value
        ? reportDate.value.format('YYYY-MM-DD')
        : null,
    },
  ),
);

const locationInvalid = computed(
  () => showErrors.value && locations.value.length === 0,
);

const columns = computed(() => {
  const base: Array<{ dataIndex: string; title: string; width?: number }> = [
    { dataIndex: 'name', title: '文件' },
    { dataIndex: 'status', title: '状态', width: 110 },
    { dataIndex: 'actions', title: '操作', width: 130 },
  ];
  if (mode.value !== 'ONE_PER_FILE') return base;
  return [
    base[0] as { dataIndex: string; title: string; width?: number },
    { dataIndex: 'nameId', title: '名称（逐份可调）', width: 190 },
    { dataIndex: 'reportDate', title: '报告时间（逐份可调）', width: 160 },
    ...base.slice(1),
  ];
});

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    resetForm();
    void loadTypes();
    void loadTree();
    void loadPolicy();
  },
  { immediate: true },
);

function resetForm() {
  rows.value = [];
  pendingFiles.clear();
  mode.value = 'ONE_PER_FILE';
  typeCode.value = null;
  nameId.value = null;
  reportDate.value = dayjs();
  locations.value = initialLocations(props.preset);
  expandedUid.value = null;
  showErrors.value = false;
}

async function loadTypes() {
  typesLoading.value = true;
  try {
    types.value = await fetchCatalogReportTypes();
  } catch {
    types.value = [];
  } finally {
    typesLoading.value = false;
  }
}

async function loadTree() {
  treeLoading.value = true;
  try {
    treeOptions.value = await fetchReportLocations(props.patientId);
    normalizeLocationsWithTree();
  } catch {
    treeOptions.value = [];
  } finally {
    treeLoading.value = false;
  }
}

async function loadPolicy() {
  try {
    const policy = await fetchUploadPolicy();
    maxFilesPerReport.value = policy.maxFilesPerReport;
  } catch {
    maxFilesPerReport.value = 0;
  }
}

/** 预选位置与树对齐（与添加报告单同口径：版本以树带出为准）。 */
function normalizeLocationsWithTree() {
  const leaves: ReportLocationInput[] = [];
  const walk = (nodes: ReportLocationNode[]) => {
    for (const node of nodes) {
      if (node.value) leaves.push(node.value);
      if (node.children) walk(node.children);
    }
  };
  walk(treeOptions.value);
  locations.value = locations.value.map(
    (item) => leaves.find((leaf) => sameReportLocation(leaf, item)) ?? item,
  );
}

async function onTypeChange(code: string) {
  typeCode.value = code;
  nameId.value = null;
  for (const row of rows.value) {
    row.nameId = null;
  }
  namesLoading.value = true;
  try {
    names.value = await fetchCatalogReportNames(code);
  } catch {
    names.value = [];
  } finally {
    namesLoading.value = false;
  }
}

function onNameChange(id: string) {
  nameId.value = id;
}

/** 选择文件入队（A-ATT-014 ≤ 50 个；MERGE 模式受每份上限约束）。 */
function enqueueFiles(fileList: File[]) {
  const result = acceptBatchFiles(
    rows.value.length,
    fileList,
    mode.value,
    maxFilesPerReport.value,
  );
  if (result.error) message.warning(result.error);
  for (const file of result.accepted) {
    const uid = `${file.name}-${file.size}-${rows.value.length}`;
    pendingFiles.set(uid, file);
    rows.value.push({
      name: file.name,
      size: file.size,
      status: 'pending',
      uid,
    });
  }
}

function beforeUpload() {
  // 由 beforeUpload 拿到 File 列表手动入队，阻止 antd 自动上传
  return false;
}

/** 逐个上传（C-SCAN-007 显示进度；失败可重试）：一次一个，进度按行展示。 */
async function startUpload() {
  const pending = rows.value.filter(
    (row) => row.status === 'pending' || row.status === 'failed',
  );
  if (pending.length === 0) return;
  uploadingAny.value = true;
  for (const row of pending) {
    await uploadRow(row);
  }
  uploadingAny.value = false;
}

async function uploadRow(row: BatchFileRow) {
  const file = fileOf(row);
  if (!file) {
    row.status = 'failed';
    row.error = '文件已失效，请重新选择';
    return;
  }
  row.status = 'uploading';
  row.error = null;
  try {
    const uploaded = await uploadFile(file);
    row.fileId = uploaded.fileId;
    row.name = uploaded.originalName;
    row.status = 'done';
  } catch (error) {
    row.status = 'failed';
    // E22-T16 / 等保 D-01 前端面：病毒检出（40001）与扫描不可用（50300）显示后端原文，
    // 不再把病毒拒绝误导成「格式与大小、可重试」；其余维持通用文案。
    row.error = batchUploadRowError(error);
  }
}

/** 行 → 原始 File（选择入队时登记，上传与重试用）。 */
function fileOf(row: BatchFileRow): File | undefined {
  return pendingFiles.get(row.uid);
}

function removeRow(uid: string) {
  rows.value = rows.value.filter((row) => row.uid !== uid);
  pendingFiles.delete(uid);
  if (expandedUid.value === uid) expandedUid.value = null;
}

function toggleExpand(uid: string) {
  expandedUid.value = expandedUid.value === uid ? null : uid;
}

function onItemNameChange(uid: string, id: string) {
  const row = rows.value.find((item) => item.uid === uid);
  if (row) row.nameId = id || null;
}

function onItemDateChange(uid: string, date: null | string) {
  const row = rows.value.find((item) => item.uid === uid);
  if (row) row.reportDate = date || null;
}

function onItemLocationsChange(uid: string, value: ReportLocationInput[]) {
  const row = rows.value.find((item) => item.uid === uid);
  if (row) row.locations = value;
}

const statusText: Record<string, string> = {
  done: '已上传',
  failed: '失败',
  pending: '待上传',
  uploading: '上传中…',
};

async function handleSubmit() {
  showErrors.value = true;
  if (batchFormHasError(errors.value) || itemErrors.value.size > 0) return;
  submitting.value = true;
  try {
    const view = await batchCreateReports(
      buildBatchPayload({
        commonNameId: nameId.value,
        commonReportDate: reportDate.value?.format('YYYY-MM-DD') ?? '',
        locations: locations.value,
        mode: mode.value,
        patientId: props.patientId,
        rows: rows.value,
        typeCode: typeCode.value ?? '',
      }),
    );
    message.success(
      mode.value === 'MERGE'
        ? `已合并为 1 份报告单（${view.fileCount} 个文件）`
        : `已创建 ${view.reportCount} 份报告单`,
    );
    emit('created', view);
    emit('update:open', false);
  } catch {
    // 错误提示由统一拦截器弹出（40001 数量上限 / 位置校验、40400 越范围在后端）
  } finally {
    submitting.value = false;
  }
}

function handleCancel() {
  emit('update:open', false);
}

// 测试驱动口（对话框内容经 Modal teleport，事件从 DOM 直取不便）
defineExpose({
  enqueueFiles,
  handleSubmit,
  uploadRow,
  itemErrors,
  mode,
  onItemDateChange,
  onItemLocationsChange,
  onItemNameChange,
  onNameChange,
  onTypeChange,
  rows,
  startUpload,
  toggleExpand,
});
</script>

<template>
  <Modal
    :footer="null"
    :open="open"
    title="批量上传报告单"
    :width="920"
    @update:open="emit('update:open', $event)"
  >
    <Form layout="vertical" @submit.prevent>
      <FormItem label="文件（可拖入或选择多个，先上传再提交）">
        <Upload.Dragger
          :before-upload="beforeUpload"
          :file-list="[]"
          :show-upload-list="false"
          multiple
          data-testid="batch-file-input"
          @change="
            enqueueFiles(
              ($event.fileList as Array<{ originFileObj?: File }>)
                .map((item) => item.originFileObj)
                .filter((item): item is File => item instanceof File),
            )
          "
        >
          <p class="hint">点击或拖入文件（可多选）</p>
          <p class="hint">
            一次最多 50 个文件{{
              maxFilesPerReport > 0 && mode === 'MERGE'
                ? `；合并为一份时最多 ${maxFilesPerReport} 个（每份报告单上限）`
                : ''
            }}
          </p>
        </Upload.Dragger>
        <div v-if="rows.length > 0" class="batch-actions">
          <Button
            :disabled="filesReady"
            :loading="uploadingAny"
            data-testid="batch-start-upload"
            type="primary"
            @click="startUpload"
          >
            开始上传
          </Button>
          <span class="hint">
            已选 {{ rows.length }} 个文件（已上传
            {{ rows.filter((row) => row.status === 'done').length }} 个）
          </span>
        </div>
        <p v-if="showErrors && errors.files" class="error">
          {{ errors.files }}
        </p>
      </FormItem>

      <FormItem label="类型" required>
        <Select
          data-testid="batch-type-select"
          :loading="typesLoading"
          :options="
            types.map((item) => ({ label: item.name, value: item.code }))
          "
          placeholder="请选择类型"
          :value="typeCode"
          @update:value="onTypeChange"
        />
        <p v-if="showErrors && errors.typeCode" class="error">
          {{ errors.typeCode }}
        </p>
      </FormItem>

      <FormItem label="生成方式" required>
        <RadioGroup
          data-testid="batch-mode"
          :value="mode"
          @update:value="mode = $event as 'MERGE' | 'ONE_PER_FILE'"
        >
          <RadioButton value="ONE_PER_FILE">每个文件一份</RadioButton>
          <RadioButton value="MERGE">合并为一份多页资料</RadioButton>
        </RadioGroup>
      </FormItem>

      <FormItem
        :label="
          mode === 'ONE_PER_FILE' ? '名称（公共默认，可逐份调整）' : '名称'
        "
        :required="currentType?.nameRequired ?? true"
      >
        <Select
          data-testid="batch-name-select"
          :disabled="!typeCode"
          :loading="namesLoading"
          :options="names.map((item) => ({ label: item.name, value: item.id }))"
          :placeholder="typeCode ? '请选择名称' : '请先选择类型'"
          show-search
          :value="nameId"
          @update:value="onNameChange"
        />
        <p v-if="showErrors && errors.nameId" class="error">
          {{ errors.nameId }}
        </p>
      </FormItem>

      <FormItem label="报告时间（公共默认，可逐份调整）" required>
        <DatePicker
          v-model:value="reportDate"
          data-testid="batch-report-date"
          style="width: 100%"
          value-format="YYYY-MM-DD"
        />
        <p v-if="showErrors && errors.reportDate" class="error">
          {{ errors.reportDate }}
        </p>
      </FormItem>

      <FormItem label="位置（公共默认，每个文件一份时可逐份调整）" required>
        <LocationPicker
          :options="treeOptions"
          :patient-id="patientId"
          :preset="preset"
          :show-error="locationInvalid"
          :value="locations"
          @update:value="locations = $event"
        />
        <p v-if="showErrors && errors.locations" class="error">
          {{ errors.locations }}
        </p>
        <p v-else-if="treeLoading" class="hint">正在加载位置树…</p>
      </FormItem>

      <FormItem v-if="rows.length > 0" label="文件清单">
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="false"
          size="small"
          data-testid="batch-file-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'name'">
              {{ record.name }}
            </template>
            <template v-else-if="column.dataIndex === 'nameId'">
              <Select
                :data-testid="`batch-item-name-${record.uid}`"
                :options="
                  names.map((item) => ({ label: item.name, value: item.id }))
                "
                placeholder="沿用公共名称"
                show-search
                size="small"
                :value="record.nameId ?? undefined"
                @update:value="
                  onItemNameChange(record.uid as string, $event as string)
                "
              />
              <p
                v-if="showErrors && itemErrors.get(record.uid as string)"
                class="error"
              >
                {{ itemErrors.get(record.uid as string) }}
              </p>
            </template>
            <template v-else-if="column.dataIndex === 'reportDate'">
              <DatePicker
                :data-testid="`batch-item-date-${record.uid}`"
                placeholder="沿用公共时间"
                size="small"
                :value="record.reportDate ?? undefined"
                value-format="YYYY-MM-DD"
                @update:value="
                  onItemDateChange(
                    record.uid as string,
                    $event as null | string,
                  )
                "
              />
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              <span :data-testid="`batch-item-status-${record.uid}`">
                {{ statusText[record.status as string] ?? record.status }}
                <span v-if="record.status === 'failed'" class="error-inline">
                  {{ record.error }}
                </span>
              </span>
            </template>
            <template v-else-if="column.dataIndex === 'actions'">
              <!-- E16-T06：上传完成后可全屏预览（缩放 / 旋转 / 翻转 / 水印，C-SCAN-006） -->
              <Button
                v-if="record.status === 'done' && record.fileId"
                size="small"
                type="link"
                @click="openFilePreview(record as BatchFileRow)"
              >
                预览
              </Button>
              <Button
                v-if="record.status === 'failed'"
                size="small"
                type="link"
                @click="uploadRow(record as BatchFileRow)"
              >
                重试
              </Button>
              <Button
                v-if="mode === 'ONE_PER_FILE'"
                size="small"
                type="link"
                @click="toggleExpand(record.uid as string)"
              >
                调整位置
              </Button>
              <Button
                danger
                size="small"
                type="link"
                @click="removeRow(record.uid as string)"
              >
                移除
              </Button>
            </template>
          </template>
          <template #expandedRowRender="{ record }">
            <div v-if="expandedUid === record.uid" class="expanded-picker">
              <LocationPicker
                :options="treeOptions"
                :patient-id="patientId"
                :value="record.locations ?? locations"
                @update:value="
                  onItemLocationsChange(record.uid as string, $event)
                "
              />
              <p class="hint">保存后该文件用这组位置（不勾选沿用公共位置）。</p>
            </div>
          </template>
        </Table>
      </FormItem>

      <div class="footer">
        <Button @click="handleCancel">取消</Button>
        <Button
          data-testid="batch-submit"
          :loading="submitting"
          type="primary"
          @click="handleSubmit"
        >
          提交
        </Button>
      </div>
    </Form>
    <!-- E16-T06 全屏查看器：上传完成后预览（含旋转，C-SCAN-006；水印不可关闭） -->
    <WatermarkPreview
      :can-download="canDownloadFile"
      :files="previewFiles"
      :index="previewIndex"
      :open="previewOpen"
      :viewer="watermarkViewer"
      @download="handlePreviewDownload"
      @update:index="previewIndex = $event"
      @update:open="previewOpen = $event"
    />
  </Modal>
</template>

<style scoped>
.batch-actions {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 8px;
}

.expanded-picker {
  padding: 8px 0;
}

.footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}

.error {
  margin: 4px 0 0;
  font-size: 12px;
  color: #ff4d4f;
}

.error-inline {
  font-size: 12px;
  color: #ff4d4f;
}

.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
