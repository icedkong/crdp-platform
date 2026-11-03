<script lang="ts" setup>
import type { PatientTimelineEntry } from '@crdp/api';

import { computed, onMounted, ref } from 'vue';

import { PATIENT_STATUS_TEXT, timelineEntryLabel } from '@crdp/api';
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
 * E13-T05 / A-PAT-013（components.md §9）：患者详情「随访记录」页签右侧内容。
 * 当前节点：出院（DISCHARGE）与状态变更事件（STATUS，含死亡），按时间顺序
 * （旧 → 新）；每年随访 / 计划外随访 / 终点事件节点随 E15 随访记录接入
 * （届时补计划日期 / 窗口 / 完成日期 / 标红列与展开查看），C 器械事件随 E21。
 * 视图切换记住用户上次选择（localStorage crdp:fu-view，只存视图名，不含患者信息）。
 */
const props = defineProps<{
  entries: PatientTimelineEntry[];
  patientId: string;
}>();

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

const jumpOptions = computed(() =>
  props.entries.map((entry, index) => ({
    label: `${timelineEntryLabel(entry)}（${entry.date}）`,
    value: index,
  })),
);

function jumpTo(index: number) {
  setView('timeline');
  document
    .querySelector(`#timeline-item-${index}`)
    ?.scrollIntoView({ block: 'start' });
}

/** 图例只列时间轴上实际出现的状态（当前为患者随访状态；任务状态随 E15）。 */
const legendStatuses = computed(() => {
  const seen: string[] = [];
  for (const entry of props.entries) {
    const text = entry.status ? PATIENT_STATUS_TEXT[entry.status] : undefined;
    if (entry.kind === 'STATUS' && text && !seen.includes(text)) {
      seen.push(text);
    }
  }
  return seen;
});

const tableColumns = [
  { dataIndex: 'label', title: '节点' },
  { dataIndex: 'status', title: '状态' },
  { dataIndex: 'date', title: '日期' },
  { dataIndex: 'note', title: '备注' },
];

const tableRows = computed(() =>
  props.entries.map((entry, index) => ({
    date: entry.date,
    key: `${entry.kind}-${entry.date}-${index}`,
    label: timelineEntryLabel(entry),
    note: entry.note ?? '—',
    status: entry.status
      ? (PATIENT_STATUS_TEXT[entry.status] ?? entry.status)
      : '',
  })),
);
</script>

<template>
  <div :data-patient-id="patientId" data-testid="followup-timeline">
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
      description="暂无时间轴节点：出院登记与状态变更会显示在这里，随访记录随随访业务开放"
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
            v-if="column.dataIndex === 'status' && record.status"
            :status="record.status"
            variant="dot"
          />

          <span v-else-if="column.dataIndex === 'status'">—</span>
        </template>
      </Table>
    </div>

    <div v-else class="cards">
      <Timeline>
        <TimelineItem
          v-for="(entry, index) in entries"
          :key="index"
          color="primary"
        >
          <Card :id="`timeline-item-${index}`" size="small">
            <div class="node-head">
              <strong>{{ timelineEntryLabel(entry) }}</strong>
              <StatusTag
                v-if="entry.kind === 'STATUS'"
                :status="
                  PATIENT_STATUS_TEXT[entry.status ?? ''] ?? entry.status ?? ''
                "
                variant="tag"
              />
              <span v-else class="kind" :style="{ color: colors.primary }">
                出院节点
              </span>
              <span class="date">{{ entry.date }}</span>
            </div>
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
</style>
