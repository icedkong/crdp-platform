<script lang="ts" setup>
import type {
  DashboardLayoutView,
  KpiCardView,
  KpiMetric,
  TrendCardView,
} from '@crdp/api';

import type { EchartsUIType } from '@vben/plugins/echarts';

import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { IconifyIcon } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import {
  DASHBOARD_DEFAULT_LAYOUT,
  kpiMetricRoute,
  layoutAreas,
  layoutCardVisible,
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
  fetchDashboardLayout,
  fetchDashboardWards,
  fetchKpiCard,
  fetchTrendCard,
} from '#/api/core/dashboard';
import StatCard from '#/components/followup/stat-card.vue';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { usePermission } from '#/reference/use-permission';

import AbnormalRecordsArea from './abnormal-records-area.vue';
import CardSettings from './card-settings.vue';
import CompletenessArea from './completeness-area.vue';
import DeviceDistArea from './device-dist-area.vue';
import EriListArea from './eri-list-area.vue';
import { defaultTrendRange, metricIcon, trendChartOption } from './logic';
import RateArea from './rate-area.vue';
import ScanProgressArea from './scan-progress-area.vue';
import TaskArea from './task-area.vue';

defineOptions({ name: 'HospitalDashboard' });

/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001 · E19-T03 / A-DASH-003、004、005、008、C-DASH-003、008 ·
 * E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004 · E19-T05 / A-DASH-010、A-DASH-011、C-DASH-009 ·
 * E19-T06 / A-DASH-013、A-DASH-014 · E21-T19 / C-DASH-002、005、006（医院 C 三卡：
 * 器械类型分布、接近 ERI 器械列表、异常标红记录）· 首页（pages.md §2）：卡片与顺序由
 * GET /dashboard/layout 下发（目录 ∩ 功能开关 ∩ 权限 × 角色布局 ∩ 用户隐藏——租户管理员
 * 按角色配置后即生效），布局接口失败时按目录默认布局兜底渲染（不白屏）。任务区
 * （看板 | 任务表切换）、统计区（每年随访率 + 节点随访率）、完整度区（基线 / 随访资料
 * 完整度半行并列）各按组内第一张可见卡片的位置渲染，组内单卡可见时不显示切换 / 只占整行。
 *
 * 卡片设置（A-DASH-014，P1）：右上角入口，用户在角色允许的卡片内隐藏 / 重新显示、
 * 恢复默认（不能加入角色未允许的卡片、不能调顺序）。病区筛选（A-DASH-005）：头部
 * 「病区 [全部▾]」全页共用——候选 = 本人全部可见病区，切换后各可见卡片联动重载。
 * 数字与列表钻取一致（钻取条件由后端随指标下发，前端只透传路由 query）。
 * 权限 followup:dashboard:view（按钮 234）；无权限只隐藏卡片不报错。
 */
const router = useRouter();
const { hasPermission } = usePermission();
const canViewCards = computed(() => hasPermission('followup:dashboard:view'));
const { isMobile } = useBreakpoint();
const responsive = false;
const title = '首页';

const RangePicker = DatePicker.RangePicker;

// ---------------------------------------------------------------- 卡片目录布局（A-DASH-013 / A-DASH-014）

const layout = ref<DashboardLayoutView>(DASHBOARD_DEFAULT_LAYOUT);
/** 首次布局加载完成前不下结论「无卡片」，避免默认布局闪烁成空态。 */
const layoutLoaded = ref(false);

async function loadLayout() {
  try {
    layout.value = await fetchDashboardLayout();
  } catch {
    layout.value = DASHBOARD_DEFAULT_LAYOUT; // 兜底：目录默认布局（页面不因布局接口故障白屏）
  } finally {
    layoutLoaded.value = true;
  }
}

/** 区块序列（同区卡片归并；顺序 = 布局响应顺序）。 */
const areas = computed(() => layoutAreas(layout.value));

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
  void loadLayout();
  void loadWardOptions();
  if (canViewCards.value) {
    void loadKpi();
    void loadTrend();
  }
}

onMounted(loadAll);

// 多标签页 keep-alive：从患者 / 任务列表回到首页时刷新数字与布局（配置可能在设置页已变）
onActivated(loadAll);

