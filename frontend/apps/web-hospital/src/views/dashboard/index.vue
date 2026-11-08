<script lang="ts" setup>
import type { KpiCardView, KpiMetric, TrendCardView } from '@crdp/api';

import type { EchartsUIType } from '@vben/plugins/echarts';

import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { IconifyIcon } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import {
  kpiMetricRoute,
  TREND_GRANULARITY_OPTIONS,
  TREND_GROUP_BY_OPTIONS,
  TREND_TIME_DIMENSION_OPTIONS,
  visibleKpiMetrics,
} from '@crdp/api';
import { sizes } from '@crdp/tokens';
import {
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  message,
  Result,
  Row,
  Select,
  Space,
  Spin,
} from 'ant-design-vue';

import {
  fetchDashboardWards,
  fetchKpiCard,
  fetchTrendCard,
} from '#/api/core/dashboard';
import StatCard from '#/components/followup/stat-card.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import CompletenessArea from './completeness-area.vue';
import { defaultTrendRange, metricIcon, trendChartOption } from './logic';
import RateArea from './rate-area.vue';
import TaskArea from './task-area.vue';

defineOptions({ name: 'HospitalDashboard' });

/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001 · E19-T03 / A-DASH-003、004、005、008、C-DASH-003、008 ·
 * E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004 · E19-T05 / A-DASH-010、A-DASH-011、C-DASH-009 ·
 * 首页（pages.md §2）：第一行指标卡（A 七项 / C 七项按租户 implant 开关）、第二行趋势图
 * （时间维度 / 粒度 / 分组 / 时间范围可切换，默认手术日期 · 月 · 不分组 · 最近 24 个月）、
 * 第三行任务区（看板 / 任务表切换，task-area 组件）、第四行统计区随访率卡片
 * （每年随访率 + 节点随访率〔node_follow_rate 开关，医院 A 默认不显示〕，rate-area 组件）、
 * 第五行数据完整度区（基线资料完整度与随访资料完整度左右并列，completeness-area 组件——
 * 最常缺失字段可钻取到缺该字段的患者列表）。
 *
 * 病区筛选（A-DASH-005）：头部「病区 [全部▾]」全页共用——候选 = 本人全部可见病区
 * （GET /dashboard/wards 按登录快照），未选 = 全部可见病区；切换后指标卡 / 趋势图 /
 * 任务区 / 随访率卡片 / 完整度卡片联动重载（行级隔离由后端 @DataScope 出行，选到范围外病区结果恒为空）。
 * 数字与列表钻取一致：钻取目标与筛选参数由后端随指标下发（同一条件树），前端只透传
 * 路由 query；范围内无患者时后端返回全 0（不报错）。卡片设置（A-DASH-013/014）随后续任务接入。
 * 权限 followup:dashboard:view（按钮 234）；无权限只隐藏卡片不报错。
 */
const router = useRouter();
const { hasPermission } = usePermission();
const canViewCards = computed(() => hasPermission('followup:dashboard:view'));
const { isMobile } = useBreakpoint();
const responsive = false;
const title = '首页';

const RangePicker = DatePicker.RangePicker;

// ---------------------------------------------------------------- 病区筛选（A-DASH-005 / C-DASH-008）

const wardId = ref<number | undefined>();
const wardOptions = ref<Array<{ label: string; value: number }>>([]);

async function loadWardOptions() {
  try {
    const view = await fetchDashboardWards();
    wardOptions.value = view.wards.map((item) => ({
      label: item.name,
      value: Number(item.id),
    }));
  } catch {
    // 候选加载失败不阻断首页（不选病区 = 全部可见病区）
  }
}

/** 病区切换：全页联动重载（A-DASH-005「全页按所选病区统计」）。 */
function handleWardChange() {
  if (canViewCards.value) {
    void loadKpi();
    void loadTrend();
  }
}

// ---------------------------------------------------------------- 指标卡（A-DASH-001 / C-DASH-001）

const kpiLoading = ref(false);
const kpi = ref<KpiCardView | null>(null);

const metrics = computed(() =>
  kpi.value ? visibleKpiMetrics(kpi.value.metrics) : [],
);

async function loadKpi() {
  kpiLoading.value = true;
  try {
    kpi.value = await fetchKpiCard(wardId.value);
  } catch {
    message.error('首页指标加载失败，请稍后重试');
  } finally {
    kpiLoading.value = false;
  }
}

/** 点指标卡钻取（条件由后端下发：患者列表 status、任务列表 tab/status/dueFrom/dueTo）。 */
function handleDrill(metric: KpiMetric) {
  const target = kpiMetricRoute(metric);
  if (target) {
    void router.push(target);
  }
}

// ---------------------------------------------------------------- 趋势图（A-DASH-002）

const trendFilters = reactive({
  groupBy: 'NONE' as (typeof TREND_GROUP_BY_OPTIONS)[number]['value'],
  granularity: 'MONTH' as (typeof TREND_GRANULARITY_OPTIONS)[number]['value'],
  range: null as [string, string] | null,
  timeDimension:
    'SURGERY' as (typeof TREND_TIME_DIMENSION_OPTIONS)[number]['value'],
});
const trendLoading = ref(false);
const trend = ref<null | TrendCardView>(null);

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

