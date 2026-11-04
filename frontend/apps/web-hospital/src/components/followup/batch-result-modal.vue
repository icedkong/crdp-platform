<script lang="ts" setup>
import type { BatchOutcomeItem, BatchResult } from '@crdp/api';

import { computed } from 'vue';

import { batchOutcomeText, countSkipByReason } from '@crdp/api';
import { Table, Tag } from 'ant-design-vue';

/**
 * E13-T07 / A-PAT-016、A-PAT-017：批量操作结果面板（批量入组与批量指定责任医生共用，
 * 由调用方放入 Modal 或页面）。tasks.csv 验收「批量操作结果逐条可查」：每行 = 患者脱敏摘要
 * （姓名 / 编号 / 病区）+ 结果（成功 / 跳过）+ 原因文案；顶部为计数汇总（成功 N、跳过 M
 * 及原因分组）。越范围 / 不存在患者（NOT_FOUND）不带摘要，不暴露对象存在。
 */
const props = defineProps<{
  /** 成功文案（批量入组「成功」/ 批量指定「已指定」）。 */
  successLabel?: string;
}>();

const result = defineModel<BatchResult | null>('result', { default: null });

const successText = computed(() => props.successLabel ?? '成功');

const skipGroups = computed(() =>
  result.value ? countSkipByReason(result.value.items) : [],
);

const columns = [
  { dataIndex: 'name', title: '姓名' },
  { dataIndex: 'patientNo', title: '编号' },
  { dataIndex: 'wardName', title: '病区' },
  { dataIndex: 'outcome', title: '结果', width: 120 },
];

const dataSource = computed(() =>
  (result.value?.items ?? []).map((item) => ({
    ...item,
    key: item.patientId,
    name: item.name ?? '（不在您的数据范围内）',
    patientNo: item.patientNo ?? '—',
    wardName: item.wardName ?? '—',
  })),
);

function isSkip(item: BatchOutcomeItem) {
  return item.outcome === 'SKIP';
}
</script>

<template>
  <div class="batch-result">
    <div v-if="result" class="summary">
      <span data-testid="batch-result-summary">
        {{ successText }} {{ result.succeeded }}、跳过 {{ result.skipped }}
      </span>
      <span v-for="group in skipGroups" :key="group.reason" class="group">
        {{ group.text }} {{ group.count }}
      </span>
    </div>
    <Table
      :columns="columns"
      :data-source="dataSource"
      :pagination="{
        pageSize: 20,
        showTotal: (count: number) => `共 ${count} 条`,
      }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <span v-if="column.dataIndex === 'outcome'">
          <Tag
            v-if="isSkip(record)"
            color="orange"
            data-testid="batch-item-skip"
          >
            跳过
          </Tag>
          <Tag v-else color="green">{{ successText }}</Tag>
          <span v-if="isSkip(record)" class="reason">
            {{ batchOutcomeText(record) }}
          </span>
        </span>
      </template>
    </Table>
  </div>
</template>

<style scoped>
.batch-result {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 14px;
}

.summary > [data-testid='batch-result-summary'] {
  font-weight: 600;
}

.group {
  font-size: 12px;
  color: #8c8c8c;
}

.reason {
  margin-left: 4px;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
