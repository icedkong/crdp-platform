<script lang="ts" setup>
import type {
  FormSchema,
  RecordDeviceContext,
  RecordPatientBrief,
  RecordTaskContext,
} from '@crdp/api';
import type {
  AutoSaveTrigger,
  NextVisitAffordance,
  PreviousRecord,
} from '@crdp/form-engine';
import type { CategoryProgress, CompareHit } from '@crdp/rules';

import type { CompareReportPaneReport } from '#/components/followup/compare-report-pane.vue';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useUserStore } from '@vben/stores';

import { taskDisplayStatus } from '@crdp/api';
import {
  asDraftConflictDetail,
  DraftConflictError,
  FormRenderer,
  useAutoSave,
} from '@crdp/form-engine';
import { useKeyboardSafeBottom } from '@crdp/responsive';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Card,
  message,
  Modal,
  Result,
  Space,
  Spin,
  Tooltip,
} from 'ant-design-vue';

import { fetchFormSchema } from '#/api/core/form';
import { fetchPatientReports } from '#/api/core/report';
import {
  fetchRecordDetail,
  fetchTaskQueue,
  saveRecordDraft,
  startTask,
  submitRecord,
} from '#/api/core/task';
import BackfillRecordModal from '#/components/followup/backfill-record-modal.vue';
import CategoryBar from '#/components/followup/category-bar.vue';
import {
  COMPARE_QUERY_KEY,
  compareReportIdOf,
  TRANSCRIBE_QUERY_KEY,
  transcribeActiveOf,
} from '#/components/followup/compare-entry-logic';
import CompareReportPane from '#/components/followup/compare-report-pane.vue';
import ConflictBanner from '#/components/followup/conflict-banner.vue';
import { downloadFileById } from '#/components/followup/file-download';
import FormStatusBar from '#/components/followup/form-status-bar.vue';
import MobileUploadQrModal from '#/components/followup/mobile-upload-qr.vue';
import ReasonModal from '#/components/followup/reason-modal.vue';
import UploadReportDialog from '#/components/followup/upload-report-dialog.vue';
import WatermarkPreview from '#/components/followup/watermark-preview.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { useFeature } from '#/reference/use-feature';
import { usePermission } from '#/reference/use-permission';
import { useWatermarkViewer } from '#/reference/use-watermark-viewer';

import { queueFilterOfQuery } from '../task/logic';
import {
  applyEntryDefaults,
  bannersOf,
  canContinueBackfill,
  canEditSubmittedRecord,
  deathFieldKey,
  diffChanges,
  draftRecordKey,
  duplicateRecordConfirmText,
  entrySummaryText,
  hasChanges,
  headerSummary,
  headerTitle,
  missingRequiredText,
  nextVisitFieldKey,
  nextVisitIssueText,
  nextVisitNote,
  nextVisitText,
  nextVisitWindowPreviewOf,
  readonlyBanner,
  rendererContextOf,
  submitConfirmText,
  submitSuccessMessage,
  transcribeAppendedText,
} from './logic';

defineOptions({ name: 'FollowupRecordForm' });

/**
 * E15-T02 / A-FU-005、C-FU-020 · 随访工作台（pages.md §5 随访整页表单，新标签页）：
 * 任务开启时已创建草稿记录（/tasks/{id}/start），统一用 recordId 路由。
 *
 * - FormRenderer mode=edit 按模板渲染（分组 / 校验 / 派生 / 带入上次值全部来自模板）；
 *   CategoryBar 竖向锚点 + 已填 / 应填（E11-T11 progress 事件）；
 * - 暂存（Ctrl/⌘+S）→ PUT /records/{id}/draft（changes 只带变更字段），成功提示
 *   「已暂存到服务器（草稿），任务变为进行中」（OQ-77②）；提交（Ctrl/⌘+Enter）→
 *   渲染器硬校验闸门 → 确认弹窗 → POST /records/{id}/submit，成功后任务完成
 *   （A-FU-005 验收「提交后任务完成」）；Enter 不提交；
 * - E25-T02 / NFR-SAVE-001/002/003 useAutoSave：每 2 分钟有改动才存服务器草稿，
 *   状态条「已自动保存 HH:mm」等四态；本地草稿每 30 秒 + blur 写 IndexedDB
 *   （断网不丢数据，恢复网络立即同步，离线不能提交）；409 → ConflictBanner 暂停
 *   并二选一（保留我的并覆盖 / 载入服务器版本）；打开时有本地草稿提示恢复；
 * - 上一条 / 下一条顺当前列表（GET /tasks/queue，筛选由列表页随链接带出）；
 *   dirty 时弹「有未保存的修改，离开前怎么处理？」（暂存并离开 / 放弃修改 / 取消），
 *   刷新 / 关标签页走 beforeunload；
 * - 只读：无 followup:record:edit 或记录已提交（整体只读，修改随后续任务）；
 * - 横幅：逾期 / 窗口外 / 补录（pages.md §5.1 文案照抄）。
 * - E24-T02 / NFR-RESP-001、002（pages.md §5.2）：平板去掉左侧锚点列，分组条
 *   吸顶横向滚动、底部栏同桌面；手机「跳到分组」下拉 + 底部抽屉（CategoryBar
 *   select 形态）、顶栏左侧「返回」、底部固定操作栏（上一条 / 下一条、暂存、
 *   提交，随 visualViewport 跟随键盘），路由已登记响应式名单（手机不再被
 *   MobileBlocker 拦下）。
 *
 * - E16-T11 / A-ATT-019、C-FU-017：?compare=<报告单 id> 右侧分屏对照录入
 *   （CompareReportPane 一侧报告单一侧表单；平板 / 手机改为「对照报告单」按钮
 *   全屏切换，pages.md §5.1），缩放 / 旋转 / 逐份翻页复用 E16-T06 全屏查看器；
 *   「报告单带入指标」随 E16-T07 的映射接口前端接入（后续任务）。
 */
