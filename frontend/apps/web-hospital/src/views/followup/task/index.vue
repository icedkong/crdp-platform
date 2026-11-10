<script lang="ts" setup>
import type { TaskRow, TaskScope, TaskStatus } from '@crdp/api';

import type { AssigneeFilter } from './logic';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  contactResultText,
  deepCloneJson,
  doctorDrillOfQuery,
  isTaskAcknowledgable,
  isTaskClaimable,
  isTaskEnterable,
  isTaskLostCandidate,
  isTaskReassignable,
  TASK_STATUS_OPTIONS,
  TASK_STATUS_TEXT,
  TASK_TABS,
  taskDisplayStatus,
  taskStatusTooltip,
  taskWindowText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { colors, sizes } from '@crdp/tokens';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  FormItem,
  message,
  Modal,
  Pagination,
  Segmented,
  Select,
  Space,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tag,
} from 'ant-design-vue';

import {
  acknowledgeTask,
  claimTask,
  confirmLostTask,
  searchTasks,
  startTask,
} from '#/api/core/task';
import TaskAssignModal from '#/components/followup/task-assign-modal.vue';
import TaskFilterDrawer from '#/components/followup/task-filter-drawer.vue';
import TempTaskModal from '#/components/followup/temp-task-modal.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  ASSIGNEE_FILTER_OPTIONS,
  boardGroups,
  buildSearchPayload,
  dateRangeOfQuery,
  dueRangeOfQuery,
  queryOfScope,
  recordFormPath,
  recordRouteTarget,
  scopeOfQuery,
  statusOfQuery,
  TASK_EMPTY_TEXT,
} from './logic';
import {
  drawerFilterCount,
  isTaskOverdue,
  MOBILE_STATUS_SEGMENTS,
  segmentOfStatus,
  statusOfSegment,
  taskCardActions,
} from './responsive-logic';

defineOptions({ name: 'FollowupTask' });

/**
 * E15-T02 / A-FU-004、C-FU-020 · E15-T03 A-FU-009 · 我的任务（pages.md §7）：
 * 页签（我的待办〔默认〕/ 本病区 / 全部）+ 状态 / 病区 / 计划日期 / 负责人（待认领）筛选 +
 * 列表 | 看板（按状态分列，不支持拖拽——状态由业务流转决定）。
 *
 * 病区隔离由后端数据范围承担（越范围患者的任务不可见）；姓名 / 病案号只显示
 * 后端脱敏值（AGENTS.md §5.3）。「录入」经 POST /tasks/{id}/start 换 recordId 后
 * 新标签页打开随访工作台（A-FU-005「从任务进入」）；链接携带当前筛选，
 * 工作台「上一条 / 下一条」顺当前列表走。
 *
 * 认领与改派（E15-T03）：待认领行（负责人为空且未完成）「认领」给当前登录用户
 * （followup:task:view，公共池自助）；行内「改派」与勾选批量改派走
 * POST /tasks/batch-assign（followup:task:assign），改派成功后新负责人收站内信。
 *
 * 「最近联系」列已随 E15-T04 沟通记录接入（该患者最新一条结果，中文由
 * packages/api contactResultText 映射，无联系显示「—」）；C 的器械类型 /
 * 随访时间点 / 上次标红项数三列随 E21 / E17 开放。
 *
 * E15-T06 / A-FU-013：「+ 临时任务」打开 TempTaskModal（检索选患者 →
 * FORM / REMINDER → 计划日期 + 窗口宽度），不占用年度任务；
 * A-FU-026：站内提醒类任务无需填表——行内「完成提醒」确认即完成（不进录入）。
 *
 * E24-T05 / NFR-RESP-001、002（pages.md §7 响应式）：平板保留主要列（去掉
 * 序号 / 最近联系）、点行进表单；手机任务卡片（患者、节点、窗口、状态、逾期
 * 标记），点卡片进表单（链接携带当前筛选 → 随访工作台「上一条 / 下一条」顺
 * 当前队列连续录入，表单页手机形态随 E24-T02）；状态筛选用顶部分段切换
 * （全部 + 看板四态，单选；分段放不下的状态用条件 chip 展示与清除），病区 /
 * 计划日期 / 负责人收进底部抽屉（design-system §5、§7）；手机无批量改派、
 * 无看板（卡片即列表形态，OQ-115）。路由已登记响应式名单（不再被
 * MobileBlocker 拦下）。
 */
const route = useRoute();
const router = useRouter();
const { hasPermission } = usePermission();
const canEditRecord = computed(() => hasPermission('followup:record:edit'));
const canAssign = computed(() => hasPermission('followup:task:assign'));
const canCreatePlan = computed(() => hasPermission('followup:plan:edit'));
const { isMobile, isTablet } = useBreakpoint();

