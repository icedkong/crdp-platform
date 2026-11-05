<script lang="ts" setup>
import type {
  ContactLogRow,
  PatientContactVersion,
  PatientOverview,
  PatientStatusHistoryRow,
  PatientTimelineEntry,
  PatientWardHistoryItem,
} from '@crdp/api';

import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  completenessText,
  contactMethodText,
  contactResultText,
  contactSlotText,
  contactSourceText,
  dischargeStatusText,
  genderText,
  latestFollowupText,
  nextVisitText,
  PATIENT_STATUS_TEXT,
  statusSourceText,
  survivalStatusText,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Drawer,
  message,
  Modal,
  Result,
  Spin,
  Table,
  TabPane,
  Tabs,
  Tooltip,
} from 'ant-design-vue';

import { deleteContactLog, fetchPatientContacts } from '#/api/core/contact';
import AudioPlayer from '#/components/followup/audio-player.vue';
import ContactLogModal from '#/components/followup/contact-log-modal.vue';
import FollowupTimeline from '#/components/followup/followup-timeline.vue';
import PatientHeader from '#/components/followup/patient-header.vue';
import TransferWardModal from '#/components/followup/transfer-ward-modal.vue';
import { usePermission } from '#/reference/use-permission';

defineOptions({ name: 'FollowupPatientDetail' });

/**
 * E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010：患者档案（pages.md §4）。
 * 概览 = 顶部 PatientHeader（OQ-21：规格「概览」页并入头部），显示基线（完整度 +
 * 登记实体列）、计划（下次随访窗口）、最近随访（关键指标）；「随访记录」页签为
 * 患者时间轴（出院 + 状态变更，随访记录节点随 E15）。
 * 姓名与编号只显示后端脱敏值；越范围 / 不存在的患者 40400 → 「患者不存在或无权
 * 查看」。基线分节（E13-T02）、报告单（E16）、沟通记录与操作日志（E15）等页签
 * 随对应任务开放。
 */
const route = useRoute();
const router = useRouter();

const loading = ref(true);
const loadFailed = ref(false);
const overview = ref<null | PatientOverview>(null);
const timeline = ref<PatientTimelineEntry[]>([]);
const tab = ref('baseline');

const contactsOpen = ref(false);
const contactsLoading = ref(false);
const contactVersions = ref<PatientContactVersion[]>([]);
const statusHistory = ref<PatientStatusHistoryRow[]>([]);
const wardHistory = ref<PatientWardHistoryItem[]>([]);

// E13-T08 / A-WARD-005：转病区（详情页入口，pages.md §6 页面 4）
const transferOpen = ref(false);

const patientId = computed(() => String(route.params.id ?? ''));

/** 当前病区 id（E13-T08 随概览下发；转病区弹窗用它排除当前病区）。 */
const currentWardId = computed<null | number>(
  () => overview.value?.wardId ?? null,
);