const route = useRoute();
const router = useRouter();
const userStore = useUserStore();
const { hasPermission } = usePermission();
const canEdit = computed(() => hasPermission('followup:record:edit'));
// E21-T11 / C-FU-026（Q17）：修改已提交的权限——开关开启（医院 C）必须
// followup:record:edit-submitted，关闭（A / B）followup:record:edit 即可；
// 删除随访记录两院同码 followup:record:delete（未分配隐藏，components.md §6）。
const { isEnabled } = useFeature();
const hasEditSubmitted = computed(() =>
  hasPermission('followup:record:edit-submitted'),
);
const canEditSubmitted = computed(() =>
  canEditSubmittedRecord({
    editSubmittedFeatureOn: isEnabled('record_edit_submitted'),
    hasEdit: canEdit.value,
    hasEditSubmitted: hasEditSubmitted.value,
  }),
);
const canDeleteRecord = computed(() => hasPermission('followup:record:delete'));
// E16-T02 / A-ATT-001：随访表单「上传」（pages.md §6.1 入口），预选本记录整条
const canUploadReport = computed(() => hasPermission('followup:scan:upload'));
const reportDialogOpen = ref(false);
// E16-T10 / A-ATT-015：手机扫码上传二维码弹窗（预选本记录位置）
const mobileUploadOpen = ref(false);
const reportPatientId = computed(
  () => context.value?.patientId ?? patient.value?.patientId ?? null,
);
const reportPreset = computed(() =>
  reportPatientId.value
    ? [
        {
          anchorTemplateVersion: schema.value?.version ?? 0,
          anchorType: null,
          linkRecordId: recordId.value,
          linkType: 'followup_record',
        },
      ]
    : [],
);
const { isDesktop, isMobile, isTouch } = useBreakpoint();
/** 手机底部固定操作栏距视口底部的偏移（键盘弹出时跟随可视区域，E24-T02）。 */
const keyboardSafeBottom = useKeyboardSafeBottom();

// ---------------------------------------------------------------- 状态

const recordId = computed(() => String(route.params.recordId ?? ''));
const loading = ref(true);
/**
 * 修改已提交记录（E21-T11 / C-FU-026）：ReasonModal「开始修改」→ edit-session →
 * 编辑态（横幅「修改中 · 保存后生成 v2」、底部按钮变「保存修改」）；保存走
 * PUT /records/{id}（reason 必填、字段级历史）；删除走 DELETE（软删除、填原因）。
 */
const editing = ref(false);
/** 修改原因（ReasonModal「开始修改」带入，PUT 保存修改随请求提交，OQ-14 必填）。 */
let editSessionReason = '';
const editReasonModalOpen = ref(false);
const deleteReasonModalOpen = ref(false);
const savingEdit = ref(false);
/** C-FU-008 自动带入（详情下发）：随访编号 / 随访序号 / 录入缺省（随访医生 = 当前用户；E21-T12 起随访方式缺省）。 */
const visitNo = ref<null | string>(null);
const sequenceNo = ref<null | number>(null);
const entryDefaults = ref<null | {
  doctorId: string;
  doctorName: string;
  visitMethod?: null | number;
}>(null);
const schema = ref<FormSchema | null>(null);
const data = ref<Record<string, unknown>>({});
const meta = ref<Record<string, unknown>>({});
/** 上次服务器版本的 data 快照（暂存差异与 dirty 判定的基准）。 */
const savedData = ref<Record<string, unknown>>({});
const version = ref(0);
const status = ref<'DRAFT' | 'SUBMITTED'>('DRAFT');
const context = ref<null | RecordTaskContext>(null);
/** 补录 / 计划外记录的患者摘要与来源（E15-T07：context 为 null 时的头幅与横幅）。 */
const patient = ref<null | RecordPatientBrief>(null);
const source = ref<null | string>(null);
const recordVisitDate = ref<null | string>(null);
/**
 * 器械上下文（E21-T03 / C-FU-009）：后端按记录随访日期匹配当时在用的器械下发，以
 * `{ device }` 传给 FormRenderer 的 context 驱动按器械条件显示（心房参数 / ICD 项目 /
 * AV 间期）；A / B 租户（implant 开关关闭）为 null，与后端提交清空同一上下文。
 */
const device = ref<null | RecordDeviceContext>(null);
/** 渲染器条件上下文（components.md §10.2 context prop：点路径 device.* 取值）。 */
const rendererContext = computed(() => rendererContextOf(device.value));
const progressItems = ref<CategoryProgress[]>([]);
/** 上一次记录（E17-T02 previous → E17-T03 渲染器对比标红与带入上次值）。 */
const previousRecord = ref<null | PreviousRecord>(null);
/** 分组标红数（E17-T03 highlight 事件 → CategoryBar ▲n）。 */
const highlightCountByKey = ref<Record<string, number>>({});
/** CategoryBar 条目：已填 / 应填 + 该组标红数（components.md §6 highlightCount）。 */
const categoryItems = computed<
  Array<CategoryProgress & { highlightCount?: number }>
>(() =>
  progressItems.value.map((item) => ({
    ...item,
    highlightCount: highlightCountByKey.value[item.key] ?? 0,
  })),
);
/**
 * 对比标红开关（E17-T03 / A-FU-027）：后端不下发规则（功能开关 compare_highlight 关闭或
 * 该表单无启用规则）即整体不标红（AGENTS.md 规则 7：开关关闭前端隐藏）。
 */
const highlightEnabled = computed(
  () => (schema.value?.compareRules?.length ?? 0) > 0,
);
const activeKey = ref('');
const showErrors = ref(false);
const submitting = ref(false);
const saving = ref(false);
const submitted = ref(false);
const queue = ref<{ nextId: null | string; prevId: null | string }>({
  nextId: null,
  prevId: null,
});
const renderer = ref<InstanceType<typeof FormRenderer> | null>(null);
/** 离开确认（dirty 上一条 / 下一条）：暂存并离开 / 放弃修改 / 取消（pages.md §5.1）。 */
const leaveConfirm = ref<{ open: boolean; taskId: null | string }>({
  open: false,
  taskId: null,
});

// ------------------------------------------- 连续补录（E21-T14 / C-FU-015）

/**
 * 提交成功面板的「继续补录下一次」：补录记录（source=BACKFILL）＋有录入权限＋能定位
 * 患者（taskless 患者摘要）＋implant 开关（医院 C；A / B 保持 E15-T07 原貌）。点击打开
 * 补录弹窗（continuous 形态）填下一份资料的日期，创建后<b>同页切换</b>到新记录（不反复
 * 打开表单，C-FU-015 验收）；新记录保留患者、器械按新日期匹配、测量值清空（新草稿），
 * 建议时间点已种入（C-FU-014）。提交成功后按钮自动聚焦：Tab 逐项录入 → Ctrl/⌘+Enter
 * 提交 → Enter 继续下一次（键盘连续补录）。
 */
