<script lang="ts" setup>
import type { ReportLocation } from '#/reference/types';

import { computed, nextTick, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { timings } from '@crdp/tokens';
import { Alert, Button, Result, Table, TabPane, Tabs } from 'ant-design-vue';

import AttachmentNav from '#/components/followup/attachment-nav.vue';
import CategoryBar from '#/components/followup/category-bar.vue';
import FollowupTimeline from '#/components/followup/followup-timeline.vue';
import PatientHeader from '#/components/followup/patient-header.vue';
import ReadonlyCategories from '#/components/followup/readonly-categories.vue';
import UploadReportDialog from '#/components/followup/upload-report-dialog.vue';
import WatermarkPreview from '#/components/followup/watermark-preview.vue';
import {
  attachments,
  contactRows,
  deviceEventsC,
  findPatient,
  locationTree,
  logRows,
  timelineA,
} from '#/reference/fixtures';
import { usePermission } from '#/reference/use-permission';

defineOptions({ name: 'FollowupPatientDetail' });

const route = useRoute();
const router = useRouter();
const { hasPermission } = usePermission();

const patient = computed(() => findPatient(String(route.params.id)));
const tab = ref('baseline');
const activeKey = ref('');
const uploadOpen = ref(false);
const previewOpen = ref(false);
const previewId = ref('');
const referenceMode = true;

const tree = computed(() => (patient.value ? locationTree(patient.value) : []));
const previewItem = computed(() =>
  attachments.find((item) => item.reportId === previewId.value),
);

const tabs = computed(() => {
  const features = patient.value?.features;
  const list = [
    { key: 'baseline', label: '基线资料' },
    { key: 'followup', label: '随访记录' },
  ];
  if (features?.report) list.push({ key: 'report', label: '报告单' });
  if (features?.paramTrend) list.push({ key: 'trend', label: '参数趋势' });
  list.push(
    { key: 'contact', label: '沟通记录' },
    { key: 'log', label: '操作日志' },
  );
  return list;
});

function flash(id: string) {
  const node = document.querySelector(id);
  if (!node) return;
  node.classList.add('crdp-anchor-flash');
  node.scrollIntoView({ block: 'start' });
  window.setTimeout(
    () => node.classList.remove('crdp-anchor-flash'),
    timings.anchorHighlightMs,
  );
}

async function jumpCategory(key: string) {
  activeKey.value = key;
  tab.value = 'baseline';
  await nextTick();
  flash(`#crdp-anchor-${key}`);
  await router.replace({ hash: `#tab=baseline&cat=${key}` });
}

async function locate(location: ReportLocation) {
  if (location.linkType === 'followup_record' && location.linkRecordId) {
    tab.value = 'followup';
    await nextTick();
    flash(`#rec-${location.linkRecordId}`);
    if (location.anchorKey) {
      await nextTick();
      flash(`#crdp-anchor-${location.anchorKey}`);
    }
    const sec = location.anchorKey ?? '';
    await router.replace({
      hash: `#tab=followup&rec=${location.linkRecordId}&sec=${sec}`,
    });
    return;
  }
  if (location.anchorKey) await jumpCategory(location.anchorKey);
}

function onHeader(type: 'contact' | 'stop' | 'unplanned' | 'upload') {
  if (type === 'upload') uploadOpen.value = true;
}

function openReport(reportId: string) {
  previewId.value = reportId;
  previewOpen.value = true;
}

if (!activeKey.value && patient.value) {
  activeKey.value = patient.value.progress[0]?.key ?? '';
}
</script>

<template>
  <Result v-if="!patient" status="404" title="患者不存在或无权查看">
    <template #extra>
      <Button type="primary" @click="router.push('/followup/patient')">
        返回患者列表
      </Button>
    </template>
  </Result>
  <div v-else data-testid="patient-detail">
    <Alert
      banner
      message="虚构样板数据，不是真实患者。示例：测试患者01，电话 138****0001。"
      type="info"
    />
    <PatientHeader
      can-contact
      can-stop
      can-unplanned
      can-upload
      :patient="patient"
      :reference-mode="referenceMode || hasPermission('followup:patient:plain')"
      @action="onHeader"
    />
    <Tabs v-model:active-key="tab" class="tabs">
      <TabPane v-for="item in tabs" :key="item.key" :tab="item.label" />
    </Tabs>

    <div v-if="tab === 'baseline'" class="split" data-testid="baseline-panel">
      <AttachmentNav
        can-upload
        :items="attachments"
        :patient-id="patient.id"
        scope="baseline"
        @locate="locate"
        @open="openReport"
        @upload="uploadOpen = true"
      />
      <div class="main">
        <CategoryBar
          :active-key="activeKey"
          :items="patient.progress"
          @jump="jumpCategory"
          @update:active-key="activeKey = $event"
        />
        <ReadonlyCategories
          :attachments="attachments"
          can-upload
          :categories="patient.categories"
          @open="openReport"
          @upload="uploadOpen = true"
        />
        <section
          v-if="patient.features.implant"
          id="crdp-anchor-implants"
          class="implants"
        >
          <h3>植入记录（当前器械置顶）</h3>
          <Table
            :columns="[
              { dataIndex: 'date', title: '日期' },
              { dataIndex: 'kind', title: '植入类型' },
              { dataIndex: 'device', title: '器械' },
              { dataIndex: 'trigger', title: '触发' },
            ]"
            :data-source="patient.implants"
            :pagination="false"
            row-key="id"
            size="middle"
          />
          <h3>电极</h3>
          <Table
            :columns="[
              { dataIndex: 'site', title: '部位' },
              { dataIndex: 'date', title: '植入日期' },
              { dataIndex: 'status', title: '状态' },
            ]"
            :data-source="patient.leads"
            :pagination="false"
            row-key="site"
            size="middle"
          />
        </section>
      </div>
    </div>

    <div
      v-else-if="tab === 'followup'"
      class="split"
      data-testid="followup-panel"
    >
      <AttachmentNav
        can-upload
        :items="attachments"
        :patient-id="patient.id"
        scope="followup"
        @locate="locate"
        @open="openReport"
        @upload="uploadOpen = true"
      />
      <div class="main">
        <FollowupTimeline
          :events="patient.features.implant ? deviceEventsC : []"
          :nodes="timelineA"
          :patient-id="patient.id"
          @jump="(_id, key) => flash(`#crdp-anchor-${key}`)"
        >
          <template #default="{ node }">
            <ReadonlyCategories
              :attachments="attachments"
              :categories="node.categories"
              @open="openReport"
            />
          </template>
        </FollowupTimeline>
      </div>
    </div>

    <div v-else-if="tab === 'contact'" class="main">
      <Table
        :columns="[
          { dataIndex: 'at', title: '时间' },
          { dataIndex: 'method', title: '方式' },
          { dataIndex: 'result', title: '结果' },
          { dataIndex: 'note', title: '备注' },
          { dataIndex: 'by', title: '操作人' },
        ]"
        :data-source="contactRows"
        :pagination="{
          pageSize: 20,
          showTotal: (total: number) => `共 ${total} 条`,
        }"
        row-key="at"
        size="middle"
      />
    </div>
    <div v-else-if="tab === 'log'" class="main">
      <Table
        :columns="[
          { dataIndex: 'at', title: '时间' },
          { dataIndex: 'by', title: '操作人' },
          { dataIndex: 'action', title: '操作' },
          { dataIndex: 'detail', title: '详情' },
        ]"
        :data-source="logRows"
        :pagination="{ pageSize: 20 }"
        row-key="at"
        size="middle"
      />
    </div>
    <div v-else class="main">
      <p>此页签的完整内容不在本次样板范围。数据仍是虚构示例。</p>
    </div>

    <UploadReportDialog
      :open="uploadOpen"
      :options="tree"
      :patient-id="patient.id"
      @update:open="uploadOpen = $event"
    />
    <WatermarkPreview
      account="nurse.demo"
      name="演示护士（示例）"
      :open="previewOpen"
      :report-name="previewItem?.name"
      tenant-name="虚构示例医院甲"
      :thumbnail-url="previewItem?.thumbnailUrl"
      @update:open="previewOpen = $event"
    />
  </div>
</template>

<style scoped>
.tabs {
  padding: 0 16px;
  background: #fff;
}

.split {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  padding: 0 16px 16px;
}

.main {
  flex: 1;
  min-width: 0;
  padding: 8px 0 24px;
}

.implants {
  margin-top: 8px;
}

h3 {
  font-size: 14px;
  font-weight: 600;
}

@media (max-width: 1279px) {
  .split {
    flex-direction: column;
  }
}
</style>
