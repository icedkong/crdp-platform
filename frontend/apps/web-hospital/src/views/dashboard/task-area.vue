<script lang="ts" setup>
import type {
  BoardScope,
  TaskBoardView,
  TaskRow,
  TaskTableTab,
  TaskTableView,
} from '@crdp/api';

import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import {
  BOARD_SCOPE_OPTIONS,
  boardColumnRoute,
  contactResultText,
  isTaskAcknowledgable,
  isTaskEnterable,
  isTaskReassignable,
  TASK_TABLE_TABS,
  taskDisplayStatus,
  taskTableTabRoute,
  taskWindowText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import {
  Button,
  Card,
  Empty,
  message,
  Segmented,
  Select,
  Space,
  Spin,
  TabPane,
  Tabs,
} from 'ant-design-vue';

import { fetchTaskBoardCard, fetchTaskTableCard } from '#/api/core/dashboard';
import { fetchDoctorOptions } from '#/api/core/patient';
import { acknowledgeTask, startTask } from '#/api/core/task';
import ContactLogModal from '#/components/followup/contact-log-modal.vue';
import TaskAssignModal from '#/components/followup/task-assign-modal.vue';
import { usePermission } from '#/reference/use-permission';

import { recordFormPath, recordRouteTarget } from '../followup/task/logic';

defineOptions({ name: 'DashboardTaskArea' });

/**
 * E19-T03 / A-DASH-003、A-DASH-004、A-DASH-008、C-DASH-003 · 首页任务区（pages.md §2）：
 * 「看板 | 任务表」切换——看板按状态四列（待随访 / 进行中 / 逾期 / 本月已完成〔C 本周完成〕），
 * 每列前 20 张卡片（其余「查看全部」进任务列表，筛选由后端随列下发）；看板筛选：我的 /
 * 本病区 / 全部（A-DASH-004）+ 负责人。任务表页签：今日任务 / 超时任务 / 我负责的任务
 * （页签显示数量），行可直接进入随访工作台（录入 / 完成提醒）。
 *
 * 病区隔离与计数口径全部在后端（@DataScope 出行；与任务列表同一条件树）；页面级病区筛选
 * wardId 由首页头部下发（A-DASH-005），变更时联动重载。卡片快速操作（A-DASH-003）：记录
 * 联系（ContactLogModal）、录入随访（POST /tasks/{id}/start → 新标签页工作台）、改派
 * （TaskAssignModal，followup:task:assign）。
 */

const props = defineProps<{
  /** 页面级病区筛选（A-DASH-005）：全部卡片共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const router = useRouter();
const { hasPermission } = usePermission();
const canEditRecord = computed(() => hasPermission('followup:record:edit'));
const canAssign = computed(() => hasPermission('followup:task:assign'));

const taskMode = ref<'board' | 'table'>('board');
const EMPTY_HINT = '暂无随访任务 · 去新建计划';

// ---------------------------------------------------------------- 看板（A-DASH-003 / A-DASH-004）

const boardScope = ref<BoardScope>('MINE');
const assigneeId = ref<number | undefined>();
const boardLoading = ref(false);
const board = ref<null | TaskBoardView>(null);
const assigneeOptions = ref<Array<{ label: string; value: number }>>([]);

async function loadBoard() {
  boardLoading.value = true;
  try {
    board.value = await fetchTaskBoardCard({
      assigneeId: assigneeId.value,
      scope: boardScope.value,
      wardId: props.wardId,
    });
  } catch {
    message.error('任务看板加载失败，请稍后重试');
  } finally {
    boardLoading.value = false;
  }
}

/** 负责人筛选候选（A-DASH-004「按负责人筛选」；与改派弹窗同源 doctor-options）。 */
async function loadAssigneeOptions() {
  try {
    const options = await fetchDoctorOptions();
    assigneeOptions.value = options.doctors.map((item) => ({
      label: item.displayName,
      value: Number(item.id),
    }));
  } catch {
    // 候选加载失败不阻断看板（筛选项留空）
  }
}

/** 列「查看全部」→ 任务列表（筛选由后端随列下发，只透传 query）。 */
function viewAllOfColumn(column: {
  drill?: null | { query?: Record<string, unknown> };
}) {
  const target = boardColumnRoute({ drill: column.drill ?? null });
  if (target) {
    void router.push(target);
  }
}

// ---------------------------------------------------------------- 任务表（A-DASH-008）

const tableTab = ref<TaskTableTab>('TODAY');
const tableLoading = ref(false);
const table = ref<null | TaskTableView>(null);

async function loadTable() {
  tableLoading.value = true;
  try {
    table.value = await fetchTaskTableCard({
      tab: tableTab.value,
      wardId: props.wardId,
    });
  } catch {
    message.error('任务表加载失败，请稍后重试');
  } finally {
    tableLoading.value = false;
  }
}

function handleTableTabChange(tab: number | string) {
  const found = TASK_TABLE_TABS.find((item) => item.value === tab);
  if (!found) return;
  tableTab.value = found.value;
  void loadTable();
}

/** 页签「查看全部」→ 任务列表。 */
function viewAllOfTab(
  tab: null | { drill?: null | { query?: Record<string, unknown> } },
) {
  if (!tab) return;
  const target = taskTableTabRoute({ drill: tab.drill ?? null });
  if (target) {
    void router.push(target);
  }
}

// ---------------------------------------------------------------- 卡片快速操作（A-DASH-003）

/** 记录联系：ContactLogModal（患者 + 任务上下文，成功后刷新）。 */
const contactOpen = ref(false);
const contactTarget = ref<null | { patientId: string; taskId: string }>(null);

function openContact(row: TaskRow) {
  contactTarget.value = { patientId: row.patientId, taskId: row.id };
  contactOpen.value = true;
}

/** 改派：TaskAssignModal（单个 = 传一条 taskIds，A-FU-009）。 */
const assignOpen = ref(false);
const assignTargetIds = ref<Array<number | string>>([]);

function openAssign(row: TaskRow) {
  assignTargetIds.value = [row.id];
  assignOpen.value = true;
}

/** 录入随访：无草稿先 start 换 recordId，新标签页打开随访工作台（A-FU-005「从任务进入」）。 */
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
    window.open(recordFormPath(recordId, { scope: 'ALL' }), '_blank');
  } catch {
    message.error('开始录入失败，请稍后重试');
  } finally {
    startingId.value = '';
  }
}

