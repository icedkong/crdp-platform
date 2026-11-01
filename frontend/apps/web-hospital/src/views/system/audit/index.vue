<script lang="ts" setup>
import type { HospitalAuditTab } from '@crdp/api';

import type { AuditColumnDef } from './audit-logic';

import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import {
  accessMethodText,
  auditResultColor,
  auditResultText,
  buildAuditSearchCondition,
  exportDownloadHint,
  HOSPITAL_AUDIT_TABS,
  jobExportStatusColor,
  jobExportStatusText,
  validateAuditTimeRange,
} from '@crdp/api';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  DescriptionsItem,
  Drawer,
  Empty,
  Input,
  message,
  Modal,
  Pagination,
  RangePicker,
  Select,
  SelectOption,
  Space,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tag,
  Textarea,
} from 'ant-design-vue';

import {
  approveExport,
  auditDetail,
  downloadAuditExport,
  myAuditExports,
  pendingExportApprovals,
  rejectExport,
  searchAuditLogs,
  submitAuditExport,
} from '#/api/core/audit';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import { columnsForTab, detailTitle, RESULT_OPTIONS } from './audit-logic';

defineOptions({ name: 'HospitalAudit' });

/**
 * 审计中心（E08-T04 / PLT-AUD-005、006、007 · pages.md §10）：页签＝操作 / 登录 / 数据访问日志
 * （不含平台访问记录，PLT-AUD-004 仅存平台侧）；组合查询（时间 ≤ 3 个月、账号、模块、结果、IP、
 * traceId）；详情抽屉展示前后值对比（敏感值为摘要）与同 traceId 关联；导出走审批流——租户管理员
 * 本人免审批、普通用户由租户管理员审批（PLT-AUD-007 已确认），文件 24 小时内限时下载、全程记审计。
 */
const router = useRouter();
const { isMobile } = useBreakpoint();
const { hasPermission } = usePermission();
const canExport = computed(() => hasPermission('tenant:audit:export'));
const canApprove = computed(() => hasPermission('tenant:audit:export:approve'));

const PAGE_SIZE = 20;
const loading = ref(false);
const tab = ref<HospitalAuditTab>('operation');
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const pageNo = ref(1);
const filter = reactive({
  range: [] as string[],
  userName: '',
  module: '',
  result: undefined as string | undefined,
  ip: '',
  traceId: '',
});
const columns = computed<AuditColumnDef[]>(() => columnsForTab(tab.value));

/** 查询（时间窗必填且 ≤ 3 个月，与后端 40001 同口径预检）。 */
async function reload() {
  const start = filter.range[0];
  const end = filter.range[1];
  const error = validateAuditTimeRange(start, end);
  if (error) {
    message.warning(error);
    return;
  }
  loading.value = true;
  try {
    const page = await searchAuditLogs(
      tab.value,
      buildAuditSearchCondition(
        {
          endTime: end,
          ip: filter.ip,
          module: filter.module,
          result: filter.result,
          startTime: start,
          traceId: filter.traceId,
          userName: filter.userName,
        },
        pageNo.value,
        PAGE_SIZE,
      ),
    );
    rows.value = page.items as unknown as Record<string, unknown>[];
    total.value = page.total;
  } catch (error_) {
    message.error((error_ as { message?: string }).message ?? '查询失败');
  } finally {
    loading.value = false;
  }
}

function resetFilter() {
  filter.range = [];
  filter.userName = '';
  filter.module = '';
  filter.result = undefined;
  filter.ip = '';
  filter.traceId = '';
}

function onTabChange() {
  pageNo.value = 1;
  rows.value = [];
  total.value = 0;
}

// ---------------------------------------------------------------- 详情（PLT-AUD-006）
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<null | Record<string, unknown>>(null);
const detailLinks = ref<Record<string, unknown>[]>([]);
const detailKind = ref('');

