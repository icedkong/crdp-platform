<script lang="ts" setup>
import type { ImportIssuePage, ImportJobView } from '@crdp/api';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import {
  IMPORT_POLL_INTERVAL_MS,
  importCommitPercent,
  importFileSizeText,
  importResultSummary,
  importStatusColor,
  importStatusText,
  importStepStatus,
  importWizardStep,
  shouldPollImport,
  validateImportConfirmForm,
  validateImportUploadFile,
} from '@crdp/api';
import { sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  message,
  Popconfirm,
  Progress,
  Radio,
  RadioGroup,
  Select,
  Space,
  Spin,
  Steps,
  Table,
  Tag,
  Upload,
} from 'ant-design-vue';

import { fetchPatientFilterOptions } from '#/api/core/patient';
import {
  cancelImport,
  confirmImport,
  downloadImportReport,
  downloadImportTemplate,
  fetchImportIssues,
  fetchImportJob,
  retryImport,
  rollbackImport,
  rollbackImportBatch,
  uploadImportFile,
} from '#/api/core/patientImport';

import {
  clearStashedImportJob,
  importBatchRows,
  importIssueRows,
  readStashedImportJob,
  stashImportJob,
} from './import-logic';

defineOptions({ name: 'FollowupPatientImport' });

/**
 * E18-T06 / A-PAT-008、A-PAT-018 · 导入向导（pages.md §10「导入向导」：下载模板 →
 * 上传 → 预检报告（行、列字母、原值、原因、级别）→ 选重复处理（更新 / 跳过）→ 确认 →
 * 结果下载；同租户同时只允许一个作业）。三步可见进度（tasks.csv E18-T06 验收）：
 * 上传（受理即回 jobId）、校验（PRECHECKING 轮询 stagedRows，完成后错误 / 警告计数与
 * 分级问题清单 + 批注版报告下载）、提交（IMPORTING 按批次进度推进，DONE / 部分失败 /
 * 回滚按批可见）。作业 id 存 sessionStorage，刷新或误关标签后回到同一作业；问题清单
 * 不回显单元格原值（敏感列脱敏，OQ-101③），原值看批注版报告。仅桌面 / 平板（pages.md
 * §0.1 响应式：否，手机由外壳 MobileBlocker 拦截）。权限 followup:patient:import；
 * 功能开关 wide_import 关闭时后端 40301（入口按钮按权限显隐）。
 */
const router = useRouter();

// ---------------------------------------------------------------- 作业状态与轮询

const job = ref<ImportJobView | null>(null);
const refreshing = ref(false);
let pollTimer: null | number = null;

const currentStep = computed(() => {
  if (
    job.value &&
    job.value.status === 'PRECHECKED' &&
    job.value.errorCount === 0
  ) {
    // 预检完成且无错误：直接进入提交步（选重复处理 → 确认）；问题报告可从提交步回看
    return 'commit';
  }
  return importWizardStep(job.value?.status ?? null);
});

const stepIndex = computed(() => {
  if (currentStep.value === 'upload') {
    return 0;
  }
  return currentStep.value === 'validate' ? 1 : 2;
});

/** 校验步顶部的文件行（文件名 + 大小）。 */
const fileLine = computed(() =>
  job.value
    ? `${job.value.fileName}（${importFileSizeText(job.value.fileSize)}）`
    : '',
);

const stepItems = computed(() => {
  const step = (key: 'commit' | 'upload' | 'validate', title: string) => ({
    status: importStepStatus(key, currentStep.value, job.value),
    title,
  });
  return [
    step('upload', '上传文件'),
    step('validate', '校验报告'),
    step('commit', '提交导入'),
  ];
});

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

function schedulePolling() {
  stopPolling();
  pollTimer = window.setInterval(
    () => void refreshJob(),
    IMPORT_POLL_INTERVAL_MS,
  );
}

watch(
  () => job.value?.status,
  (status) => {
    if (shouldPollImport(status ?? null)) {
      schedulePolling();
    } else {
      stopPolling();
    }
  },
);

async function refreshJob() {
  const jobId = job.value?.jobId;
  if (!jobId || refreshing.value) {
    return;
  }
  refreshing.value = true;
  try {
    job.value = await fetchImportJob(jobId);
  } catch {
    // 轮询失败保留上次视图，下一轮重试；接口异常由统一拦截器提示
  } finally {
    refreshing.value = false;
  }
}

