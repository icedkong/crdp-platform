<script lang="ts" setup>
import type {
  ContactLogRow,
  FormSchema,
  PatientBaseline,
  PatientContactVersion,
  PatientOverview,
  PatientStatusHistoryRow,
  PatientTimelineEntry,
  PatientWardHistoryItem,
  ReportLocationInput,
  ReportRow,
} from '@crdp/api';

import type { ReportLocation } from '#/reference/types';

import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  BASELINE_LOCKED_TOOLTIP,
  baselineLockBanner,
  baselineProgressText,
  baselineSectionStatusText,
  canEditBaseline,
  completenessText,
  contactMethodText,
  contactResultText,
  contactSlotText,
  contactSourceText,
  dischargeStatusText,
  genderText,
  latestFollowupText,
  nextVisitText,
  PATIENT_STATUS_TEXT,
  reportLocationText,
  sectionDisplaySchema,
  showBaselineLock,
  showBaselineUnlock,
  statusSourceText,
  survivalStatusText,
} from '@crdp/api';
import { FormRenderer } from '@crdp/form-engine';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Drawer,
  message,
  Modal,
  Result,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import { fetchPatientBaseline, setBaselineLock } from '#/api/core/baseline';
import { deleteContactLog, fetchPatientContacts } from '#/api/core/contact';
import { getFilePreviewUrl } from '#/api/core/file';
import { fetchFormSchema } from '#/api/core/form';
import {
  fetchPatientContactInfo,
  fetchPatientStatusHistory,
  fetchPatientSummary,
  fetchPatientTimeline,
  fetchPatientWardHistory,
} from '#/api/core/patient';
import { deleteReport, fetchPatientReports } from '#/api/core/report';
import { startTask } from '#/api/core/task';
import {
  categoryHash,
  jumpToAnchor,
  parseCategoryHash,
  parseRecordHash,
  recordHash,
} from '#/components/followup/anchor-nav';
import {
  itemsAtBaselineAnchor,
  thumbnailFileOf,
  toNavItems,
} from '#/components/followup/attachment-nav-logic';
import AttachmentNav from '#/components/followup/attachment-nav.vue';
import AudioPlayer from '#/components/followup/audio-player.vue';
import BackfillRecordModal from '#/components/followup/backfill-record-modal.vue';
import BatchUploadDialog from '#/components/followup/batch-upload-dialog.vue';
import CategoryBar from '#/components/followup/category-bar.vue';
import ContactLogModal from '#/components/followup/contact-log-modal.vue';
import { downloadFileById } from '#/components/followup/file-download';
import FollowupTimeline from '#/components/followup/followup-timeline.vue';
import InlineAttachmentStrip from '#/components/followup/inline-attachment-strip.vue';
import PatientHeader from '#/components/followup/patient-header.vue';
import ReasonModal from '#/components/followup/reason-modal.vue';
import {
  reportDeleteLabel,
  reportDeleteNote,
} from '#/components/followup/report-delete-logic';
import ReportLocationModal from '#/components/followup/report-location-modal.vue';
import {
  buildReportTree,
  findTreeNode,
  reportsForKey,
  treeFilterLabel,
} from '#/components/followup/report-tree-logic';
import ReportTree from '#/components/followup/report-tree.vue';
import TempTaskModal from '#/components/followup/temp-task-modal.vue';
import TransferWardModal from '#/components/followup/transfer-ward-modal.vue';
import UnplannedRecordModal from '#/components/followup/unplanned-record-modal.vue';
import UploadReportDialog from '#/components/followup/upload-report-dialog.vue';
import WatermarkPreview from '#/components/followup/watermark-preview.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';
import { useWatermarkViewer } from '#/reference/use-watermark-viewer';

defineOptions({ name: 'FollowupPatientDetail' });

/**
 * E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010：患者档案（pages.md §4）。
 * 概览 = 顶部 PatientHeader（OQ-21：规格「概览」页并入头部），显示基线（完整度 +
 * 登记实体列）、计划（下次随访窗口）、最近随访（关键指标）；「随访记录」页签为
 * 患者时间轴（出院 + 状态变更，随访记录节点随 E15）。
 * 姓名与编号只显示后端脱敏值；越范围 / 不存在的患者 40400 → 「患者不存在或无权
 * 查看」。报告单（E16）等页签随对应任务开放。
 *
 * E13-T02 / A-PAT-003、A-PAT-006：基线资料页签 = 模板分类的只读分节视图
 * （出院登记实体列在上方登记信息块；分节只渲染 JSON 字段）+ 分类进度 +
 * 「编辑」（新标签页整页，锁定置灰）与「锁定 / 解锁」（解锁需
 * followup:patient:lock 并填写原因，ReasonModal；锁定 / 解锁留痕见操作日志）。
 */
const route = useRoute();
const router = useRouter();
const { hasPermission } = usePermission();
// E16-T14：左栏布局形态（桌面左栏 / 平板与手机收为「附件（n）」抽屉入口）
const { isDesktop } = useBreakpoint();

const loading = ref(true);
const loadFailed = ref(false);
const overview = ref<null | PatientOverview>(null);
const timeline = ref<PatientTimelineEntry[]>([]);
// 初始页签可由 ?tab= 指定（E16-T05 全院报告单列表「点击进入该患者的报告单页签」；未知值回落基线资料）
const TAB_KEYS = ['baseline', 'contacts', 'followup', 'reports'] as const;
const initialTab = Array.isArray(route.query.tab)
  ? route.query.tab[0]
  : route.query.tab;
const tab = ref(
  initialTab && (TAB_KEYS as readonly string[]).includes(initialTab)
    ? initialTab
    : 'baseline',
);

// ---------------------------------------------------------------- E13-T02 基线分节与锁定

const baseline = ref<null | PatientBaseline>(null);
const baselineSchema = ref<FormSchema | null>(null);
const unlockOpen = ref(false);
const lockSubmitting = ref(false);

const baselineLocked = computed(() => baseline.value?.lock.locked ?? false);
const baselineLockBannerText = computed(() =>
  baselineLockBanner(baseline.value?.lock ?? null),
);

const contactsOpen = ref(false);
const contactsLoading = ref(false);
const contactVersions = ref<PatientContactVersion[]>([]);
const statusHistory = ref<PatientStatusHistoryRow[]>([]);
const wardHistory = ref<PatientWardHistoryItem[]>([]);

// E13-T08 / A-WARD-005：转病区（详情页入口，pages.md §6 页面 4）
const transferOpen = ref(false);

// E15-T07 / A-FU-014：历史补录弹窗（随访记录页签入口）
const backfillOpen = ref(false);