const continueBackfillOpen = ref(false);
const backfillPatientId = computed(
  () => patient.value?.patientId ?? context.value?.patientId ?? null,
);
const showContinueBackfill = computed(() =>
  canContinueBackfill({
    canEdit: canEdit.value,
    implantEnabled: isEnabled('implant'),
    patientId: backfillPatientId.value,
    source: source.value,
  }),
);
const continueButton = ref<InstanceType<typeof Button> | null>(null);

/** 继续补录创建成功：同页切换到新记录（去掉 compare / transcribe 参数——新记录不对照旧报告单）。 */
async function handleContinueCreated(newRecordId: string) {
  const query = { ...route.query };
  delete query[COMPARE_QUERY_KEY];
  delete query[TRANSCRIBE_QUERY_KEY];
  await router.replace({ path: `/followup/record/${newRecordId}`, query });
  submitted.value = false;
  showErrors.value = false;
  await load();
}

const filter = queueFilterOfQuery(route.query);
// 已提交记录整体只读；编辑态（edit-session 进入）放开工单（C-FU-026）
const readonly = computed(
  () => !canEdit.value || (status.value === 'SUBMITTED' && !editing.value),
);
// bannersOf(context, visitDate, today?, source?)——today 走默认时钟，别把
// source 顶进 today 形参位（E24-T09 走查 W-0 连带修复：此前 `today is not a
// function` 使横幅 computed 崩掉，逾期 / 窗口外横幅不显示）
const banners = computed(() => {
  if (editing.value) {
    return [
      {
        key: 'editing',
        text: '修改中 · 保存后生成 v2',
        tone: 'warning',
      } as const,
      ...bannersOf(context.value, visitDate(), undefined, source.value),
    ];
  }
  return readonly.value
    ? [
        readonlyBanner(status.value),
        ...bannersOf(context.value, visitDate(), undefined, source.value),
      ]
    : bannersOf(context.value, visitDate(), undefined, source.value);
});
const dirty = computed(() => hasChanges(savedData.value, data.value));
/** C 头幅自动带入行（C-FU-008）：各段缺省整段省略、全空不渲染。 */
const entrySummary = computed(() =>
  entrySummaryText({
    device: device.value,
    sequenceNo: sequenceNo.value,
    visitNo: visitNo.value,
  }),
);

/**
 * 「下次随访时间」字段的录入辅助（E21-T08 / C-FU-005，Q05）：C 程控表单（f333）专用，
 * A / B 模板无该字段编码 → null 整体不生效。基准 = 本次随访日期（表单 ?? 记录；缺省由
 * 渲染器回落今天）；窗口预览按当前任务节点的窗口宽度围绕已填日期展开；补录提示「只保存」。
 */
const hasNextVisitField = computed(
  () => nextVisitFieldKey(schema.value) !== null,
);

const nextVisitAffordance = computed<NextVisitAffordance | null>(() => {
  const key = nextVisitFieldKey(schema.value);
  if (!key || readonly.value) return null;
  return {
    baseDate: visitDate() ?? recordVisitDate.value ?? null,
    note: nextVisitNote(source.value),
    windowPreview: nextVisitWindowPreviewOf(data.value[key], context.value),
  };
});

/** 提交 / 保存修改前的「下次随访时间」校验（pages.md §5.1 照抄）；空 = 通过。 */
function nextVisitBlocked(): string {
  const key = nextVisitFieldKey(schema.value);
  return key ? nextVisitIssueText(data.value[key], effectiveVisitDate()) : '';
}

// ---------------------------------------------------------------- 对照报告单分屏（E16-T11 / A-ATT-019、C-FU-017）

/**
 * ?compare=<报告单 id>（患者详情报告单页签「对照录入」带入）：一侧表单、一侧
 * 报告单（pages.md §5 线框右栏）；平板 / 手机不分屏、改「对照报告单」按钮
 * 全屏切换（§5.1）。关闭对照 = 移除该参数（上一条 / 下一条切换任务不带 compare）。
 */
const compareReportId = computed(() => compareReportIdOf(route.query));
const compareReport = ref<CompareReportPaneReport | null>(null);
/** 平板 / 手机（与桌面分屏的「全屏查看」）走 E16-T06 全屏查看器。 */
const compareViewerOpen = ref(false);
const watermarkViewer = useWatermarkViewer();
const canDownloadFile = computed(() => hasPermission('followup:scan:download'));
const compareViewerFiles = computed(() =>
  compareReport.value?.firstFile
    ? [
        {
          fileId: compareReport.value.firstFile.fileId,
          format: compareReport.value.firstFile.format,
          name: compareReport.value.name ?? compareReport.value.typeName,
        },
      ]
    : [],
);

/** 记录加载出患者后取对照报告单元信息（清单接口行含首附件与名称 / 类型）。 */
async function loadCompareReport() {
  compareReport.value = null;
  const reportId = compareReportId.value;
  if (!reportId) return;
  const patientId =
    context.value?.patientId ?? patient.value?.patientId ?? null;
  if (!patientId) return;
  try {
    const page = await fetchPatientReports(patientId, 'all');
    const row = page.items.find((item) => item.id === reportId);
    if (!row) {
      message.info('对照报告单不存在或已删除');
      return;
    }
    compareReport.value = {
      fileCount: row.fileCount,
      firstFile: row.firstFile,
      id: row.id,
      name: row.name,
      reportDate: row.reportDate,
      typeName: row.typeName,
    };
  } catch {
    message.error('对照报告单加载失败，请稍后重试');
  }
}

/**
 * 转录态（E21-T17 / C-SCAN-012：?compare= 报告单 id &transcribe=1）：提交 / 保存修改
 * 带 transcribeReportId，同一事务把报告单自动加入该记录位置并置「已电子化」；普通
 * 对照录入（只带 compare）不改电子化状态（如对照心脏超声报告录随访）。
 */
const transcribing = computed(() => transcribeActiveOf(route.query));

watch(compareReportId, () => void loadCompareReport());

/** 关闭对照：仅移除 compare 参数（表单数据与队列筛选不受影响）。 */
function closeCompare() {
  const query = { ...route.query };
  delete query[COMPARE_QUERY_KEY];
  void router.replace({ query });
}