onMounted(async () => {
  try {
    const [summary, timelineResult] = await Promise.all([
      fetchPatientSummary(patientId.value),
      fetchPatientTimeline(patientId.value),
    ]);
    overview.value = summary;
    timeline.value = timelineResult.items;
  } catch {
    // 越范围 / 不存在统一 40400（AGENTS.md §5.2，不暴露对象存在）
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
});

const dischargeStatusLabel = computed(() =>
  overview.value?.dischargeStatus === null ||
  overview.value?.dischargeStatus === undefined
    ? '—'
    : dischargeStatusText(overview.value.dischargeStatus),
);

async function openContacts() {
  contactsOpen.value = true;
  if (
    contactVersions.value.length > 0 ||
    statusHistory.value.length > 0 ||
    wardHistory.value.length > 0
  )
    return;
  contactsLoading.value = true;
  try {
    const [contactInfo, history, wardHist] = await Promise.all([
      fetchPatientContactInfo(patientId.value),
      fetchPatientStatusHistory(patientId.value),
      fetchPatientWardHistory(patientId.value),
    ]);
    contactVersions.value = contactInfo.items;
    statusHistory.value = history;
    wardHistory.value = wardHist.items;
  } catch {
    // 抽屉内失败保留空态；概览本体不受影响
    contactVersions.value = [];
    statusHistory.value = [];
    wardHistory.value = [];
  } finally {
    contactsLoading.value = false;
  }
}

/** 转病区成功（A-WARD-005）：原病区医生立即失去访问权——操作者本人可能已不可见该患者，统一返回列表。 */
function handleTransferred() {
  message.success('已转病区，正在返回患者列表');
  void router.replace('/followup/patient');
}

// ---------------------------------------------------------------- E15-T04 沟通记录页签（pages.md §4.8）

const { hasPermission } = usePermission();
const canEditRecord = computed(() => hasPermission('followup:record:edit'));
const canDownload = computed(() => hasPermission('followup:scan:download'));

const contactLogs = ref<ContactLogRow[]>([]);
const contactLogsTotal = ref(0);
const contactLogsPage = ref(1);
const contactLogsLoading = ref(false);
const contactModalOpen = ref(false);
const editingRow = ref<ContactLogRow | null>(null);
/** 患者已终止（死亡 / 停止 / 失访）时「记录沟通」置灰（pages.md §4.10，后端 terminated 口径）。 */
const followupTerminated = computed(() => overview.value?.terminated ?? false);

async function loadContactLogs(page = 1) {
  contactLogsLoading.value = true;
  try {
    const pageResult = await fetchPatientContacts(patientId.value, {
      pageNo: page,
      pageSize: 20,
    });
    contactLogs.value = pageResult.items;
    contactLogsTotal.value = pageResult.total;
    contactLogsPage.value = pageResult.pageNo;
  } catch {
    contactLogs.value = [];
    contactLogsTotal.value = 0;
  } finally {
    contactLogsLoading.value = false;
  }
}

// 切到页签时加载（沟通记录属于患者档案的一部分，不随首屏拉取）
watch([tab, loading], ([current, isLoading]) => {
  if (current === 'contacts' && !isLoading && overview.value) {
    void loadContactLogs(1);
  }
});

const contactLogColumns = [
  { dataIndex: 'contactTime', title: '时间', width: 160 },
  { dataIndex: 'methodText', title: '方式', width: 90 },
  { dataIndex: 'resultText', title: '结果', width: 110 },
  { dataIndex: 'nextContact', title: '约定下次联系', width: 160 },
  { dataIndex: 'note', title: '备注' },
  { dataIndex: 'audios', title: '录音' },
  { dataIndex: 'creatorName', title: '操作人', width: 110 },
  { dataIndex: 'action', title: '操作', width: 120 },
];

const contactLogRows = computed(() =>
  contactLogs.value.map((item) => ({
    ...item,
    contactTime: formatDateTime(item.contactTime),
    creatorName: item.creatorName ?? '—',
    key: item.id,
    methodText: contactMethodText(item.method),
    nextContact: item.nextContactTime
      ? formatDateTime(item.nextContactTime)
      : '—',
    note: item.note ?? '—',
    resultText: contactResultText(item.result),
  })),
);

/** ISO 时间 →「YYYY-MM-DD HH:mm」（本地展示口径）。 */
function formatDateTime(value: string): string {
  const normalized = value.includes('T') ? value : `${value}T00:00:00`;
  return normalized.slice(0, 16).replaceAll('T', ' ');
}

function openCreateContact() {
  editingRow.value = null;
  contactModalOpen.value = true;
}

function openEditContact(row: ContactLogRow) {
  editingRow.value = row;
  contactModalOpen.value = true;
}

function handleContactSaved() {
  void loadContactLogs(contactLogsPage.value);
}

/** 删除确认要写明两处同时删除（pages.md §4.8 / A-ATT-017）。 */
function handleDeleteContact(row: ContactLogRow) {
  Modal.confirm({
    cancelText: '取消',
    content:
      '录音与「通话记录」报告单是同一文件：删除后两处同时消失，且不可恢复。',
    okButtonProps: { danger: true },
    okText: '删除',
    onOk: async () => {
      try {
        await deleteContactLog(row.id);
        message.success('沟通记录已删除');
        void loadContactLogs(contactLogsPage.value);
      } catch {
        // 提示由统一拦截器弹出
      }
    },
    title: '删除这条沟通记录？',
  });
}

const contactColumns = [
  { dataIndex: 'slot', title: '槽位' },
  { dataIndex: 'phoneMasked', title: '电话（脱敏）' },
  { dataIndex: 'version', title: '版本' },
  { dataIndex: 'effectiveDate', title: '生效日期' },
  { dataIndex: 'source', title: '来源' },
  { dataIndex: 'current', title: '当前' },
];

const contactRows = computed(() =>
  contactVersions.value.map((item) => ({
    ...item,
    current: item.current ? '是' : '—',
    key: item.id,
    slot: contactSlotText(item.slot),
    source: contactSourceText(item.source),
  })),
);

const historyColumns = [
  { dataIndex: 'effectiveDate', title: '生效日期' },
  { dataIndex: 'status', title: '状态' },
  { dataIndex: 'source', title: '来源' },
  { dataIndex: 'reason', title: '原因' },
];

// E13-T08 / A-WARD-005「转病区历史可查」
const wardHistoryColumns = [
  { dataIndex: 'transferredAt', title: '时间' },
  { dataIndex: 'fromWardName', title: '原病区' },
  { dataIndex: 'toWardName', title: '新病区' },
  { dataIndex: 'reason', title: '原因' },
  { dataIndex: 'operatorName', title: '操作人' },
];

const wardHistoryRows = computed(() =>
  wardHistory.value.map((item) => ({
    ...item,
    fromWardName: item.fromWardName ?? '—',
    key: item.id,
    operatorName: item.operatorName ?? '—',
    toWardName: item.toWardName ?? '—',
  })),
);

const historyRows = computed(() =>
  statusHistory.value.map((item) => ({
    ...item,
    key: item.id,
    reason: item.reason ?? '—',
    source: statusSourceText(item.source),
    status: PATIENT_STATUS_TEXT[item.status] ?? item.status,
  })),
);

const timelineLegendNote =
  '随访记录节点（每年随访、计划外随访、终点事件）随随访业务（E15）接入；医院 C 的器械事件随植入模块（E21）。';
</script>

<template>
  <div v-if="loading" class="loading">
    <Spin tip="正在加载患者档案…" />
  </div>

  <Result
    v-else-if="loadFailed || !overview"
    status="404"
    title="患者不存在或无权查看"
  >
    <template #extra>
      <Button type="primary" @click="router.push('/followup/patient')">
        返回患者列表
      </Button>
    </template>
  </Result>

  <div v-else data-testid="patient-detail">
    <PatientHeader
      :overview="overview"
      @contacts="openContacts"
      @transfer="transferOpen = true"
    />
    <Tabs v-model:active-key="tab" class="tabs">
      <TabPane key="baseline" tab="基线资料" />
      <TabPane key="followup" tab="随访记录" />
      <TabPane key="contacts" tab="沟通记录" />
    </Tabs>

    <div v-if="tab === 'baseline'" class="main" data-testid="baseline-panel">
      <Alert
        class="notice"
        message="基线按分类分节补录与报告单导航随后续任务开放（E13-T02 / E16）；此处先展示出院登记的最小必填集。"
        type="info"
        show-icon
      />
      <Descriptions
        bordered
        :column="{ xs: 1, sm: 1, md: 2, xl: 3 }"
        size="middle"
        title="基线信息（出院登记）"
      >
        <DescriptionsItem label="姓名（脱敏）">
          {{ overview.name }}
        </DescriptionsItem>
        <DescriptionsItem label="编号（脱敏）">
          {{ overview.patientNo }}
        </DescriptionsItem>
        <DescriptionsItem label="原系统编号">
          {{ overview.legacyNo ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="随访编号">
          {{ overview.visitNo ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="性别">
          {{ genderText(overview.gender) }}
        </DescriptionsItem>
        <DescriptionsItem label="年龄（手术时）">
          {{ overview.age ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="病区">
          {{ overview.deptName ? `${overview.deptName} · ` : ''
          }}{{ overview.wardName ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="负责医生">
          {{ overview.responsibleName ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="患者类型">
          {{ overview.patientType ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="手术日期">
          {{ overview.surgeryDate ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="入院日期">
          {{ overview.admissionDate ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="出院日期">
          {{ overview.dischargeDate ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="出院状态">
          {{ dischargeStatusLabel }}
        </DescriptionsItem>
        <DescriptionsItem label="基线资料完整度">
          {{ completenessText(overview.baselineCompleteness) }}
        </DescriptionsItem>
        <DescriptionsItem label="随访资料完整度">
          {{ completenessText(overview.followupCompleteness) }}
        </DescriptionsItem>
      </Descriptions>

      <Descriptions
        bordered
        :column="{ xs: 1, sm: 1, md: 2, xl: 3 }"
        size="middle"
        title="计划与随访（随入组与随访记录写入更新）"
      >
        <DescriptionsItem label="下次随访">
          {{ nextVisitText(overview) }}
        </DescriptionsItem>
        <DescriptionsItem label="最近随访">
          {{ latestFollowupText(overview) }}
        </DescriptionsItem>
        <DescriptionsItem v-if="overview.latestFollowup" label="生存状态">
          {{ survivalStatusText(overview.latestFollowup.survivalStatus) }}
        </DescriptionsItem>
      </Descriptions>
      <p class="hint">
        完整度列在基线分节（E13-T02）与随访记录（E15）写入前显示「—」；计划与随访块在入组调度（E14）与首次随访提交（E15）前显示占位。
      </p>
    </div>

    <div
      v-else-if="tab === 'followup'"
      class="main"
      data-testid="followup-panel"
    >
      <Alert
        class="notice"
        :message="timelineLegendNote"
        type="info"
        show-icon
      />
      <FollowupTimeline :entries="timeline" :patient-id="overview.id" />
    </div>

    <div v-else class="main" data-testid="contacts-panel">
      <div class="toolbar">
        <Tooltip v-if="followupTerminated" title="患者不在随访中">
          <Button disabled>记录沟通</Button>
        </Tooltip>
        <Button
          v-else-if="canEditRecord"
          data-testid="contact-create"
          type="primary"
          @click="openCreateContact"
        >
          记录沟通
        </Button>
      </div>
      <Table
        :columns="contactLogColumns"
        :data-source="contactLogRows"
        :loading="contactLogsLoading"
        :pagination="{
          current: contactLogsPage,
          pageSize: 20,
          showSizeChanger: false,
          total: contactLogsTotal,
        }"
        size="small"
        data-testid="contact-log-table"
        @change="(pag) => loadContactLogs(pag.current ?? 1)"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'resultText'">
            <StatusTag :status="record.resultText" variant="dot" />
          </template>
          <template v-else-if="column.dataIndex === 'audios'">
            <template v-if="record.audios.length === 0">—</template>
            <AudioPlayer
              v-for="audio in record.audios"
              :key="audio.fileId"
              :audio="audio"
              :can-download="canDownload"
            />
          </template>
          <template v-else-if="column.dataIndex === 'action'">
            <template v-if="canEditRecord">
              <Button size="small" type="link" @click="openEditContact(record)">
                修改
              </Button>
              <Button
                danger
                size="small"
                type="link"
                @click="handleDeleteContact(record)"
              >
                删除
              </Button>
            </template>
            <span v-else class="hint">—</span>
          </template>
        </template>
        <template #emptyText> 暂无沟通记录 </template>
      </Table>
      <p class="hint">
        录音与「通话记录」报告单是同一文件（A-ATT-017，报告单页签随 E16）；
        删除任一处两处同时删除。
      </p>
    </div>

    <Drawer v-model:open="contactsOpen" :width="640" title="联系方式与变更记录">
      <Spin :spinning="contactsLoading">
        <h3 class="drawer-title">联系方式版本（换号保留历史）</h3>
        <Table
          :columns="contactColumns"
          :data-source="contactRows"
          :pagination="false"
          size="small"
        >
          <template #emptyText> 暂无联系方式版本 </template>
        </Table>
        <h3 class="drawer-title">状态变更记录</h3>
        <Table
          :columns="historyColumns"
          :data-source="historyRows"
          :pagination="false"
          size="small"
        >
          <template #emptyText> 暂无状态变更 </template>
        </Table>
        <h3 class="drawer-title">转病区历史</h3>
        <Table
          :columns="wardHistoryColumns"
          :data-source="wardHistoryRows"
          :pagination="false"
          size="small"
        >
          <template #emptyText> 暂无转病区记录 </template>
        </Table>
      </Spin>
      <p class="hint">
        电话明文需持有「查看明文」权限，经患者头部的「显示明文」查看并写审计。
      </p>
    </Drawer>

    <ContactLogModal
      v-model:open="contactModalOpen"
      :patient-id="overview.id"
      :row="editingRow"
      @success="handleContactSaved"
    />

    <TransferWardModal
      v-model:open="transferOpen"
      :current-ward-id="currentWardId"
      :label="`${overview.name} · ${overview.patientNo}`"
      :patient-id="overview.id"
      @success="handleTransferred"
    />
  </div>
</template>

<style scoped>
.loading {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: center;
  min-height: 320px;
}

.tabs {
  padding: 0 16px;
  background: #fff;
}

.main {
  padding: 16px;
}

.notice {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.hint {
  margin-top: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.drawer-title {
  margin: 12px 0 8px;
  font-size: 14px;
  font-weight: 600;
}

.drawer-title:first-of-type {
  margin-top: 0;
}
</style>
