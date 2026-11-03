<script lang="ts" setup>
import type { PatientOverview } from '@crdp/api';

import { computed, ref } from 'vue';

import {
  completenessText,
  genderText,
  latestFollowupText,
  nextVisitText,
  PATIENT_STATUS_TEXT,
} from '@crdp/api';
import { StatusTag } from '@crdp/status-tag';
import { Button } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

import MaskedField from './masked-field.vue';

/**
 * E13-T05 / A-PAT-004、C-PAT-010（components.md §4）：患者详情顶部吸顶信息条。
 * 概览三块（验收「概览显示基线、计划、最近随访」）：完整度（基线块）、下次随访
 * （计划块，E14 写入前「未安排」）、最近随访（E15 写入前「暂无随访记录」）。
 * 姓名 MaskedField revealable：明文走 plaintext 专用接口并写审计，60 秒恢复脱敏。
 * C 当前器械行随 E21 植入数据开放；四个操作按钮随 E14（停止）/ E15（计划外、
 * 沟通）/ E16（上传）的接口落地后按 summary.actions 渲染。
 */
const props = defineProps<{
  overview: PatientOverview;
}>();

const emit = defineEmits<{
  contacts: [];
  transfer: [];
}>();

/** E13-T08 / A-WARD-005：转病区按钮按 summary.actions（后端按权限计算）显示。 */
const canTransfer = computed(() => props.overview.actions.includes('transfer'));

const { isMobile } = useBreakpoint();
const detailsOpen = ref(false);

const ageText = computed(() =>
  props.overview.age === null || props.overview.age === undefined
    ? '—'
    : `${props.overview.age} 岁`,
);

const idText = computed(() => {
  const parts = [`编号 ${props.overview.patientNo}`];
  if (props.overview.legacyNo)
    parts.push(`原系统编号 ${props.overview.legacyNo}`);
  if (props.overview.visitNo) parts.push(`随访编号 ${props.overview.visitNo}`);
  return parts;
});

const wardText = computed(() => {
  const ward = props.overview.wardName ?? '—';
  return props.overview.deptName
    ? `${props.overview.deptName} · ${ward}`
    : ward;
});

/** 第三行元信息：基线块 / 随访块 / 最近随访块（验收「概览显示基线、计划、最近随访」）。 */
const metrics = computed(() => [
  `基线资料完整度 ${completenessText(props.overview.baselineCompleteness)}`,
  `随访资料完整度 ${completenessText(props.overview.followupCompleteness)}`,
  `最近随访 ${latestFollowupText(props.overview)}`,
]);
</script>

<template>
  <header class="header" data-testid="patient-header">
    <div class="line">
      <strong class="name">
        <MaskedField
          field="name"
          :patient-id="overview.id"
          revealable
          :value="overview.name"
        />
      </strong>
      <StatusTag
        :status="PATIENT_STATUS_TEXT[overview.status] ?? overview.status"
        variant="tag"
      />
      <span class="meta">下次随访 {{ nextVisitText(overview) }}</span>
      <Button
        v-if="isMobile"
        size="small"
        type="link"
        @click="detailsOpen = !detailsOpen"
      >
        {{ detailsOpen ? '收起 ▴' : '详情 ▾' }}
      </Button>
    </div>
    <div v-if="!isMobile || detailsOpen" class="line">
      <span>{{ genderText(overview.gender) }} · {{ ageText }}</span>
      <span v-for="item in idText" :key="item">{{ item }}</span>
      <span>病区 {{ wardText }}</span>
      <span>负责医生 {{ overview.responsibleName ?? '—' }}</span>
    </div>
    <div v-if="!isMobile || detailsOpen" class="line">
      <span v-for="item in metrics" :key="item">{{ item }}</span>
    </div>
    <div class="line actions">
      <!-- A-PAT-004「联系方式与变更记录从概览进入」：抽屉内容走 E13-T03 接口 -->
      <Button
        data-testid="open-contacts"
        size="small"
        @click="emit('contacts')"
      >
        联系方式与变更记录
      </Button>
      <!-- A-WARD-005「患者详情 · 转病区」（pages.md §6）：actions 由后端按
           followup:patient:transfer 计算下发 -->
      <Button
        v-if="canTransfer"
        data-testid="open-transfer"
        size="small"
        @click="emit('transfer')"
      >
        转病区
      </Button>
    </div>
  </header>
</template>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 8;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e9e9e9;
}

.line {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
  margin-bottom: 8px;
}

.line.actions {
  margin-bottom: 0;
}

.name {
  font-size: 16px;
  font-weight: 600;
}

.meta {
  color: #8c8c8c;
}
</style>
