<script lang="ts" setup>
/**
 * 资源变更记录抽屉（E05-T10 / PLT-PERM-012，验收「菜单、接口资源变更可查」）：
 * 菜单维护 / 清单同步 / 接口标记 / 登记扫描的变更流水，按类型、动作、渠道、关键词筛选，
 * 服务端分页；导出带水印的 CSV（每次导出后端写数据访问审计）。
 * 菜单资源页与接口资源页共用；权限 platform:menu:view（docs/api/platform.md §3）。
 */
import type { ResourceChangeLogItem } from '@crdp/api';

import { ref, watch } from 'vue';

import {
  resourceChangeActionText,
  resourceChangeChannelText,
  resourceChangeTypeText,
} from '@crdp/api';
import {
  Alert,
  Button,
  Drawer,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  exportResourceChangeLogs,
  listResourceChangeLogs,
} from '#/api/core/resource-log';

import {
  ACTION_OPTIONS,
  actionColor,
  CHANNEL_OPTIONS,
  emptyFilters,
  operatorText,
  RESOURCE_TYPE_OPTIONS,
  timeText,
  toQuery,
  typeColor,
} from './resource-log-logic';

const props = defineProps<{
  /** 打开时的默认资源类型筛选（菜单 / 接口页各自预选）。 */
  defaultResourceType: '' | 'API' | 'FIELD' | 'MENU';
  open: boolean;
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const loading = ref(false);
const errorMessage = ref('');
const items = ref<ResourceChangeLogItem[]>([]);
const total = ref(0);
const pageNo = ref(1);
const pageSize = 20;
const filters = ref(emptyFilters());
const exporting = ref(false);
const exportError = ref('');
const exportMessage = ref('');

const columns = [
  { dataIndex: 'createdAt', title: '时间', width: 150 },
  { dataIndex: 'resourceType', title: '类型', width: 70 },
  { dataIndex: 'resourceName', title: '资源', width: 200 },
  { dataIndex: 'action', title: '动作', width: 76 },
  { dataIndex: 'channel', title: '渠道', width: 104 },
  { dataIndex: 'operatorName', title: '操作人', width: 140 },
  { dataIndex: 'detail', title: '摘要' },
];

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const page = await listResourceChangeLogs(
      toQuery(filters.value, pageNo.value, pageSize),
    );
    items.value = page.items;
    total.value = page.total;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
    items.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    filters.value = emptyFilters(props.defaultResourceType);
    pageNo.value = 1;
    exportError.value = '';
    exportMessage.value = '';
    void load();
  },
);

function applyFilters() {
  pageNo.value = 1;
  void load();
}

function changePage(page: number) {
  pageNo.value = page;
  void load();
}

async function exportCsv() {
  exporting.value = true;
  exportError.value = '';
  exportMessage.value = '';
  try {
    await exportResourceChangeLogs(
      toQuery(filters.value, pageNo.value, pageSize),
    );
    exportMessage.value = '已导出 CSV（含水印列；导出已写审计）';
  } catch (error) {
    exportError.value = error instanceof Error ? error.message : String(error);
  } finally {
    exporting.value = false;
  }
}
</script>

<template>
  <Drawer
    :open="open"
    title="资源变更记录"
    width="72%"
    :body-style="{ padding: '16px' }"
    :destroy-on-close="true"
    @close="emit('close')"
  >
    <Space wrap class="mb-4" style="row-gap: 8px">
      <Select
        v-model:value="filters.resourceType"
        allow-clear
        placeholder="资源类型"
        style="width: 120px"
        :options="RESOURCE_TYPE_OPTIONS"
        @change="applyFilters"
      />
      <Select
        v-model:value="filters.action"
        allow-clear
        placeholder="动作"
        style="width: 110px"
        :options="ACTION_OPTIONS"
        @change="applyFilters"
      />
      <Select
        v-model:value="filters.channel"
        allow-clear
        placeholder="渠道"
        style="width: 130px"
        :options="CHANNEL_OPTIONS"
        @change="applyFilters"
      />
      <Input
        v-model:value="filters.keyword"
        allow-clear
        placeholder="资源名 / 标识 / 摘要关键词"
        style="width: 220px"
        @press-enter="applyFilters"
      />
      <Button data-testid="resource-log-search" @click="applyFilters">
        查询
      </Button>
      <Button
        data-testid="resource-log-export"
        :loading="exporting"
        @click="exportCsv"
      >
        导出 CSV
      </Button>
    </Space>

    <Alert
      v-if="errorMessage"
      :message="`加载失败：${errorMessage}`"
      show-icon
      type="error"
      class="mb-3"
    />
    <Alert
      v-if="exportError"
      :message="`导出失败：${exportError}`"
      show-icon
      type="error"
      class="mb-3"
    />
    <Alert
      v-if="exportMessage"
      :message="exportMessage"
      show-icon
      type="success"
      class="mb-3"
    />

    <Table
      :columns="columns"
      :data-source="items"
      :loading="loading"
      :pagination="{
        current: pageNo,
        pageSize,
        total,
        showSizeChanger: false,
        onChange: changePage,
      }"
      row-key="id"
      size="small"
      :scroll="{ x: 960 }"
      :locale="{ emptyText: '没有资源变更记录' }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'createdAt'">
          {{ timeText((record as ResourceChangeLogItem).createdAt) }}
        </template>
        <template v-else-if="column.dataIndex === 'resourceType'">
          <Tag
            :color="typeColor((record as ResourceChangeLogItem).resourceType)"
          >
            {{
              resourceChangeTypeText(
                (record as ResourceChangeLogItem).resourceType,
              )
            }}
          </Tag>
        </template>
        <template v-else-if="column.dataIndex === 'resourceName'">
          <span :title="(record as ResourceChangeLogItem).resourceName">
            {{ (record as ResourceChangeLogItem).resourceName }}
          </span>
          <div
            v-if="(record as ResourceChangeLogItem).resourceIdent"
            class="ident-line"
          >
            {{ (record as ResourceChangeLogItem).resourceIdent }}
          </div>
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <Tag :color="actionColor((record as ResourceChangeLogItem).action)">
            {{
              resourceChangeActionText((record as ResourceChangeLogItem).action)
            }}
          </Tag>
        </template>
        <template v-else-if="column.dataIndex === 'channel'">
          {{
            resourceChangeChannelText((record as ResourceChangeLogItem).channel)
          }}
        </template>
        <template v-else-if="column.dataIndex === 'operatorName'">
          {{ operatorText((record as ResourceChangeLogItem).operatorName) }}
        </template>
        <template v-else-if="column.dataIndex === 'detail'">
          <span :title="(record as ResourceChangeLogItem).detail">
            {{ (record as ResourceChangeLogItem).detail }}
          </span>
        </template>
      </template>
    </Table>
  </Drawer>
</template>

<style scoped>
.ident-line {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
