<script lang="ts" setup>
import type { ViewRuleItem, ViewRuleVersionItem } from '@crdp/api';

import { ref, watch } from 'vue';

import {
  viewRuleChangeTypeText,
  viewRuleVersionStatusColor,
  viewRuleVersionStatusText,
} from '@crdp/api';
import { Button, Drawer, Spin, Table, Tag } from 'ant-design-vue';

import { listViewRuleVersions } from '#/api/core/view-rule';

import { parseVersionContent, versionContentSummary } from './logic';

/**
 * 规则版本记录抽屉（E20-T05 / PLT-VIEW-015）：每次变更生成版本——变更人、前后值、
 * 复核人与状态（待复核 / 已生效 / 已驳回）；已生效过的版本可回滚（放宽回滚同样待复核）。
 */
const props = defineProps<{
  canEdit: boolean;
  open: boolean;
  rule: null | ViewRuleItem;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'rollback', rule: ViewRuleItem, toVersion: number): void;
}>();

const loading = ref(false);
const versions = ref<ViewRuleVersionItem[]>([]);

const columns = [
  { dataIndex: 'version', title: '版本', width: 70 },
  { dataIndex: 'changeType', title: '类型', width: 80 },
  { dataIndex: 'before', title: '变更前' },
  { dataIndex: 'after', title: '变更后' },
  { dataIndex: 'proposedByName', title: '变更人', width: 120 },
  { dataIndex: 'reviewedByName', title: '复核人', width: 120 },
  { dataIndex: 'status', title: '状态', width: 90 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 80 },
];

watch(
  () => [props.open, props.rule?.id],
  async ([open, id]) => {
    if (!open || !id) return;
    loading.value = true;
    versions.value = [];
    try {
      versions.value = await listViewRuleVersions(id as number);
    } finally {
      loading.value = false;
    }
  },
);

function time(text: null | string): string {
  return text ? text.slice(0, 19).replace('T', ' ') : '—';
}

function reviewerText(row: ViewRuleVersionItem): string {
  return row.reviewedByName
    ? `${row.reviewedByName}（${time(row.reviewedAt)}）`
    : '—';
}
</script>

<template>
  <Drawer
    :open="open"
    :title="`版本记录：${rule?.name ?? ''}（${rule?.code ?? ''}）`"
    :width="860"
    @close="emit('close')"
  >
    <div class="hint mb-2">
      规则每次变更生成版本，记录变更人、前后值与复核人；普通变更提交即生效，高敏变更经第二人复核通过后生效。已生效过的版本可回滚。
    </div>
    <Spin :spinning="loading">
      <Table
        :columns="columns"
        :data-source="versions"
        :pagination="false"
        :row-key="(row: ViewRuleVersionItem) => row.id"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'version'">
            v{{ record.version }}
          </template>
          <span v-else-if="column.dataIndex === 'changeType'">
            {{ viewRuleChangeTypeText(record.changeType) }}
            <Tag v-if="record.sensitive" color="warning">高敏</Tag>
          </span>
          <span v-else-if="column.dataIndex === 'before'">
            {{ versionContentSummary(parseVersionContent(record.beforeJson)) }}
          </span>
          <span v-else-if="column.dataIndex === 'after'">
            {{ versionContentSummary(parseVersionContent(record.afterJson)) }}
          </span>
          <span v-else-if="column.dataIndex === 'proposedByName'">
            {{ record.proposedByName ?? '—' }}
            <div class="cell-time">{{ time(record.proposedAt) }}</div>
          </span>
          <span v-else-if="column.dataIndex === 'reviewedByName'">
            {{ reviewerText(record as ViewRuleVersionItem) }}
            <div v-if="record.reviewComment" class="cell-time">
              意见：{{ record.reviewComment }}
            </div>
          </span>
          <Tag
            v-else-if="column.dataIndex === 'status'"
            :color="viewRuleVersionStatusColor(record.status)"
          >
            {{ viewRuleVersionStatusText(record.status) }}
          </Tag>
          <Button
            v-else-if="
              column.dataIndex === 'action' &&
              canEdit &&
              record.status === 'APPLIED' &&
              record.version !== rule?.version
            "
            size="small"
            type="link"
            :data-testid="`view-rule-rollback-${record.id}`"
            @click="emit('rollback', rule as ViewRuleItem, record.version)"
          >
            回滚到此
          </Button>
          <span v-else-if="column.dataIndex === 'action'">—</span>
        </template>
      </Table>
    </Spin>
  </Drawer>
</template>

<style scoped>
.hint {
  font-size: 13px;
  color: #86909c;
}

.cell-time {
  font-size: 12px;
  color: #86909c;
}

.mb-2 {
  margin-bottom: 8px;
}
</style>