/** 完成提醒（站内提醒类任务无需填表，确认即完成，A-FU-026；今日任务含站内提醒）。 */
const acknowledgingId = ref('');

async function handleAcknowledge(row: TaskRow) {
  acknowledgingId.value = row.id;
  try {
    await acknowledgeTask(row.id);
    message.success('提醒已确认，任务完成（无需填表）');
    void reload();
  } catch (error) {
    const body = error as { message?: string };
    message.error(body?.message || '完成提醒失败，请稍后重试');
  } finally {
    acknowledgingId.value = '';
  }
}

function reload() {
  if (taskMode.value === 'board') {
    void loadBoard();
  } else {
    void loadTable();
  }
}

// ---------------------------------------------------------------- 装配

/** 病区筛选（页面级，A-DASH-005）与看板筛选（A-DASH-004）变更 → 按新参数重载。 */
watch(
  () => props.wardId,
  () => reload(),
);
watch(boardScope, () => {
  void loadBoard();
});
watch(assigneeId, () => {
  void loadBoard();
});
watch(taskMode, () => reload());

void loadAssigneeOptions();
void loadBoard();
</script>

<template>
  <Card class="task-card" title="随访任务">
    <template #extra>
      <Space wrap>
        <Segmented
          v-model:value="taskMode"
          :options="[
            { label: '看板', value: 'board' },
            { label: '任务表', value: 'table' },
          ]"
          data-testid="task-mode"
        />
        <template v-if="taskMode === 'board'">
          <Segmented
            v-model:value="boardScope"
            :options="BOARD_SCOPE_OPTIONS"
            data-testid="board-scope"
          />
          <Select
            v-model:value="assigneeId"
            allow-clear
            :options="assigneeOptions"
            placeholder="负责人"
            :style="{ width: 140 }"
            data-testid="board-assignee"
          />
        </template>
      </Space>
    </template>

    <!-- 看板（A-DASH-003：按状态四列，每列前 20 张卡片；其余「查看全部」） -->
    <div
      v-if="taskMode === 'board'"
      class="board"
      data-testid="dashboard-board"
    >
      <Spin :spinning="boardLoading">
        <div class="board-columns">
          <div
            v-for="column in board?.columns ?? []"
            :key="column.status"
            class="board-column"
            :data-testid="`board-column-${column.status}`"
          >
            <div class="board-column-title">
              {{ column.title }}
              <span class="sub-text">{{ column.total }}</span>
            </div>
            <div
              v-for="task in column.tasks"
              :key="task.id"
              class="board-card"
              :data-testid="`dashboard-board-card-${task.id}`"
            >
              <div class="board-card-patient">{{ task.patientName }}</div>
              <div class="sub-text">
                {{ task.wardName ?? '—' }} · {{ task.nodeName ?? '—' }}
              </div>
              <div class="sub-text">
                窗口至 {{ taskWindowText(task.windowStart, task.windowEnd) }}
              </div>
              <div class="board-card-footer">
                <StatusTag :status="taskDisplayStatus(task)" variant="dot" />
                <span class="sub-text">{{
                  task.assigneeName ?? '待认领'
                }}</span>
              </div>
              <div class="sub-text">
                最近联系：{{ contactResultText(task.lastContactResult) }}
              </div>
              <div class="board-card-actions">
                <Button size="small" type="link" @click="openContact(task)">
                  记录联系
                </Button>
                <Button
                  v-if="isTaskEnterable(task) && canEditRecord"
                  :data-testid="`dashboard-board-enter-${task.id}`"
                  :loading="startingId === task.id"
                  size="small"
                  type="link"
                  @click="handleEnter(task)"
                >
                  录入随访
                </Button>
                <Button
                  v-if="isTaskReassignable(task) && canAssign"
                  :data-testid="`dashboard-board-assign-${task.id}`"
                  size="small"
                  type="link"
                  @click="openAssign(task)"
                >
                  改派
                </Button>
              </div>
            </div>
            <div v-if="column.tasks.length === 0" class="sub-text board-empty">
              暂无任务
            </div>
            <Button
              v-if="column.total > column.tasks.length"
              block
              :data-testid="`board-view-all-${column.status}`"
              size="small"
              type="link"
              @click="viewAllOfColumn(column)"
            >
              查看全部（{{ column.total }}）
            </Button>
          </div>
        </div>
        <Empty
          v-if="!boardLoading && (board === null || board.columns.length === 0)"
          :description="EMPTY_HINT"
        />
      </Spin>
    </div>

    <!-- 任务表（A-DASH-008：今日 / 超时 / 我负责，页签显示数量；行可直接进入随访工作台） -->
    <div v-else class="table" data-testid="dashboard-task-table">
      <Tabs
        :active-key="tableTab"
        data-testid="dashboard-task-table-tabs"
        @change="handleTableTabChange"
      >
        <TabPane v-for="tab in TASK_TABLE_TABS" :key="tab.value">
          <template #tab>
            {{ tab.label }}
            {{ table?.tabs.find((item) => item.tab === tab.value)?.total ?? 0 }}
          </template>
        </TabPane>
      </Tabs>
      <Spin :spinning="tableLoading">
        <div class="table-rows">
          <div
            v-for="row in table?.items ?? []"
            :key="row.id"
            class="table-row"
            :data-testid="`dashboard-task-row-${row.id}`"
          >
            <div class="table-row-main">
              <span class="table-row-patient">{{ row.patientName }}</span>
              <span class="sub-text">
                {{ row.wardName ?? '—' }} · {{ row.nodeName ?? '—' }} ·
                {{ taskWindowText(row.windowStart, row.windowEnd) }}
              </span>
              <StatusTag :status="taskDisplayStatus(row)" variant="dot" />
            </div>
            <div class="table-row-actions">
              <span class="sub-text">{{ row.assigneeName ?? '待认领' }}</span>
              <Button
                v-if="isTaskEnterable(row) && canEditRecord"
                :data-testid="`dashboard-row-enter-${row.id}`"
                :loading="startingId === row.id"
                size="small"
                type="link"
                @click="handleEnter(row)"
              >
                录入
              </Button>
              <Button
                v-else-if="row.recordId"
                size="small"
                type="link"
                @click="handleEnter(row)"
              >
                查看
              </Button>
              <Button
                v-if="isTaskAcknowledgable(row) && canEditRecord"
                :data-testid="`dashboard-row-ack-${row.id}`"
                :loading="acknowledgingId === row.id"
                size="small"
                type="link"
                @click="handleAcknowledge(row)"
              >
                完成提醒
              </Button>
            </div>
          </div>
        </div>
        <Empty
          v-if="!tableLoading && (table === null || table.items.length === 0)"
          :description="EMPTY_HINT"
        />
        <div
          v-if="(table?.total ?? 0) > (table?.items.length ?? 0)"
          class="table-footer"
        >
          <Button
            data-testid="table-view-all"
            size="small"
            type="link"
            @click="
              viewAllOfTab(
                table?.tabs.find((item) => item.tab === tableTab) ?? null,
              )
            "
          >
            查看全部（{{ table?.total }}）
          </Button>
        </div>
      </Spin>
    </div>

    <!-- 快速操作弹窗：记录联系 / 改派（成功后刷新当前视图） -->
    <ContactLogModal
      v-model:open="contactOpen"
      :patient-id="contactTarget?.patientId"
      :task-id="contactTarget?.taskId"
      @success="reload"
    />
    <TaskAssignModal
      v-model:open="assignOpen"
      :count="assignTargetIds.length"
      :task-ids="assignTargetIds"
      @success="reload"
    />
  </Card>
</template>

<style scoped>
.task-card {
  border-radius: 8px;
}

.sub-text {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.board-columns {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  min-height: 240px;
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
  background: #fff;
  border: 1px solid rgb(0 0 0 / 8%);
  border-radius: 6px;
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

.board-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding-top: 4px;
  border-top: 1px dashed rgb(0 0 0 / 6%);
}

.board-empty {
  padding: 8px 0;
  text-align: center;
}

.table-rows {
  display: flex;
  flex-direction: column;
}

.table-row {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 8px 4px;
  border-bottom: 1px solid rgb(0 0 0 / 4%);
}

.table-row-main {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.table-row-patient {
  font-weight: 500;
}

.table-row-actions {
  display: flex;
  flex-shrink: 0;
  gap: 4px;
  align-items: center;
}

.table-footer {
  padding-top: 8px;
  text-align: center;
}
</style>