/** 手机卡片次操作显隐（taskCardActions 的权限入参，与桌面行内同源）。 */
const cardPerms = computed(() => ({
  canAssign: canAssign.value,
  canEditRecord: canEditRecord.value,
}));

const RangePicker = DatePicker.RangePicker;

// ---------------------------------------------------------------- 筛选与分页状态

const scope = ref<TaskScope>(scopeOfQuery(route.query.tab));
const filters = reactive({
  assignee: 'ALL' as AssigneeFilter,
  completedRange: null as [string, string] | null,
  dueRange: null as [string, string] | null,
  status: undefined as null | TaskStatus[],
  wardIds: [] as number[],
  windowRange: null as [string, string] | null,
});
const applied = ref({
  assignee: 'ALL' as AssigneeFilter,
  completedRange: null,
  dueRange: null,
  status: null,
  wardIds: [],
  windowRange: null,
});
const pageNo = ref(1);
const pageSize = ref(sizes.tablePageSize);

const loading = ref(false);
const rows = ref<TaskRow[]>([]);
const total = ref(0);
const tabCounts = ref({ mine: 0, total: 0, ward: 0 });
// E19-T07 / A-DASH-009 · 医护负责患者数钻取：统计页点数字带 doctorId 进列表（后端语义 =
// 「任务负责人或患者责任医生」）；关闭标签即取消钻取（页面筛选条暂无对应控件）
const doctorDrill = ref<null | { doctorId: number; label: string }>(null);

function clearDoctorDrill() {
  doctorDrill.value = null;
  pageNo.value = 1;
  handleSearch();
}
const wardOptions = ref<Array<{ label: string; value: number }>>([]);
const viewMode = ref<'board' | 'list'>('list');

const payload = computed(() =>
  buildSearchPayload({
    ...filters,
    doctorId: doctorDrill.value?.doctorId,
    scope: scope.value,
  }),
);

// ---------------------------------------------------------------- 加载

async function load() {
  loading.value = true;
  try {
    const page = await searchTasks({
      ...payload.value,
      pageNo: pageNo.value,
      pageSize: pageSize.value,
    });
    rows.value = page.items;
    total.value = page.total;
    tabCounts.value = page.tabCounts;
    wardOptions.value = page.wards.map((item) => ({
      label: item.name,
      value: item.id,
    }));
    // E24-T07：deepCloneJson 替代 structuredClone（微信 XWeb 等旧内核无此 API）；
    // filters 是纯 JSON（状态 / 病区 / 日期区间），JSON 往返等价
    applied.value = deepCloneJson(filters);
  } catch {
    message.error('任务列表加载失败，请稍后重试');
  } finally {
    loading.value = false;
  }
}

function syncQuery() {
  void router.replace({
    query: {
      ...route.query,
      status:
        filters.status && filters.status.length > 0
          ? filters.status.join(',')
          : undefined,
      tab: queryOfScope(scope.value),
    },
  });
}

function handleTabChange(next: number | string) {
  const tab = TASK_TABS.find((item) => item.scope === next);
  if (!tab) return;
  scope.value = tab.scope;
  pageNo.value = 1;
  syncQuery();
  void load();
}

function handleSearch() {
  pageNo.value = 1;
  syncQuery();
  void load();
}

function handleReset() {
  filters.assignee = 'ALL';
  filters.completedRange = null;
  filters.dueRange = null;
  filters.status = null;
  filters.wardIds = [];
  filters.windowRange = null;
  doctorDrill.value = null;
  handleSearch();
}

function handleTableChange(nextPage: number, nextSize: number) {
  pageNo.value = nextPage;
  pageSize.value = nextSize;
  void load();
}

onMounted(() => {
  const status = statusOfQuery(route.query.status);
  if (status) {
    filters.status = status;
  }
  // E19-T02 指标卡钻取：首页点「本月应随访」带 tab=all&dueFrom&dueTo 进列表
  const dueRange = dueRangeOfQuery(route.query.dueFrom, route.query.dueTo);
  if (dueRange) {
    filters.dueRange = dueRange;
  }
  // E19-T03 首页钻取：任务表「今日任务」带 windowFrom=windowTo=当日、看板已完成列带
  // completedFrom/completedTo（页面筛选条暂无控件，随「清除筛选」一并清空）
  const windowRange = dateRangeOfQuery(
    route.query.windowFrom,
    route.query.windowTo,
  );
  if (windowRange) {
    filters.windowRange = windowRange;
  }
  const completedRange = dateRangeOfQuery(
    route.query.completedFrom,
    route.query.completedTo,
  );
  if (completedRange) {
    filters.completedRange = completedRange;
  }
  // E19-T07 医护负责患者数钻取：doctorId / doctorName（随「清除筛选」一并清空）
  doctorDrill.value = doctorDrillOfQuery(route.query);
  void load();
});