onBeforeUnmount(stopPolling);

// ---------------------------------------------------------------- 第 1 步：下载模板与上传

const selectedFile = ref<File | null>(null);
const uploading = ref(false);

const fileHint = computed(() =>
  selectedFile.value
    ? `${selectedFile.value.name}（${importFileSizeText(selectedFile.value.size)}）`
    : '点击或拖入 .xlsx 文件（单文件，上限 200 MB）',
);

/** 接管文件（阻止 antd 自动上传），由「上传并开始预检」显式提交。 */
function onFileSelected(file: File) {
  selectedFile.value = file;
  return false;
}

async function startUpload() {
  const error = validateImportUploadFile(
    selectedFile.value
      ? { name: selectedFile.value.name, size: selectedFile.value.size }
      : null,
  );
  if (error) {
    message.warning(error);
    return;
  }
  const file = selectedFile.value;
  if (!file) {
    return;
  }
  uploading.value = true;
  try {
    const uploaded = await uploadImportFile(file);
    stashImportJob(uploaded.jobId);
    selectedFile.value = null;
    // 立即拉一次状态（UPLOADED/PRECHECKING），轮询由 watch 接管
    job.value = await fetchImportJob(uploaded.jobId);
    message.success('上传成功，预检已开始');
  } catch {
    // 40901（已有运行中作业）等由统一拦截器提示
  } finally {
    uploading.value = false;
  }
}

function restartWizard() {
  stopPolling();
  clearStashedImportJob();
  job.value = null;
  selectedFile.value = null;
  issuePage.value = null;
  issueLevel.value = 'ALL';
  reportVisible.value = false;
  duplicateStrategy.value = null;
  defaultWardId.value = null;
}

// ---------------------------------------------------------------- 第 2 步：校验报告

type IssueLevelFilter = 'ALL' | 'ERROR' | 'WARN';

const issueLevel = ref<IssueLevelFilter>('ALL');
const issuePage = ref<ImportIssuePage | null>(null);
const issuePageNo = ref(1);
const issuePageSize = ref(sizes.tablePageSize);
const issueLoading = ref(false);

const issueRows = computed(() => importIssueRows(issuePage.value?.items ?? []));

const issueColumns = [
  { dataIndex: 'level', title: '级别', width: 76 },
  { dataIndex: 'position', title: '位置（工作表 · 行 · 列）', width: 220 },
  { dataIndex: 'header', title: '表头', width: 140 },
  { dataIndex: 'message', title: '原因' },
];

async function loadIssues() {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  issueLoading.value = true;
  try {
    issuePage.value = await fetchImportIssues(
      jobId,
      issueLevel.value === 'ALL' ? null : issueLevel.value,
      issuePageNo.value,
      issuePageSize.value,
    );
  } catch {
    issuePage.value = null;
  } finally {
    issueLoading.value = false;
  }
}

// 预检完成后加载问题清单（PRECHECK_FAILED 时问题已清空，只看整体错误清单）
watch(
  () => job.value?.status,
  (status) => {
    if (status === 'PRECHECKED') {
      issuePageNo.value = 1;
      void loadIssues();
    }
  },
);

function handleIssueLevelChange(level: IssueLevelFilter) {
  issueLevel.value = level;
  issuePageNo.value = 1;
  void loadIssues();
}

function onIssueLevelChange(event: unknown) {
  const value = (event as { target?: { value?: unknown } }).target?.value;
  if (value === 'ALL' || value === 'ERROR' || value === 'WARN') {
    handleIssueLevelChange(value);
  }
}

function handleIssuePageChange(page: number, pageSize: number) {
  issuePageNo.value = page;
  issuePageSize.value = pageSize;
  void loadIssues();
}

/** 提交步回看校验报告的开关（步骤不可点，用按钮切换）。 */
const reportVisible = ref(false);

// ---------------------------------------------------------------- 第 3 步：确认与提交进度

const duplicateStrategy = ref<'SKIP' | 'UPDATE' | null>(null);
const defaultWardId = ref<null | number>(null);
const wardOptions = ref<Array<{ label: string; value: number }>>([]);
const confirming = ref(false);
const rollingBack = ref(false);
const retrying = ref(false);
const cancelling = ref(false);