async function handleCompareDownload(fileId: number | string) {
  const ok = await downloadFileById(fileId);
  if (!ok) message.error('下载失败，请稍后重试');
}

// ---------------------------------------------------------------- 自动保存（E25-T02 / NFR-SAVE-001/002/003）

/** 本地草稿键：`${tenantId}:${userId}:${kind}:${id}`（design-system §13；租户取会话 tenantCode）。 */
const recordKey = computed(() =>
  draftRecordKey(
    userStore.userInfo?.tenantCode,
    userStore.userInfo?.userId,
    recordId.value,
  ),
);

/** 40900 响应 → DraftConflictError（useAutoSave 冲突流程），其余错误原样抛出。 */
async function saveDraftToServer(body: {
  baseVersion: number;
  changes: Record<string, unknown>;
  overwrite?: boolean;
}) {
  try {
    return await saveRecordDraft(recordId.value, body);
  } catch (error) {
    const detail = asDraftConflictDetail(error);
    if (detail) throw new DraftConflictError(detail);
    throw error;
  }
}

const autosave = useAutoSave({
  // 编辑已提交记录不走草稿自动保存（PUT /draft 对 SUBMITTED 40901）——保存统一走「保存修改」
  enabled: computed(
    () => !readonly.value && !submitted.value && !editing.value,
  ),
  getBaseVersion: () => version.value,
  getChanges: () => diffChanges(savedData.value, data.value),
  getSnapshot: () => ({
    baseVersion: version.value,
    data: { ...data.value },
    meta: { ...meta.value },
  }),
  isDirty: dirty,
  kind: 'followup',
  async onSaved(_result, trigger: AutoSaveTrigger) {
    version.value = _result.version;
    savedData.value = { ...data.value };
    if (trigger === 'manual') {
      message.success('已暂存到服务器（草稿），任务变为进行中');
    }
    if (trigger === 'keepMine') {
      message.success('已用你的版本覆盖服务器草稿，覆盖记入操作日志');
    }
  },
  recordKey,
  async restoreServer() {
    await load();
  },
  saveDraft: saveDraftToServer,
});

/** 冲突处理中标志（ConflictBanner 按钮 loading）。 */
const resolving = ref<'keepMine' | 'loadServer' | null>(null);

async function handleKeepMine() {
  if (resolving.value) return;
  resolving.value = 'keepMine';
  try {
    await autosave.keepMine();
  } finally {
    resolving.value = null;
  }
}

async function handleLoadServer() {
  if (resolving.value) return;
  resolving.value = 'loadServer';
  try {
    await autosave.loadServer();
    message.info('已载入服务器最新草稿；你的改动另存一份本地副本，可对照');
  } finally {
    resolving.value = null;
  }
}

/** 提交闸门：离线 / 冲突中不能提交（pages.md §5.1 文案照抄）。 */
const submitBlocked = computed(
  () => autosave.status.value === 'offline' || autosave.conflict.value !== null,
);
const submitBlockedTooltip = computed(() =>
  autosave.conflict.value
    ? '请先处理版本冲突再提交'
    : '离线时不能提交：改动已存本地草稿，恢复网络后再提交',
);

/** 随访日期（窗口外横幅判定用；字段编码 followup_date，与后端口径一致）。 */
function visitDate(): null | string {
  if (!schema.value) return null;
  for (const category of schema.value.categories) {
    for (const field of category.fields) {
      if (field.code === 'followup_date') {
        const value = data.value[field.key];
        return typeof value === 'string' ? value : null;
      }
    }
  }
  return null;
}

const deathSelected = computed(() => {
  const key = deathFieldKey(schema.value);
  if (!key) return false;
  return data.value[key] === 1 || data.value[key] === '1';
});

// ---------------------------------------------------------------- 加载

async function load() {
  loading.value = true;
  editing.value = false;
  try {
    const detail = await fetchRecordDetail(recordId.value);
    // E17-T03：对比规则由记录详情下发（followup 域 fu_compare_rule），并入渲染器模板契约
    // 的 schema.compareRules（components.md §10.1）；previous 同源（E17-T02）。
    const fetched = await fetchFormSchema(detail.formCode, detail.formVersion);
    schema.value = { ...fetched, compareRules: detail.compareRules ?? null };
    previousRecord.value = detail.previous
      ? {
          date: detail.previous.date ?? null,
          kind: detail.previous.kind,
          recordId: detail.previous.recordId ?? null,
          values: detail.previous.values,
        }
      : null;
    highlightCountByKey.value = {};
    data.value = { ...detail.data };
    savedData.value = { ...detail.data };
    meta.value = { ...detail.dataMeta };
    version.value = detail.version;
    status.value = detail.status;
    context.value = detail.context ?? null;
    patient.value = detail.patient ?? null;
    source.value = detail.source ?? null;
    recordVisitDate.value = detail.visitDate ?? null;
    device.value = detail.device ?? null;
    visitNo.value = detail.visitNo ?? null;
    sequenceNo.value = detail.sequenceNo ?? null;
    entryDefaults.value = detail.entryDefaults ?? null;
    // C-FU-008 自动带入：随访医生默认当前用户；E21-T12 起随访方式缺省（REMOTE「远程监测」/ 患者默认，
    // 模板含对应字段且当前无值时写入）
    if (detail.status === 'DRAFT' && detail.entryDefaults) {
      const prefilled = applyEntryDefaults(
        schema.value,
        data.value,
        detail.entryDefaults,
      );
      data.value = prefilled;
    }
    // ?edit=1（患者详情时间轴「修改」带入）：已提交且有权限直接弹「修改原因」（E21-T11）
    if (
      route.query.edit === '1' &&
      detail.status === 'SUBMITTED' &&
      canEditSubmitted.value
    ) {
      editReasonModalOpen.value = true;
      const query = { ...route.query };
      delete query.edit;
      void router.replace({ query });
    }
    if (detail.context) {
      const queueView = await fetchTaskQueue(detail.context.taskId, {
        scope: filter.scope,
        status: filter.status,
        wardIds: filter.wardIds,
      });
      queue.value = {
        nextId: queueView.nextId ?? null,
        prevId: queueView.prevId ?? null,
      };
    }
    await offerLocalDraftRestore();
    // E16-T11：?compare= 报告单分屏（患者确定后才能取患者内清单）
    await loadCompareReport();
  } catch {
    message.error('随访记录加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

/** 对比标红命中（E17-T03 / A-FU-027）：按分组计数并入 CategoryBar（▲n，design-system §14）。 */
function onHighlight(hits: CompareHit[]): void {
  const count: Record<string, number> = {};
  for (const hit of hits) {
    count[hit.categoryKey] = (count[hit.categoryKey] ?? 0) + 1;
  }
  highlightCountByKey.value = count;
}

/** 打开时如有本地草稿（上次断网 / 未同步），提示「发现未提交的草稿，是否恢复」。 */
async function offerLocalDraftRestore() {
  if (readonly.value || !(await autosave.hasLocalDraft())) return;
  Modal.confirm({
    title: '发现未提交的草稿',
    content: '本机有一份未同步到服务器的草稿，是否恢复？',
    okText: '恢复草稿',
    cancelText: '不恢复',
    onOk: async () => {
      const draft = await autosave.restoreLocalDraft();
      if (!draft) return;
      data.value = { ...draft.data };
      meta.value = { ...draft.meta };
      message.success('已恢复本机草稿，改动会在下次保存时同步');
    },
  });
}

onMounted(() => {
  void load();
  window.addEventListener('keydown', handleKeydown);
  window.addEventListener('beforeunload', handleBeforeUnload);
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleKeydown);
  window.removeEventListener('beforeunload', handleBeforeUnload);
});

/** 有改动时刷新 / 关标签页提醒（浏览器原生确认；本应用内离开走 leaveConfirm 弹窗）。 */
function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value || submitted.value) return;
  event.preventDefault();
  event.returnValue = '';
}