// ---------------------------------------------------------------- 录入入口（A-FU-005「从任务进入」）

const startingId = ref('');

async function handleEnter(row: TaskRow) {
  if (!canEditRecord.value || !isTaskEnterable(row)) return;
  try {
    let recordId = recordRouteTarget(row, null);
    if (!recordId) {
      startingId.value = row.id;
      const started = await startTask(row.id);
      recordId = recordRouteTarget(
        { ...row, recordId: started.recordId },
        started.recordId,
      );
    }
    if (!recordId) return;
    window.open(recordFormPath(recordId, payload.value), '_blank');
  } catch {
    message.error('开始录入失败，请稍后重试');
  } finally {
    startingId.value = '';
  }
}

function openReadonly(row: TaskRow) {
  if (!row.recordId) {
    message.warning('该任务尚未开始录入，没有可查看的记录');
    return;
  }
  window.open(recordFormPath(row.recordId, payload.value), '_blank');
}

// ---------------------------------------------------------------- 认领与改派（E15-T03 / A-FU-009）

const claimingId = ref('');
const selectedIds = ref<string[]>([]);
const assignOpen = ref(false);
const assignTargetIds = ref<Array<number | string>>([]);

/** 从公共池认领（A-FU-009）：待认领任务认领给当前登录用户；成功后刷新列表。 */
async function handleClaim(row: TaskRow) {
  claimingId.value = row.id;
  try {
    await claimTask(row.id);
    message.success('认领成功，任务已加入我的待办');
    selectedIds.value = selectedIds.value.filter((id) => id !== row.id);
    void load();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '认领失败，请稍后重试');
  } finally {
    claimingId.value = '';
  }
}

/** E15-T06 / A-FU-026：完成提醒（站内提醒类任务无需填表，确认即完成）。 */
const acknowledgingId = ref('');

async function handleAcknowledge(row: TaskRow) {
  acknowledgingId.value = row.id;
  try {
    await acknowledgeTask(row.id);
    message.success('提醒已确认，任务完成（无需填表）');
    void load();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '完成提醒失败，请稍后重试');
  } finally {
    acknowledgingId.value = '';
  }
}

// ---------------------------------------------------------------- 确认失访（S5 / A-FU-012 · E15-T05）

/**
 * S5 / A-FU-012「失访候选：医生可确认失访」：调用既有 confirm-lost（原因与弹窗形态
 * 按 pages.md §7；接口不收原因，用确认弹窗写明后果）；响应 patientLostPrompt = true
 * 时（连续 2 个窗口失访）引导医生去患者详情把患者状态改为失访（不自动改）。
 */
const lostConfirmingId = ref('');

function openConfirmLost(row: TaskRow) {
  Modal.confirm({
    content:
      '确认后该窗口随访标为失访，同窗口未完成跟进任务一并关闭；已完成随访保留（A-FU-012）。',
    okText: '确认失访',
    cancelText: '取消',
    onOk: () => handleConfirmLost(row),
    title: `确认失访？${row.patientName ?? ''}`,
  });
}

async function handleConfirmLost(row: TaskRow) {
  lostConfirmingId.value = row.id;
  try {
    const result = await confirmLostTask(row.id);
    message.success(
      result.terminatedFollowups > 0
        ? `已确认失访；同窗口跟进任务关闭 ${result.terminatedFollowups} 条`
        : '已确认失访',
    );
    if (result.patientLostPrompt) {
      Modal.confirm({
        content:
          '该患者已连续 2 个随访窗口失访。请在患者详情「改为失访」并填写原因；改判后未开始任务全部终止、后续不再创建（A-FU-012）。',
        okText: '去患者详情',
        cancelText: '稍后处理',
        onOk: () => {
          void router.push(`/followup/patient/${row.patientId}`);
        },
        title: '建议把患者状态改为失访',
      });
    }
    void load();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '确认失访失败，请稍后重试');
  } finally {
    lostConfirmingId.value = '';
  }
}

// ---------------------------------------------------------------- 临时任务（E15-T06 / A-FU-013）

const tempOpen = ref(false);

function onTempCreated() {
  void load();
}

// ---------------------------------------------------------------- E24-T05 · 平板点行 / 手机点卡片进表单

