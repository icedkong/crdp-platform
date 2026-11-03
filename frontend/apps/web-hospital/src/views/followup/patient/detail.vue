<script lang="ts" setup>
import type {
  PatientContactVersion,
  PatientOverview,
  PatientStatusHistoryRow,
  PatientTimelineEntry,
  PatientWardHistoryItem,
} from '@crdp/api';

import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import {
  completenessText,
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
import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Drawer,
  message,
  Result,
  Spin,
  Table,
  TabPane,
  Tabs,
} from 'ant-design-vue';

import {
  fetchPatientContactInfo,
  fetchPatientStatusHistory,
  fetchPatientSummary,
  fetchPatientTimeline,
  fetchPatientWardHistory,
} from '#/api/core/patient';
import FollowupTimeline from '#/components/followup/followup-timeline.vue';
import PatientHeader from '#/components/followup/patient-header.vue';
import TransferWardModal from '#/components/followup/transfer-ward-modal.vue';

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

    <div v-else class="main" data-testid="followup-panel">
      <Alert
        class="notice"
        :message="timelineLegendNote"
        type="info"
        show-icon
      />
      <FollowupTimeline :entries="timeline" :patient-id="overview.id" />
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
