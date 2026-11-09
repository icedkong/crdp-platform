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
import { Button, Drawer } from 'ant-design-vue';

import { useBreakpoint } from '#/reference/use-breakpoint';

import MaskedField from './masked-field.vue';

/**
 * E13-T05 / A-PAT-004、C-PAT-010（components.md §4）：患者详情顶部吸顶信息条。
 * 概览三块（验收「概览显示基线、计划、最近随访」）：完整度（基线块）、下次随访
 * （计划块，E14 写入前「未安排」）、最近随访（E15 写入前「暂无随访记录」）。
 * 姓名 MaskedField revealable：明文走 plaintext 专用接口并写审计，60 秒恢复脱敏。
 * 操作按钮按 summary.actions（后端按权限 + 状态计算）渲染：E15-T06 起接入
 * 「新建计划外随访」（unplanned，C-FU-022：患者在随访中 + followup:record:edit）；
 * 上传报告单（E16）/ 停止随访（E14 接口待页面接入）随对应任务开放。
 *
 * E24-T04 / NFR-RESP-002（components.md §4 手机行）：手机折叠为「姓名 + 状态 +
 * 下次随访」+「详情 ▾」展开其余；操作按钮收进「操作」底部抽屉（design-system
 * §7 允许的手机「操作」菜单场景），点完先关抽屉再执行。
 */
const props = defineProps<{
  overview: PatientOverview;
}>();

const emit = defineEmits<{
  contacts: [];
  transfer: [];
  unplanned: [];
}>();

/** E13-T08 / A-WARD-005：转病区按钮按 summary.actions（后端按权限计算）显示。 */
const canTransfer = computed(() => props.overview.actions.includes('transfer'));

/** E15-T06 / C-FU-022：新建计划外随访（components.md §4 按权限 + 在随访中显示）。 */
const canUnplanned = computed(() =>
  props.overview.actions.includes('unplanned'),
);

const { isMobile } = useBreakpoint();
const detailsOpen = ref(false);
/** E24-T04 手机「操作」底部抽屉开关。 */
const actionsOpen = ref(false);

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

/**
 * E24-T04 手机操作抽屉按钮列表：与桌面行内按钮同一组、同一显隐口径
 * （联系方式常显；transfer / unplanned 由 summary.actions 按权限计算）。
 */
type HeaderAction = 'contacts' | 'transfer' | 'unplanned';

const actionButtons = computed<
  Array<{
    event: HeaderAction;
    label: string;
    primary?: boolean;
    testid: string;
  }>
>(() => {
  const list: Array<{
    event: HeaderAction;
    label: string;
    primary?: boolean;
    testid: string;
  }> = [
    { event: 'contacts', label: '联系方式与变更记录', testid: 'open-contacts' },
  ];
  if (canTransfer.value)
    list.push({ event: 'transfer', label: '转病区', testid: 'open-transfer' });
  if (canUnplanned.value)
    list.push({
      event: 'unplanned',
      label: '新建计划外随访',
      primary: true,
      testid: 'open-unplanned',
    });
  return list;
});

/** 抽屉内点操作：先关抽屉再上抛（components.md §5「抽屉模式：点完先关抽屉再跳转」同口径）。 */
function runAction(event: HeaderAction) {
  actionsOpen.value = false;
  emit(event);
}
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
      <template v-if="isMobile">
        <!-- E24-T04 手机：折叠行尾部「详情 ▾」+「操作」（操作收进底部抽屉） -->
        <Button
          data-testid="header-details-toggle"
          size="small"
          type="link"
          @click="detailsOpen = !detailsOpen"
        >
          {{ detailsOpen ? '收起 ▴' : '详情 ▾' }}
        </Button>
        <Button
          data-testid="header-actions-open"
          size="small"
          type="link"
          @click="actionsOpen = true"
        >
          操作 ▾
        </Button>
      </template>
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
    <!-- E24-T04：桌面行内操作；手机收进「操作」底部抽屉（下方 Drawer） -->
    <div v-if="!isMobile" class="line actions">
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
      <!-- C-FU-022 症状就诊、程控调整等直接新建记录；患者不在随访中时后端不下发该 action -->
      <Button
        v-if="canUnplanned"
        data-testid="open-unplanned"
        size="small"
        type="primary"
        @click="emit('unplanned')"
      >
        新建计划外随访
      </Button>
    </div>
    <!-- E24-T04 / components.md §4：手机操作收进底部抽屉（design-system §7
         「手机“操作”菜单」允许场景）；点完先关抽屉再执行 -->
    <Drawer
      v-if="isMobile"
      v-model:open="actionsOpen"
      class-name="crdp-header-actions-drawer"
      placement="bottom"
      title="操作"
    >
      <div class="action-list">
        <Button
          v-for="action in actionButtons"
          :key="action.event"
          block
          :data-testid="action.testid"
          :type="action.primary ? 'primary' : 'default'"
          @click="runAction(action.event)"
        >
          {{ action.label }}
        </Button>
      </div>
    </Drawer>
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

/* E24-T04 手机操作抽屉：按钮纵向铺开，触控可点区域 ≥ 40px由 tokens globalCss 承担 */
.action-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-bottom: 8px;
}
</style>
