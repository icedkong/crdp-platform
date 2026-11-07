<script lang="ts" setup>
import type {
  FormSchema,
  PatientTimelineEntry,
  ReportLocationInput,
} from '@crdp/api';
import type { CategoryProgress } from '@crdp/rules';

import type { AttachmentNavItem } from '#/reference/types';

import { computed, nextTick, onMounted, ref, watch } from 'vue';

import {
  PATIENT_STATUS_TEXT,
  taskDisplayStatus,
  TIMELINE_PLANNED_TEXT,
  timelineEntryLabel,
  timelineProgressText,
} from '@crdp/api';
import { FormRenderer } from '@crdp/form-engine';
import { StatusTag } from '@crdp/status-tag';
import { colors } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Empty,
  Segmented,
  Select,
  Spin,
  Table,
  Timeline,
  TimelineItem,
} from 'ant-design-vue';

import { fetchFormSchema } from '#/api/core/form';
import { fetchRecordDetail } from '#/api/core/task';
import { useBreakpoint } from '#/reference/use-breakpoint';

import { flashAnchor, jumpToAnchor } from './anchor-nav';
import { itemsAtRecord } from './attachment-nav-logic';
import CategoryBar from './category-bar.vue';
import InlineAttachmentStrip from './inline-attachment-strip.vue';

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
 *
 * E13-T11 / A-PAT-021、C-PAT-018：有 recordId 的节点可「展开」——卡片内只读渲染该次记录
 * （FormRenderer readonly），顶部吸顶分组条（分组名、已填 / 应填、未填完橙色，A-PAT-019 口径
 * 由 progress 事件实时上抛），点击滚动到该分组并高亮 2 秒；平板横向滚动、手机「跳到分组」
 * 下拉 + 底部抽屉（CategoryBar select 形态）。标红数随 E17（compareRules）接入。
 */
const props = defineProps<{
  /** E16-T14：所在位置附件（分组 / 整条记录末尾缩略图条；空数组不渲染）。 */
  attachments?: AttachmentNavItem[];
  canUpload?: boolean;
  entries: PatientTimelineEntry[];
  /** v-model：当前展开的记录（一次只展开一条；null = 全部收起）。 */
  expandedRecordId?: null | string;
  patientId: string;
}>();