/**
 * 平板点行、手机点卡片进表单（pages.md §7）：主操作与行内「录入 / 查看」同源
 * （taskCardActions），可录入且有权 → 经 start 换 recordId 新标签页打开随访
 * 工作台；链接携带当前筛选，表单「上一条 / 下一条」顺当前队列连续录入。
 */
function handleOpenTask(row: TaskRow) {
  const { primary } = taskCardActions(row, {
    canAssign: canAssign.value,
    canEditRecord: canEditRecord.value,
  });
  if (primary === 'enter') {
    void handleEnter(row);
    return;
  }
  if (primary === 'view') {
    openReadonly(row);
  }
}

/** 平板表格行点击（customRow）；操作列已 click.stop 拦截，不再冒泡到行。 */
function tableCustomRow(record: TaskRow) {
  return {
    class: 'row-clickable',
    onClick: () => handleOpenTask(record),
  };
}

// ---------------------------------------------------------------- E24-T05 · 手机状态分段与筛选抽屉

/** 顶部分段（pages.md §7）：全部 + 看板四态单选；改动即检索并回写路由 query。 */
const segmentValue = computed({
  get: () => segmentOfStatus(filters.status) ?? 'ALL',
  set: (value: (typeof MOBILE_STATUS_SEGMENTS)[number]['value']) => {
    filters.status = statusOfSegment(value);
    handleSearch();
  },
});

/** 分段放不下的状态（失访候选 / 多选钻取参数）：条件 chip 展示与清除，不丢筛选。 */
const segmentOverflow = computed(
  () =>
    filters.status &&
    filters.status.length > 0 &&
    segmentOfStatus(filters.status) === null,
);

const segmentOverflowText = computed(() =>
  (filters.status ?? []).map((item) => TASK_STATUS_TEXT[item]).join('、'),
);

function clearSegmentOverflow() {
  filters.status = null;
  handleSearch();
}

/** 筛选抽屉（病区 / 计划日期 / 负责人；状态分段常驻顶栏不进抽屉）。 */
const filterOpen = ref(false);
const drawerCount = computed(() =>
  drawerFilterCount({
    assignee: filters.assignee,
    dueRange: filters.dueRange,
    wardIds: filters.wardIds,
  }),
);

function handleDrawerSearch() {
  handleSearch();
}

function handleCardPageChange(nextPage: number) {
  handleTableChange(nextPage, pageSize.value);
}

/** 打开改派弹窗（单个改派 = 传一条）。 */
function openAssign(ids: Array<number | string>) {
  if (ids.length === 0) return;
  assignTargetIds.value = ids;
  assignOpen.value = true;
}

/** 改派弹窗提交成功：清空勾选并刷新（负责人列与页签徽标随之更新）。 */
function handleAssignSuccess() {
  selectedIds.value = [];
  void load();
}

const rowSelection = computed(() => ({
  selectedRowKeys: selectedIds.value,
  onChange: (keys: (number | string)[]) => {
    selectedIds.value = keys.map(String);
  },
  // 只有未完成任务可改派（A-FU-009）；待认领行同时支持勾选改派
  getCheckboxProps: (record: TaskRow) => ({
    disabled: !isTaskReassignable(record),
  }),
}));

// ---------------------------------------------------------------- 列定义（pages.md §7）

const desktopColumns = computed(() => [
  { dataIndex: 'index', title: '#', width: 48 },
  { dataIndex: 'patientName', title: '患者' },
  { dataIndex: 'wardName', title: '病区', width: 120 },
  { dataIndex: 'nodeName', title: '节点', width: 110 },
  { dataIndex: 'planDate', title: '计划日期', width: 110 },
  { dataIndex: 'window', title: '窗口', width: 150 },
  { dataIndex: 'status', title: '状态', width: 150 },
  { dataIndex: 'assigneeName', title: '负责人', width: 110 },
  { dataIndex: 'lastContact', title: '最近联系', width: 96 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 168 },
]);

/** 平板保留主要列（pages.md §7：去掉序号 / 最近联系，信息在行展开前的主列足够定位任务）。 */
const tabletColumns = computed(() => [
  { dataIndex: 'patientName', title: '患者' },
  { dataIndex: 'wardName', title: '病区', width: 110 },
  { dataIndex: 'nodeName', title: '节点', width: 100 },
  { dataIndex: 'planDate', title: '计划日期', width: 104 },
  { dataIndex: 'window', title: '窗口', width: 140 },
  { dataIndex: 'status', title: '状态', width: 140 },
  { dataIndex: 'assigneeName', title: '负责人', width: 100 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 150 },
]);

