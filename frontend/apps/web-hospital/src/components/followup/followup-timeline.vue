<script lang="ts" setup>
import type { DeviceEvent, TimelineNode } from '#/reference/types';

import { computed, onMounted, ref } from 'vue';

import { colors } from '@crdp/tokens';
import {
  Button,
  Card,
  Segmented,
  Select,
  Table,
  Timeline,
  TimelineItem,
  Tooltip,
} from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

import CategoryBar from './category-bar.vue';
import StatusTag from './status-tag.vue';

const props = defineProps<{
  events?: DeviceEvent[];
  nodes: TimelineNode[];
  patientId: string;
}>();

const emit = defineEmits<{
  jump: [recordId: string | undefined, key: string];
  'update:expandedRecordId': [id: string | undefined];
  'update:view': [view: 'table' | 'timeline'];
}>();

const VIEW_KEY = 'crdp:fu-view';
const view = ref<'table' | 'timeline'>('timeline');
const expanded = ref<string>();
const { isMobile } = useBreakpoint();

onMounted(() => {
  const saved = localStorage.getItem(VIEW_KEY);
  if (saved === 'table' || saved === 'timeline') view.value = saved;
});

function setView(next: 'table' | 'timeline') {
  view.value = next;
  localStorage.setItem(VIEW_KEY, next);
  emit('update:view', next);
}

function toggle(node: TimelineNode) {
  if (!node.recordId) return;
  expanded.value = expanded.value === node.recordId ? undefined : node.recordId;
  emit('update:expandedRecordId', expanded.value);
}

function jumpTo(recordId: string) {
  setView('timeline');
  expanded.value = recordId;
  emit('update:expandedRecordId', recordId);
  document
    .querySelector(`#rec-${recordId}`)
    ?.scrollIntoView({ block: 'start' });
}

const jumpOptions = computed(() =>
  props.nodes
    .filter((node) => node.recordId)
    .map((node) => ({ label: node.label, value: node.recordId as string })),
);

const columns = [
  { dataIndex: 'label', title: '节点' },
  { dataIndex: 'status', title: '状态' },
  { dataIndex: 'planDate', title: '计划日期' },
  { dataIndex: 'window', title: '窗口' },
  { dataIndex: 'doneDate', title: '完成日期' },
  { dataIndex: 'highlightCount', title: '标红' },
  { dataIndex: 'note', title: '备注' },
];

const tableRows = computed(() =>
  props.nodes.map((node) => ({
    ...node,
    key: node.recordId ?? node.label,
    window:
      node.windowStart && node.windowEnd
        ? `${node.windowStart} ~ ${node.windowEnd}`
        : '—',
  })),
);

defineExpose({ jumpTo, setView });
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
        allow-clear
        placeholder="跳到节点"
        :options="jumpOptions"
        style="width: 180px"
        @update:value="jumpTo(String($event))"
      />
      <span class="legend">
        状态：
        <StatusTag status="待随访" />
        <StatusTag status="进行中" />
        <StatusTag status="已完成" />
        <StatusTag status="逾期" />
        <StatusTag status="补录" />
        <StatusTag status="窗口外" />
      </span>
    </div>

    <div v-if="view === 'table' && !isMobile">
      <Table
        :columns="columns"
        :data-source="tableRows"
        :pagination="false"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <StatusTag
            v-if="column.dataIndex === 'status'"
            :status="record.status"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'highlightCount'">
            {{ record.highlightCount ? `标红 ${record.highlightCount}` : '—' }}
          </span>
        </template>
      </Table>
    </div>

    <div v-else class="cards">
      <div
        v-for="event in events ?? []"
        :key="event.date + event.title"
        class="event"
      >
        <i class="diamond" :style="{ background: colors.primary }"></i>
        <div>
          <strong>{{ event.date }} {{ event.title }}</strong>
          <div class="note">{{ event.detail }}</div>
        </div>
      </div>
      <Timeline>
        <TimelineItem v-for="node in nodes" :key="node.label">
          <Card
            :id="node.recordId ? `rec-${node.recordId}` : undefined"
            size="small"
          >
            <div class="node-head">
              <strong>{{ node.label }}</strong>
              <StatusTag :status="node.status" />
              <span v-if="node.highlightCount" class="flag">
                标红 {{ node.highlightCount }}
              </span>
              <Tooltip
                v-if="!node.canOpen"
                title="计划中的节点尚未创建任务，任务开启日才可录入"
              >
                <Button disabled size="small">展开</Button>
              </Tooltip>
              <Button
                v-else-if="node.recordId"
                size="small"
                @click="toggle(node)"
              >
                {{ expanded === node.recordId ? '收起' : '展开' }}
              </Button>
            </div>
            <p class="note">
              <span v-if="node.planDate">计划 {{ node.planDate }}</span>
              <span v-else>无计划日期</span>
              <span v-if="node.windowStart">
                · 窗口 {{ node.windowStart }} ~ {{ node.windowEnd }}
              </span>
              <span v-if="node.doneDate"> · 完成 {{ node.doneDate }}</span>
              <span v-if="node.note"> · {{ node.note }}</span>
            </p>
            <div v-if="expanded === node.recordId" class="expanded">
              <CategoryBar
                :active-key="node.sections[0]?.key"
                :items="node.sections"
                show-highlight-count
                @jump="(key: string) => emit('jump', node.recordId, key)"
              />
              <slot :node="node" />
            </div>
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

.flag {
  color: #ff4d4f;
}

.node-head {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.event {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.diamond {
  width: 8px;
  height: 8px;
  margin-top: 6px;
  transform: rotate(45deg);
}
</style>