const emit = defineEmits<{
  (event: 'open', entry: PatientTimelineEntry): void;
  (event: 'openAttachment', reportId: string): void;
  (event: 'update:expandedRecordId', id: null | string): void;
  (event: 'uploadAt', preset: ReportLocationInput[]): void;
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

// ---------------------------------------------------------------- E13-T11 展开记录与分组条

const recordLoading = ref(false);
const recordFailed = ref(false);
const recordSchema = ref<FormSchema | null>(null);
const recordData = ref<Record<string, unknown>>({});
const recordProgress = ref<CategoryProgress[]>([]);
const recordActiveKey = ref('');
/** 已加载过的记录（切换展开不重复请求；只存模板与值，不含患者敏感信息）。 */
const recordCache = new Map<
  string,
  { data: Record<string, unknown>; schema: FormSchema }
>();

/** 展开 / 收起（一次只展开一条；按钮在卡片内，stopPropagation 不触发 open）。 */
function toggleExpand(entry: PatientTimelineEntry) {
  if (!entry.recordId) return;
  emit(
    'update:expandedRecordId',
    entry.recordId === props.expandedRecordId ? null : entry.recordId,
  );
}

async function loadRecord(recordId: string) {
  recordLoading.value = true;
  recordFailed.value = false;
  try {
    const detail = await fetchRecordDetail(recordId);
    const schema = await fetchFormSchema(detail.formCode, detail.formVersion);
    recordCache.set(recordId, { data: detail.data, schema });
    if (props.expandedRecordId === recordId) {
      recordSchema.value = schema;
      recordData.value = detail.data;
    }
  } catch {
    recordFailed.value = true;
  } finally {
    recordLoading.value = false;
  }
}

watch(
  () => props.expandedRecordId,
  (recordId) => {
    recordActiveKey.value = '';
    recordProgress.value = [];
    recordFailed.value = false;
    if (!recordId) return;
    const cached = recordCache.get(recordId);
    if (cached) {
      recordSchema.value = cached.schema;
      recordData.value = cached.data;
      return;
    }
    recordSchema.value = null;
    void loadRecord(recordId);
  },
  { immediate: true },
);

/** 分组条点击：滚到该分组锚点（FormRenderer 输出 #crdp-anchor-<key>）并高亮 2 秒。 */
function jumpRecordSection(key: string) {
  recordActiveKey.value = key;
  jumpToAnchor(key, document);
}

// ---------------------------------------------------------------- E16-T14 附件条与记录内定位

/** 某分组末尾的附件（左栏同源条目，同一报告单在每个所在位置各出现一次）。 */
function sectionItems(recordId: string, anchorKey: string) {
  return itemsAtRecord(props.attachments ?? [], recordId, anchorKey);
}

/** 整条记录末尾的附件（只含整条挂靠位置；分组附件显示在各自分组末尾）。 */
function wholeRecordItems(recordId: string) {
  return itemsAtRecord(props.attachments ?? [], recordId);
}

/** 条内「上传附件」预选该位置（模板版本由上传对话框按位置树带出回填）。 */
function stripPreset(
  recordId: string,
  anchorKey?: string,
): ReportLocationInput[] {
  return [
    {
      ...(anchorKey ? { anchorKey } : {}),
      anchorTemplateVersion: 0,
      anchorType: anchorKey ? 'section' : null,
      linkRecordId: recordId,
      linkType: 'followup_record',
    },
  ];
}

/** 展开记录容器 DOM id（locateInRecord 整条记录滚动目标）。 */
function recordDomId(recordId: string) {
  return `crdp-record-${recordId}`;
}

/** 待定位请求（记录异步加载完成后重放：滚到分组锚点或记录顶部并高亮）。 */
const pendingLocate = ref<null | { anchorKey?: string; recordId: string }>(
  null,
);

/**
 * E16-T14 左栏位置标签跳转入口（患者详情页经模板 ref 调用）：确保时间轴视图、
 * 展开该记录（v-model）、等记录渲染后滚到分组锚点（anchorKey 缺省滚到记录顶部）
 * 并高亮 2 秒（A-PAT-020「点标签跳到该位置」）。
 */
function locateInRecord(recordId: string, anchorKey?: string) {
  setView('timeline');
  if (props.expandedRecordId !== recordId) {
    emit('update:expandedRecordId', recordId);
  }
  pendingLocate.value = { anchorKey, recordId };
  void resolvePendingLocate();
}

async function resolvePendingLocate() {
  const pending = pendingLocate.value;
  if (!pending) return;
  if (props.expandedRecordId !== pending.recordId) return;
  if (recordLoading.value || !recordSchema.value) {
    await nextTick();
    return; // 记录仍在加载：加载完成的 watch 会再次触发
  }
  await nextTick();
  const container = document.querySelector(`#${recordDomId(pending.recordId)}`);
  if (!container) {
    pendingLocate.value = null;
    return;
  }
  // 分组锚点不存在（模板改版 / 整条记录）时回落到记录顶部
  const target = pending.anchorKey
    ? (container.querySelector(`#crdp-anchor-${pending.anchorKey}`) ??
      container)
    : container;
  target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  flashAnchor(target, document.defaultView);
  if (pending.anchorKey) recordActiveKey.value = pending.anchorKey;
  pendingLocate.value = null;
}

watch(
  [() => props.expandedRecordId, recordSchema, recordLoading],
  () => {
    void resolvePendingLocate();
  },
  { flush: 'post' },
);

defineExpose({ locateInRecord });

const jumpOptions = computed(() =>
  props.entries.map((entry, index) => ({
    label: `${timelineEntryLabel(entry)}（${nodeDate(entry)}）`,
    value: index,
  })),
);

function jumpTo(index: number) {
  setView('timeline');
  const entry = props.entries[index];
  // 「跳到节点 ▾」选中后滚动并展开（components.md §9）
  if (entry?.recordId && entry.recordId !== props.expandedRecordId) {
    emit('update:expandedRecordId', entry.recordId);
  }
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
              <span class="head-actions">
                <!-- E13-T11「展开」：卡片内只读展开该次记录（分组条在其顶部吸顶） -->
                <Button
                  v-if="entry.recordId"
                  class="expand-toggle"
                  :data-testid="`record-expand-toggle-${entry.recordId}`"
                  size="small"
                  type="link"
                  @click.stop="toggleExpand(entry)"
                >
                  {{ entry.recordId === expandedRecordId ? '收起' : '展开' }}
                </Button>
              </span>
            </div>
            <p class="detail">{{ nodeDetail(entry) }}</p>
            <p v-if="entry.note" class="note">{{ entry.note }}</p>
          </Card>

          <!-- E13-T11 展开的随访记录：顶部吸顶分组条 + FormRenderer 只读内容；
               E16-T14 各分组末尾与整条记录末尾显示所在位置附件缩略图条 -->
          <div
            v-if="entry.recordId && entry.recordId === expandedRecordId"
            :id="recordDomId(entry.recordId)"
            class="record-expand"
            :data-testid="`record-expand-${entry.recordId}`"
          >
            <Spin
              v-if="recordLoading"
              class="record-loading"
              tip="正在加载记录…"
            />
            <Alert
              v-else-if="recordFailed"
              show-icon
              type="error"
              message="记录加载失败，请稍后重试"
            >
              <template #action>
                <Button
                  data-testid="record-expand-retry"
                  size="small"
                  type="primary"
                  @click="loadRecord(entry.recordId ?? '')"
                >
                  重试
                </Button>
              </template>
            </Alert>
            <template v-else-if="recordSchema">
              <CategoryBar
                v-model:active-key="recordActiveKey"
                :items="recordProgress"
                show-highlight-count
                spy
                @jump="jumpRecordSection"
              />
              <FormRenderer
                :model-value="recordData"
                :schema="recordSchema"
                mode="readonly"
                @progress="recordProgress = $event"
              >
                <template #category-end="{ category }">
                  <InlineAttachmentStrip
                    :can-upload="canUpload"
                    :items="sectionItems(entry.recordId ?? '', category.key)"
                    @open="emit('openAttachment', $event)"
                    @upload="
                      emit(
                        'uploadAt',
                        stripPreset(entry.recordId ?? '', category.key),
                      )
                    "
                  />
                </template>
              </FormRenderer>
              <!-- 整条记录末尾（只含整条挂靠位置；分组附件在各自分组末尾） -->
              <InlineAttachmentStrip
                :can-upload="canUpload"
                :items="wholeRecordItems(entry.recordId ?? '')"
                @open="emit('openAttachment', $event)"
                @upload="emit('uploadAt', stripPreset(entry.recordId ?? ''))"
              />
            </template>
          </div>
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

/* E13-T11 展开记录：分组条顶部吸顶（CategoryBar 自带 sticky），内容收进浅底卡片 */
.record-expand {
  padding: 8px 12px;
  margin-top: 4px;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
}

.record-loading {
  display: block;
  padding: 24px 0;
  text-align: center;
}

.head-actions {
  margin-left: auto;
}

.expand-toggle {
  padding: 0;
}
</style>