// ---------------------------------------------------------------- 暂存与提交

async function handleSaveDraft() {
  if (readonly.value || saving.value) return false;
  if (!dirty.value) {
    message.info('没有需要暂存的修改');
    return true;
  }
  saving.value = true;
  try {
    // 409 由 useAutoSave 进入冲突流程（不再当作普通失败提示）
    return await autosave.saveNow();
  } catch (error) {
    message.error('暂存失败，请稍后重试');
    console.error(error);
    return false;
  } finally {
    saving.value = false;
  }
}

async function handleSubmit() {
  if (readonly.value || submitting.value || submitted.value) return;
  if (submitBlocked.value) {
    message.warning(submitBlockedTooltip.value);
    return;
  }
  const result: RendererValidateResult | undefined =
    await renderer.value?.validate();
  if (result && !result.ok) {
    showErrors.value = true;
    message.warning(missingRequiredText(result.missingRequired.length));
    renderer.value?.focusFirstError();
    return;
  }
  // C-FU-005（pages.md §5.1 照抄）：下次随访时间不能早于或等于本次随访日期、不能早于今天
  const nextVisitIssue = nextVisitBlocked();
  if (nextVisitIssue) {
    message.warning(nextVisitIssue);
    return;
  }
  // C-FU-024 重复记录提示：同一患者同一随访日期已有记录时先确认「确定继续？」再提交
  const duplicate = await checkDuplicateOfCurrent();
  if (duplicate) {
    Modal.confirm({
      title: '重复记录提示',
      content: duplicateRecordConfirmText(effectiveVisitDate() ?? ''),
      okText: '继续保存',
      cancelText: '取消',
      onOk: () => doSubmit(),
    });
    return;
  }
  Modal.confirm({
    title: '确认提交',
    content: submitConfirmText(context.value !== null, source.value),
    okText: '确认提交',
    cancelText: '取消',
    onOk: () => doSubmit(),
  });
}

/** 本次记录的有效随访日期（重复提示口径：表单随访日期 ?? 记录 visit_date ?? 任务计划日期）。 */
function effectiveVisitDate(): null | string {
  return (
    visitDate() ?? recordVisitDate.value ?? context.value?.planDate ?? null
  );
}

/** 重复记录检查（C-FU-024）：接口失败不拦截提交（后端保存复算与提示各自兜底）。 */
async function checkDuplicateOfCurrent(): Promise<boolean> {
  const patientId =
    context.value?.patientId ?? patient.value?.patientId ?? null;
  const date = effectiveVisitDate();
  if (!patientId || !date) return false;
  try {
    const view = await checkRecordDuplicate(patientId, date, recordId.value);
    return view.duplicate;
  } catch {
    return false;
  }
}

async function doSubmit() {
  submitting.value = true;
  try {
    const result = await submitRecord(recordId.value, {
      baseVersion: version.value,
      data: data.value,
      dataMeta: meta.value,
      // 转录态提交（E21-T17）：同事务完成转录（报告单自动置「已电子化」）
      transcribeReportId: transcribing.value
        ? (compareReportId.value ?? undefined)
        : undefined,
    });
    submitted.value = true;
    status.value = 'SUBMITTED';
    // 服务器已收到全量数据：丢弃本机草稿，重开不再提示恢复
    void autosave.discardLocalDraft();
    message.success(
      submitSuccessMessage(
        deathSelected.value,
        context.value !== null,
        source.value,
      ) + transcribeAppendedText(result.transcribedReportId),
    );
    // 连续补录（C-FU-015）：提交成功后聚焦「继续补录下一次」——Enter 即可开始下一条
    // （键盘逐项录入：Tab 跳项、Ctrl/⌘+Enter 提交、Enter 继续）
    if (showContinueBackfill.value) {
      void nextTick(() => continueButton.value?.$el?.focus());
    }
  } catch (error) {
    message.error('提交失败，请稍后重试');
    console.error(error);
  } finally {
    submitting.value = false;
  }
}

// ----------------------------------------------- 修改 / 删除已提交（E21-T11 / C-FU-026）

/** 开始修改（ReasonModal「修改原因」必填 → edit-session → 编辑态，pages.md §5.1）。 */
async function handleStartEdit(reason: string) {
  try {
    const view = await startRecordEditSession(recordId.value, { reason });
    version.value = view.version;
    savedData.value = { ...data.value };
    editing.value = true;
    editReasonModalOpen.value = false;
    message.info('进入修改：保存后生成 v2，字段级历史记下原因');
  } catch (error) {
    message.error('开始修改失败，请稍后重试');
    console.error(error);
  }
}

