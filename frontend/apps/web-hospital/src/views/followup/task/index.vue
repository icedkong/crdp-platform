<script lang="ts" setup>
import type { TaskRow, TaskScope, TaskStatus } from '@crdp/api';

import type { AssigneeFilter } from './logic';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  contactResultText,
  isTaskAcknowledgable,
  isTaskClaimable,
  isTaskEnterable,
  isTaskReassignable,
  TASK_STATUS_OPTIONS,
  TASK_TABS,
  taskDisplayStatus,
  taskStatusTooltip,
  taskWindowText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { sizes } from '@crdp/tokens';
import {
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  FormItem,
  message,
  Result,
  Select,
  Space,
  Spin,
  Table,
  TabPane,
  Tabs,
} from 'ant-design-vue';

import {
  acknowledgeTask,
  claimTask,
  searchTasks,
  startTask,
} from '#/api/core/task';
import TaskAssignModal from '#/components/followup/task-assign-modal.vue';
import TempTaskModal from '#/components/followup/temp-task-modal.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import {
  ASSIGNEE_FILTER_OPTIONS,
  boardGroups,
  buildSearchPayload,
  dueRangeOfQuery,
  queryOfScope,
  recordFormPath,
  recordRouteTarget,
  scopeOfQuery,
  statusOfQuery,
  TASK_EMPTY_TEXT,
} from './logic';

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
 * 任务卡片手机响应式随 E24-T05。
 */
const route = useRoute();
const router = useRouter();
const { hasPermission } = usePermission();
const canEditRecord = computed(() => hasPermission('followup:record:edit'));
const canAssign = computed(() => hasPermission('followup:task:assign'));
const canCreatePlan = computed(() => hasPermission('followup:plan:edit'));
const { isMobile } = useBreakpoint();

const RangePicker = DatePicker.RangePicker;

// ---------------------------------------------------------------- 筛选与分页状态

const scope = ref<TaskScope>(scopeOfQuery(route.query.tab));
const filters = reactive({
  assignee: 'ALL' as AssigneeFilter,
  dueRange: null as [string, string] | null,
  status: undefined as null | TaskStatus[],
  wardIds: [] as number[],
});
const applied = ref({
  assignee: 'ALL' as AssigneeFilter,
  dueRange: null,
  status: null,
  wardIds: [],
});
const pageNo = ref(1);
const pageSize = ref(sizes.tablePageSize);

const loading = ref(false);
const rows = ref<TaskRow[]>([]);
const total = ref(0);
const tabCounts = ref({ mine: 0, total: 0, ward: 0 });
const wardOptions = ref<Array<{ label: string; value: number }>>([]);
const viewMode = ref<'board' | 'list'>('list');

const payload = computed(() =>
  buildSearchPayload({ ...filters, scope: scope.value }),
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
    applied.value = structuredClone(filters);
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
  filters.dueRange = null;
  filters.status = null;
  filters.wardIds = [];
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

// ---------------------------------------------------------------- 临时任务（E15-T06 / A-FU-013）

const tempOpen = ref(false);

function onTempCreated() {
  void load();
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

const columns = computed(() => [
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
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else class="page" data-testid="task-list">
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
              <span v-if="record.assigneeName">{{ record.assigneeName }}</span>
              <span v-else class="pool-tag">待认领</span>
            </template>
            <template v-else-if="column.dataIndex === 'action'">
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
                v-if="isTaskAcknowledgable(record as TaskRow) && canEditRecord"
                :data-testid="`task-ack-${record.id}`"
                :loading="acknowledgingId === record.id"
                size="small"
                type="link"
                @click="handleAcknowledge(record as TaskRow)"
              >
                完成提醒
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
                  !(isTaskReassignable(record as TaskRow) && canAssign)
                "
                class="sub-text"
              >
                —
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
            <div v-for="group in groups" :key="group.key" class="board-column">
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
                  <StatusTag :status="taskDisplayStatus(task)" variant="dot" />
                  <span class="sub-text">{{
                    task.assigneeName ?? '待认领'
                  }}</span>
                </div>
              </div>
              <div v-if="group.rows.length === 0" class="sub-text board-empty">
                暂无任务
              </div>
            </div>
          </div>
        </Spin>
      </div>
    </Card>

    <!-- 改派弹窗（单个与批量共用 batch-assign；改派后新负责人收站内信） -->
    <TaskAssignModal
      v-model:open="assignOpen"
      :count="assignTargetIds.length"
      :task-ids="assignTargetIds"
      @success="handleAssignSuccess"
    />
    <!-- 临时任务（E15-T06 / A-FU-013：不占用年度任务；FORM / REMINDER 两形态） -->
    <TempTaskModal v-model:open="tempOpen" @created="onTempCreated" />
  </div>
</template>

<style scoped>
.page {
  min-height: 100%;
  padding: 16px;
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
