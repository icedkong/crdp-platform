<script lang="ts" setup>
import type { TenantListItem, TenantStatusAction } from '@crdp/api';

import { h, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { TENANT_STATUS_OPTIONS } from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { breakpoints, sizes } from '@crdp/tokens';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Empty,
  Form,
  FormItem,
  Input,
  Result,
  Select,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';
import dayjs from 'dayjs';

import { listTenants } from '#/api/core/tenant';
import { usePermission } from '#/reference/use-permission';

import { canDisable, canEnable, canRenew } from './lifecycle';
import {
  canOpenProvision,
  EMPTY_FILTER,
  expireText,
  rowNumber,
  statusText,
  toTenantListQuery,
  userCountText,
} from './list';
import RenewDialog from './renew-dialog.vue';
import StatusDialog from './status-dialog.vue';

defineOptions({ name: 'PlatformTenant' });

const router = useRouter();
const { hasPermission } = usePermission();
const canCreate = hasPermission('platform:tenant:create');
const canChangeStatus = hasPermission('platform:tenant:status');
const canRenewTenant = hasPermission('platform:tenant:renew');

const isMobile = ref(false);
const loading = ref(false);
const items = ref<TenantListItem[]>([]);
const total = ref(0);
const pagination = reactive({ pageNo: 1, pageSize: sizes.tablePageSize });
/** 已提交的筛选（表格数据源）；编辑中的筛选在 filter 里，点查询才生效。 */
const applied = ref({ ...EMPTY_FILTER });
const filter = reactive({ ...EMPTY_FILTER });
const errorMessage = ref('');

// 状态变更 / 续期对话框（E05-T02）：一次只开一个，done 后刷新列表
const statusDialogOpen = ref(false);
const statusAction = ref<'DISABLE' | 'ENABLE'>('DISABLE');
const statusTarget = ref<null | TenantListItem>(null);
const renewDialogOpen = ref(false);
const renewTarget = ref<null | TenantListItem>(null);

const columns = [
  { dataIndex: 'index', title: '序号', width: 64 },
  { dataIndex: 'name', title: '医院名称' },
  { dataIndex: 'code', title: '租户编码', width: 120 },
  { dataIndex: 'status', title: '状态', width: 96 },
  { dataIndex: 'expireAt', title: '到期日', width: 120 },
  { dataIndex: 'modules', title: '已开通模块' },
  {
    dataIndex: 'userCount',
    title: '用户数',
    width: 88,
    align: 'right' as const,
  },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 230 },
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
    const page = await listTenants(
      toTenantListQuery(applied.value, pagination.pageNo, pagination.pageSize),
    );
    items.value = page.items;
    total.value = page.total;
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    loading.value = false;
  }
}

function search() {
  applied.value = { ...filter };
  pagination.pageNo = 1;
  void load();
}

function reset() {
  Object.assign(filter, EMPTY_FILTER);
  search();
}

function changePage(pageNo: number, pageSize: number) {
  pagination.pageNo = pageNo;
  pagination.pageSize = pageSize;
  void load();
}

function openDetail(row: TenantListItem) {
  void router.push(`/platform/tenant/${row.id}`);
}

function openProvision(row: TenantListItem) {
  void router.push(`/platform/tenant/${row.id}/provision`);
}

function openStatusDialog(row: TenantListItem, action: TenantStatusAction) {
  statusTarget.value = row;
  statusAction.value = action;
  statusDialogOpen.value = true;
}

function openRenewDialog(row: TenantListItem) {
  renewTarget.value = row;
  renewDialogOpen.value = true;
}

function onLifecycleDone() {
  void load();
}

function onExpireChange(date: null | string | string[]) {
  filter.expireBefore = Array.isArray(date) ? '' : (date ?? '');
}