/** 保存修改（PUT /records/{id}，reason 必填；成功提示新版本号）。 */
async function handleSaveEdit() {
  if (!editing.value || savingEdit.value) return;
  const result: RendererValidateResult | undefined =
    await renderer.value?.validate();
  if (result && !result.ok) {
    showErrors.value = true;
    message.warning(missingRequiredText(result.missingRequired.length));
    renderer.value?.focusFirstError();
    return;
  }
  // 修改同样覆盖 doctor_next_date（E21-T06 / SW-10）：校验口径与提交一致（C-FU-005）
  const editNextVisitIssue = nextVisitBlocked();
  if (editNextVisitIssue) {
    message.warning(editNextVisitIssue);
    return;
  }
  if (savedData.value && !hasChanges(savedData.value, data.value)) {
    message.info('没有需要保存的修改');
    return;
  }
  Modal.confirm({
    title: '保存修改',
    content: '保存后生成新版本，字段级历史将记下本次修改的原因。',
    okText: '保存修改',
    cancelText: '取消',
    onOk: () => doSaveEdit(),
  });
}

async function doSaveEdit() {
  savingEdit.value = true;
  try {
    const result = await updateSubmittedRecord(recordId.value, {
      baseVersion: version.value,
      data: data.value,
      dataMeta: meta.value,
      reason: editSessionReason,
      // 转录态保存修改（E21-T17）：位置中已有已提交记录的对照录入同样完成转录
      transcribeReportId: transcribing.value
        ? (compareReportId.value ?? undefined)
        : undefined,
    });
    version.value = result.version;
    savedData.value = { ...data.value };
    editing.value = false;
    message.success(
      `已保存修改（v${result.version}），字段级历史已记录（${result.fieldChanges} 项）${transcribeAppendedText(
        result.transcribedReportId,
      )}`,
    );
  } catch (error) {
    message.error('保存修改失败，请稍后重试');
    console.error(error);
  } finally {
    savingEdit.value = false;
  }
}

/** 取消修改：放弃本地改动回到只读（pages.md §5.1 底部按钮「取消」）。 */
function handleCancelEdit() {
  if (hasChanges(savedData.value, data.value)) {
    Modal.confirm({
      title: '放弃修改？',
      content: '未保存的修改将丢失，记录回到服务器版本。',
      okText: '放弃修改',
      cancelText: '继续编辑',
      onOk: () => void load(),
    });
    return;
  }
  void load();
}

/** 删除记录（ReasonModal 删除原因必填 → DELETE 软删除 → 返回任务列表）。 */
async function handleDelete(reason: string) {
  try {
    await deleteRecord(recordId.value, { reason });
    deleteReasonModalOpen.value = false;
    message.success('已删除随访记录（软删除，原因已记入审计）');
    void router.push('/followup/task');
  } catch (error) {
    message.error('删除失败，请稍后重试');
    console.error(error);
  }
}

// ---------------------------------------------------------------- 键盘（Enter 不提交；Ctrl/⌘+S 暂存；Ctrl/⌘+Enter 提交）

function handleKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault();
    void handleSaveDraft();
    return;
  }
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault();
    void handleSubmit();
  }
}

// ---------------------------------------------------------------- 上一条 / 下一条（顺当前列表）

function gotoNeighbor(taskId: null | string) {
  if (!taskId) {
    message.info('已经到头了');
    return;
  }
  if (hasChanges(savedData.value, data.value) && !submitted.value) {
    leaveConfirm.value = { open: true, taskId };
    return;
  }
  void openTask(taskId);
}

async function confirmLeaveSave() {
  const taskId = leaveConfirm.value.taskId;
  leaveConfirm.value = { open: false, taskId: null };
  if (!taskId) return;
  const saved = await handleSaveDraft();
  if (saved) {
    await openTask(taskId);
  }
}

async function confirmLeaveDiscard() {
  const taskId = leaveConfirm.value.taskId;
  leaveConfirm.value = { open: false, taskId: null };
  if (taskId) {
    await openTask(taskId);
  }
}

/** 打开相邻任务：未开始的先 start 换 recordId，再本页切换（同一标签页继续录入）。 */
async function openTask(taskId: string) {
  try {
    const detail = await startTask(taskId);
    const query = new URLSearchParams();
    query.set('scope', filter.scope);
    if (filter.status) query.set('status', filter.status.join(','));
    if (filter.wardIds) query.set('wardIds', filter.wardIds.join(','));
    await router.replace(
      `/followup/record/${detail.recordId}?${query.toString()}`,
    );
    submitted.value = false;
    showErrors.value = false;
    await load();
  } catch {
    message.error('打开任务失败，请稍后重试');
  }
}

function backToList() {
  void router.push('/followup/task');
}

// ---------------------------------------------------------------- 分类锚点

function handleJump(key: string) {
  activeKey.value = key;
  renderer.value?.scrollTo(key);
}
</script>

