<script lang="ts" setup>
import type {
  ReportCatalogName,
  ReportCatalogType,
  ReportCreatedView,
  ReportLocationInput,
  ReportLocationNode,
} from '@crdp/api';
import type { Dayjs } from 'dayjs';

import type { CallContactOption, IndicatorRow } from './report-dialog-logic';

import { computed, reactive, ref, watch } from 'vue';

import { reportIndicatorFlag } from '@crdp/api';
import {
  Button,
  DatePicker,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Select,
  Table,
  Upload,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { fetchPatientContacts } from '#/api/core/contact';
import { fetchUploadPolicy, uploadFile } from '#/api/core/file';
import {
  createReport,
  fetchCatalogReportNames,
  fetchCatalogReportTypes,
  fetchReportLocations,
} from '#/api/core/report';
import { usePermission } from '#/reference/use-permission';
import { useWatermarkViewer } from '#/reference/use-watermark-viewer';

import { downloadFileById } from './file-download';
import LocationPicker from './location-picker.vue';
import {
  attachmentCountText,
  attachmentOverLimit,
  buildIndicatorRows,
  CALL_TYPE_CODE,
  callContactOptions,
  hasReportFormError,
  indicatorPayload,
  initialLocations,
  locationRequired,
  moveItem,
  reportConclusionLabel,
  reportDateLabel,
  reportFindingsLabel,
  reportNoteLabel,
  sameReportLocation,
  validateReportForm,
  withIndicatorFlag,
} from './report-dialog-logic';
import WatermarkPreview from './watermark-preview.vue';

/**
 * E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010（pages.md §6.1 添加报告单，弹窗
 * 800–960）：先选类型 → 名称（名称字典）/ 报告时间 / 指标表（按名称绑定模板自动带出）/ 所见与结论
 * （按类型显示）/ 位置（LocationPicker 必填可多选，从分类或分组内打开已预选）/ 附件 / 备注。
 * 心脏超声映射确认（A-ATT-013）随 E16-T07；手机扫码（A-ATT-015）随 E16-T10。
 *
 * E16-T03 / A-ATT-004、C-SCAN-006：多附件——打开时取上传策略，超过每份上限（A 默认
 * 10 个）的选择直接拦截并提示（服务端同口径 40001 兜底）；附件可上移 / 下移调整顺序
 * （顺序随 sortNo 落库）、可移除。
 */
const props = defineProps<{
  open: boolean;
  /** 指标参考范围按患者性别选定（A-ATT-012 可按性别区分） */
  patientGender?: 'F' | 'M' | null;
  patientId: string;
  /** 从基线分类 / 随访记录（整条或分组）内打开时预选该位置 */
  preset?: ReportLocationInput[];
}>();

const emit = defineEmits<{
  created: [view: ReportCreatedView];
  'update:open': [open: boolean];
}>();

const types = ref<ReportCatalogType[]>([]);
const typesLoading = ref(false);
const maxFilesPerReport = ref(0);
const names = ref<ReportCatalogName[]>([]);
const namesLoading = ref(false);
const treeOptions = ref<ReportLocationNode[]>([]);
const treeLoading = ref(false);
const submitting = ref(false);

const typeCode = ref<null | string>(null);
// E16-T08 / A-ATT-017：仅「通话记录」显示「关联沟通记录」（录音即附件，两处同一数据）
const contactOptions = ref<CallContactOption[]>([]);
const contactLogId = ref<null | string>(null);
const contactsLoading = ref(false);
const nameId = ref<null | string>(null);
const reportDate = ref<Dayjs | null>(null);
const findings = ref('');
const conclusion = ref('');
const note = ref('');
const locations = ref<ReportLocationInput[]>([]);
/** 已上传附件（fileId + 后端真实格式 + 原文件名；格式供预览方式判定）。 */
const files = ref<Array<{ fileId: string; format: string; name: string }>>([]);
const uploading = ref(false);
const showErrors = ref(false);

// ------------------------------ E16-T06 全屏查看器（A-ATT-005 / C-SCAN-006 旋转）

const { hasPermission } = usePermission();
const watermarkViewer = useWatermarkViewer();
const canDownloadFile = computed(() => hasPermission('followup:scan:download'));
const previewOpen = ref(false);
const previewIndex = ref(0);

/** 「预览」→ 全屏查看器打开该附件（水印不可关闭；下载按 followup:scan:download）。 */
function openFilePreview(index: number) {
  previewIndex.value = index;
  previewOpen.value = true;
}

async function handlePreviewDownload(fileId: number | string) {
  const ok = await downloadFileById(fileId);
  if (!ok) {
    message.error('下载失败，请稍后重试');
  }
}

const currentType = computed(
  () => types.value.find((item) => item.code === typeCode.value) ?? null,
);
const currentName = computed(
  () => names.value.find((item) => item.id === nameId.value) ?? null,
);
const dateLabel = computed(() =>
  reportDateLabel(currentType.value?.fields ?? []),
);
const findingsLabel = computed(() =>
  reportFindingsLabel(currentType.value?.fields ?? []),
);
const conclusionLabel = computed(() =>
  reportConclusionLabel(currentType.value?.fields ?? []),
);
const noteLabel = computed(() =>
  reportNoteLabel(currentType.value?.fields ?? []),
);

const indicatorRows = reactive<IndicatorRow[]>(
  buildIndicatorRows(null, props.patientGender),
);

const errors = computed(() =>
  validateReportForm({
    nameId: nameId.value,
    nameRequired: currentType.value?.nameRequired ?? true,
    reportDate: reportDate.value ? 'set' : null,
    typeCode: typeCode.value,
  }),
);
const locationInvalid = computed(
  () =>
    showErrors.value &&
    locations.value.length === 0 &&
    locationRequired(typeCode.value, contactLogId.value),
);

const indicatorColumns = [
  { dataIndex: 'itemName', title: '项目' },
  { dataIndex: 'result', title: '结果' },
  { dataIndex: 'unit', title: '单位' },
  { dataIndex: 'refRange', title: '参考范围' },
  { dataIndex: 'flag', title: '', width: 40 },
];

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

/** 上传策略（E16-T03）：每份报告单文件数上限，选择附件时前置拦截（后端 40001 兜底）。 */
async function loadPolicy() {
  try {
    const policy = await fetchUploadPolicy();
    maxFilesPerReport.value = policy.maxFilesPerReport;
  } catch {
    maxFilesPerReport.value = 0;
  }
}

function resetForm() {
  typeCode.value = null;
  contactLogId.value = null;
  nameId.value = null;
  reportDate.value = dayjs();
  findings.value = '';
  conclusion.value = '';
  note.value = '';
  locations.value = initialLocations(props.preset);
  files.value = [];
  showErrors.value = false;
  replaceRows(buildIndicatorRows(null, props.patientGender));
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

/**
 * 预选位置与树对齐：anchorTemplateVersion 以树带出为准（进入位置只给了四元组时回填版本，
 * A-ATT-002「锚点所属模板版本」；树里已不存在的位置保留原样，由后端校验兜底）。
 */
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
  contactLogId.value = null;
  nameId.value = null;
  replaceRows(buildIndicatorRows(null, props.patientGender));
  namesLoading.value = true;
  try {
    names.value = await fetchCatalogReportNames(code);
  } catch {
    names.value = [];
  } finally {
    namesLoading.value = false;
  }
  if (code === CALL_TYPE_CODE && contactOptions.value.length === 0) {
    // 只在首次选「通话记录」时拉取（最近 100 条沟通记录足够覆盖关联候选）
    contactsLoading.value = true;
    try {
      const page = await fetchPatientContacts(props.patientId, {
        pageNo: 1,
        pageSize: 100,
      });
      contactOptions.value = callContactOptions(page.items);
    } catch {
      contactOptions.value = [];
    } finally {
      contactsLoading.value = false;
    }
  }
}

function onNameChange(id: string) {
  nameId.value = id;
  replaceRows(buildIndicatorRows(currentName.value, props.patientGender));
}

function replaceRows(rows: IndicatorRow[]) {
  indicatorRows.splice(0, indicatorRows.length, ...rows);
}

function onResultInput(row: IndicatorRow, value: string) {
  const next = withIndicatorFlag({ ...row, result: value });
  const index = indicatorRows.indexOf(row);
  if (index !== -1) indicatorRows.splice(index, 1, next);
}

/** 上传前即时标 ↑ / ↓（与服务端同口径；最终以后端落库为准）。 */
function flagText(refRange: null | string, result: string): string {
  return reportIndicatorFlag(refRange, result) ?? '';
}

async function customRequest(options: {
  file: File;
  onError: (error: Error) => void;
  onSuccess: (body: unknown) => void;
}) {
  // A-ATT-004 / C-SCAN-006：超过每份上限的选择直接拦截，不上传（后端同口径 40001 兜底）
  const over = attachmentOverLimit(
    files.value.length,
    1,
    maxFilesPerReport.value,
  );
  if (over) {
    message.warning(over);
    options.onError(new Error(over));
    return;
  }
  uploading.value = true;
  try {
    const uploaded = await uploadFile(options.file);
    files.value.push({
      fileId: uploaded.fileId,
      format: uploaded.format,
      name: uploaded.originalName,
    });
    options.onSuccess(uploaded);
  } catch (error) {
    options.onError(error as Error);
  } finally {
    uploading.value = false;
  }
}

function removeFile(fileId: string) {
  files.value = files.value.filter((item) => item.fileId !== fileId);
}

/** 附件顺序调整（C-SCAN-006「可调整顺序」）：顺序随提交落 sortNo。 */
function moveFile(fileId: string, delta: -1 | 1) {
  const index = files.value.findIndex((item) => item.fileId === fileId);
  files.value = moveItem(files.value, index, delta);
}

const fileCountHint = computed(() =>
  attachmentCountText(files.value.length, maxFilesPerReport.value),
);

function beforeUpload() {
  // 由 customRequest 接管，阻止 antd 自动上传（与 ContactLogModal 同口径）
  return false;
}

async function handleSubmit() {
  showErrors.value = true;
  if (
    hasReportFormError(errors.value) ||
    (locations.value.length === 0 &&
      locationRequired(typeCode.value, contactLogId.value))
  ) {
    return;
  }
  submitting.value = true;
  try {
    const created = await createReport({
      conclusion: conclusion.value.trim() || null,
      contactLogId:
        typeCode.value === CALL_TYPE_CODE ? contactLogId.value : null,
      fields: {},
      fileIds: files.value.map((item) => item.fileId),
      findings: findings.value.trim() || null,
      indicators: indicatorPayload(indicatorRows),
      locations: locations.value,
      nameId: nameId.value,
      note: note.value.trim() || null,
      patientId: props.patientId,
      reportDate: reportDate.value?.format('YYYY-MM-DD') ?? '',
      typeCode: typeCode.value ?? '',
    });
    message.success(
      `报告单已保存：${created.typeName}，位置 ${created.locationCount} 个`,
    );
    emit('created', created);
    emit('update:open', false);
  } catch {
    // 错误提示由统一拦截器弹出（40001 位置 / 附件上限与 40400 越范围在后端校验）
  } finally {
    submitting.value = false;
  }
}

function handleCancel() {
  emit('update:open', false);
}

// 测试驱动口（对话框内容经 Modal teleport，事件从 DOM 直取不便）
defineExpose({
  indicatorRows,
  contactLogId,
  contactOptions,
  customRequest,
  files,
  moveFile,
  onNameChange,
  onResultInput,
  onTypeChange,
});
</script>

<template>
  <Modal
    :confirm-loading="submitting"
    :footer="null"
    :open="open"
    title="添加报告单"
    :width="880"
    @update:open="emit('update:open', $event)"
  >
    <Form layout="vertical" @submit.prevent>
      <FormItem label="类型" required>
        <Select
          data-testid="report-type-select"
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
        <p v-else-if="!typesLoading && types.length === 0" class="hint">
          暂无可用资料类型，请在「报告单设置」中配置。
        </p>
      </FormItem>

      <FormItem label="名称" :required="currentType?.nameRequired ?? true">
        <Select
          data-testid="report-name-select"
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

      <!-- E16-T08 / A-ATT-017：通话记录可选关联某条沟通记录，录音即附件（同一批文件，两处同一数据） -->
      <FormItem
        v-if="typeCode === 'CALL'"
        label="关联沟通记录"
        data-testid="call-contact-link"
      >
        <Select
          :loading="contactsLoading"
          :options="contactOptions"
          allow-clear
          data-testid="call-contact-select"
          placeholder="选择带录音的沟通记录（可选）"
          :value="contactLogId"
          @update:value="contactLogId = $event"
        />
        <p class="hint">
          关联后该沟通的录音即本报告单附件（同一文件、不重复存储）；位置留空时自动取该沟通关联任务的随访记录（无则进入「未指定位置」）；删除任一处两处同时删除（A-ATT-017）。
        </p>
      </FormItem>

      <FormItem :label="dateLabel" required>
        <DatePicker
          v-model:value="reportDate"
          data-testid="report-date"
          style="width: 100%"
          value-format="YYYY-MM-DD"
        />
        <p v-if="showErrors && errors.reportDate" class="error">
          {{ errors.reportDate }}
        </p>
      </FormItem>

      <FormItem
        v-if="indicatorRows.length > 0"
        label="指标（按名称绑定的模板带出，超出参考范围自动标 ↑ / ↓）"
      >
        <Table
          :columns="indicatorColumns"
          :data-source="indicatorRows"
          :pagination="false"
          size="small"
          data-testid="indicator-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'result'">
              <Input
                :data-testid="`indicator-result-${record.itemCode}`"
                :value="record.result"
                @update:value="
                  onResultInput(record as IndicatorRow, $event as string)
                "
              />
            </template>
            <template v-else-if="column.dataIndex === 'refRange'">
              {{ record.refRange ?? '—' }}
            </template>
            <template v-else-if="column.dataIndex === 'flag'">
              <span data-testid="indicator-flag">{{
                flagText(record.refRange, record.result)
              }}</span>
            </template>
          </template>
        </Table>
      </FormItem>

      <FormItem v-if="findingsLabel" :label="findingsLabel">
        <Input.TextArea
          v-model:value="findings"
          :rows="2"
          data-testid="report-findings"
        />
      </FormItem>
      <FormItem v-if="conclusionLabel" :label="conclusionLabel">
        <Input.TextArea
          v-model:value="conclusion"
          :rows="2"
          data-testid="report-conclusion"
        />
      </FormItem>

      <FormItem
        label="位置"
        :required="locationRequired(typeCode, contactLogId)"
      >
        <LocationPicker
          :options="treeOptions"
          :patient-id="patientId"
          :preset="preset"
          :show-error="locationInvalid"
          :value="locations"
          @update:value="locations = $event"
        />
        <p v-if="treeLoading" class="hint">正在加载位置树…</p>
        <p v-else-if="treeOptions.length === 0" class="hint">
          该患者暂无可选位置（基线分类与随访记录随模板与记录生成）。
        </p>
      </FormItem>

      <FormItem label="附件">
        <Upload
          :before-upload="beforeUpload"
          :custom-request="customRequest"
          :file-list="[]"
          multiple
        >
          <Button :loading="uploading">选择文件（可多选）</Button>
        </Upload>
        <ul
          v-if="files.length > 0"
          class="file-list"
          data-testid="report-files"
        >
          <li v-for="(item, index) in files" :key="item.fileId">
            {{ index + 1 }}. {{ item.name }}
            <!-- E16-T06 / C-SCAN-006：上传后即可全屏预览（缩放 / 旋转 / 翻转 / 水印） -->
            <Button size="small" type="link" @click="openFilePreview(index)">
              预览
            </Button>
            <Button
              :disabled="index === 0"
              size="small"
              type="link"
              @click="moveFile(item.fileId, -1)"
            >
              上移
            </Button>
            <Button
              :disabled="index === files.length - 1"
              size="small"
              type="link"
              @click="moveFile(item.fileId, 1)"
            >
              下移
            </Button>
            <Button
              danger
              size="small"
              type="link"
              @click="removeFile(item.fileId)"
            >
              移除
            </Button>
          </li>
        </ul>
        <p class="hint" data-testid="report-file-count">
          {{ fileCountHint }}；格式与大小按租户配置（真实类型以后端检测为准）。
        </p>
      </FormItem>

      <FormItem :label="noteLabel">
        <Input v-model:value="note" data-testid="report-note" />
      </FormItem>

      <div class="footer">
        <Button @click="handleCancel">取消</Button>
        <Button
          data-testid="report-submit"
          :loading="submitting"
          type="primary"
          @click="handleSubmit"
        >
          保存
        </Button>
      </div>
    </Form>
    <!-- E16-T06 全屏查看器：上传后预览（含旋转，C-SCAN-006；水印不可关闭） -->
    <WatermarkPreview
      :can-download="canDownloadFile"
      :files="files"
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

.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}

.file-list {
  padding-left: 16px;
  margin: 8px 0 0;
}

.file-list li {
  font-size: 13px;
}
</style>