async function openDetail(row: Record<string, unknown>) {
  detailVisible.value = true;
  detailLoading.value = true;
  detailKind.value = String(row.logType ?? '');
  try {
    const view = await auditDetail(tab.value, Number(row.id));
    detail.value = view as unknown as Record<string, unknown>;
    detailLinks.value = view.traceLinks as unknown as Record<string, unknown>[];
  } catch (error) {
    message.error((error as { message?: string }).message ?? '详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

// ---------------------------------------------------------------- 导出（PLT-AUD-007）
const exportVisible = ref(false);
const exportLoading = ref(false);
const exportForm = reactive({ range: [] as string[], reason: '' });

function openExport() {
  exportForm.range = [...filter.range];
  exportForm.reason = '';
  exportVisible.value = true;
}

async function submitExport() {
  const start = exportForm.range[0];
  const end = exportForm.range[1];
  const error = validateAuditTimeRange(start, end);
  if (error) {
    message.warning(error);
    return;
  }
  exportLoading.value = true;
  try {
    const submitted = await submitAuditExport({
      condition: buildAuditSearchCondition({
        endTime: end,
        module: filter.module || undefined,
        startTime: start,
      }),
      reason: exportForm.reason || undefined,
      type: tab.value,
    });
    exportVisible.value = false;
    message.success(
      submitted.status === 'GENERATING'
        ? '导出已提交，文件生成后可在「我的导出」下载'
        : '导出申请已提交，待租户管理员审批',
    );
    exportsVisible.value = true;
    reloadExports();
  } catch (error_) {
    message.error((error_ as { message?: string }).message ?? '提交失败');
  } finally {
    exportLoading.value = false;
  }
}

const exportsVisible = ref(false);
const exportJobs = ref<Record<string, any>[]>([]);
const exportsLoading = ref(false);

async function reloadExports() {
  exportsLoading.value = true;
  try {
    const page = await myAuditExports(1, 100);
    exportJobs.value = page.items as unknown as Record<string, any>[];
  } finally {
    exportsLoading.value = false;
  }
}

async function download(job: Record<string, any>) {
  try {
    await downloadAuditExport(Number(job.id), String(job.fileName ?? ''));
    reloadExports();
  } catch (error) {
    message.error((error as { message?: string }).message ?? '下载失败');
  }
}

// ---------------------------------------------------------------- 审批（租户管理员）
const approvalsVisible = ref(false);
const approvalsLoading = ref(false);
const pendingJobs = ref<Record<string, any>[]>([]);
const approveBusy = ref(0);
const commentModal = reactive({
  visible: false,
  job: null as null | Record<string, any>,
  approve: true,
  comment: '',
});

async function reloadApprovals() {
  approvalsLoading.value = true;
  try {
    const page = await pendingExportApprovals(1, 100);
    pendingJobs.value = page.items as unknown as Record<string, any>[];
  } finally {
    approvalsLoading.value = false;
  }
}

function openApprovals() {
  approvalsVisible.value = true;
  reloadApprovals();
}

function askDecision(job: Record<string, any>, approve: boolean) {
  commentModal.job = job;
  commentModal.approve = approve;
  commentModal.comment = '';
  commentModal.visible = true;
}

async function decide() {
  if (!commentModal.job) return;
  approveBusy.value = 1;
  try {
    if (commentModal.approve) {
      await approveExport(
        Number(commentModal.job.id),
        commentModal.comment || undefined,
      );
      message.success('已通过，文件开始生成');
    } else {
      await rejectExport(
        Number(commentModal.job.id),
        commentModal.comment || undefined,
      );
      message.success('已驳回');
    }
    commentModal.visible = false;
    reloadApprovals();
  } catch (error) {
    message.error((error as { message?: string }).message ?? '操作失败');
  } finally {
    approveBusy.value = 0;
  }
}

const approvalColumns = [
  { dataIndex: 'id', title: '申请号', width: 80 },
  { dataIndex: 'applicantName', title: '申请人', width: 120 },
  { dataIndex: 'applicantDisplayName', title: '姓名', width: 110 },
  { dataIndex: 'exportType', title: '日志类型', width: 120 },
  { dataIndex: 'reason', title: '申请理由' },
  { dataIndex: 'createdAt', title: '申请时间', width: 170 },
  { dataIndex: 'action', title: '操作', width: 150 },
];
</script>

<template>
  <div v-if="isMobile" class="page">
    <Alert message="请在电脑或平板上操作" show-icon type="info" />
    <Button style="margin-top: 16px" @click="router.push('/dashboard')">
      返回首页
    </Button>
  </div>
  <div v-else class="page">
    <h1 class="crdp-page-title">审计中心</h1>
    <Card>
      <Tabs v-model:active-key="tab.value" @change="onTabChange">
        <TabPane
          v-for="item in HOSPITAL_AUDIT_TABS"
          :key="item.key"
          :tab="item.label"
        />
      </Tabs>
      <div class="filter-bar">
        <RangePicker
          v-model:value="filter.range"
          show-time
          value-format="YYYY-MM-DDTHH:mm:ss+08:00"
          :placeholder="['开始时间', '结束时间']"
        />
        <Input
          v-model:value="filter.userName"
          allow-clear
          placeholder="账号"
          style="width: 140px"
        />
        <Input
          v-model:value="filter.module"
          allow-clear
          placeholder="模块"
          style="width: 120px"
        />
        <Select
          v-model:value="filter.result"
          allow-clear
          placeholder="结果"
          style="width: 110px"
        >
          <SelectOption
            v-for="opt in RESULT_OPTIONS"
            :key="opt.value"
            :value="opt.value"
          >
            {{ opt.label }}
          </SelectOption>
        </Select>
        <Input
          v-model:value="filter.ip"
          allow-clear
          placeholder="IP"
          style="width: 130px"
        />
        <Input
          v-model:value="filter.traceId"
          allow-clear
          placeholder="traceId"
          style="width: 200px"
        />
        <Button
          :loading="loading"
          type="primary"
          @click="
            pageNo = 1;
            reload();
          "
        >
          查询
        </Button>
        <Button @click="resetFilter">重置</Button>
        <span style="flex: 1"></span>
        <Button
          v-if="canExport"
          @click="
            exportsVisible = true;
            reloadExports();
          "
        >
          我的导出
        </Button>
        <Button v-if="canApprove" @click="openApprovals">导出审批</Button>
        <Button v-if="canExport" type="primary" @click="openExport">
          申请导出
        </Button>
      </div>
      <p class="hint">
        单次查询时间跨度不超过 3 个月；日志只读，不可修改与删除。
      </p>
      <Spin :spinning="loading">
        <Table
          :columns="columns"
          :data-source="rows"
          :pagination="false"
          row-key="id"
          size="middle"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'result'">
              <Tag :color="auditResultColor(String(record.result))">
                {{ auditResultText(String(record.result)) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'accessMethod'">
              {{ accessMethodText(record.accessMethod) }}
            </template>
            <template v-else-if="column.dataIndex === 'action-link'">
              <a @click="openDetail(record)">详情</a>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无日志，请调整查询条件" />
          </template>
        </Table>
      </Spin>
      <div class="pager">
        <Pagination
          v-model:current="pageNo"
          :page-size="PAGE_SIZE"
          :total="total"
          show-quick-jumper
          @change="reload"
        />
      </div>
    </Card>

    <!-- 详情（PLT-AUD-006：前后值对比 + 同 traceId 关联） -->
    <Drawer
      v-model:open="detailVisible"
      placement="right"
      :width="560"
      :title="detailTitle(detailKind, Number(detail?.row?.id ?? 0))"
    >
      <Spin :spinning="detailLoading">
        <Descriptions v-if="detail" :column="1" bordered size="small">
          <DescriptionsItem label="时间">
            {{ detail.row?.createdAt }}
          </DescriptionsItem>
          <DescriptionsItem label="操作人">
            {{ detail.row?.displayName }}（{{ detail.row?.userName }}）
          </DescriptionsItem>
          <DescriptionsItem label="模块 / 操作">
            {{ detail.row?.module }} / {{ detail.row?.action }}
          </DescriptionsItem>
          <DescriptionsItem label="对象">
            {{ detail.row?.objectType }} {{ detail.row?.objectId }}
          </DescriptionsItem>
          <DescriptionsItem label="结果">
            {{ auditResultText(String(detail.row?.result)) }}
            <span v-if="detail.row?.reason">（{{ detail.row?.reason }}）</span>
          </DescriptionsItem>
          <DescriptionsItem label="IP / 终端">
            {{ detail.row?.ip }}<br />{{ detail.userAgent }}
          </DescriptionsItem>
          <DescriptionsItem label="traceId">
            {{ detail.row?.traceId }}
          </DescriptionsItem>
          <DescriptionsItem label="变更前值">
            <pre class="json">{{ detail.beforeJson ?? '—' }}</pre>
          </DescriptionsItem>
          <DescriptionsItem label="变更后值">
            <pre class="json">{{ detail.afterJson ?? '—' }}</pre>
          </DescriptionsItem>
          <DescriptionsItem v-if="detail.hash" label="哈希链">
            前序 {{ detail.prevHash?.slice(0, 16) }}…<br />本条
            {{ detail.hash?.slice(0, 16) }}…
          </DescriptionsItem>
        </Descriptions>
        <h4 v-if="detailLinks.length > 0" style="margin: 16px 0 8px">
          同 traceId 关联日志
        </h4>
        <ul v-if="detailLinks.length > 0" class="trace-links">
          <li v-for="link in detailLinks" :key="`${link.logType}-${link.id}`">
            {{ link.createdAt }} · {{ link.action }} ·
            {{ link.description ?? '' }}
          </li>
        </ul>
      </Spin>
    </Drawer>

    <!-- 申请导出（PLT-AUD-007） -->
    <Modal
      v-model:open="exportVisible"
      title="申请导出审计日志"
      :confirm-loading="exportLoading"
      ok-text="提交"
      @ok="submitExport"
    >
      <p class="hint">
        导出需审批：租户管理员本人导出免审批；普通用户由租户管理员审批。文件加水印，24
        小时内有效，导出全程记审计。
      </p>
      <RangePicker
        v-model:value="exportForm.range"
        show-time
        style="width: 100%; margin-bottom: 12px"
        value-format="YYYY-MM-DDTHH:mm:ss+08:00"
        :placeholder="['开始时间', '结束时间']"
      />
      <Textarea
        v-model:value="exportForm.reason"
        :maxlength="200"
        placeholder="申请理由（选填，最多 200 字）"
        :rows="3"
      />
    </Modal>

    <!-- 我的导出 -->
    <Drawer
      v-model:open="exportsVisible"
      placement="right"
      :width="640"
      title="我的导出"
    >
      <Spin :spinning="exportsLoading">
        <Table
          :columns="[
            { dataIndex: 'id', title: '申请号', width: 80 },
            { dataIndex: 'exportType', title: '日志类型', width: 110 },
            {
              customRender: 'status',
              dataIndex: 'status',
              title: '状态',
              width: 100,
            },
            { dataIndex: 'rowCount', title: '行数', width: 80 },
            { dataIndex: 'createdAt', title: '申请时间', width: 170 },
            { dataIndex: 'action', title: '操作', width: 130 },
          ]"
          :data-source="exportJobs"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'status'">
              <Tag :color="jobExportStatusColor(String(record.status))">
                {{ jobExportStatusText(String(record.status)) }}
              </Tag>
              <div class="hint">{{ exportDownloadHint(record) }}</div>
            </template>
            <template v-else-if="column.dataIndex === 'action'">
              <Button
                v-if="record.status === 'READY'"
                size="small"
                type="primary"
                @click="download(record)"
              >
                下载
              </Button>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无导出申请" />
          </template>
        </Table>
      </Spin>
    </Drawer>

    <!-- 导出审批（租户管理员） -->
    <Drawer
      v-model:open="approvalsVisible"
      placement="right"
      :width="720"
      title="待审批的导出申请"
    >
      <Spin :spinning="approvalsLoading">
        <Table
          :columns="approvalColumns"
          :data-source="pendingJobs"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'action'">
              <Space>
                <Button
                  size="small"
                  type="primary"
                  @click="askDecision(record, true)"
                >
                  通过
                </Button>
                <Button size="small" danger @click="askDecision(record, false)">
                  驳回
                </Button>
              </Space>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无待审批申请" />
          </template>
        </Table>
      </Spin>
    </Drawer>

    <!-- 审批意见 -->
    <Modal
      v-model:open="commentModal.visible"
      :title="commentModal.approve ? '通过导出申请' : '驳回导出申请'"
      :confirm-loading="approveBusy === 1"
      :ok-text="commentModal.approve ? '通过' : '驳回'"
      :ok-button-props="{ danger: !commentModal.approve }"
      @ok="decide"
    >
      <Textarea
        v-model:value="commentModal.comment"
        :maxlength="200"
        placeholder="审批意见（选填，最多 200 字）"
        :rows="3"
      />
    </Modal>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}

.hint {
  margin: 4px 0 12px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.json {
  max-height: 180px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
}

.trace-links {
  padding-left: 18px;
  margin: 0;
  font-size: 12px;
}
</style>