<template>
  <Result
    v-if="submitted"
    data-testid="record-submitted"
    status="success"
    :sub-title="submitSuccessMessage(deathSelected, context !== null, source)"
    title="已提交"
  >
    <template #extra>
      <Space>
        <Button @click="backToList">返回任务列表</Button>
        <!-- E21-T14 / C-FU-015 连续补录：提交成功面板提供「继续补录下一次」（同页切换，
             不需反复打开表单；提交后自动聚焦，Enter 即可继续） -->
        <Button
          v-if="showContinueBackfill"
          ref="continueButton"
          data-testid="record-continue-backfill"
          type="primary"
          @click="continueBackfillOpen = true"
        >
          继续补录下一次
        </Button>
        <Button
          v-if="queue.nextId"
          :type="showContinueBackfill ? 'default' : 'primary'"
          @click="gotoNeighbor(queue.nextId)"
        >
          下一条
        </Button>
      </Space>
    </template>
  </Result>
  <div
    v-else
    class="page"
    :class="{ 'page-mobile': isMobile }"
    data-testid="record-form"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <!-- 手机顶栏左侧为「返回」（pages.md §5.2） -->
        <Button
          v-if="isMobile"
          class="back-button"
          data-testid="record-back"
          @click="backToList"
        >
          ← 返回
        </Button>
        <div>
          <h1 class="crdp-page-title">
            {{ headerTitle(context, patient, source) }}
          </h1>
          <div class="sub-text">
            {{ headerSummary(context, recordVisitDate, source) }}
          </div>
          <!-- C-FU-008 自动带入行：随访编号 · 第 N 次 · 当前器械 · 植入时长（A / B 无值不渲染） -->
          <div
            v-if="entrySummary"
            class="sub-text"
            data-testid="record-entry-summary"
          >
            {{ entrySummary }}
          </div>
        </div>
        <!-- 平板 / 手机：对照报告单改为全屏切换（pages.md §5.1；桌面走右侧分屏） -->
        <Button
          v-if="!isDesktop && compareReport"
          data-testid="record-compare-open"
          size="small"
          @click="compareViewerOpen = true"
        >
          对照报告单
        </Button>
        <StatusTag
          v-if="context"
          :status="
            taskDisplayStatus({
              completeKind: context.completeKind,
              createType: context.createType,
              status: context.taskStatus,
              taskType: context.taskType,
            })
          "
          variant="dot"
        />
      </div>

      <FormStatusBar
        v-if="!readonly"
        :last-saved-at="autosave.lastSavedAt.value"
        :status="autosave.status.value"
      />

      <ConflictBanner
        v-if="autosave.conflict.value"
        :conflict="autosave.conflict.value"
        :pending="resolving"
        @keep-mine="handleKeepMine"
        @load-server="handleLoadServer"
      />

      <Alert
        v-for="banner in banners"
        :key="banner.key"
        :message="banner.text"
        :type="banner.tone"
        show-icon
        :style="{ marginBottom: '8px' }"
      />

      <Spin :spinning="loading">
        <div v-if="schema" class="workbench" :class="{ stacked: !isDesktop }">
          <!-- 桌面：左侧分组锚点列；平板 / 手机收起左列（分组条吸顶横向滚动 /
               「跳到分组」下拉 + 底部抽屉，pages.md §5.2） -->
          <div v-if="isDesktop" class="anchors">
            <CategoryBar
              :active-key="activeKey"
              :items="categoryItems"
              mode="bar"
              show-highlight-count
              @jump="handleJump"
            />
          </div>
          <div class="form-area">
            <CategoryBar
              v-if="!isDesktop"
              :active-key="activeKey"
              :items="categoryItems"
              :mode="isMobile ? 'select' : 'bar'"
              show-highlight-count
              @jump="handleJump"
            />
            <FormRenderer
              ref="renderer"
              v-model="data"
              :context="rendererContext"
              :highlight-enabled="highlightEnabled"
              :meta="meta"
              :mode="readonly ? 'readonly' : 'edit'"
              :next-visit="nextVisitAffordance"
              :previous="previousRecord"
              :schema="schema"
              :show-errors="showErrors"
              @field-blur="autosave.noteBlur()"
              @highlight="onHighlight"
              @progress="progressItems = $event"
            />
            <!-- A / B：只读行（按计划自动生成）；C（模板含下次随访时间字段）由表单字段 +
                 间隔快捷 / 窗口预览承载（E21-T08 / C-FU-005），不再重复只读行 -->
            <div
              v-if="!hasNextVisitField && nextVisitText(context)"
              class="next-visit"
            >
              {{ nextVisitText(context) }}
            </div>
          </div>
          <!-- E16-T11 / A-ATT-019：右侧对照报告单分屏（仅桌面；平板 / 手机用顶部
               「对照报告单」按钮全屏切换） -->
          <CompareReportPane
            v-if="isDesktop && compareReport"
            :can-download="canDownloadFile"
            :report="compareReport"
            :viewer="watermarkViewer"
            @close="closeCompare"
            @download="handleCompareDownload"
            @fullscreen="compareViewerOpen = true"
          />
        </div>
      </Spin>

      <!-- 手机改为底部固定操作栏（见 .mobile-footer）；平板 / 桌面底部栏同桌面 -->
      <div v-if="!isMobile" class="footer-bar">
        <Space>
          <Button :disabled="!queue.prevId" @click="gotoNeighbor(queue.prevId)">
            ← 上一条
          </Button>
          <Button :disabled="!queue.nextId" @click="gotoNeighbor(queue.nextId)">
            下一条 →
          </Button>
        </Space>
        <Space>
          <Button
            v-if="canUploadReport && reportPatientId"
            data-testid="record-upload-report"
            @click="reportDialogOpen = true"
          >
            上传报告单
          </Button>
          <Button
            v-if="canUploadReport && reportPatientId"
            data-testid="record-mobile-upload"
            @click="mobileUploadOpen = true"
          >
            手机拍照
          </Button>
        </Space>
        <!-- 已提交（非编辑态）：修改（未分配权限也置灰显示，tooltip 说明）与删除（未分配隐藏），
             components.md §6 例外规则 / C-FU-026 -->
        <Space v-if="readonly && status === 'SUBMITTED'">
          <Tooltip
            :title="
              canEditSubmitted
                ? ''
                : '需要“修改已提交记录”权限（C Q17 / C-FU-026）'
            "
            :trigger="isTouch ? 'click' : 'hover'"
          >
            <Button
              :disabled="!canEditSubmitted"
              data-testid="record-edit-submitted"
              @click="editReasonModalOpen = true"
            >
              修改
            </Button>
          </Tooltip>
          <Button
            v-if="canDeleteRecord"
            danger
            data-testid="record-delete"
            @click="deleteReasonModalOpen = true"
          >
            删除
          </Button>
        </Space>
        <!-- 编辑已提交（edit-session 进入）：保存修改 / 取消（pages.md §5.1「底部按钮变为保存修改」） -->
        <Space v-else-if="editing">
          <Button
            :loading="savingEdit"
            data-testid="record-save-edit"
            type="primary"
            @click="handleSaveEdit"
          >
            保存修改
          </Button>
          <Button data-testid="record-cancel-edit" @click="handleCancelEdit">
            取消
          </Button>
        </Space>
        <Space v-else-if="!readonly">
          <Button
            :loading="saving"
            data-testid="record-save-draft"
            @click="handleSaveDraft"
          >
            暂存
          </Button>
          <Tooltip
            :title="submitBlocked ? submitBlockedTooltip : ''"
            :trigger="isTouch ? 'click' : 'hover'"
          >
            <Button
              :disabled="submitBlocked"
              :loading="submitting"
              data-testid="record-submit"
              type="primary"
              @click="handleSubmit"
            >
              提交
            </Button>
          </Tooltip>
        </Space>
      </div>
    </Card>

    <UploadReportDialog
      :open="reportDialogOpen"
      :patient-gender="null"
      :patient-id="reportPatientId ?? ''"
      :preset="reportPreset"
      @update:open="reportDialogOpen = $event"
    />

    <!-- E16-T10 / A-ATT-015：一次性二维码（15 分钟，预选本记录位置），
         手机提交后刷新（报告单页签在患者详情，此处仅提示） -->
    <MobileUploadQrModal
      :open="mobileUploadOpen"
      :patient-id="reportPatientId ?? ''"
      :preset="reportPreset"
      @created="() => message.success('手机端已上传成功，请在患者详情查看')"
      @update:open="mobileUploadOpen = $event"
    />

    <!-- E16-T11 / A-ATT-019：对照报告单全屏查看（缩放 / 旋转 / 翻转 / 水印，E16-T06） -->
    <WatermarkPreview
      :can-download="canDownloadFile"
      :files="compareViewerFiles"
      :open="compareViewerOpen"
      :report-id="compareReport?.id"
      :viewer="watermarkViewer"
      @download="handleCompareDownload"
      @update:open="compareViewerOpen = $event"
    />

    <!-- 手机底部固定操作栏：上一条 / 下一条、暂存、提交；随 visualViewport 跟随键盘 -->
    <div
      v-if="isMobile"
      class="mobile-footer"
      :style="{ bottom: `${keyboardSafeBottom}px` }"
      data-testid="mobile-footer"
    >
      <Button :disabled="!queue.prevId" @click="gotoNeighbor(queue.prevId)">
        ← 上一条
      </Button>
      <Button :disabled="!queue.nextId" @click="gotoNeighbor(queue.nextId)">
        下一条 →
      </Button>
      <template v-if="editing">
        <Button
          :loading="savingEdit"
          data-testid="record-save-edit"
          type="primary"
          @click="handleSaveEdit"
        >
          保存修改
        </Button>
        <Button data-testid="record-cancel-edit" @click="handleCancelEdit">
          取消
        </Button>
      </template>
      <template v-else-if="readonly && status === 'SUBMITTED'">
        <Button
          :disabled="!canEditSubmitted"
          data-testid="record-edit-submitted"
          @click="editReasonModalOpen = true"
        >
          修改
        </Button>
        <Button
          v-if="canDeleteRecord"
          danger
          data-testid="record-delete"
          @click="deleteReasonModalOpen = true"
        >
          删除
        </Button>
      </template>
      <template v-else-if="!readonly">
        <Button
          :loading="saving"
          data-testid="record-save-draft"
          @click="handleSaveDraft"
        >
          暂存
        </Button>
        <Tooltip
          :title="submitBlocked ? submitBlockedTooltip : ''"
          trigger="click"
        >
          <Button
            :disabled="submitBlocked"
            :loading="submitting"
            data-testid="record-submit"
            type="primary"
            @click="handleSubmit"
          >
            提交
          </Button>
        </Tooltip>
      </template>
    </div>

    <!-- E21-T11 / C-FU-026：修改已提交（修改原因必填，按钮「开始修改」）与删除（删除原因必填） -->
    <ReasonModal
      confirm-text="开始修改"
      label="修改已提交记录"
      note="进入修改：保存后生成 v2，字段级历史记下原因"
      :open="editReasonModalOpen"
      placeholder="必填，如：抄录错误"
      @confirm="handleStartEdit"
      @update:open="editReasonModalOpen = $event"
    />
    <ReasonModal
      confirm-text="删除"
      label="删除随访记录"
      note="删除为软删除：数据长期保留，原因记入审计；关联任务不重开（重录走补录 / 计划外入口）"
      :open="deleteReasonModalOpen"
      placeholder="必填，如：重复录入"
      @confirm="handleDelete"
      @update:open="deleteReasonModalOpen = $event"
    />

    <Modal
      v-model:open="leaveConfirm.open"
      :footer="null"
      title="有未保存的修改，离开前怎么处理？"
    >
      <Space style="justify-content: flex-end; width: 100%">
        <Button type="primary" @click="confirmLeaveSave">暂存并离开</Button>
        <Button danger @click="confirmLeaveDiscard">放弃修改</Button>
        <Button @click="leaveConfirm = { open: false, taskId: null }">
          取消
        </Button>
      </Space>
    </Modal>
  </div>

  <!-- E21-T14 / C-FU-015 连续补录：提交成功后「继续补录下一次」打开（同页切换到新记录） -->
  <BackfillRecordModal
    continuous
    :open="continueBackfillOpen"
    :patient-id="backfillPatientId ?? ''"
    @created="handleContinueCreated"
    @update:open="continueBackfillOpen = $event"
  />