function emptyNode() {
  return h(Empty, { image: Empty.PRESENTED_IMAGE_SIMPLE }, () =>
    canCreate ? '暂无租户 · 去新建' : '暂无租户',
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
    title="租户管理"
    description="平台租户的检索、信息维护与开通入口。"
  >
    <Card :bordered="false" class="crdp-card">
      <div class="title-row">
        <span></span>
        <Button
          v-if="canCreate"
          type="primary"
          data-testid="create-tenant"
          @click="router.push('/platform/tenant/new')"
        >
          新建租户
        </Button>
      </div>
      <Form layout="inline" class="filter">
        <FormItem label="名称/编码">
          <Input
            v-model:value="filter.keyword"
            allow-clear
            placeholder="医院名称或租户编码"
            :style="{ width: `${sizes.filterInputWidth.max}px` }"
            @press-enter="search"
          />
        </FormItem>
        <FormItem label="状态">
          <Select
            v-model:value="filter.status"
            allow-clear
            :options="TENANT_STATUS_OPTIONS"
            placeholder="全部"
            style="width: 140px"
          />
        </FormItem>
        <FormItem label="到期日不晚于">
          <DatePicker
            :value="
              filter.expireBefore ? dayjs(filter.expireBefore) : undefined
            "
            placeholder="选择日期"
            style="width: 160px"
            value-format="YYYY-MM-DD"
            @change="
              (_: unknown, date: string | string[] | null) =>
                onExpireChange(date)
            "
          />
        </FormItem>
        <FormItem>
          <Space>
            <Button type="primary" @click="search">查询</Button>
            <Button @click="reset">重置</Button>
          </Space>
        </FormItem>
      </Form>

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
        :row-key="(row: TenantListItem) => row.id"
        size="middle"
      >
        <template #bodyCell="{ column, record, index }">
          <span v-if="column.dataIndex === 'index'">
            {{ rowNumber(index, pagination.pageNo, pagination.pageSize) }}
          </span>
          <span v-else-if="column.dataIndex === 'name'">
            {{ record.name }}
            <span v-if="record.shortName" class="short-name">
              （{{ record.shortName }}）
            </span>
          </span>
          <StatusTag
            v-else-if="column.dataIndex === 'status'"
            :status="statusText(record.status)"
            variant="dot"
          />
          <span v-else-if="column.dataIndex === 'expireAt'">
            {{ expireText(record) }}
          </span>
          <span v-else-if="column.dataIndex === 'modules'">
            <Tag v-for="module in record.modules" :key="module.code">
              {{ module.name }}
            </Tag>
            <span v-if="record.modules.length === 0">—</span>
          </span>
          <span v-else-if="column.dataIndex === 'userCount'">
            {{ userCountText(record) }}
          </span>
          <Space v-else-if="column.dataIndex === 'action'" :size="0" wrap>
            <Button size="small" type="link" @click="openDetail(record)">
              详情
            </Button>
            <Button
              v-if="canOpenProvision(record.status)"
              size="small"
              type="link"
              @click="openProvision(record)"
            >
              开通进度
            </Button>
            <Button
              v-if="canChangeStatus && canDisable(record.status)"
              danger
              size="small"
              type="link"
              data-testid="disable-tenant"
              @click="openStatusDialog(record, 'DISABLE')"
            >
              停用
            </Button>
            <Button
              v-if="canChangeStatus && canEnable(record.status)"
              size="small"
              type="link"
              data-testid="enable-tenant"
              @click="openStatusDialog(record, 'ENABLE')"
            >
              {{ record.status === 'TRIAL' ? '转正' : '启用' }}
            </Button>
            <Button
              v-if="canRenewTenant && canRenew(record.status)"
              size="small"
              type="link"
              data-testid="renew-tenant"
              @click="openRenewDialog(record)"
            >
              续期
            </Button>
          </Space>
        </template>
      </Table>
    </Card>

    <StatusDialog
      v-model:open="statusDialogOpen"
      :action="statusAction"
      :status="statusTarget?.status ?? 'PROVISIONING'"
      :tenant="statusTarget"
      @done="onLifecycleDone"
    />
    <RenewDialog
      v-model:open="renewDialogOpen"
      :status="renewTarget?.status ?? 'PROVISIONING'"
      :tenant="renewTarget"
      @done="onLifecycleDone"
    />
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

.filter {
  margin-bottom: 8px;
}

.short-name {
  color: #86909c;
}
</style>
