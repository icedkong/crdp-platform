<script lang="ts" setup>
import type { NotifySendLog, NotifySendLogPage } from '@crdp/api';

import { onMounted, reactive, ref } from 'vue';

import { NOTIFY_SEND_STATUS_LABELS } from '@crdp/api';
import {
  Alert,
  Button,
  InputNumber,
  message,
  Pagination,
  RangePicker,
  Select,
  SelectOption,
  Space,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import { retryNotifySendLog, searchNotifySendLogs } from '#/api/core/notify';

import {
  CHANNEL_FILTER_OPTIONS,
  isRetryable,
  SEND_STATUS_OPTIONS,
  sendStatusColor,
  sendTimeText,
  toSendLogSearch,
} from './send-logs';

defineOptions({ name: 'FollowupNoticeSendLogs' });

/**
 * E09-T07 / A-NTF-006、C-NTF-006 · 通知设置「发送记录」页签
 * （A 规格「通知发送记录」页：按事件、渠道、结果、日期筛选）。
 * 每条发送结果：事件、接收人、渠道、时间、结果、失败原因；失败可重试
 * （FAILED → QUEUED，等待 E09-T05 汇总作业按人合并发送）。查询写批量查询审计。
 */
const props = defineProps<{
  /** 事件筛选选项（父页已加载的规则目录：label + eventType）。 */
  eventOptions: { label: string; value: string }[];
}>();

const PAGE_SIZE = 20;
const loading = ref(false);
const loadError = ref('');
const rows = ref<NotifySendLog[]>([]);
const total = ref(0);
const pageNo = ref(1);

const filter = reactive({
  channel: undefined as string | undefined,
  dateRange: undefined as [string, string] | undefined,
  eventType: undefined as string | undefined,
  recipientId: undefined as number | undefined,
  sendStatus: undefined as string | undefined,
});

const columns = [
  {
    title: '事件',
    dataIndex: 'eventLabel',
    key: 'eventLabel',
    width: 110,
  },
  {
    title: '接收人',
    dataIndex: 'recipientName',
    key: 'recipientName',
    width: 120,
  },
  { title: '渠道', dataIndex: 'channel', key: 'channel', width: 80 },
  { title: '结果', dataIndex: 'sendStatus', key: 'sendStatus', width: 90 },
  { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
  { title: '说明', dataIndex: 'detail', key: 'detail', ellipsis: true },
  { title: '发送时间', key: 'sentAt', width: 150 },
  {
    title: '记录时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    width: 150,
  },
  { title: '操作', key: 'action', width: 80 },
];

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const page: NotifySendLogPage = await searchNotifySendLogs(
      toSendLogSearch(
        {
          channel: filter.channel as never,
          dateRange: filter.dateRange,
          eventType: filter.eventType,
          recipientId: filter.recipientId ?? null,
          sendStatus: filter.sendStatus as never,
        },
        pageNo.value,
        PAGE_SIZE,
      ),
    );
    rows.value = page.items;
    total.value = page.total;
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '查询失败';
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  pageNo.value = 1;
  load();
}

function handleReset() {
  filter.eventType = undefined;
  filter.channel = undefined;
  filter.sendStatus = undefined;
  filter.recipientId = undefined;
  filter.dateRange = undefined;
  handleSearch();
}

async function handleRetry(record: NotifySendLog) {
  const result = await retryNotifySendLog(record.id);
  if (result.requeued) {
    message.success('已重新排队，等待汇总发送');
  } else {
    message.info('该记录已被重新排队，无需重复操作');
  }
  load();
}

function channelText(channel: string): string {
  return channel === 'SMS' ? '短信' : '站内信';
}

function statusText(status: keyof typeof NOTIFY_SEND_STATUS_LABELS): string {
  return NOTIFY_SEND_STATUS_LABELS[status] ?? status;
}

onMounted(load);
</script>

<template>
  <div data-testid="notify-send-logs">
    <Space class="mb-4" wrap>
      <Select
        v-model:value="filter.eventType"
        allow-clear
        placeholder="事件"
        style="width: 140px"
      >
        <SelectOption
          v-for="option in props.eventOptions"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </SelectOption>
      </Select>
      <Select
        v-model:value="filter.channel"
        allow-clear
        placeholder="渠道"
        style="width: 110px"
      >
        <SelectOption
          v-for="option in CHANNEL_FILTER_OPTIONS"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </SelectOption>
      </Select>
      <Select
        v-model:value="filter.sendStatus"
        allow-clear
        placeholder="结果"
        style="width: 110px"
      >
        <SelectOption
          v-for="option in SEND_STATUS_OPTIONS"
          :key="option.value"
          :value="option.value"
        >
          {{ option.label }}
        </SelectOption>
      </Select>
      <RangePicker
        v-model:value="filter.dateRange"
        value-format="YYYY-MM-DD"
        :placeholder="['开始日期', '结束日期']"
      />
      <InputNumber
        v-model:value="filter.recipientId"
        :min="1"
        :precision="0"
        placeholder="接收人 ID"
        style="width: 120px"
      />
      <Button type="primary" @click="handleSearch">查询</Button>
      <Button @click="handleReset">重置</Button>
    </Space>

    <Alert
      v-if="loadError"
      class="mb-4"
      :message="loadError"
      show-icon
      type="error"
    />
    <Spin v-else :spinning="loading">
      <Table
        :columns="columns"
        :data-source="rows"
        :pagination="false"
        row-key="id"
        size="middle"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'recipientName'">
            {{ record.recipientName ?? record.recipientId }}
          </template>
          <template v-else-if="column.key === 'channel'">
            {{ channelText(record.channel) }}
          </template>
          <template v-else-if="column.key === 'sendStatus'">
            <Tag :color="sendStatusColor(record.sendStatus)">
              {{ statusText(record.sendStatus) }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'sentAt'">
            {{ sendTimeText(record.sentAt) }}
          </template>
          <template v-else-if="column.key === 'createdAt'">
            {{ sendTimeText(record.createdAt) }}
          </template>
          <template v-else-if="column.key === 'detail'">
            {{ record.detail ?? '—' }}
          </template>
          <template v-else-if="column.key === 'action'">
            <Button
              v-if="isRetryable(record.sendStatus)"
              size="small"
              type="link"
              @click="handleRetry(record)"
            >
              重试
            </Button>
            <span v-else>—</span>
          </template>
        </template>
      </Table>
      <Pagination
        v-model:current="pageNo"
        class="mt-4"
        :page-size="PAGE_SIZE"
        :show-size-changer="false"
        :show-total="(count: number) => `共 ${count} 条`"
        :total="total"
        @change="load"
      />
    </Spin>
  </div>
</template>