</template>

<style scoped>
.page {
  min-height: 100%;
  padding: 16px;
}

/* 手机：底部固定操作栏不遮内容（40px 触控 + 留白 + 安全区） */
.page-mobile {
  padding-bottom: calc(76px + env(safe-area-inset-bottom));
}

.title-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}

.back-button {
  flex: none;
}

.sub-text {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.workbench {
  display: flex;
  gap: 16px;
}

/* 平板 / 手机：去掉左侧锚点列，分组导航收进表单区顶部（吸顶横向滚动 / 抽屉） */
.workbench.stacked {
  flex-direction: column;
  gap: 0;
}

.anchors {
  flex: 0 0 auto;
  width: 180px;
}

.form-area {
  flex: 1 1 auto;
  min-width: 0;
}

.next-visit {
  padding: 8px 12px;
  margin-top: 12px;
  color: rgb(0 0 0 / 65%);
  background: rgb(58 117 255 / 6%);
  border-radius: 6px;
}

.footer-bar {
  display: flex;
  justify-content: space-between;
  padding-top: 12px;
  margin-top: 16px;
  border-top: 1px solid rgb(0 0 0 / 6%);
}

/* 手机底部固定操作栏（pages.md §5.2）：上一条 / 下一条、暂存、提交；
   bottom 由 useKeyboardSafeBottom 内联控制（键盘弹出跟随可视区域） */
.mobile-footer {
  position: fixed;
  right: 0;
  left: 0;
  z-index: 20;
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 12px calc(8px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1px solid rgb(0 0 0 / 6%);
  box-shadow: 0 -2px 8px rgb(0 0 0 / 4%);
}

/* 手机按钮均分一行（只读时只渲染上一条 / 下一条，§5.1 只读角色） */
.mobile-footer :deep(.ant-btn) {
  flex: 1 1 0;
  min-width: 0;
}
</style>