const columns = computed(() =>
  isTablet.value ? tabletColumns.value : desktopColumns.value,
);

const dataSource = computed(() =>
  rows.value.map((item, index) => ({
    ...item,
    index: (pageNo.value - 1) * pageSize.value + index + 1,
    key: item.id,
    lastContact: contactResultText(item.lastContactResult),
    statusText: taskDisplayStatus(item),
    window: taskWindowText(item.windowStart, item.windowEnd),
  })),
);

const groups = computed(() => boardGroups(rows.value));

const hasActiveFilters = computed(
  () =>
    (applied.value.status?.length ?? 0) > 0 ||
    (applied.value.wardIds?.length ?? 0) > 0 ||
    Boolean(applied.value.dueRange) ||
    Boolean(applied.value.windowRange) ||
    Boolean(applied.value.completedRange) ||
    applied.value.assignee !== 'ALL',
);

const emptyInfo = computed(() => ({
  description: TASK_EMPTY_TEXT,
}));

function handleEmptyAction() {
  if (hasActiveFilters.value) {
    handleReset();
    return;
  }
  if (canCreatePlan.value) {
    void router.push('/followup/plan');
  }
}
</script>

<template>
  <div
    class="page"
    :class="{ 'page-mobile': isMobile }"
    data-testid="task-list"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <h1 class="crdp-page-title">我的任务</h1>
        <Button
          v-if="canEditRecord"
          data-testid="temp-task-open"
          type="primary"
          @click="tempOpen = true"
        >
          + 临时任务
        </Button>
      </div>

      <Tabs
        v-model:active-key="scope"
        data-testid="task-tabs"
        @change="handleTabChange"
      >
        <TabPane key="MINE">
          <template #tab> 我的待办 {{ tabCounts.mine }} </template>
        </TabPane>
        <TabPane key="WARD">
          <template #tab> 本病区 {{ tabCounts.ward }} </template>
        </TabPane>
        <TabPane key="ALL">
          <template #tab>全部 {{ tabCounts.total }}</template>
        </TabPane>
      </Tabs>

      <!-- E24-T05 · 手机：状态分段切换（pages.md §7）+ 筛选抽屉入口 + 任务卡片列表 -->
      <template v-if="isMobile">
        <Segmented
          v-model:value="segmentValue"
          block
          :options="[...MOBILE_STATUS_SEGMENTS]"
          data-testid="task-status-segment"
        />
        <div v-if="segmentOverflow" class="segment-chip">
          <Tag
            closable
            color="blue"
            data-testid="task-status-chip"
            @close.prevent="clearSegmentOverflow"
          >
            状态：{{ segmentOverflowText }}
          </Tag>
        </div>
        <div class="mobile-filter-row">
          <Button data-testid="task-filter-open" @click="filterOpen = true">
            筛选
            <span
              v-if="drawerCount > 0"
              class="filter-count"
              :style="{ background: colors.primary }"
              data-testid="task-filter-count"
            >
              {{ drawerCount }}
            </span>
          </Button>
        </div>

        <div class="cards">
          <Spin :spinning="loading">
            <Card
              v-for="row in rows"
              :key="row.id"
              :bordered="false"
              class="task-item"
              :data-testid="`task-card-${row.id}`"
              :style="
                isTaskOverdue(row)
                  ? { borderLeft: `3px solid ${colors.error}` }
                  : undefined
              "
              @click="handleOpenTask(row)"
            >
              <div class="item-line">
                <span class="item-patient">{{ row.patientName }}</span>
                <StatusTag
                  :status="taskDisplayStatus(row)"
                  :tooltip="taskStatusTooltip(taskDisplayStatus(row))"
                  variant="dot"
                />
              </div>
              <div class="sub-text">{{ row.mrn }}</div>
              <div class="sub-text">
                {{ row.nodeName ?? '—' }} · {{ row.wardName ?? '—' }}
              </div>
              <div class="sub-text">
                计划 {{ row.planDate || '—' }} · 窗口
                {{ taskWindowText(row.windowStart, row.windowEnd) }}
              </div>
              <div class="item-line item-footer">
                <span class="sub-text">
                  {{ row.assigneeName ?? '待认领' }}
                  <span
                    v-if="isTaskOverdue(row)"
                    class="overdue-flag"
                    :style="{ color: colors.error }"
                  >
                    · 已逾期
                  </span>
                </span>
                <span class="item-actions" @click.stop>
                  <Button
                    v-if="
                      taskCardActions(row, cardPerms).secondary.includes(
                        'acknowledge',
                      )
                    "
                    :data-testid="`task-ack-${row.id}`"
                    :loading="acknowledgingId === row.id"
                    size="small"
                    @click="handleAcknowledge(row)"
                  >
                    完成提醒
                  </Button>
                  <Button
                    v-if="
                      taskCardActions(row, cardPerms).secondary.includes(
                        'lostConfirm',
                      )
                    "
                    :data-testid="`task-lost-${row.id}`"
                    :loading="lostConfirmingId === row.id"
                    size="small"
                    @click="openConfirmLost(row)"
                  >
                    确认失访
                  </Button>
                  <Button
                    v-if="
                      taskCardActions(row, cardPerms).secondary.includes(
                        'claim',
                      )
                    "
                    :data-testid="`task-claim-${row.id}`"
                    :loading="claimingId === row.id"
                    size="small"
                    @click="handleClaim(row)"
                  >
                    认领
                  </Button>
                  <Button
                    v-if="
                      taskCardActions(row, cardPerms).secondary.includes(
                        'assign',
                      )
                    "
                    :data-testid="`task-assign-${row.id}`"
                    size="small"
                    @click="openAssign([row.id])"
                  >
                    改派
                  </Button>
                </span>
              </div>
            </Card>
            <Empty
              v-if="!loading && rows.length === 0"
              :description="emptyInfo.description"
            >
              <Button
                v-if="hasActiveFilters"
                data-testid="task-empty-reset"
                type="primary"
                @click="handleEmptyAction"
              >
                清除筛选
              </Button>
              <Button
                v-else-if="canCreatePlan"
                data-testid="task-empty-create-plan"
                type="primary"
                @click="handleEmptyAction"
              >
                去新建计划
              </Button>
            </Empty>
          </Spin>
          <div v-if="rows.length > 0" class="card-pagination">
            <span
              class="total"
              :style="{ color: colors.textSecondary, fontSize: '12px' }"
            >
              共 {{ total }} 条
            </span>
            <Pagination
              :current="pageNo"
              data-testid="task-card-pagination"
              :page-size="pageSize"
              simple
              :total="total"
              @change="handleCardPageChange"
            />
          </div>
        </div>
      </template>

      <!-- 桌面：行内筛选 + 列表 / 看板；平板保留主要列、点行进表单（pages.md §7） -->
      <template v-else>
        <Form class="search-bar" layout="inline" @submit.prevent>
          <FormItem label="状态">
            <Select
              v-model:value="filters.status"
              allow-clear
              :max-tag-count="2"
              mode="multiple"
              :options="TASK_STATUS_OPTIONS"
              placeholder="全部"
              style="width: 220px"
            />
          </FormItem>
          <FormItem label="病区">
            <Select
              v-model:value="filters.wardIds"
              allow-clear
              :max-tag-count="2"
              mode="multiple"
              :options="wardOptions"
              placeholder="全部"
              style="width: 200px"
            />
          </FormItem>
          <FormItem label="计划日期">
            <RangePicker
              v-model:value="filters.dueRange"
              value-format="YYYY-MM-DD"
            />
          </FormItem>
          <FormItem label="负责人">
            <Select
              v-model:value="filters.assignee"
              :options="ASSIGNEE_FILTER_OPTIONS"
              style="width: 120px"
            />
          </FormItem>
          <FormItem>
            <Space>
              <Button
                data-testid="task-search"
                type="primary"
                @click="handleSearch"
              >
                查询
              </Button>
              <Button @click="handleReset">重置</Button>
            </Space>
          </FormItem>
        </Form>

        <!-- E19-T07 / A-DASH-009 · 医护负责患者数钻取：显示责任医生，关闭即取消
             （页面筛选条暂无责任医生控件，与窗口 / 完成时间钻取同口径） -->
        <div v-if="doctorDrill" class="drill-bar">
          <Tag
            closable
            color="blue"
            data-testid="task-doctor-drill"
            @close.prevent="clearDoctorDrill"
          >
            责任医生：{{ doctorDrill.label }}
          </Tag>
        </div>

        <div class="view-switch">
          <Button
            :type="viewMode === 'list' ? 'primary' : 'default'"
            size="small"
            @click="viewMode = 'list'"
          >
            列表
          </Button>
          <Button
            :type="viewMode === 'board' ? 'primary' : 'default'"
            size="small"
            @click="viewMode = 'board'"
          >
            看板
          </Button>
          <Button
            v-if="canAssign"
            data-testid="task-batch-assign"
            :disabled="selectedIds.length === 0"
            size="small"
            type="primary"
            @click="openAssign(selectedIds)"
          >
            批量改派（{{ selectedIds.length }}）
          </Button>
        </div>

        <div v-if="viewMode === 'list'" class="table-wrap">
          <Table
            :columns="columns"
            :custom-row="isTablet ? tableCustomRow : undefined"
            :data-source="dataSource"
            :loading="loading"
            :pagination="{
              current: pageNo,
              pageSize,
              showSizeChanger: false,
              total,
              onChange: handleTableChange,
              showTotal: (count: number) => `共 ${count} 条`,
            }"
            :row-selection="canAssign ? rowSelection : undefined"
            row-key="id"
            size="middle"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'patientName'">
                <div>{{ record.patientName }}</div>
                <div class="sub-text">{{ record.mrn }}</div>
              </template>
              <template v-else-if="column.dataIndex === 'status'">
                <StatusTag
                  :status="record.statusText"
                  :tooltip="taskStatusTooltip(record.statusText)"
                  variant="dot"
                />
              </template>
              <template v-else-if="column.dataIndex === 'assigneeName'">
                <span v-if="record.assigneeName">{{
                  record.assigneeName
                }}</span>
                <span v-else class="pool-tag">待认领</span>
              </template>
              <template v-else-if="column.dataIndex === 'action'">
                <!-- 平板点行进表单：操作列拦截冒泡，避免点按钮同时触发行点击 -->
                <span class="action-cell" @click.stop>
                  <Button
                    v-if="isTaskEnterable(record as TaskRow) && canEditRecord"
                    :data-testid="`task-enter-${record.id}`"
                    :loading="startingId === record.id"
                    size="small"
                    type="link"
                    @click="handleEnter(record as TaskRow)"
                  >
                    录入
                  </Button>
                  <Button
                    v-else-if="record.recordId"
                    size="small"
                    type="link"
                    @click="openReadonly(record as TaskRow)"
                  >
                    查看
                  </Button>
                  <Button
                    v-if="
                      isTaskAcknowledgable(record as TaskRow) && canEditRecord
                    "
                    :data-testid="`task-ack-${record.id}`"
                    :loading="acknowledgingId === record.id"
                    size="small"
                    type="link"
                    @click="handleAcknowledge(record as TaskRow)"
                  >
                    完成提醒
                  </Button>
                  <!-- S5 / A-FU-012：失访候选医生可「确认失访」（随访员只看到候选标记） -->
                  <Button
                    v-if="
                      isTaskLostCandidate(record as TaskRow) && canEditRecord
                    "
                    :data-testid="`task-lost-${record.id}`"
                    :loading="lostConfirmingId === record.id"
                    size="small"
                    type="link"
                    @click="openConfirmLost(record as TaskRow)"
                  >
                    确认失访
                  </Button>
                  <Button
                    v-if="isTaskClaimable(record as TaskRow)"
                    :data-testid="`task-claim-${record.id}`"
                    :loading="claimingId === record.id"
                    size="small"
                    type="link"
                    @click="handleClaim(record as TaskRow)"
                  >
                    认领
                  </Button>
                  <Button
                    v-if="isTaskReassignable(record as TaskRow) && canAssign"
                    :data-testid="`task-assign-${record.id}`"
                    size="small"
                    type="link"
                    @click="openAssign([record.id])"
                  >
                    改派
                  </Button>
                  <span
                    v-if="
                      !isTaskEnterable(record as TaskRow) &&
                      !record.recordId &&
                      !isTaskAcknowledgable(record as TaskRow) &&
                      !isTaskClaimable(record as TaskRow) &&
                      !(
                        isTaskLostCandidate(record as TaskRow) && canEditRecord
                      ) &&
                      !(isTaskReassignable(record as TaskRow) && canAssign)
                    "
                    class="sub-text"
                  >
                    —
                  </span>
                </span>
              </template>
            </template>
            <template #emptyText>
              <Empty :description="emptyInfo.description">
                <Button
                  v-if="hasActiveFilters"
                  data-testid="task-empty-reset"
                  type="primary"
                  @click="handleEmptyAction"
                >
                  清除筛选
                </Button>
                <Button
                  v-else-if="canCreatePlan"
                  data-testid="task-empty-create-plan"
                  type="primary"
                  @click="handleEmptyAction"
                >
                  去新建计划
                </Button>
              </Empty>
            </template>
          </Table>
        </div>

        <div v-else class="board">
          <Spin :spinning="loading">
            <div class="board-columns">
              <div
                v-for="group in groups"
                :key="group.key"
                class="board-column"
              >
                <div class="board-column-title">
                  {{ group.label }}
                  <span class="sub-text">{{ group.rows.length }}</span>
                </div>
                <div
                  v-for="task in group.rows"
                  :key="task.id"
                  class="board-card"
                  :data-testid="`board-card-${task.id}`"
                  @click="handleEnter(task)"
                >
                  <div class="board-card-patient">{{ task.patientName }}</div>
                  <div class="sub-text">{{ task.nodeName }}</div>
                  <div class="sub-text">
                    {{ taskWindowText(task.windowStart, task.windowEnd) }}
                  </div>
                  <div class="board-card-footer">
                    <StatusTag
                      :status="taskDisplayStatus(task)"
                      variant="dot"
                    />
                    <span class="sub-text">{{
                      task.assigneeName ?? '待认领'
                    }}</span>
                  </div>
                </div>
                <div
                  v-if="group.rows.length === 0"
                  class="sub-text board-empty"
                >
                  暂无任务
                </div>
              </div>
            </div>
          </Spin>
        </div>
      </template>
    </Card>

    <!-- 改派弹窗（单个与批量共用 batch-assign；改派后新负责人收站内信；
         手机无批量改派，仅卡片 / 行内单条入口） -->
    <TaskAssignModal
      v-model:open="assignOpen"
      :count="assignTargetIds.length"
      :task-ids="assignTargetIds"
      @success="handleAssignSuccess"
    />
    <!-- 临时任务（E15-T06 / A-FU-013：不占用年度任务；FORM / REMINDER 两形态） -->
    <TempTaskModal v-model:open="tempOpen" @created="onTempCreated" />
    <!-- E24-T05 · 手机筛选抽屉（病区 / 计划日期 / 负责人；状态分段常驻顶栏） -->
    <TaskFilterDrawer
      v-model:open="filterOpen"
      :filters="{
        assignee: filters.assignee,
        dueRange: filters.dueRange,
        wardIds: filters.wardIds,
      }"
      placement="bottom"
      :ward-options="wardOptions"
      @reset="handleReset"
      @search="handleDrawerSearch"
      @update:filters="Object.assign(filters, $event)"
    />
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  padding: 16px;
}