const batchRows = computed(() => importBatchRows(job.value?.batches ?? []));

const batchColumns = [
  { dataIndex: 'batchNo', title: '批次', width: 72 },
  { dataIndex: 'statusText', title: '状态', width: 96 },
  { dataIndex: 'patientCount', title: '患者数', width: 88 },
  { dataIndex: 'createdPatients', title: '新建', width: 72 },
  { dataIndex: 'updatedPatients', title: '更新', width: 72 },
  { dataIndex: 'skippedPatients', title: '跳过', width: 72 },
  { dataIndex: 'recordsCreated', title: '随访记录', width: 88 },
  { dataIndex: 'failReason', title: '失败原因' },
  { dataIndex: 'actions', title: '操作', width: 96 },
];

const canConfirm = computed(
  () =>
    job.value !== null &&
    job.value.status === 'PRECHECKED' &&
    job.value.errorCount === 0,
);

const commitPercent = computed(() =>
  importCommitPercent(job.value?.stats ?? null),
);

const layoutText = computed(() => {
  if (job.value?.layout === 'WIDE') {
    return '宽表';
  }
  return job.value?.layout === 'LONG' ? '基线+随访记录' : '';
});

async function submitConfirm() {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  const error = validateImportConfirmForm(duplicateStrategy.value);
  if (error) {
    message.warning(error);
    return;
  }
  confirming.value = true;
  try {
    await confirmImport(jobId, {
      defaultWardId: defaultWardId.value,
      duplicateStrategy: duplicateStrategy.value ?? 'UPDATE',
    });
    await refreshJob();
    message.success('导入已开始，按批次推进（可离开本页，稍后回来查看进度）');
  } catch {
    // 有 ERROR 40001 等由统一拦截器提示
  } finally {
    confirming.value = false;
  }
}

async function submitRetry() {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  retrying.value = true;
  try {
    await retryImport(jobId);
    await refreshJob();
    message.success('失败批次已开始重试');
  } catch {
    // 40901（无失败批次）由统一拦截器提示
  } finally {
    retrying.value = false;
  }
}

async function submitRollback() {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  rollingBack.value = true;
  try {
    await rollbackImport(jobId);
    await refreshJob();
    message.success('已按批回滚（新建患者连主档删除，更新患者保留主档）');
  } catch {
    // 40901（无已完成批次）由统一拦截器提示
  } finally {
    rollingBack.value = false;
  }
}

async function submitBatchRollback(batchNo: number) {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  try {
    await rollbackImportBatch(jobId, batchNo);
    await refreshJob();
    message.success(`批次 ${batchNo} 已回滚`);
  } catch {
    // 由统一拦截器提示
  }
}

async function submitCancel() {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  cancelling.value = true;
  try {
    await cancelImport(jobId);
    restartWizard();
    message.success('已取消本次导入，可重新上传');
  } catch {
    // 进行中 / 已终态 40901 由统一拦截器提示
  } finally {
    cancelling.value = false;
  }
}

async function handleDownloadReport() {
  const jobId = job.value?.jobId;
  if (!jobId) {
    return;
  }
  try {
    await downloadImportReport(jobId);
  } catch {
    message.error('校验报告下载失败，请稍后重试');
  }
}

// ---------------------------------------------------------------- 初始化

onMounted(async () => {
  try {
    const options = await fetchPatientFilterOptions();
    wardOptions.value = options.wards.map((item) => ({
      label: item.name,
      value: item.id,
    }));
  } catch {
    wardOptions.value = [];
  }
  const stashed = readStashedImportJob();
  if (stashed) {
    try {
      job.value = await fetchImportJob(stashed);
    } catch {
      // 作业已不存在（40400）：清掉续接记录，从上传步开始
      clearStashedImportJob();
    }
  }
});
</script>