/** 卡片设置保存后：更新布局（新增 / 移除卡片区块，未受影响的卡片不重载）。 */
function handleLayoutSaved(view: DashboardLayoutView) {
  layout.value = view;
}
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
      <Space>
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
        <!-- 卡片设置（A-DASH-014，P1）：角色允许范围内隐藏 / 显示、恢复默认 -->
        <CardSettings :layout="layout" @saved="handleLayoutSaved" />
      </Space>
    </div>

    <template v-if="canViewCards">
      <!-- 卡片与顺序由布局下发（A-DASH-013）；同区卡片归并到组内第一张可见卡片的位置 -->
      <template v-for="area in areas" :key="area.kind">
        <!-- 指标卡（点击进入对应列表，pages.md §2） -->
        <Row v-if="area.kind === 'kpi'" :gutter="sizes.gutter" class="kpi-row">
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

        <!-- 趋势图（时间维度 / 粒度 / 分组 / 时间范围可切换） -->
        <Card
          v-else-if="area.kind === 'trend'"
          class="trend-card"
          title="趋势图"
        >
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

        <!-- 任务区（看板 / 任务表切换，A-DASH-003、008；组内单卡可见时不显示切换） -->
        <TaskArea
          v-else-if="area.kind === 'task'"
          :show-board="area.codes.includes('task_board')"
          :show-table="area.codes.includes('task_table')"
          :ward-id="wardId"
        />

        <!-- 统计区：每年随访率与节点随访率（A-DASH-006、A-DASH-012 / C-DASH-004；节点随访率
             由布局解析显隐——目录 default_visible=0〔医院 A 默认不显示〕+ node_follow_rate 开关） -->
        <RateArea
          v-else-if="area.kind === 'rate'"
          :show-node="layoutCardVisible(layout, 'node_rate')"
          :show-yearly="layoutCardVisible(layout, 'yearly_rate')"
          :ward-id="wardId"
        />

        <!-- 数据完整度：基线资料完整度与随访资料完整度左右并列（A-DASH-010 / A-DASH-011 /
             C-DASH-009；单卡可见时占整行） -->
        <CompletenessArea
          v-else-if="area.kind === 'completeness'"
          :show-baseline="layoutCardVisible(layout, 'baseline_completeness')"
          :show-followup="layoutCardVisible(layout, 'followup_completeness')"
          :ward-id="wardId"
        />

        <!-- 补录进度（E21-T15 / C-DASH-007，医院 C 默认顺序末位）：开关 scan_progress
             关闭时布局不含本卡（目录 ∩ 功能开关），组件渲染即代表开关开启 -->
        <ScanProgressArea
          v-else-if="area.kind === 'scan_progress'"
          :ward-id="wardId"
        />

        <!-- 器械类型分布（E21-T19 / C-DASH-002，医院 C）：开关 implant 关闭时布局不含本卡 -->
        <DeviceDistArea
          v-else-if="area.kind === 'device_dist'"
          :ward-id="wardId"
        />

        <!-- 接近 ERI 器械列表（E21-T19 / C-DASH-005，医院 C）：开关 eri_cycle 关闭时布局
             不含本卡；total 与指标卡「接近 ERI 器械数」同查询 -->
        <EriListArea v-else-if="area.kind === 'eri_list'" :ward-id="wardId" />

        <!-- 异常标红记录（E21-T19 / C-DASH-006，医院 C）：开关 implant 关闭时布局不含本卡；
             点击行进入患者详情随访记录页签定位该记录，只查看不处理 -->
        <AbnormalRecordsArea
          v-else-if="area.kind === 'abnormal_records'"
          :ward-id="wardId"
        />
      </template>

      <Row
        v-if="layoutLoaded && areas.length === 0 && !kpiLoading"
        :gutter="sizes.gutter"
        class="kpi-row"
      >
        <Col :span="24">
          <Empty
            description="首页卡片已全部隐藏 · 可在「卡片设置」中重新显示，或联系租户管理员调整角色布局"
          >
            <template #image>
              <IconifyIcon icon="ant-design:dashboard-outlined" />
            </template>
          </Empty>
        </Col>
      </Row>
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
