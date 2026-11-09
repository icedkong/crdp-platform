<script lang="ts" setup>
import type { PlatformAuditTab, TenantViewSessionRecord } from '@crdp/api';

import type { AuditColumnDef } from './logic';

import { computed, reactive, ref } from 'vue';

import {
  auditResultColor,
  auditResultText,
  buildAuditSearchCondition,
  exportDownloadHint,
  isReauthCancelled,
  jobExportStatusColor,
  jobExportStatusText,
  PLATFORM_AUDIT_TABS,
  tenantViewEndMethodText,
  tenantViewPurposeText,
  tenantViewSessionStatusText,
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
  InputNumber,
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
  approvePlatformExport,
  downloadPlatformAuditExport,
  myPlatformAuditExports,
  pendingPlatformExportApprovals,
  platformAuditDetail,
  platformAuditIntegrity,
  rejectPlatformExport,
  searchPlatformAuditLogs,
  submitPlatformAuditExport,
} from '#/api/core/audit';
import {
  getTenantViewSession,
  listTenantViewSessions,
} from '#/api/core/tenant-view';
import ReauthModal from '#/components/reauth-modal.vue';
import { usePermission } from '#/reference/use-permission';

import {
  chainStatusColor,
  chainStatusText,
  PLATFORM_EXPORT_HINT,
  platformColumnsForTab,
} from './logic';

defineOptions({ name: 'PlatformAudit' });

/**
 * 审计中心（E08-T04 / PLT-AUD-005、006、007、009 · pages.md §11）：页签＝平台操作 / 平台登录 /
 * 租户视角 / 视角规则变更 / 租户控制面日志；组合查询（时间 ≤ 3 个月、租户、账号、模块、结果、IP、
 * traceId）；详情含前后值对比与同 traceId 关联；导出一律由另一名平台管理员审批（申请人不能自审），
 * 文件 24 小时内限时下载、全程记审计；「验链结果」展示最近一次哈希链校验（PLT-AUD-009）。
 */
const { hasPermission } = usePermission();
const canExport = computed(() => hasPermission('platform:audit:export'));
const canApprove = computed(() =>
  hasPermission('platform:audit:export:approve'),
);
// 视角会话台账（E20-T05 / PLT-VIEW-016）：仅平台侧可见，权限 platform:view:session:view
const canViewSessions = computed(() =>
  hasPermission('platform:view:session:view'),
);
const visibleTabs = computed(() =>
  PLATFORM_AUDIT_TABS.filter(
    (item) => item.key !== 'view-session' || canViewSessions.value,
  ),
);

const PAGE_SIZE = 20;
const loading = ref(false);
const tab = ref<PlatformAuditTab>('operation');
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const pageNo = ref(1);
const filter = reactive({
  range: [] as string[],
  tenantId: undefined as number | undefined,
  userName: '',
  module: '',
  result: undefined as string | undefined,
  ip: '',
  traceId: '',
});
const columns = computed<AuditColumnDef[]>(() =>
  platformColumnsForTab(tab.value),
);

// ---------------------------------------------------------------- 视角会话台账（E20-T05 / PLT-VIEW-016）
const sessionLoading = ref(false);
const sessions = ref<TenantViewSessionRecord[]>([]);
const sessionTotal = ref(0);
const sessionPageNo = ref(1);
const sessionFilter = reactive({
  accountName: '',
  tenantId: undefined as number | undefined,
  status: undefined as string | undefined,
});

const sessionColumns = [
  { dataIndex: 'sessionNo', title: '会话号', width: 140 },
  { dataIndex: 'accountName', title: '平台账号', width: 130 },
  { dataIndex: 'displayName', title: '姓名', width: 120 },
  { dataIndex: 'tenantName', title: '租户', width: 150 },
  { dataIndex: 'purpose', title: '目的', width: 90 },
  { dataIndex: 'startedAt', title: '开始时间', width: 160 },
  { dataIndex: 'endedAt', title: '结束', width: 150 },
  { dataIndex: 'counts', title: '页面 / 查询 / 被拒 / 代为', width: 160 },
  { dataIndex: 'status', title: '状态', width: 84 },
  { dataIndex: 'action-link', title: '操作', width: 70 },
];

async function loadSessions() {
  sessionLoading.value = true;
  try {
    const page = await listTenantViewSessions({
      accountName: sessionFilter.accountName || undefined,
      pageNo: sessionPageNo.value,
      pageSize: PAGE_SIZE,
      status: sessionFilter.status,
      tenantId: sessionFilter.tenantId,
    });
    sessions.value = page.items;
    sessionTotal.value = page.total;
  } catch (error) {
    message.error(
      (error as { message?: string }).message ?? '会话台账加载失败',
    );
  } finally {
    sessionLoading.value = false;
  }
}