<template>
  <div class="page" data-testid="patient-import">
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <h1 class="crdp-page-title">导入向导</h1>
        <Button
          data-testid="import-back"
          @click="router.push('/followup/patient')"
        >
          返回患者列表
        </Button>
      </div>

      <Steps class="steps" :current="stepIndex" :items="stepItems" />

      <!-- 第 1 步：下载模板与上传 -->
      <div v-if="currentStep === 'upload'" class="step-pane">
        <Alert
          show-icon
          type="info"
          message="三步导入：下载模板并按模板填写 → 上传并预检（出校验报告）→ 选重复处理后确认导入；同一医院同时只允许一个导入作业。"
          description="模板示例行全部为虚构数据，正式导入前请删除；宽表（医院现有格式，A-PAT-018）与基线+随访记录两张表的标准版（A-PAT-008）都支持。"
        />
        <Space>
          <Button
            data-testid="import-template-standard"
            @click="downloadImportTemplate('STANDARD')"
          >
            下载模板（基线+随访记录）
          </Button>
          <Button
            data-testid="import-template-wide"
            @click="downloadImportTemplate('WIDE')"
          >
            下载模板（宽表）
          </Button>
        </Space>
        <Upload.Dragger
          :before-upload="onFileSelected"
          :max-count="1"
          :show-upload-list="false"
          accept=".xlsx"
          data-testid="import-file-input"
        >
          <p class="hint">{{ fileHint }}</p>
          <p class="hint subtle">
            上传后自动开始预检：解析版面与表头、逐行校验并生成报告
          </p>
        </Upload.Dragger>
        <div class="actions">
          <Button
            :loading="uploading"
            data-testid="import-start-upload"
            type="primary"
            @click="startUpload"
          >
            {{ uploading ? '上传中…' : '上传并开始预检' }}
          </Button>
        </div>
      </div>

      <!-- 第 2 步：校验进度与报告 -->
      <div v-else-if="currentStep === 'validate' && job" class="step-pane">
        <div class="job-line">
          <Tag
            :color="importStatusColor(job.status)"
            data-testid="import-status"
          >
            {{ importStatusText(job.status) }}
          </Tag>
          <span>{{ fileLine }}</span>
        </div>

        <!-- 预检中：stagedRows 递增即进度（E18-T06 验收「校验可见进度」） -->
        <div
          v-if="job.status === 'PRECHECKING' || job.status === 'UPLOADED'"
          class="job-line"
        >
          <Spin />
          <span data-testid="import-precheck-progress">
            预检进行中，已解析
            {{ job.stagedRows }} 行（大文件逐行流式解析，请勿关闭本页）
          </span>
        </div>

        <!-- 预检失败：整体错误清单 + 取消重来 -->
        <template v-else-if="job.status === 'PRECHECK_FAILED'">
          <Alert show-icon type="error" :message="job.failReason ?? '预检失败'">
            <template #description>
              <ul v-if="job.layoutProblems.length > 0" class="problems">
                <li v-for="(problem, index) in job.layoutProblems" :key="index">
                  {{ problem }}
                </li>
              </ul>
              <span>请按清单修正文件后重新上传；取消本次作业后重新开始。</span>
            </template>
          </Alert>
          <div class="actions">
            <Button
              :loading="cancelling"
              data-testid="import-cancel"
              type="primary"
              @click="submitCancel"
            >
              取消并重新上传
            </Button>
          </div>
        </template>

        <!-- 预检完成：统计 + 问题清单 + 报告下载 -->
        <template v-else>
          <Space size="large">
            <span>
              错误
              <span class="count-error" data-testid="import-error-count">
                {{ job.errorCount }}
              </span>
            </span>
            <span>
              警告
              <span class="count-warn" data-testid="import-warn-count">
                {{ job.warnCount }}
              </span>
            </span>
            <span>已解析 {{ job.stagedRows }} 行</span>
            <span v-if="layoutText">版面：{{ layoutText }}</span>
          </Space>
          <Space>
            <Button
              data-testid="import-report-download"
              @click="handleDownloadReport"
            >
              下载批注版校验报告
            </Button>
            <Button
              v-if="job.errorCount > 0"
              :disabled="cancelling"
              data-testid="import-cancel-after-check"
              @click="submitCancel"
            >
              取消本次导入
            </Button>
          </Space>
          <Alert
            v-if="job.errorCount > 0"
            show-icon
            type="error"
            message="校验报告存在错误问题，不能确认导入（A-PAT-018：错误行不可导入）。请修正原表或在病区管理处理病区映射后重新上传预检；警告不拦导入。"
          />
          <RadioGroup
            :value="issueLevel"
            data-testid="import-issue-level"
            size="small"
            @change="onIssueLevelChange"
          >
            <Radio value="ALL">全部问题</Radio>
            <Radio value="ERROR">仅错误</Radio>
            <Radio value="WARN">仅警告</Radio>
          </RadioGroup>
          <Table
            :columns="issueColumns"
            :data-source="issueRows"
            :loading="issueLoading"
            :pagination="{
              current: issuePage?.pageNo ?? 1,
              pageSize: issuePage?.pageSize ?? issuePageSize,
              total: issuePage?.total ?? 0,
              showSizeChanger: false,
              onChange: handleIssuePageChange,
            }"
            :scroll="{ x: 720 }"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'level'">
                <Tag :color="record.levelColor">
                  {{ record.level === 'ERROR' ? '错误' : '警告' }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'header'">
                {{ record.header || '—' }}
              </template>
            </template>
          </Table>
          <p class="hint subtle">
            问题清单不显示单元格原值（敏感列脱敏，OQ-101③）；原值与标注请看批注版校验报告。
          </p>
        </template>
      </div>

      <!-- 第 3 步：重复处理 → 确认 → 提交进度与结果 -->
      <div v-else-if="job" class="step-pane">
        <div class="job-line">
          <Tag
            :color="importStatusColor(job.status)"
            data-testid="import-status"
          >
            {{ importStatusText(job.status) }}
          </Tag>
          <span>{{ job.fileName }}</span>
          <Button
            v-if="canConfirm"
            data-testid="import-show-report"
            size="small"
            type="link"
            @click="reportVisible = !reportVisible"
          >
            {{ reportVisible ? '收起校验报告' : '查看校验报告与问题清单' }}
          </Button>
        </div>

        <!-- 确认选项（预检完成且无错误） -->
        <template v-if="canConfirm">
          <Alert
            show-icon
            type="success"
            :message="`预检完成：无错误、${job.warnCount} 个警告（不拦导入），已解析 ${job.stagedRows} 行`"
          />
          <Form class="crdp-form" layout="vertical" @submit.prevent>
            <FormItem label="重复患者处理（文件内或系统内已存在的病案号）">
              <RadioGroup
                v-model:value="duplicateStrategy"
                data-testid="import-strategy"
              >
                <Radio value="UPDATE">更新已有患者（只改有变化的字段）</Radio>
                <Radio value="SKIP">跳过（保留系统内已有数据）</Radio>
              </RadioGroup>
            </FormItem>
            <FormItem
              label="兜底病区（病区/中心为空的行落入该病区，不选则这些行跳过）"
              :style="{ maxWidth: 360 }"
            >
              <Select
                v-model:value="defaultWardId"
                allow-clear
                :options="wardOptions"
                placeholder="不选则病区为空的行跳过"
              />
            </FormItem>
          </Form>
          <Space>
            <Popconfirm
              title="确认导入？提交后按批写入，过程中请勿重复确认。"
              ok-text="确认导入"
              cancel-text="再想想"
              @confirm="submitConfirm"
            >
              <Button
                :loading="confirming"
                data-testid="import-confirm"
                type="primary"
              >
                确认导入
              </Button>
            </Popconfirm>
            <Button
              data-testid="import-report-before-confirm"
              @click="handleDownloadReport"
            >
              下载批注版校验报告
            </Button>
            <Button :disabled="cancelling" @click="submitCancel">
              取消本次导入
            </Button>
          </Space>
        </template>

        <!-- 提交进度（IMPORTING：按批次推进，E18-T06 验收「提交可见进度」） -->
        <div v-else-if="job.status === 'IMPORTING'">
          <Progress
            :percent="commitPercent"
            data-testid="import-commit-progress"
            status="active"
          />
          <p class="hint" data-testid="import-commit-progress-text">
            导入中：已完成 {{ job.stats.batchesDone }} /
            {{ job.stats.batches }} 批 （每批约 200
            名患者，失败批次不影响其他批次，可离开本页稍后回看）
          </p>
        </div>

        <!-- 结果：完成 / 部分失败 / 已取消 / 已回滚 -->
        <template v-else>
          <Alert
            v-if="job.status === 'DONE'"
            show-icon
            type="success"
            :message="importResultSummary(job.stats)"
            data-testid="import-result-summary"
          />
          <Alert
            v-else-if="job.status === 'PARTIAL_FAILED'"
            show-icon
            type="error"
            :message="`部分批次失败：失败批次 ${job.stats.failedBatches.join('、')}；${importResultSummary(job.stats)}`"
            description="可单独重试失败批次（只重跑失败批，已成功批次不动），或回滚整个作业后重新导入。"
          />
          <Alert
            v-else-if="job.status === 'ROLLED_BACK'"
            show-icon
            type="warning"
            message="本次导入已回滚（新建患者连主档删除，更新患者保留主档只删导入写入的记录）。"
          />
          <Alert
            v-else-if="job.status === 'CANCELLED'"
            show-icon
            type="warning"
            message="本次导入已取消。"
          />
          <Space wrap>
            <Button
              v-if="job.status === 'PARTIAL_FAILED'"
              :loading="retrying"
              data-testid="import-retry"
              type="primary"
              @click="submitRetry"
            >
              重试失败批次
            </Button>
            <Button
              v-if="job.status === 'DONE' || job.status === 'PARTIAL_FAILED'"
              :loading="rollingBack"
              data-testid="import-rollback"
              danger
              @click="submitRollback"
            >
              回滚整个作业
            </Button>
            <Button
              v-if="job.status === 'DONE' || job.status === 'PARTIAL_FAILED'"
              @click="handleDownloadReport"
            >
              下载批注版校验报告
            </Button>
            <Button data-testid="import-restart" @click="restartWizard">
              再导入一批
            </Button>
          </Space>
        </template>

        <!-- 批次进度表（确认后可见；含按批回滚入口） -->
        <Table
          v-if="job.batches.length > 0"
          :columns="batchColumns"
          :data-source="batchRows"
          :pagination="false"
          :scroll="{ x: 860 }"
          size="small"
          data-testid="import-batches"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'statusText'">
              <Tag :color="record.statusColor">{{ record.statusText }}</Tag>
            </template>
            <template v-else-if="column.dataIndex === 'failReason'">
              {{ record.failReason || '—' }}
            </template>
            <template v-else-if="column.dataIndex === 'actions'">
              <Button
                v-if="record.status === 'DONE'"
                size="small"
                type="link"
                danger
                @click="submitBatchRollback(record.batchNo)"
              >
                回滚本批
              </Button>
              <span v-else>—</span>
            </template>
          </template>
        </Table>

        <!-- 提交步内回看校验报告 -->
        <template v-if="reportVisible && job.status !== 'IMPORTING'">
          <Space size="large">
            <span>错误 {{ job.errorCount }}</span>
            <span>警告 {{ job.warnCount }}</span>
            <Button
              data-testid="import-report-download-2"
              @click="handleDownloadReport"
            >
              下载批注版校验报告
            </Button>
          </Space>
          <Table
            :columns="issueColumns"
            :data-source="issueRows"
            :loading="issueLoading"
            :pagination="{
              current: issuePage?.pageNo ?? 1,
              pageSize: issuePage?.pageSize ?? issuePageSize,
              total: issuePage?.total ?? 0,
              showSizeChanger: false,
              onChange: handleIssuePageChange,
            }"
            :scroll="{ x: 720 }"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'level'">
                <Tag :color="record.levelColor">
                  {{ record.level === 'ERROR' ? '错误' : '警告' }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'header'">
                {{ record.header || '—' }}
              </template>
            </template>
          </Table>
        </template>
      </div>
    </Card>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.steps {
  margin-bottom: 24px;
}

.step-pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.job-line {
  display: flex;
  gap: 12px;
  align-items: center;
}

.hint {
  margin-bottom: 0;
}

.subtle {
  color: rgb(0 0 0 / 45%);
}

.problems {
  padding-left: 18px;
  margin: 8px 0 0;
}

.count-error {
  font-size: 16px;
  font-weight: 600;
  color: #ff4d4f;
}

.count-warn {
  font-size: 16px;
  font-weight: 600;
  color: #fa8c16;
}

.actions {
  display: flex;
  gap: 8px;
}
</style>
