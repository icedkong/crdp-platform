<script lang="ts" setup>
import type { PatientTimelineEntry } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';

import {
  PATIENT_STATUS_TEXT,
  taskDisplayStatus,
  TIMELINE_PLANNED_TEXT,
  timelineEntryLabel,
  timelineProgressText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { colors } from '@crdp/tokens';
import {
  Card,
  Empty,
  Segmented,
  Select,
  Table,
  Timeline,
  TimelineItem,
} from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

/**
 * E15-T07 / A-FU-023、C-FU-023（components.md §9）：患者详情「随访记录」页签右侧内容。
 *
 * 节点：出院（DISCHARGE）、状态变更（STATUS）、每年随访（FOLLOWUP，带计划日期 / 窗口 / 完成日期 /
 * 完成进度 x/y；REMINDER 站内提醒任务同为任务节点，note 标「无需填表」）、历史补录（BACKFILL，
 * 单独标识）、计划外随访（UNPLANNED，E15-T06 / C-FU-022：单独成节点、不影响后续节点）、
 * 下次计划日期（PLANNED「计划中」，canOpen=false 置灰：任务开启日才创建）；终点事件随 E17、
 * C 器械事件随 E21。点节点卡片 canOpen 时 emit open（有 recordId 直接开表单页；未开始任务先
 * start 再开，由父页处理）。视图切换记住用户上次选择（localStorage crdp:fu-view，只存视图名，
 * 不含患者信息）。
 */
const props = defineProps<{
  entries: PatientTimelineEntry[];
  patientId: string;
}>();

const emit = defineEmits<{
  (event: 'open', entry: PatientTimelineEntry): void;
}>();

/** 颜色全部取自 tokens（不写魔法数）；stylelint 不认 v-bind()，走 CSS 变量注入。 */
const cssVars = {
  '--tl-primary': colors.primary,
};

const VIEW_KEY = 'crdp:fu-view';
const view = ref<'table' | 'timeline'>('timeline');
const { isMobile } = useBreakpoint();

onMounted(() => {
  const saved = localStorage.getItem(VIEW_KEY);
  if (saved === 'table' || saved === 'timeline') view.value = saved;
});

function setView(next: 'table' | 'timeline') {
  view.value = next;
  localStorage.setItem(VIEW_KEY, next);
}

/** 是否随访类节点（任务 / 补录 / 计划外 / 计划中——图例与状态列按任务状态口径展示）。 */
function isFollowupKind(entry: PatientTimelineEntry): boolean {
  return (
    entry.kind === 'FOLLOWUP' ||
    entry.kind === 'BACKFILL' ||
    entry.kind === 'UNPLANNED'
  );
}

/** 节点显示状态（StatusTag 文案）：FOLLOWUP 用任务状态派生（含补录 / 逾期完成细分）、PLANNED 固定「计划中」。 */
function nodeStatusText(entry: PatientTimelineEntry): string {
  if (entry.kind === 'PLANNED') return TIMELINE_PLANNED_TEXT;
  if (isFollowupKind(entry) && entry.status) {
    return taskDisplayStatus({
      completeKind: (entry.completeKind ?? undefined) as
        | 'BEFORE_WINDOW'
        | 'ON_TIME'
        | 'OVERDUE'
        | undefined,
      createType: entry.createType,
      status: entry.status as Parameters<typeof taskDisplayStatus>[0]['status'],
    });
  }
  if (entry.kind === 'STATUS' && entry.status) {
    return PATIENT_STATUS_TEXT[entry.status] ?? entry.status;
  }
  return '';
}

/** 可点击（canOpen=true 的随访类节点；出院 / 状态事件是信息节点，不可点）。 */
function isClickable(entry: PatientTimelineEntry): boolean {
  return (
    entry.canOpen === true &&
    entry.kind !== 'DISCHARGE' &&
    entry.kind !== 'STATUS'
  );
}

/** 置灰（计划中：任务开启日才创建，components.md §9 置灰 + 提示）。 */
function isDimmed(entry: PatientTimelineEntry): boolean {
  return entry.canOpen === false;
}

/** 节点主日期显示：随访节点用计划日期（补录 / 计划外用随访日期），其余用 date。 */
function nodeDate(entry: PatientTimelineEntry): string {
  return entry.planDate ?? entry.date;
}

/** 窗口文案（无窗口的节点为空）。 */
function windowText(entry: PatientTimelineEntry): string {
  if (!entry.windowStart || !entry.windowEnd) return '';
  return `${entry.windowStart} ~ ${entry.windowEnd}`;
}

function nodeDetail(entry: PatientTimelineEntry): string {
  const parts: string[] = [];
  if (entry.kind === 'FOLLOWUP' || entry.kind === 'PLANNED') {
    parts.push(`计划 ${nodeDate(entry)}`);
  } else if (entry.kind === 'BACKFILL' || entry.kind === 'UNPLANNED') {
    parts.push(`随访日期 ${nodeDate(entry)}`);
  } else {
    parts.push(entry.date);
  }
  const window = windowText(entry);
  if (window) parts.push(`窗口 ${window}`);
  if (entry.doneDate) parts.push(`完成 ${entry.doneDate}`);
  const progress = timelineProgressText(entry);
  if (progress) parts.push(progress);
  if (entry.highlightCount !== null && entry.highlightCount !== undefined) {
    parts.push(`标红 ${entry.highlightCount}`);
  }
  return parts.join(' · ');
}

function onNodeClick(entry: PatientTimelineEntry) {
  if (isClickable(entry)) emit('open', entry);
}

const jumpOptions = computed(() =>
  props.entries.map((entry, index) => ({
    label: `${timelineEntryLabel(entry)}（${nodeDate(entry)}）`,
    value: index,
  })),
);

function jumpTo(index: number) {
  setView('timeline');
  document
    .querySelector(`#timeline-item-${index}`)
    ?.scrollIntoView({ block: 'start' });
}

/** 图例只列时间轴上实际出现的显示状态。 */
const legendStatuses = computed(() => {
  const seen: string[] = [];
  for (const entry of props.entries) {
    const text = nodeStatusText(entry);
    if (text && !seen.includes(text)) seen.push(text);
  }
  return seen;
});

const tableColumns = [
  { dataIndex: 'label', title: '节点' },
  { dataIndex: 'statusText', title: '状态' },
  { dataIndex: 'planDate', title: '计划日期' },
  { dataIndex: 'windowText', title: '窗口' },
  { dataIndex: 'doneDate', title: '完成日期' },
  { dataIndex: 'progress', title: '进度' },
  { dataIndex: 'note', title: '备注' },
  { dataIndex: 'action', title: '操作' },
];

const tableRows = computed(() =>
  props.entries.map((entry, index) => ({
    action: entry.canOpen ? '打开' : '—',
    doneDate: entry.doneDate ?? '—',
    key: `${entry.kind}-${entry.date}-${index}`,
    label: timelineEntryLabel(entry),
    note: entry.note ?? '—',
    planDate: entry.planDate ?? '—',
    progress: timelineProgressText(entry) || '—',
    statusText: nodeStatusText(entry) || '—',
    windowText: windowText(entry) || '—',
  })),
);
</script>

<template>
  <div
    :data-patient-id="patientId"
    :style="cssVars"
    data-testid="followup-timeline"
  >
    <div class="toolbar">
      <Segmented
        :options="[
          { label: '时间轴', value: 'timeline' },
          { label: '表格', value: 'table' },
        ]"
        :value="view"
        @update:value="setView($event as 'table' | 'timeline')"
      />
      <Select
        v-if="entries.length > 0"
        allow-clear
        placeholder="跳到节点"
        :options="jumpOptions"
        style="width: 220px"
        @update:value="($event) => jumpTo(Number($event))"
      />
      <span v-if="legendStatuses.length > 0" class="legend">
        状态：
        <StatusTag
          v-for="status in legendStatuses"
          :key="status"
          :status="status"
        />
      </span>
    </div>

    <Empty
      v-if="entries.length === 0"
      description="暂无时间轴节点：出院登记、状态变更与随访记录会显示在这里"
    />

    <div v-else-if="view === 'table' && !isMobile" class="table">
      <Table
        :columns="tableColumns"
        :data-source="tableRows"
        :pagination="false"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <StatusTag
            v-if="
              column.dataIndex === 'statusText' && record.statusText !== '—'
            "
            :status="record.statusText"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'statusText'">—</span>
        </template>
      </Table>
    </div>

    <div v-else class="cards">
      <Timeline>
        <TimelineItem
          v-for="(entry, index) in entries"
          :key="index"
          :color="entry.kind === 'PLANNED' ? 'gray' : 'primary'"
        >
          <Card
            :id="`timeline-item-${index}`"
            class="node"
            :class="{
              clickable: isClickable(entry),
              disabled: isDimmed(entry),
            }"
            :data-testid="`timeline-node-${entry.kind}`"
            size="small"
            @click="onNodeClick(entry)"
          >
            <div class="node-head">
              <strong>{{ timelineEntryLabel(entry) }}</strong>
              <StatusTag
                v-if="nodeStatusText(entry)"
                :status="nodeStatusText(entry)"
                variant="tag"
              />
              <span
                v-if="entry.kind !== 'STATUS'"
                class="kind"
                :style="{ color: colors.primary }"
              >
                {{
                  entry.kind === 'DISCHARGE'
                    ? '出院节点'
                    : entry.kind === 'PLANNED'
                      ? '下次计划日期'
                      : entry.kind === 'BACKFILL'
                        ? '补录'
                        : '随访节点'
                }}
              </span>
              <span class="date">{{ nodeDate(entry) }}</span>
            </div>
            <p class="detail">{{ nodeDetail(entry) }}</p>
            <p v-if="entry.note" class="note">{{ entry.note }}</p>
          </Card>
        </TimelineItem>
      </Timeline>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: #8c8c8c;
}

.note {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}

.detail {
  margin: 4px 0 0;
  font-size: 12px;
}

.node-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.date {
  font-size: 12px;
  color: #8c8c8c;
}

.kind {
  font-size: 12px;
}

.node {
  cursor: default;
}

.node.clickable {
  cursor: pointer;
}

.node.clickable:hover {
  border-color: var(--tl-primary);
}

.node.disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