function searchSessions() {
  sessionPageNo.value = 1;
  void loadSessions();
}

// 会话明细：台账整行 + 该会话的访问记录（按会话号钻取，PLT-VIEW-016「明细」口径）
const sessionDetailVisible = ref(false);
const sessionDetailLoading = ref(false);
const sessionDetail = ref<null | TenantViewSessionRecord>(null);
const sessionAccessRows = ref<Record<string, unknown>[]>([]);

async function openSessionDetail(row: Record<string, unknown>) {
  sessionDetailVisible.value = true;
  sessionDetailLoading.value = true;
  sessionAccessRows.value = [];
  try {
    const record = await getTenantViewSession(Number(row.id));
    sessionDetail.value = record;
    // 访问明细窗口 = 会话起止 ± 5 分钟（跨度远小于 3 个月上限）
    const start = new Date(new Date(record.startedAt).getTime() - 5 * 60 * 1000)
      .toISOString()
      .replace('Z', '+08:00');
    const endRaw = record.endAt ?? record.endsAt ?? new Date().toISOString();
    const end = new Date(new Date(endRaw).getTime() + 5 * 60 * 1000)
      .toISOString()
      .replace('Z', '+08:00');
    const page = await searchPlatformAuditLogs('tenant-view', {
      endTime: end,
      pageNo: 1,
      pageSize: 50,
      startTime: start,
      viewSessionId: record.sessionNo,
    });
    sessionAccessRows.value = page.items as unknown as Record<
      string,
      unknown
    >[];
  } catch (error) {
    message.error(
      (error as { message?: string }).message ?? '会话明细加载失败',
    );
  } finally {
    sessionDetailLoading.value = false;
  }
}

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
    const page = await searchPlatformAuditLogs(
      tab.value,
      buildAuditSearchCondition(
        {
          endTime: end,
          ip: filter.ip,
          module: filter.module,
          result: filter.result,
          startTime: start,
          tenantId: filter.tenantId,
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
  filter.tenantId = undefined;
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
  if (tab.value === 'view-session') {
    sessionPageNo.value = 1;
    void loadSessions();
  }
}

// ---------------------------------------------------------------- 详情
const detailVisible = ref(false);
const detailLoading = ref(false);
const detail = ref<null | Record<string, unknown>>(null);
const detailLinks = ref<Record<string, unknown>[]>([]);

async function openDetail(row: Record<string, unknown>) {
  detailVisible.value = true;
  detailLoading.value = true;
  try {
    const view = await platformAuditDetail(tab.value, Number(row.id));
    detail.value = view as unknown as Record<string, unknown>;
    detailLinks.value = view.traceLinks as unknown as Record<string, unknown>[];
  } catch (error) {
    message.error((error as { message?: string }).message ?? '详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

// ---------------------------------------------------------------- 验链结果（PLT-AUD-009）
const integrityVisible = ref(false);
const integrityLoading = ref(false);
const integrityRows = ref<Record<string, unknown>[]>([]);
const integrityTenantId = ref<number | undefined>(undefined);

async function openIntegrity() {
  integrityVisible.value = true;
  integrityLoading.value = true;
  try {
    const list = await platformAuditIntegrity(integrityTenantId.value);
    integrityRows.value = list as unknown as Record<string, unknown>[];
  } catch (error) {
    message.error(
      (error as { message?: string }).message ?? '验链结果加载失败',
    );
  } finally {
    integrityLoading.value = false;
  }
}

// ---------------------------------------------------------------- 导出（PLT-AUD-007）
const exportVisible = ref(false);
const exportLoading = ref(false);
const exportForm = reactive({ range: [] as string[], reason: '' });
const reauthModal = ref<InstanceType<typeof ReauthModal>>();

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
    // E04-T08 / PLT-AUTH-012：导出提交是敏感操作，先二次认证（X-Reauth-Token）
    await reauthModal.value?.run((headers) =>
      submitPlatformAuditExport(
        {
          condition: buildAuditSearchCondition({
            endTime: end,
            module: filter.module || undefined,
            startTime: start,
            tenantId: filter.tenantId,
          }),
          reason: exportForm.reason || undefined,
          type: tab.value,
        },
        headers,
      ),
    );
    exportVisible.value = false;
    message.success('导出申请已提交，待另一名平台管理员审批');
    exportsVisible.value = true;
    reloadExports();
  } catch (error_) {
    if (isReauthCancelled(error_)) {
      return;
    }
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
    const page = await myPlatformAuditExports(1, 100);
    exportJobs.value = page.items as unknown as Record<string, any>[];
  } finally {
    exportsLoading.value = false;
  }
}

async function download(job: Record<string, any>) {
  try {
    // 下载同为敏感操作（E04-T08）：二次认证窗口内复用同一令牌
    await reauthModal.value?.run((headers) =>
      downloadPlatformAuditExport(
        Number(job.id),
        String(job.fileName ?? ''),
        headers,
      ),
    );
    reloadExports();
  } catch (error) {
    if (isReauthCancelled(error)) {
      return;
    }
    message.error((error as { message?: string }).message ?? '下载失败');
  }
}

// ---------------------------------------------------------------- 审批
const approvalsVisible = ref(false);
const approvalsLoading = ref(false);
const pendingJobs = ref<Record<string, any>[]>([]);
const commentModal = reactive({
  visible: false,
  job: null as null | Record<string, any>,
  approve: true,
  comment: '',
});
const decideBusy = ref(false);

async function reloadApprovals() {
  approvalsLoading.value = true;
  try {
    const page = await pendingPlatformExportApprovals(1, 100);
    pendingJobs.value = page.items as unknown as Record<string, any>[];
  } finally {
    approvalsLoading.value = false;
  }
}

function askDecision(job: Record<string, any>, approve: boolean) {
  commentModal.job = job;
  commentModal.approve = approve;
  commentModal.comment = '';
  commentModal.visible = true;
}

async function decide() {
  if (!commentModal.job) return;
  decideBusy.value = true;
  try {
    if (commentModal.approve) {
      await approvePlatformExport(
        Number(commentModal.job.id),
        commentModal.comment || undefined,
      );
      message.success('已通过，文件开始生成');
    } else {
      await rejectPlatformExport(
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
    decideBusy.value = false;
  }
}

const approvalColumns = [
  { dataIndex: 'id', title: '申请号', width: 80 },
  { dataIndex: 'applicantName', title: '申请人', width: 130 },
  { dataIndex: 'applicantDisplayName', title: '姓名', width: 120 },
  { dataIndex: 'exportType', title: '日志类型', width: 120 },
  { dataIndex: 'reason', title: '申请理由' },
  { dataIndex: 'createdAt', title: '申请时间', width: 170 },
  { dataIndex: 'action', title: '操作', width: 150 },
];
</script>

<template>
  <div class="page">
    <h1 class="crdp-page-title">审计中心</h1>
    <Card>
      <Tabs v-model:active-key="tab.value" @change="onTabChange">
        <TabPane
          v-for="item in visibleTabs"
          :key="item.key"
          :tab="item.label"
        />
      </Tabs>

      <!-- 视角会话台账（E20-T05 / PLT-VIEW-016，仅平台侧可见） -->
      <template v-if="tab === 'view-session'">
        <div class="filter-bar">
          <Input
            v-model:value="sessionFilter.accountName"
            allow-clear
            placeholder="平台账号"
            style="width: 150px"
          />
          <InputNumber
            v-model:value="sessionFilter.tenantId"
            :min="1"
            placeholder="租户ID"
            style="width: 100px"
          />
          <Select
            v-model:value="sessionFilter.status"
            allow-clear
            placeholder="状态"
            style="width: 110px"
          >
            <SelectOption value="ACTIVE">进行中</SelectOption>
            <SelectOption value="ENDED">已结束</SelectOption>
          </Select>
          <Button
            :loading="sessionLoading"
            type="primary"
            @click="searchSessions"
          >
            查询
          </Button>
          <span style="flex: 1"></span>
        </div>
        <p class="hint">
          视角会话台账（进入与退出、页面 / 查询 /
          被拒计数、生效规则版本）；点击「明细」查看该会话的访问记录。仅平台侧可见，医院端无查看入口。
        </p>
        <Spin :spinning="sessionLoading">
          <Table
            :columns="sessionColumns"
            :data-source="sessions"
            :pagination="false"
            row-key="id"
            size="middle"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'purpose'">
                {{ tenantViewPurposeText(String(record.purpose)) }}
              </template>
              <template v-else-if="column.dataIndex === 'startedAt'">
                {{
                  String(record.startedAt ?? '')
                    .slice(0, 19)
                    .replace('T', ' ')
                }}
              </template>
              <template v-else-if="column.dataIndex === 'endedAt'">
                <template v-if="record.endAt">
                  {{ String(record.endAt).slice(0, 19).replace('T', ' ') }}
                  （{{ tenantViewEndMethodText(String(record.endMethod)) }}）
                </template>
                <template v-else>进行中</template>
              </template>
              <template v-else-if="column.dataIndex === 'counts'">
                {{ record.pageCount }} / {{ record.queryCount }} /
                {{ record.deniedCount }} / {{ record.proxyCount }}
              </template>
              <template v-else-if="column.dataIndex === 'status'">
                <Tag
                  :color="record.status === 'ACTIVE' ? 'processing' : 'default'"
                >
                  {{ tenantViewSessionStatusText(String(record.status)) }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'action-link'">
                <a @click="openSessionDetail(record)">明细</a>
              </template>
            </template>
            <template #emptyText>
              <Empty description="暂无视角会话" />
            </template>
          </Table>
        </Spin>
        <div class="pager">
          <Pagination
            v-model:current="sessionPageNo"
            :page-size="PAGE_SIZE"
            :total="sessionTotal"
            show-quick-jumper
            @change="loadSessions"
          />
        </div>
      </template>

      <div v-else class="filter-bar">
        <RangePicker
          v-model:value="filter.range"
          show-time
          value-format="YYYY-MM-DDTHH:mm:ss+08:00"
          :placeholder="['开始时间', '结束时间']"
        />
        <InputNumber
          v-model:value="filter.tenantId"
          :min="1"
          placeholder="租户ID"
          style="width: 100px"
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
          <SelectOption value="SUCCESS">成功</SelectOption>
          <SelectOption value="DENIED">被拒绝</SelectOption>
          <SelectOption value="FAIL">失败</SelectOption>
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
        <Button @click="openIntegrity">验链结果</Button>
        <Button
          v-if="canExport"
          @click="
            exportsVisible = true;
            reloadExports();
          "
        >
          我的导出
        </Button>
        <Button
          v-if="canApprove"
          @click="
            approvalsVisible = true;
            reloadApprovals();
          "
        >
          导出审批
        </Button>
        <Button
          v-if="canExport && tab !== 'view-session'"
          type="primary"
          @click="openExport"
        >
          申请导出
        </Button>
      </div>
      <p v-if="tab !== 'view-session'" class="hint">
        单次查询时间跨度不超过 3 个月；日志只读，不可修改与删除。
      </p>
      <Spin v-if="tab !== 'view-session'" :spinning="loading">
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
            <template v-else-if="column.dataIndex === 'action-link'">
              <a @click="openDetail(record)">详情</a>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无日志，请调整查询条件" />
          </template>
        </Table>
      </Spin>
      <div v-if="tab !== 'view-session'" class="pager">
        <Pagination
          v-model:current="pageNo"
          :page-size="PAGE_SIZE"
          :total="total"
          show-quick-jumper
          @change="reload"
        />
      </div>
    </Card>

    <!-- 视角会话明细（台账 + 该会话的访问记录，PLT-VIEW-016） -->
    <Drawer
      v-model:open="sessionDetailVisible"
      placement="right"
      :width="720"
      title="视角会话明细"
    >
      <Spin :spinning="sessionDetailLoading">
        <Descriptions v-if="sessionDetail" :column="1" bordered size="small">
          <DescriptionsItem label="会话号">
            {{ sessionDetail.sessionNo }}
          </DescriptionsItem>
          <DescriptionsItem label="平台账号">
            {{ sessionDetail.displayName }}（{{ sessionDetail.accountName }}）
          </DescriptionsItem>
          <DescriptionsItem label="平台角色">
            {{ sessionDetail.roleCodes }}
          </DescriptionsItem>
          <DescriptionsItem label="租户">
            {{ sessionDetail.tenantName }}（{{ sessionDetail.tenantCode }}）
          </DescriptionsItem>
          <DescriptionsItem label="目的与说明">
            {{ tenantViewPurposeText(sessionDetail.purpose) }} ·
            {{ sessionDetail.note }}
          </DescriptionsItem>
          <DescriptionsItem label="生效规则版本">
            {{ sessionDetail.ruleVersion }}
          </DescriptionsItem>
          <DescriptionsItem label="起止时间">
            {{ String(sessionDetail.startedAt).slice(0, 19).replace('T', ' ') }}
            ~
            <template v-if="sessionDetail.endAt">
              {{ String(sessionDetail.endAt).slice(0, 19).replace('T', ' ') }}
              （{{ tenantViewEndMethodText(sessionDetail.endMethod ?? '') }}）
            </template>
            <template v-else>进行中</template>
          </DescriptionsItem>
          <DescriptionsItem label="计数摘要">
            页面 {{ sessionDetail.pageCount }} · 查询
            {{ sessionDetail.queryCount }} · 被拒
            {{ sessionDetail.deniedCount }} · 代为操作
            {{ sessionDetail.proxyCount }}
          </DescriptionsItem>
        </Descriptions>
        <h4 style="margin: 16px 0 8px">访问记录（最近 50 条）</h4>
        <Table
          :columns="[
            { dataIndex: 'createdAt', title: '时间', width: 160 },
            { dataIndex: 'action', title: '操作', width: 120 },
            { dataIndex: 'description', title: '详情' },
            { dataIndex: 'objectType', title: '对象', width: 90 },
            { dataIndex: 'recordCount', title: '条数', width: 70 },
            { dataIndex: 'result', title: '结果', width: 80 },
          ]"
          :data-source="sessionAccessRows"
          :pagination="false"
          row-key="id"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'result'">
              <Tag :color="auditResultColor(String(record.result))">
                {{ auditResultText(String(record.result)) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'recordCount'">
              {{ record.recordCount ?? '—' }}
            </template>
          </template>
          <template #emptyText>
            <Empty description="该会话暂无访问记录" />
          </template>
        </Table>
      </Spin>
    </Drawer>

    <!-- 详情 -->
    <Drawer
      v-model:open="detailVisible"
      placement="right"
      :width="560"
      title="日志详情"
    >
      <Spin :spinning="detailLoading">
        <Descriptions v-if="detail" :column="1" bordered size="small">
          <DescriptionsItem label="时间">
            {{ detail.row?.createdAt }}
          </DescriptionsItem>
          <DescriptionsItem label="租户ID">
            {{ detail.row?.tenantId ?? '—' }}
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
          <DescriptionsItem label="视角会话号">
            {{ detail.viewSessionId ?? '—' }}
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

    <!-- 验链结果 -->
    <Drawer
      v-model:open="integrityVisible"
      placement="right"
      :width="560"
      title="哈希链校验结果（最近一次）"
    >
      <div style="margin-bottom: 12px">
        <InputNumber
          v-model:value="integrityTenantId"
          :min="1"
          placeholder="租户ID（空 = 平台库自身）"
          style="width: 220px"
        />
        <Button style="margin-left: 8px" @click="openIntegrity">查询</Button>
      </div>
      <Spin :spinning="integrityLoading">
        <Table
          :columns="[
            { dataIndex: 'tableName', title: '审计表' },
            {
              customRender: 'status',
              dataIndex: 'status',
              title: '状态',
              width: 90,
            },
            { dataIndex: 'hashedRows', title: '已封存行数', width: 100 },
            { dataIndex: 'breakCount', title: '断链数', width: 80 },
            { dataIndex: 'checkedAt', title: '检查时间', width: 170 },
          ]"
          :data-source="integrityRows"
          :pagination="false"
          row-key="tableName"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'status'">
              <Tag :color="chainStatusColor(String(record.status))">
                {{ chainStatusText(String(record.status)) }}
              </Tag>
            </template>
          </template>
          <template #emptyText>
            <Empty description="暂无校验记录" />
          </template>
        </Table>
      </Spin>
    </Drawer>

    <!-- 申请导出 -->
    <Modal
      v-model:open="exportVisible"
      title="申请导出平台审计日志"
      :confirm-loading="exportLoading"
      ok-text="提交"
      @ok="submitExport"
    >
      <p class="hint">{{ PLATFORM_EXPORT_HINT }}</p>
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
            { dataIndex: 'exportType', title: '日志类型', width: 120 },
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

    <!-- 导出审批 -->
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
      :confirm-loading="decideBusy"
      :ok-text="commentModal.approve ? '通过' : '驳回'"
      :ok-button-props="{ danger: !commentModal.approve }"
      @ok="decide"
    >
      <Alert
        v-if="commentModal.approve"
        message="申请人不能审批自己的申请；审批通过后文件开始异步生成"
        show-icon
        style="margin-bottom: 12px"
      />
      <Textarea
        v-model:value="commentModal.comment"
        :maxlength="200"
        placeholder="审批意见（选填，最多 200 字）"
        :rows="3"
      />
    </Modal>

    <!-- E04-T08 / PLT-AUTH-012：敏感操作二次认证（导出提交 / 下载） -->
    <ReauthModal ref="reauthModal" />
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
