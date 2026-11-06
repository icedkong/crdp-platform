<script lang="ts" setup>
import type { ConfigRegistryEntry, ConfigTemplateView } from '@crdp/api';

import { h, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { configModeText } from '@crdp/api';
import { breakpoints, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  Drawer,
  Empty,
  Result,
  Space,
  Table,
} from 'ant-design-vue';

import {
  configTableRegistry,
  listConfigTemplates,
} from '#/api/core/config-template';
import { usePermission } from '#/reference/use-permission';

import ApplyDialog from './apply-dialog.vue';
import ExportDialog from './export-dialog.vue';
import {
  exportedAtText,
  hashText,
  rowNumber,
  sourceTenantText,
  versionText,
} from './list';

/**
 * 配置模板管理页（E12-T04 / A-FLD-014）：模板列表（新→旧）、导出、详情与「应用到租户」
 * （导入前预览见 apply-dialog）。导出范围（配置表注册表）在抽屉里预览「会导出什么」。
 */
defineOptions({ name: 'PlatformConfigTemplate' });

const router = useRouter();
const { hasPermission } = usePermission();
const canCreate = hasPermission('platform:tenant:create');

const isMobile = ref(false);
const loading = ref(false);
const items = ref<ConfigTemplateView[]>([]);
const total = ref(0);
const pagination = reactive({ pageNo: 1, pageSize: sizes.tablePageSize });
const errorMessage = ref('');

const exportOpen = ref(false);
const applyOpen = ref(false);
const applyTarget = ref<ConfigTemplateView | null>(null);

// 导出范围（注册表）抽屉：GET /config-templates/registry
const registryOpen = ref(false);
const registryLoading = ref(false);
const registryError = ref('');
const registry = ref<ConfigRegistryEntry[]>([]);

const columns = [
  { dataIndex: 'index', title: '序号', width: 64 },
  { dataIndex: 'name', title: '模板名称' },
  { dataIndex: 'tenant', title: '源租户' },
  { dataIndex: 'version', title: '版本', width: 72 },
  {
    dataIndex: 'tableCount',
    title: '配置表数',
    width: 96,
    align: 'right' as const,
  },
  {
    dataIndex: 'rowCount',
    title: '入包行数',
    width: 96,
    align: 'right' as const,
  },
  { dataIndex: 'packageHash', title: '包哈希', width: 140 },
  { dataIndex: 'exportedAt', title: '导出时间', width: 140 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 150 },
];

const registryColumns = [
  { dataIndex: 'table', title: '配置表' },
  { dataIndex: 'module', title: '模块', width: 90 },
  { dataIndex: 'mode', title: '复制方式', width: 110 },
  { dataIndex: 'category', title: '类别' },
  { dataIndex: 'description', title: '说明' },
];

function read() {
  isMobile.value = window.matchMedia(
    `(max-width: ${breakpoints.tablet - 1}px)`,
  ).matches;
}

async function load() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const page = await listConfigTemplates(
      pagination.pageNo,
      pagination.pageSize,
    );
    items.value = page.items;
    total.value = page.total;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function changePage(pageNo: number, pageSize: number) {
  pagination.pageNo = pageNo;
  pagination.pageSize = pageSize;
  void load();
}

function openDetail(row: ConfigTemplateView) {
  void router.push(`/platform/config-template/${row.id}`);
}

function openApply(row: ConfigTemplateView) {
  applyTarget.value = row;
  applyOpen.value = true;
}

async function openRegistry() {
  registryOpen.value = true;
  if (registry.value.length > 0 || registryLoading.value) return;
  registryLoading.value = true;
  registryError.value = '';
  try {
    registry.value = await configTableRegistry();
  } catch (error) {
    registryError.value =
      error instanceof Error ? error.message : String(error);
  } finally {
    registryLoading.value = false;
  }
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () =>
    canCreate ? '暂无配置模板 · 去导出' : '暂无配置模板',
  );
}

onMounted(() => {
  read();
  window.addEventListener('resize', read);
  void load();
});
onUnmounted(() => window.removeEventListener('resize', read));
</script>

<template>
  <Result v-if="isMobile" status="info" title="请在电脑或平板上操作">
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <Page
    v-else
    title="配置模板"
    description="从源租户导出只含配置的模板包，应用到其他租户完成初始化；患者数据与用户账号不随模板复制。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <Button data-testid="open-registry" @click="openRegistry">
          导出范围
        </Button>
        <Space>
          <span class="hint">应用前会逐表预览差异</span>
          <Button
            v-if="canCreate"
            data-testid="export-template"
            type="primary"
            @click="exportOpen = true"
          >
            导出模板
          </Button>
        </Space>
      </div>

      <Alert
        v-if="errorMessage"
        :message="`加载失败：${errorMessage}`"
        show-icon
        type="error"
        class="mb-4"
      />

      <Table
        :columns="columns"
        :data-source="items"
        :loading="loading"
        :locale="{ emptyText: emptyNode() }"
        :pagination="{
          current: pagination.pageNo,
          pageSize: pagination.pageSize,
          showQuickJumper: true,
          showSizeChanger: true,
          showTotal: (count: number) => `共 ${count} 条`,
          total,
          onChange: changePage,
          onShowSizeChange: changePage,
        }"
        :row-key="(row: ConfigTemplateView) => row.id"
        size="middle"
      >
        <template #bodyCell="{ column, record, index }">
          <span v-if="column.dataIndex === 'index'">
            {{ rowNumber(index, pagination.pageNo, pagination.pageSize) }}
          </span>
          <span v-else-if="column.dataIndex === 'tenant'">
            {{ sourceTenantText(record) }}
          </span>
          <span v-else-if="column.dataIndex === 'version'">
            {{ versionText(record) }}
          </span>
          <span v-else-if="column.dataIndex === 'packageHash'">
            {{ hashText(record.packageHash) }}
          </span>
          <span v-else-if="column.dataIndex === 'exportedAt'">
            {{ exportedAtText(record) }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button size="small" type="link" @click="openDetail(record)">
              详情
            </Button>
            <Button
              v-if="canCreate"
              data-testid="apply-template"
              size="small"
              type="link"
              @click="openApply(record)"
            >
              应用到租户
            </Button>
          </Space>
        </template>
      </Table>
    </Card>

    <ExportDialog v-model:open="exportOpen" @done="load" />
    <ApplyDialog
      v-model:open="applyOpen"
      :template="applyTarget"
      @done="load"
    />

    <Drawer
      v-model:open="registryOpen"
      title="导出范围（配置表注册表）"
      width="720"
    >
      <Alert
        message="只导出下表登记的配置表；未登记的表一律不导出（患者与业务数据、账号、审计等被显式排除）。行排除谓词（如术者取值）在建包时剔除对应行。"
        show-icon
        type="info"
        class="mb-4"
      />
      <Alert
        v-if="registryError"
        :message="`加载失败：${registryError}`"
        show-icon
        type="error"
        class="mb-4"
      />
      <Table
        :columns="registryColumns"
        :data-source="registry"
        :loading="registryLoading"
        :pagination="false"
        :row-key="(row: ConfigRegistryEntry) => row.table"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'mode'">
            {{ configModeText(record.mode) }}
          </template>
        </template>
      </Table>
    </Drawer>
  </Page>
</template>

<style scoped>
.title-row {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.hint {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