/* E19-T07 医护负责患者数钻取标签条（与患者列表完整度钻取同形态） */
.drill-bar {
  margin-bottom: 12px;
}

/* 手机底栏无需让位（本页无固定底栏），仅收窄留白 */
.page-mobile {
  padding: 12px;
}

.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.search-bar {
  row-gap: 8px;
}

.view-switch {
  display: flex;
  gap: 8px;
  margin: 8px 0;
}

/* 平板点行进表单：行内可点（customRow 注入 class；:deep 穿透 antd 表格行） */
:deep(.row-clickable) {
  cursor: pointer;
}

/* 操作列按钮包一层 span（平板行点击时 click.stop 拦截冒泡） */
.action-cell {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}

/* E24-T05 · 手机状态分段与筛选入口 */
.mobile-filter-row {
  display: flex;
  margin: 8px 0;
}

.filter-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  margin-left: 4px;
  font-size: 12px;
  line-height: 1;
  vertical-align: 2px;
  color: #fff;
  border-radius: 8px;
}

.segment-chip {
  margin-top: 8px;
}

/* E24-T05 · 手机任务卡片（患者、节点、窗口、状态、逾期标记；触控 ≥ 40px 由 globalCss）。
   逾期标记 = 卡片左侧 error 色竖条（内联 style 注入 tokens colors.error）+ 状态 Tag error 色 */
.task-item {
  margin-bottom: 8px;
  cursor: pointer;
}

.item-line {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.item-patient {
  font-weight: 600;
}

.item-footer {
  margin-top: 4px;
}

.item-actions {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
  justify-content: flex-end;
}

.overdue-flag {
  font-size: 12px;
  font-weight: 600;
}

/* 手机卡片分页：总数 + 简单分页居中 */
.card-pagination {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: center;
  padding-top: 4px;
}

.sub-text {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

/* 待认领（病区公共池，A-FU-009）：负责人为空的行 */
.pool-tag {
  font-size: 12px;
  color: rgb(250 140 22);
}

.board-columns {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  min-height: 320px;
}

.board-column {
  padding: 12px;
  background: rgb(0 0 0 / 2%);
  border-radius: 8px;
}

.board-column-title {
  margin-bottom: 8px;
  font-weight: 600;
}

.board-card {
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  background: #fff;
  border: 1px solid rgb(0 0 0 / 8%);
  border-radius: 6px;
}

.board-card:hover {
  border-color: rgb(58 117 255 / 45%);
}

.board-card-patient {
  margin-bottom: 2px;
  font-weight: 500;
}

.board-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
}

.board-empty {
  padding: 8px 0;
  text-align: center;
}
</style>