async function loadTrend() {
  trendLoading.value = true;
  try {
    const fallback = defaultTrendRange();
    trend.value = await fetchTrendCard({
      from: trendFilters.range?.[0] ?? fallback.from,
      granularity: trendFilters.granularity,
      groupBy: trendFilters.groupBy,
      timeDimension: trendFilters.timeDimension,
      to: trendFilters.range?.[1] ?? fallback.to,
      wardId: wardId.value,
    });
    renderEcharts(trendChartOption(trend.value));
  } catch {
    message.error('趋势图加载失败，请稍后重试');
  } finally {
    trendLoading.value = false;
  }
}

// ---------------------------------------------------------------- 装配

function loadAll() {
  // 首页不在 5 个响应式页面内：手机显示 MobileBlocker（OQ-24）
  if (isMobile.value && !responsive) return;
  void loadWardOptions();
  if (canViewCards.value) {
    void loadKpi();
    void loadTrend();
  }
}

onMounted(loadAll);

// 多标签页 keep-alive：从患者 / 任务列表回到首页时刷新数字（钻取后可能已改动数据）
onActivated(loadAll);
</script>

<template>
  <Result
    v-if="isMobile && !responsive"
    status="info"
    title="请在电脑或平板上操作"
  >
    <template #extra>
      <Button type="primary" @click="router.push('/dashboard')">
        返回首页
      </Button>
    </template>
  </Result>
  <div v-else class="page">
    <div class="title-row">
      <h1 class="crdp-page-title">{{ title }}</h1>
      <!-- 病区筛选（A-DASH-005 / C-DASH-008）：全页共用，未选 = 本人全部可见病区 -->
      <Select
        v-model:value="wardId"
        allow-clear
        :options="wardOptions"
        placeholder="全部病区"
        :style="{ width: 180 }"
        data-testid="dashboard-ward"
        @change="handleWardChange"
      />
    </div>

    <template v-if="canViewCards">
      <!-- 第一行：指标卡（点击进入对应列表，pages.md §2） -->
      <Row :gutter="sizes.gutter" class="kpi-row">
        <Col
          v-for="metric in metrics"
          :key="metric.code"
          :lg="6"
          :md="8"
          :sm="12"
          :xs="24"
        >
          <StatCard
            :data-testid="`kpi-${metric.code}`"
            :icon="metricIcon(metric.code)"
            :loading="kpiLoading"
            :metric-code="metric.code"
            :title="metric.title"
            :value="metric.value"
            @drill="handleDrill(metric)"
          />
        </Col>
      </Row>
      <Empty
        v-if="!kpiLoading && metrics.length === 0"
        description="暂无随访数据 · 去新建计划"
      />

      <!-- 第二行：趋势图（时间维度 / 粒度 / 分组 / 时间范围可切换） -->
      <Card class="trend-card" title="趋势图">
        <template #extra>
          <Space wrap>
            <Select
              v-model:value="trendFilters.timeDimension"
              :options="TREND_TIME_DIMENSION_OPTIONS"
              :style="{ width: 120 }"
              data-testid="trend-dimension"
              @change="loadTrend"
            />
            <Select
              v-model:value="trendFilters.granularity"
              :options="TREND_GRANULARITY_OPTIONS"
              :style="{ width: 96 }"
              data-testid="trend-granularity"
              @change="loadTrend"
            />
            <Select
              v-model:value="trendFilters.groupBy"
              :options="TREND_GROUP_BY_OPTIONS"
              :style="{ width: 120 }"
              data-testid="trend-group"
              @change="loadTrend"
            />
            <RangePicker
              v-model:value="trendFilters.range"
              data-testid="trend-range"
              value-format="YYYY-MM-DD"
              @change="loadTrend"
            />
          </Space>
        </template>
        <Spin :spinning="trendLoading">
          <Empty
            v-if="
              !trendLoading && (trend === null || trend.buckets.length === 0)
            "
            description="暂无患者数据"
          />
          <EchartsUI
            v-show="trend !== null && trend.buckets.length > 0"
            ref="chartRef"
            height="360px"
          />
        </Spin>
      </Card>

      <!-- 第三行：任务区（看板 / 任务表切换，A-DASH-003、008；病区筛选联动） -->
      <TaskArea :ward-id="wardId" />

      <!-- 第四行统计区：每年随访率与节点随访率（A-DASH-006、A-DASH-012 / C-DASH-004；
           节点随访率受 node_follow_rate 开关控制，病区筛选联动） -->
      <RateArea :ward-id="wardId" />

      <!-- 第五行数据完整度：基线资料完整度与随访资料完整度左右并列
           （A-DASH-010 / A-DASH-011 / C-DASH-009；最常缺失字段可钻取患者列表） -->
      <CompletenessArea :ward-id="wardId" />
    </template>

    <Empty
      v-else
      description="暂无首页卡片权限 · 联系租户管理员在角色中授予「首页统计卡片」"
    >
      <template #image>
        <IconifyIcon icon="ant-design:dashboard-outlined" />
      </template>
    </Empty>
  </div>
</template>

<style scoped>
.page {
  padding: 16px;
}

.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.kpi-row {
  margin-bottom: 16px;
}

.kpi-row :deep(.ant-col) {
  margin-bottom: 16px;
}

.trend-card {
  margin-bottom: 16px;
  border-radius: 8px;
}
</style>