// E15-T06 / A-FU-013：临时添加任务弹窗（A-FU-023 时间轴「添加任务」）与
// C-FU-022 计划外随访弹窗（患者头部入口，components.md §4）
const tempTaskOpen = ref(false);
const unplannedOpen = ref(false);

const patientId = computed(() => String(route.params.id ?? ''));

/** 当前病区 id（E13-T08 随概览下发；转病区弹窗用它排除当前病区）。 */
const currentWardId = computed<null | number>(
  () => overview.value?.wardId ?? null,
);

async function reloadTimeline() {
  try {
    const result = await fetchPatientTimeline(patientId.value);
    timeline.value = result.items;
  } catch {
    // 时间轴刷新失败保留旧数据（详情页主体不受影响）
  }
}

onMounted(async () => {
  try {
    const [summary, timelineResult] = await Promise.all([
      fetchPatientSummary(patientId.value),
      fetchPatientTimeline(patientId.value),
    ]);
    overview.value = summary;
    timeline.value = timelineResult.items;
    void loadBaseline();
  } catch {
    // 越范围 / 不存在统一 40400（AGENTS.md §5.2，不暴露对象存在）
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
});

/** 基线全量视图（分节 + 进度 + 锁定态）；失败保留空态，不影响档案主体。 */
async function loadBaseline() {
  try {
    const view = await fetchPatientBaseline(patientId.value);
    baseline.value = view;
    baselineSchema.value = await fetchFormSchema(
      view.formCode,
      view.formVersion,
    );
  } catch {
    baseline.value = null;
    baselineSchema.value = null;
  }
}

/** 基线分节标题与展示模板（分节只渲染 JSON 字段，实体列在登记信息块）。 */
const baselineSectionViews = computed(() => {
  const view = baseline.value;
  const formSchema = baselineSchema.value;
  if (!view || !formSchema) return [];
  return view.sections
    .map((section) => {
      const schema = sectionDisplaySchema(formSchema, section.code);
      const progress = view.progress.find((item) => item.key === section.code);
      const label =
        formSchema.categories.find((category) => category.key === section.code)
          ?.label ?? section.code;
      return {
        code: section.code,
        data: section.data,
        hasDraft: section.hasDraft,
        label,
        progressText: progress
          ? baselineProgressText(progress.filled, progress.total)
          : baselineProgressText(0, 0),
        schema,
        statusText: baselineSectionStatusText(section.status),
        updatedAt: section.updatedAt,
        updatedBy: section.updatedBy,
      };
    })
    .filter((item) => item.schema !== null);
});

// ---------------------------------------------------------------- E13-T11 基线分类条（A-PAT-021）

/**
 * 顶部分类条（吸顶）：每个分类显示「已填 / 应填」（服务端按 A-PAT-019 口径随
 * 基线视图下发，与分节标题同源）；未填完的数字由 CategoryBar 标橙色。
 */
const baselineBarItems = computed(() =>
  baselineSectionViews.value.map((section) => {
    const progress = baseline.value?.progress.find(
      (item) => item.key === section.code,
    );
    return {
      // E16-T14：分类条 📎 计数 = 挂在该分类下的报告单数（A-PAT-020）
      attachmentCount: baselineAnchorItems(section.code).length,
      filled: progress?.filled ?? 0,
      key: section.code,
      label: section.label,
      total: progress?.total ?? 0,
    };
  }),
);

const baselineActiveKey = ref('');
let lastJumpedKey = '';

/**
 * 点击分类：滚动到该分类并高亮 2 秒，位置写入页面地址 `#cat=<key>`
 * （A-PAT-021 可分享、可后退；后退 / 分享链接由 watch(route.hash) 接住重放）。
 */
function jumpToBaselineCategory(key: string) {
  baselineActiveKey.value = key;
  lastJumpedKey = key;
  jumpToAnchor(key, document);
  if (route.hash !== categoryHash(key)) {
    void router.push({ hash: categoryHash(key) });
  }
}

// 分节懒加载完成后：分享 / 后退进来的 #cat=<key> 重放定位（只对有效分类生效）
watch(
  [baselineBarItems, () => route.hash],
  ([items, hash]) => {
    if (parseRecordHash(String(hash ?? '')).recordId) return; // 随访位置 hash 走 #rec 重放
    const key = parseCategoryHash(String(hash ?? ''));
    if (!key) return;
    if (!items.some((item) => item.key === key)) return;
    if (key === lastJumpedKey) return;
    baselineActiveKey.value = key;
    lastJumpedKey = key;
    jumpToAnchor(key, document);
  },
  { immediate: true },
);

// 随访记录页签：展开的记录（v-model，一次一条；分组条在该记录顶部吸顶）
const expandedRecordId = ref<null | string>(null);

/** 「编辑」入口（pages.md §4.2：锁定置灰；无编辑权限隐藏）。 */
const canEditPatient = computed(() => hasPermission('followup:patient:edit'));
const canLockBaseline = computed(() => hasPermission('followup:patient:lock'));
const editEnabled = computed(() =>
  canEditBaseline(baselineLocked.value, canEditPatient.value),
);
const unlockVisible = computed(() =>
  showBaselineUnlock(baselineLocked.value, canLockBaseline.value),
);
const lockVisible = computed(() =>
  showBaselineLock(baselineLocked.value, canLockBaseline.value),
);

function openBaselineEdit() {
  if (!editEnabled.value) return;
  window.open(`/followup/patient/${patientId.value}/baseline/edit`, '_blank');
}

/** 锁定（A-PAT-006：病区医生确认无误后锁定；锁定说明可选）。 */
function confirmBaselineLock() {
  Modal.confirm({
    content:
      '锁定后基线只读；要修改须持有「锁定/解锁基线」权限并填写原因解锁（A-PAT-006）。',
    okText: '确认锁定',
    cancelText: '取消',
    onOk: () => doSetBaselineLock(true, '出院 30 天内确认无误'),
    title: '锁定基线？',
  });
}

/** 解锁（ReasonModal：原因必填，pages.md §4.2）。 */
async function handleUnlock(reason: string) {
  await doSetBaselineLock(false, reason);
}

async function doSetBaselineLock(locked: boolean, reason: string) {
  if (lockSubmitting.value) return;
  lockSubmitting.value = true;
  try {
    await setBaselineLock(patientId.value, { locked, reason });
    message.success(
      locked ? '基线已锁定，分节只读' : '基线已解锁，可继续编辑（解锁已留痕）',
    );
    unlockOpen.value = false;
    await loadBaseline();
  } catch {
    // 提示由统一拦截器弹出（如「基线已是锁定状态」「解锁基线必须填写原因」）
  } finally {
    lockSubmitting.value = false;
  }
}

const dischargeStatusLabel = computed(() =>
  overview.value?.dischargeStatus === null ||
  overview.value?.dischargeStatus === undefined
    ? '—'
    : dischargeStatusText(overview.value.dischargeStatus),
);

async function openContacts() {
  contactsOpen.value = true;
  if (
    contactVersions.value.length > 0 ||
    statusHistory.value.length > 0 ||
    wardHistory.value.length > 0
  )
    return;
  contactsLoading.value = true;
  try {
    const [contactInfo, history, wardHist] = await Promise.all([
      fetchPatientContactInfo(patientId.value),
      fetchPatientStatusHistory(patientId.value),
      fetchPatientWardHistory(patientId.value),
    ]);
    contactVersions.value = contactInfo.items;
    statusHistory.value = history;
    wardHistory.value = wardHist.items;
  } catch {
    // 抽屉内失败保留空态；概览本体不受影响
    contactVersions.value = [];
    statusHistory.value = [];
    wardHistory.value = [];
  } finally {
    contactsLoading.value = false;
  }
}

/** 转病区成功（A-WARD-005）：原病区医生立即失去访问权——操作者本人可能已不可见该患者，统一返回列表。 */
function handleTransferred() {
  message.success('已转病区，正在返回患者列表');
  void router.replace('/followup/patient');
}

// ---------------------------------------------------------------- E16-T02 报告单页签（pages.md §4.6）

/** 上传入口权限（pages.md §6.1：添加报告单 / 页内「上传附件」都按 followup:scan:upload）。 */
const canUploadReport = computed(() => hasPermission('followup:scan:upload'));
const reports = ref<ReportRow[]>([]);
const reportsLoading = ref(false);
const reportDialogOpen = ref(false);
/** E16-T03 批量上传对话框（A-ATT-014 / C-SCAN-007；与添加报告单同权限）。 */
const batchDialogOpen = ref(false);
/** 页内上传预选位置（基线分类 / 随访记录整条；A-ATT-001「自动预选该位置」）。 */
const reportPreset = ref<ReportLocationInput[]>([]);

async function loadReports() {
  reportsLoading.value = true;
  try {
    const page = await fetchPatientReports(patientId.value, 'all');
    reports.value = page.items;
    void loadNavThumbnails(page.items);
  } catch {
    reports.value = [];
  } finally {
    reportsLoading.value = false;
  }
}

// 切到基线 / 随访 / 报告单页签时加载（左栏附件导航与内联缩略图共用一份 all 清单；
// 不随首屏拉取，上传 / 修改位置成功后也走这里刷新）
watch([tab, loading], ([current, isLoading]) => {
  if (
    (current === 'baseline' ||
      current === 'followup' ||
      current === 'reports') &&
    !isLoading &&
    overview.value
  ) {
    void loadReports();
  }
});

/** 报告单页签 / 基线分类 / 随访展开记录的添加入口；preset 缺省 = 报告单页签（不预选）。 */
function openReportUpload(preset?: ReportLocationInput[]) {
  reportPreset.value = preset ?? [];
  reportDialogOpen.value = true;
}

/** E16-T03 批量上传入口（pages.md §4.6 / §6.3；沿用当前预选位置）。 */
function openBatchUpload() {
  batchDialogOpen.value = true;
}

/** 基线分类末尾「上传附件」：预选 {baseline / category / 分类 key / 基线表单版本}。 */
function baselineUploadPreset(sectionCode: string): ReportLocationInput[] {
  return [
    {
      anchorKey: sectionCode,
      anchorTemplateVersion: baseline.value?.formVersion ?? 0,
      anchorType: 'category',
      linkType: 'baseline',
    },
  ];
}

/** 随访页签「上传附件」：展开的记录预选整条（anchorType=null）。 */
const followupUploadPreset = computed<ReportLocationInput[] | undefined>(() =>
  expandedRecordId.value
    ? [
        {
          anchorTemplateVersion: 0,
          anchorType: null,
          linkRecordId: expandedRecordId.value,
          linkType: 'followup_record',
        },
      ]
    : undefined,
);

function handleReportCreated() {
  void loadReports();
}

// ------------------------------ E16-T14 左栏附件导航与各位置内联缩略图（A-PAT-020 / C-PAT-017）

/** 图片首附件 → 预览短时链接（≤ 5 分钟、写审计；懒加载、不跨会话缓存）。 */
const thumbnailUrls = ref<Record<string, string>>({});

async function loadNavThumbnails(rows: ReportRow[]) {
  const targets = rows
    .map((row) => ({ fileId: thumbnailFileOf(row)?.fileId, row }))
    .filter(
      (item): item is { fileId: string; row: ReportRow } =>
        item.fileId !== undefined && !thumbnailUrls.value[item.fileId],
    );
  await Promise.all(
    targets.map(async ({ fileId }) => {
      try {
        const grant = await getFilePreviewUrl(fileId);
        thumbnailUrls.value = { ...thumbnailUrls.value, [fileId]: grant.url };
      } catch {
        // 换不到链接时降级为文件类型图标（不阻塞导航）
      }
    }),
  );
}

/** 附件导航条目（缩略图 URL 就绪后回填；无位置条目进两侧「未指定位置」组）。 */
const navItems = computed(() =>
  toNavItems(reports.value).map((item) => {
    const row = reports.value.find((report) => report.id === item.reportId);
    const fileId = row ? thumbnailFileOf(row)?.fileId : undefined;
    return fileId && thumbnailUrls.value[fileId]
      ? { ...item, thumbnailUrl: thumbnailUrls.value[fileId] }
      : item;
  }),
);

/** 基线某分类末尾的附件（内联缩略图条 + 分类条 📎 计数同源）。 */
function baselineAnchorItems(anchorKey: string) {
  return itemsAtBaselineAnchor(navItems.value, anchorKey);
}

// 左栏位置标签跳转：基线滚到分类锚点（分类条同步高亮、写 #cat=<key>）；随访经
// FollowupTimeline.locateInRecord 展开该记录并滚到分组锚点 / 记录顶部（写
// #rec=<id>&cat=<key>；A-PAT-020「点标签跳到该位置」，抽屉模式组件内先关抽屉）。
function handleNavLocate(location: ReportLocation) {
  if (location.linkType === 'followup_record' && location.linkRecordId) {
    void locateFollowup(location.linkRecordId, location.anchorKey);
    return;
  }
  if (location.anchorKey) jumpToBaselineCategory(location.anchorKey);
}

/** 随访时间轴模板 ref（locateInRecord 展开记录并滚动定位）。 */
const timelineRef = ref<null | {
  locateInRecord: (id: string, key?: string) => void;
}>(null);

let lastFollowupHash = '';

async function locateFollowup(recordId: string, anchorKey?: string) {
  if (tab.value !== 'followup') tab.value = 'followup';
  lastFollowupHash = recordHash(recordId, anchorKey);
  if (route.hash !== lastFollowupHash) {
    void router.push({ hash: lastFollowupHash });
  }
  await nextTick();
  timelineRef.value?.locateInRecord(recordId, anchorKey);
}

// 分享 / 后退进来的 #rec=<id>&cat=<key> 重放（面板挂载后执行；只对有效记录生效）
watch(
  [() => route.hash, tab, timeline],
  ([hash, current]) => {
    const { anchorKey, recordId } = parseRecordHash(String(hash ?? ''));
    if (!recordId) return;
    if (current !== 'followup') return;
    if (!timeline.value.some((entry) => entry.recordId === recordId)) return;
    if (recordHash(recordId, anchorKey ?? undefined) === lastFollowupHash)
      return;
    void locateFollowup(recordId, anchorKey ?? undefined);
  },
  { immediate: true },
);

// ------------------------------ E16-T14 修改 / 指定位置（E16-T13 接口，A-ATT-020 / C-SCAN-020）

/** 修改位置弹窗目标（「未指定位置」组「指定位置」与条目 ⋮ 菜单共用）。 */
const locationEditOpen = ref(false);
const locationEditTarget = ref<null | {
  id: string;
  label: string;
  locations: ReportRow['locations'];
}>(null);

function openLocationEdit(reportId: string) {
  const row = reports.value.find((report) => report.id === reportId);
  if (!row) return;
  locationEditTarget.value = {
    id: row.id,
    label: reportDeleteLabel(row),
    locations: row.locations,
  };
  locationEditOpen.value = true;
}

function handleLocationsChanged() {
  void loadReports(); // 位置标签 / 「未指定位置」组随清单刷新
}

// ------------------------------ E16-T14 附件预览（E16-T06 全屏查看器 + 水印）

const watermarkViewer = useWatermarkViewer();
const previewOpen = ref(false);
const previewFiles = ref<
  Array<{ fileId: string; format: string; name: string }>
>([]);

/** 左栏 / 内联缩略图 → 全屏查看器打开首附件（带水印；无附件给出提示）。 */
function openAttachmentPreview(reportId: string) {
  const row = reports.value.find((report) => report.id === reportId);
  if (!row?.firstFile) {
    message.info('该报告单暂无可预览的附件');
    return;
  }
  previewFiles.value = [
    {
      fileId: row.firstFile.fileId,
      format: row.firstFile.format,
      name: row.name ?? row.typeName,
    },
  ];
  previewOpen.value = true;
}

/** 查看器「下载」：换 5 分钟签名链接（后端写 DOWNLOAD 审计）交给浏览器。 */
async function handlePreviewDownload(fileId: number | string) {
  const ok = await downloadFileById(fileId);
  if (!ok) message.error('下载失败，请稍后重试');
}

// ------------------------------ E16-T04 双树浏览（按类型 / 按时间，A-ATT-010 / C-SCAN-013）

/** 树模式与选中分组键（v-model 给 ReportTree；切换树时组件会清空选中）。 */
const reportTreeMode = ref<'time' | 'type'>('type');
const reportTreeKey = ref<null | string>(null);

/** 选中键 → 当前列表（null = 全部；类型 / 日期分组或单份报告单）。 */
const filteredReports = computed(() =>
  reportsForKey(reports.value, reportTreeMode.value, reportTreeKey.value),
);

/** 工具栏筛选标签：「类型：心脏超声」/「时间：2026-10-28」/「报告单：…」。 */
const reportTreeFilterLabel = computed(() => {
  if (!reportTreeKey.value) return null;
  const node = findTreeNode(
    buildReportTree(reports.value, reportTreeMode.value),
    reportTreeKey.value,
  );
  return node ? treeFilterLabel(node) : null;
});

// 列表刷新（上传 / 批量上传后）可能移走选中分组对应的报告单，键失效时回到全部
watch(reports, () => {
  if (
    reportTreeKey.value &&
    !findTreeNode(
      buildReportTree(reports.value, reportTreeMode.value),
      reportTreeKey.value,
    )
  ) {
    reportTreeKey.value = null;
  }
});

const reportColumns = [
  { dataIndex: 'typeName', title: '类型', width: 110 },
  { dataIndex: 'name', title: '名称' },
  { dataIndex: 'reportDate', title: '报告时间', width: 110 },
  { dataIndex: 'locations', title: '位置' },
  { dataIndex: 'creatorName', title: '上传人', width: 110 },
  { dataIndex: 'createdAt', title: '上传时间', width: 150 },
  { dataIndex: 'action', title: '操作', width: 70 },
];

/** 位置列：标签「基线 · 病例基本信息」，超过 2 个折叠 +N，悬停看全部（pages.md §4.6）。 */
function locationTags(row: ReportRow) {
  return row.locations.map((tag) => reportLocationText(tag));
}

// ------------------------------ E16-T09 删除（A-ATT-007 / C-SCAN-016：软删除、原因必填、记审计）

/** 删除权限（followup:scan:delete，按钮 plt_menu 233）：无权限不显示入口（design-system §9）。 */
const canDeleteReport = computed(() => hasPermission('followup:scan:delete'));
const reportDeleteOpen = ref(false);
const reportDeleteTarget = ref<null | ReportRow>(null);
const reportDeleteSubmitting = ref(false);

function openReportDelete(row: ReportRow) {
  reportDeleteTarget.value = row;
  reportDeleteOpen.value = true;
}

/**
 * 确认删除：原因必填由 ReasonModal 校验（≤ 255 字）；软删除成功关弹窗并刷新清单，
 * 失败保持打开可改原因重试（提示由统一拦截器弹出，后端 message 即中文文案）。
 */
async function handleReportDelete(reason: string) {
  const target = reportDeleteTarget.value;
  if (!target || reportDeleteSubmitting.value) return;
  reportDeleteSubmitting.value = true;
  try {
    await deleteReport(target.id, reason);
    message.success('已删除报告单（数据保留可审计）');
    reportDeleteOpen.value = false;
    await loadReports();
  } catch {
    // 提示由统一拦截器弹出（如「请填写删除原因」「报告单已被删除，无需重复操作」）
  } finally {
    reportDeleteSubmitting.value = false;
  }
}

// ---------------------------------------------------------------- E15-T04 沟通记录页签（pages.md §4.8）

const canEditRecord = computed(() => hasPermission('followup:record:edit'));
const canDownload = computed(() => hasPermission('followup:scan:download'));

const contactLogs = ref<ContactLogRow[]>([]);
const contactLogsTotal = ref(0);
const contactLogsPage = ref(1);
const contactLogsLoading = ref(false);
const contactModalOpen = ref(false);
const editingRow = ref<ContactLogRow | null>(null);
/** 患者已终止（死亡 / 停止 / 失访）时「记录沟通」置灰（pages.md §4.10，后端 terminated 口径）。 */
const followupTerminated = computed(() => overview.value?.terminated ?? false);

async function loadContactLogs(page = 1) {
  contactLogsLoading.value = true;
  try {
    const pageResult = await fetchPatientContacts(patientId.value, {
      pageNo: page,
      pageSize: 20,
    });
    contactLogs.value = pageResult.items;
    contactLogsTotal.value = pageResult.total;
    contactLogsPage.value = pageResult.pageNo;
  } catch {
    contactLogs.value = [];
    contactLogsTotal.value = 0;
  } finally {
    contactLogsLoading.value = false;
  }
}

// 切到页签时加载（沟通记录属于患者档案的一部分，不随首屏拉取）
watch([tab, loading], ([current, isLoading]) => {
  if (current === 'contacts' && !isLoading && overview.value) {
    void loadContactLogs(1);
  }
});

const contactLogColumns = [
  { dataIndex: 'contactTime', title: '时间', width: 160 },
  { dataIndex: 'methodText', title: '方式', width: 90 },
  { dataIndex: 'resultText', title: '结果', width: 110 },
  { dataIndex: 'nextContact', title: '约定下次联系', width: 160 },
  { dataIndex: 'note', title: '备注' },
  { dataIndex: 'audios', title: '录音' },
  { dataIndex: 'creatorName', title: '操作人', width: 110 },
  { dataIndex: 'action', title: '操作', width: 120 },
];

const contactLogRows = computed(() =>
  contactLogs.value.map((item) => ({
    ...item,
    contactTime: formatDateTime(item.contactTime),
    creatorName: item.creatorName ?? '—',
    key: item.id,
    methodText: contactMethodText(item.method),
    nextContact: item.nextContactTime
      ? formatDateTime(item.nextContactTime)
      : '—',
    note: item.note ?? '—',
    resultText: contactResultText(item.result),
  })),
);

/** ISO 时间 →「YYYY-MM-DD HH:mm」（本地展示口径）。 */
function formatDateTime(value: string): string {
  const normalized = value.includes('T') ? value : `${value}T00:00:00`;
  return normalized.slice(0, 16).replaceAll('T', ' ');
}

function openCreateContact() {
  editingRow.value = null;
  contactModalOpen.value = true;
}

function openEditContact(row: ContactLogRow) {
  editingRow.value = row;
  contactModalOpen.value = true;
}

function handleContactSaved() {
  void loadContactLogs(contactLogsPage.value);
  // E16-T08 / A-ATT-017：录音同时生成「通话记录」报告单，两处看到同一份数据——保存后同步刷新报告单清单
  void loadReports();
}

/** 删除确认要写明两处同时删除（pages.md §4.8 / A-ATT-017）。 */
function handleDeleteContact(row: ContactLogRow) {
  Modal.confirm({
    cancelText: '取消',
    content:
      '录音与「通话记录」报告单是同一文件：删除后两处同时消失，且不可恢复。',
    okButtonProps: { danger: true },
    okText: '删除',
    onOk: async () => {
      try {
        await deleteContactLog(row.id);
        message.success('沟通记录已删除（通话记录报告单同时删除）');
        void loadContactLogs(contactLogsPage.value);
        // E16-T08 / A-ATT-017：连带软删「通话记录」报告单，两处同时消失
        void loadReports();
      } catch {
        // 提示由统一拦截器弹出
      }
    },
    title: '删除这条沟通记录？',
  });
}

const contactColumns = [
  { dataIndex: 'slot', title: '槽位' },
  { dataIndex: 'phoneMasked', title: '电话（脱敏）' },
  { dataIndex: 'version', title: '版本' },
  { dataIndex: 'effectiveDate', title: '生效日期' },
  { dataIndex: 'source', title: '来源' },
  { dataIndex: 'current', title: '当前' },
];

const contactRows = computed(() =>
  contactVersions.value.map((item) => ({
    ...item,
    current: item.current ? '是' : '—',
    key: item.id,
    slot: contactSlotText(item.slot),
    source: contactSourceText(item.source),
  })),
);

const historyColumns = [
  { dataIndex: 'effectiveDate', title: '生效日期' },
  { dataIndex: 'status', title: '状态' },
  { dataIndex: 'source', title: '来源' },
  { dataIndex: 'reason', title: '原因' },
];

// E13-T08 / A-WARD-005「转病区历史可查」
const wardHistoryColumns = [
  { dataIndex: 'transferredAt', title: '时间' },
  { dataIndex: 'fromWardName', title: '原病区' },
  { dataIndex: 'toWardName', title: '新病区' },
  { dataIndex: 'reason', title: '原因' },
  { dataIndex: 'operatorName', title: '操作人' },
];

const wardHistoryRows = computed(() =>
  wardHistory.value.map((item) => ({
    ...item,
    fromWardName: item.fromWardName ?? '—',
    key: item.id,
    operatorName: item.operatorName ?? '—',
    toWardName: item.toWardName ?? '—',
  })),
);

const historyRows = computed(() =>
  statusHistory.value.map((item) => ({
    ...item,
    key: item.id,
    reason: item.reason ?? '—',
    source: statusSourceText(item.source),
    status: PATIENT_STATUS_TEXT[item.status] ?? item.status,
  })),
);

/** 时间轴节点打开（components.md §9 open）：有 recordId 直达表单页；未开始任务先 start。 */
async function openTimelineNode(entry: PatientTimelineEntry) {
  if (entry.canOpen !== true) return;
  if (entry.recordId) {
    window.open(`/followup/record/${entry.recordId}`, '_blank');
    return;
  }
  if (!entry.taskId) return;
  try {
    const started = await startTask(entry.taskId);
    window.open(`/followup/record/${started.recordId}`, '_blank');
  } catch {
    message.error('打开随访表单失败，请稍后重试');
  }
}

/** 补录 / 计划外创建成功：新标签页打开表单并刷新时间轴（记录节点随建随显）。 */
async function onRecordCreated(recordId: string) {
  window.open(`/followup/record/${recordId}`, '_blank');
  await reloadTimeline();
}

/** 临时任务创建成功（E15-T06 / A-FU-013）：刷新时间轴（任务节点随建随显）。 */
async function onTempTaskCreated() {
  await reloadTimeline();
}

const timelineLegendNote =
  '时间轴含每年随访、历史补录、计划外随访（不影响后续节点）与下次计划日期（计划中）；终点事件随 E17、医院 C 的器械事件随 E21 接入。';
</script>

<template>
  <div v-if="loading" class="loading">
    <Spin tip="正在加载患者档案…" />
  </div>

  <Result
    v-else-if="loadFailed || !overview"
    status="404"
    title="患者不存在或无权查看"
  >
    <template #extra>
      <Button type="primary" @click="router.push('/followup/patient')">
        返回患者列表
      </Button>
    </template>
  </Result>

  <div v-else data-testid="patient-detail">
    <PatientHeader
      :overview="overview"
      @contacts="openContacts"
      @transfer="transferOpen = true"
      @unplanned="unplannedOpen = true"
    />
    <Tabs v-model:active-key="tab" class="tabs">
      <TabPane key="baseline" tab="基线资料" />
      <TabPane key="followup" tab="随访记录" />
      <TabPane key="reports" tab="报告单" />
      <TabPane key="contacts" tab="沟通记录" />
    </Tabs>

    <div
      v-if="tab === 'baseline'"
      class="tab-layout"
      :class="[{ 'tab-layout-stacked': !isDesktop }]"
      data-testid="baseline-panel"
    >
      <!-- E16-T14 / A-PAT-020：左栏只做报告单 / 附件导航（宽 240 可收起；平板「附件（n）」
           左抽屉、手机底部抽屉由组件内处理）；点标签跳该位置、点本体跳第一个位置、
           无位置报告单在「未指定位置」组补指定 -->
      <AttachmentNav
        :can-upload="canUploadReport"
        :items="navItems"
        :patient-id="patientId"
        scope="baseline"
        @edit-location="openLocationEdit"
        @locate="handleNavLocate"
        @open="openAttachmentPreview"
        @upload="openReportUpload()"
      />
      <div class="main">
        <!-- E13-T11 / A-PAT-021：右侧内容顶部吸顶分类条（已填 / 应填，未填完橙色；
           点击滚到该分类并高亮 2 秒，位置写入地址；平板横向滚动、手机下拉） -->
        <CategoryBar
          v-if="baselineBarItems.length > 0"
          v-model:active-key="baselineActiveKey"
          class="baseline-bar"
          :items="baselineBarItems"
          data-testid="baseline-category-bar"
          spy
          @jump="jumpToBaselineCategory"
        />
        <Alert
          v-if="baselineLockBannerText"
          class="notice"
          data-testid="baseline-lock-banner"
          :message="baselineLockBannerText"
          type="warning"
          show-icon
        />
        <div class="toolbar">
          <Tooltip
            v-if="canEditPatient && !editEnabled"
            :title="BASELINE_LOCKED_TOOLTIP"
          >
            <Button data-testid="baseline-edit" disabled>编辑</Button>
          </Tooltip>
          <Button
            v-else-if="canEditPatient"
            data-testid="baseline-edit"
            type="primary"
            @click="openBaselineEdit"
          >
            编辑
          </Button>
          <Button
            v-if="unlockVisible"
            data-testid="baseline-unlock"
            :loading="lockSubmitting"
            @click="unlockOpen = true"
          >
            解锁
          </Button>
          <Button
            v-else-if="lockVisible"
            data-testid="baseline-lock"
            :loading="lockSubmitting"
            @click="confirmBaselineLock"
          >
            锁定
          </Button>
        </div>
        <Descriptions
          bordered
          :column="{ xs: 1, sm: 1, md: 2, xl: 3 }"
          size="middle"
          title="基线信息（出院登记）"
        >
          <DescriptionsItem label="姓名（脱敏）">
            {{ overview.name }}
          </DescriptionsItem>
          <DescriptionsItem label="编号（脱敏）">
            {{ overview.patientNo }}
          </DescriptionsItem>
          <DescriptionsItem label="原系统编号">
            {{ overview.legacyNo ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="随访编号">
            {{ overview.visitNo ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="性别">
            {{ genderText(overview.gender) }}
          </DescriptionsItem>
          <DescriptionsItem label="年龄（手术时）">
            {{ overview.age ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="病区">
            {{ overview.deptName ? `${overview.deptName} · ` : ''
            }}{{ overview.wardName ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="负责医生">
            {{ overview.responsibleName ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="患者类型">
            {{ overview.patientType ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="手术日期">
            {{ overview.surgeryDate ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="入院日期">
            {{ overview.admissionDate ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="出院日期">
            {{ overview.dischargeDate ?? '—' }}
          </DescriptionsItem>
          <DescriptionsItem label="出院状态">
            {{ dischargeStatusLabel }}
          </DescriptionsItem>
          <DescriptionsItem label="基线资料完整度">
            {{ completenessText(overview.baselineCompleteness) }}
          </DescriptionsItem>
          <DescriptionsItem label="随访资料完整度">
            {{ completenessText(overview.followupCompleteness) }}
          </DescriptionsItem>
        </Descriptions>

        <Descriptions
          bordered
          :column="{ xs: 1, sm: 1, md: 2, xl: 3 }"
          size="middle"
          title="计划与随访（随入组与随访记录写入更新）"
        >
          <DescriptionsItem label="下次随访">
            {{ nextVisitText(overview) }}
          </DescriptionsItem>
          <DescriptionsItem label="最近随访">
            {{ latestFollowupText(overview) }}
          </DescriptionsItem>
          <DescriptionsItem v-if="overview.latestFollowup" label="生存状态">
            {{ survivalStatusText(overview.latestFollowup.survivalStatus) }}
          </DescriptionsItem>
        </Descriptions>

        <section
          v-for="section in baselineSectionViews"
          :id="`crdp-anchor-${section.code}`"
          :key="section.code"
          class="baseline-section"
          :data-testid="`baseline-section-${section.code}`"
        >
          <h3 class="section-title">
            {{ section.label }}
            <span class="section-meta">
              {{ section.progressText }} · {{ section.statusText }}
              <template v-if="section.hasDraft"> · 有未保存草稿</template>
              <template v-if="section.updatedBy">
                · {{ section.updatedBy }} 保存于
                {{ section.updatedAt?.slice(0, 10) ?? '' }}
              </template>
            </span>
            <!-- E16-T02 / A-ATT-001：分类内直接添加，预选该分类位置 -->
            <Button
              v-if="canUploadReport"
              class="section-upload"
              size="small"
              type="link"
              @click="openReportUpload(baselineUploadPreset(section.code))"
            >
              上传附件
            </Button>
          </h3>
          <FormRenderer
            :model-value="section.data"
            :schema="section.schema"
            mode="readonly"
          />
          <!-- E16-T14 / A-PAT-020：分类末尾显示位于此处的附件缩略图（点开带水印查看器） -->
          <InlineAttachmentStrip
            :items="baselineAnchorItems(section.code)"
            @open="openAttachmentPreview"
            @upload="openReportUpload(baselineUploadPreset(section.code))"
          />
        </section>
        <p v-if="baseline && baselineSectionViews.length === 0" class="hint">
          基线分节尚未填写：点「编辑」按分类补录（A-PAT-003
          可分次补录、先暂存后保存）。
        </p>
        <p class="hint">
          分节字段值按模板只读展示；字段修改历史与锁定 /
          解锁留痕可在操作日志查询（随报告单页签后的日志入口开放）。
        </p>
      </div>
    </div>

    <div
      v-else-if="tab === 'followup'"
      class="tab-layout"
      :class="[{ 'tab-layout-stacked': !isDesktop }]"
      data-testid="followup-panel"
    >
      <!-- E16-T14 / C-PAT-017：随访记录页签左栏同一附件导航（按随访记录分组，新的在上） -->
      <AttachmentNav
        :can-upload="canUploadReport"
        :items="navItems"
        :patient-id="patientId"
        scope="followup"
        @edit-location="openLocationEdit"
        @locate="handleNavLocate"
        @open="openAttachmentPreview"
        @upload="openReportUpload(followupUploadPreset)"
      />
      <div class="main">
        <Alert
          class="notice"
          :message="timelineLegendNote"
          type="info"
          show-icon
        />
        <div class="toolbar">
          <Tooltip v-if="followupTerminated" title="患者不在随访中">
            <Button disabled>历史补录</Button>
          </Tooltip>
          <Button
            v-else-if="canEditRecord"
            data-testid="backfill-create"
            @click="backfillOpen = true"
          >
            历史补录
          </Button>
          <!-- A-FU-023「中间该子计划的任务列表，可临时添加任务」：时间轴工具栏入口 -->
          <Tooltip v-if="followupTerminated" title="患者不在随访中">
            <Button disabled>添加任务</Button>
          </Tooltip>
          <Button
            v-else-if="canEditRecord"
            data-testid="temp-task-create"
            @click="tempTaskOpen = true"
          >
            添加任务
          </Button>
          <!-- E16-T02 / A-ATT-001：随访记录内直接添加；展开记录时预选整条记录 -->
          <Tooltip
            :title="
              expandedRecordId
                ? undefined
                : '先展开一条随访记录，可预选该记录为位置'
            "
          >
            <Button
              v-if="canUploadReport"
              data-testid="followup-upload-report"
              @click="openReportUpload(followupUploadPreset)"
            >
              上传附件
            </Button>
          </Tooltip>
        </div>
        <FollowupTimeline
          ref="timelineRef"
          :attachments="navItems"
          :can-upload="canUploadReport"
          v-model:expanded-record-id="expandedRecordId"
          :entries="timeline"
          :patient-id="overview.id"
          @open="openTimelineNode"
          @open-attachment="openAttachmentPreview"
          @upload-at="(preset) => openReportUpload(preset)"
        />
        <BackfillRecordModal
          v-model:open="backfillOpen"
          :patient-id="overview.id"
          @created="onRecordCreated"
        />
        <TempTaskModal
          v-model:open="tempTaskOpen"
          :patient-id="overview.id"
          :patient-label="overview.name"
          @created="onTempTaskCreated"
        />
        <UnplannedRecordModal
          v-model:open="unplannedOpen"
          :patient-id="overview.id"
          @created="onRecordCreated"
        />
      </div>
    </div>

    <div v-else-if="tab === 'reports'" class="main" data-testid="reports-panel">
      <div class="reports-layout">
        <!-- E16-T04 左侧双树（按类型 / 按时间，A-ATT-010 / C-SCAN-013） -->
        <ReportTree
          v-model:mode="reportTreeMode"
          v-model:selected-key="reportTreeKey"
          :loading="reportsLoading"
          :reports="reports"
        />
        <div class="reports-content">
          <div class="toolbar">
            <Tag
              v-if="reportTreeFilterLabel"
              closable
              data-testid="report-tree-filter"
              @close.prevent="reportTreeKey = null"
            >
              {{ reportTreeFilterLabel }}
            </Tag>
            <Button
              v-if="canUploadReport"
              data-testid="report-batch-upload"
              @click="openBatchUpload()"
            >
              批量上传
            </Button>
            <Button
              v-if="canUploadReport"
              data-testid="report-create"
              type="primary"
              @click="openReportUpload()"
            >
              添加报告单
            </Button>
          </div>
          <Table
            :columns="reportColumns"
            :data-source="filteredReports"
            :loading="reportsLoading"
            :pagination="{ pageSize: 20, showSizeChanger: false }"
            size="small"
            data-testid="report-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'name'">
                {{ record.name ?? '—' }}
                <!-- E16-T08 / A-ATT-017：关联沟通记录的通话记录（录音与沟通记录页签同一文件） -->
                <Tooltip
                  v-if="record.contactLogId"
                  title="与沟通记录页签的录音为同一文件（A-ATT-017），删除任一处两处同时删除"
                >
                  <Tag data-testid="report-call-link-tag">关联沟通记录</Tag>
                </Tooltip>
              </template>
              <template v-else-if="column.dataIndex === 'locations'">
                <template v-if="record.locations.length === 0">
                  <span class="unassigned">未指定位置</span>
                </template>
                <template v-else>
                  <Tag
                    v-for="text in locationTags(record).slice(0, 2)"
                    :key="text"
                  >
                    {{ text }}
                  </Tag>
                  <Tooltip
                    v-if="locationTags(record).length > 2"
                    :title="locationTags(record).join('、')"
                  >
                    <Tag>+{{ locationTags(record).length - 2 }}</Tag>
                  </Tooltip>
                </template>
              </template>
              <template v-else-if="column.dataIndex === 'creatorName'">
                {{ record.creatorName ?? '—' }}
              </template>
              <template v-else-if="column.dataIndex === 'createdAt'">
                {{ formatDateTime(record.createdAt) }}
              </template>
              <template v-else-if="column.dataIndex === 'action'">
                <!-- E16-T09 / A-ATT-007：删除（followup:scan:delete；无权限不显示，软删除须填原因） -->
                <a
                  v-if="canDeleteReport"
                  class="report-delete"
                  data-testid="report-row-delete"
                  @click.prevent="openReportDelete(record)"
                >
                  删除
                </a>
              </template>
            </template>
            <template #emptyText> 暂无报告单 </template>
          </Table>
          <p class="hint">
            位置标签「基线 · 分类」「第N年随访 ·
            分组」；「未指定位置」的报告单在基线 /
            随访页签左栏的「未指定位置」组补指定位置（E16-T14）；行内「删除」为软删除、须填原因（E16-T09），逐文件预览与删除需报告单文件清单接口（随后续任务）。
          </p>
        </div>
      </div>
    </div>

    <div v-else class="main" data-testid="contacts-panel">
      <div class="toolbar">
        <Tooltip v-if="followupTerminated" title="患者不在随访中">
          <Button disabled>记录沟通</Button>
        </Tooltip>
        <Button
          v-else-if="canEditRecord"
          data-testid="contact-create"
          type="primary"
          @click="openCreateContact"
        >
          记录沟通
        </Button>
      </div>
      <Table
        :columns="contactLogColumns"
        :data-source="contactLogRows"
        :loading="contactLogsLoading"
        :pagination="{
          current: contactLogsPage,
          pageSize: 20,
          showSizeChanger: false,
          total: contactLogsTotal,
        }"
        size="small"
        data-testid="contact-log-table"
        @change="(pag) => loadContactLogs(pag.current ?? 1)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'resultText'">
            <StatusTag :status="record.resultText" variant="dot" />
          </template>
          <template v-else-if="column.dataIndex === 'audios'">
            <template v-if="record.audios.length === 0">—</template>
            <AudioPlayer
              v-for="audio in record.audios"
              :key="audio.fileId"
              :audio="audio"
              :can-download="canDownload"
            />
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <template v-if="canEditRecord">
              <Button size="small" type="link" @click="openEditContact(record)">
                修改
              </Button>
              <Button
                danger
                size="small"
                type="link"
                @click="handleDeleteContact(record)"
              >
                删除
              </Button>
            </template>
            <span v-else class="hint">—</span>
          </template>
        </template>
        <template #emptyText> 暂无沟通记录 </template>
      </Table>
      <p class="hint">
        录音同时显示为一份「通话记录」报告单（A-ATT-017，见报告单页签），两处是同一文件；
        删除任一处两处同时删除。
      </p>
    </div>

    <Drawer v-model:open="contactsOpen" :width="640" title="联系方式与变更记录">
      <Spin :spinning="contactsLoading">
        <h3 class="drawer-title">联系方式版本（换号保留历史）</h3>
        <Table
          :columns="contactColumns"
          :data-source="contactRows"
          :pagination="false"
          size="small"
        >
          <template #emptyText> 暂无联系方式版本 </template>
        </Table>
        <h3 class="drawer-title">状态变更记录</h3>
        <Table
          :columns="historyColumns"
          :data-source="historyRows"
          :pagination="false"
          size="small"
        >
          <template #emptyText> 暂无状态变更 </template>
        </Table>
        <h3 class="drawer-title">转病区历史</h3>
        <Table
          :columns="wardHistoryColumns"
          :data-source="wardHistoryRows"
          :pagination="false"
          size="small"
        >
          <template #emptyText> 暂无转病区记录 </template>
        </Table>
      </Spin>
      <p class="hint">
        电话明文需持有「查看明文」权限，经患者头部的「显示明文」查看并写审计。
      </p>
    </Drawer>

    <ContactLogModal
      v-model:open="contactModalOpen"
      :patient-id="overview.id"
      :row="editingRow"
      @success="handleContactSaved"
    />

    <UploadReportDialog
      :open="reportDialogOpen"
      :patient-gender="overview.gender"
      :patient-id="overview.id"
      :preset="reportPreset"
      @created="handleReportCreated"
      @update:open="reportDialogOpen = $event"
    />

    <BatchUploadDialog
      :open="batchDialogOpen"
      :patient-id="overview.id"
      :preset="reportPreset"
      @created="handleReportCreated"
      @update:open="batchDialogOpen = $event"
    />

    <TransferWardModal
      v-model:open="transferOpen"
      :current-ward-id="currentWardId"
      :label="`${overview.name} · ${overview.patientNo}`"
      :patient-id="overview.id"
      @success="handleTransferred"
    />

    <ReasonModal
      v-model:open="unlockOpen"
      confirm-text="解锁"
      :label="`${overview.name} · ${overview.patientNo}`"
      note="解锁原因必填，将记入操作日志（A-PAT-006）；解锁后基线恢复可编辑。"
      placeholder="必填，如：发现录入错误需要修正"
      @confirm="handleUnlock"
    />

    <!-- E16-T09 / A-ATT-007、C-SCAN-016：删除报告单（软删除，原因必填；后端写 RPT_DELETE 审计） -->
    <ReasonModal
      v-if="reportDeleteTarget"
      v-model:open="reportDeleteOpen"
      confirm-text="删除"
      :label="reportDeleteLabel(reportDeleteTarget)"
      :note="reportDeleteNote(reportDeleteTarget)"
      placeholder="必填，如：上传错误，重复上传"
      @confirm="handleReportDelete"
    />

    <!-- E16-T14 / A-ATT-020、C-SCAN-020：修改 / 指定位置（PUT locations，E16-T13 接口） -->
    <ReportLocationModal
      v-if="locationEditTarget"
      v-model:open="locationEditOpen"
      :patient-id="patientId"
      :report="locationEditTarget"
      @success="handleLocationsChanged"
    />

    <!-- E16-T14 附件预览（E16-T06 全屏查看器：缩放 / 旋转 / 翻转 / 水印，带水印口径） -->
    <WatermarkPreview
      :can-download="canDownload"
      :files="previewFiles"
      :open="previewOpen"
      :viewer="watermarkViewer"
      @download="handlePreviewDownload"
      @update:open="previewOpen = $event"
    />
  </div>
</template>

<style scoped>
.loading {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

.tabs {
  padding: 0 16px;
  background: #fff;
}

.main {
  padding: 16px;
}

/* E16-T14 / A-PAT-020：基线 / 随访页签 = 左栏附件导航（240，组件自带收起）+ 右侧内容；
   平板 / 手机收成「附件（n）」抽屉入口，纵向排在右侧内容顶部 */
.tab-layout {
  display: flex;
  gap: 0;
  align-items: stretch;
}

.tab-layout > .main {
  flex: 1;
  min-width: 0;
}

.tab-layout-stacked {
  flex-direction: column;
}

/* E16-T02 基线分类标题旁「上传附件」 */
.section-upload {
  margin-left: 8px;
}

.unassigned {
  color: #fa8c16;
}

/* E16-T09 报告单行「删除」：破坏性操作红色（design-system §9，与患者列表删除同色） */
.report-delete {
  color: #ff4d4f;
}

/* E13-T11 基线分类条：吸顶后与下方锁定横幅留出间距 */
.baseline-bar {
  margin-bottom: 12px;
}

.baseline-section {
  padding: 8px 4px 0;
  margin-top: 16px;
}

.section-title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.section-meta {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
  color: #8c8c8c;
}

.notice {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  margin-bottom: 12px;
}

/* E16-T04 报告单页签：左侧双树 + 右侧列表（pages.md §4.6；右侧预览随 E16-T06） */
.reports-layout {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.reports-content {
  flex: 1;
  min-width: 0;
}

.hint {
  margin-top: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.drawer-title {
  margin: 12px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.drawer-title:first-of-type {
  margin-top: 0;
}
</style>
