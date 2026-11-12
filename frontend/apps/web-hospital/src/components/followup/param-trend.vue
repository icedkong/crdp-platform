<script lang="ts" setup>
import type {
  ImplantRecordView,
  ParamTrendChamber,
  ParamTrendKind,
  ParamTrendView,
  SelectedTrendSeries,
} from '@crdp/api';

import type { EchartsUIType } from '@vben/plugins/echarts';

import { computed, onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import {
  TREND_BATTERY_PARAMS,
  TREND_CHAMBER_OPTIONS,
  TREND_KIND_OPTIONS,
  trendKindParamKeys,
  trendSeriesFor,
  trendTableRows,
} from '@crdp/api';
import { Button, Empty, Radio, RadioGroup, Spin, Table } from 'ant-design-vue';

import { fetchPatientImplants } from '#/api/core/implant';
import { fetchPatientParamTrend } from '#/api/core/patient';

import { paramTrendChartOption, trendSeparators } from './param-trend-logic';

defineOptions({ name: 'ParamTrend' });

/**
 * E21-T13 / C-FU-012：患者档案「参数趋势」页签（pages.md §4.1：折线图 + 下方
 * 历次参数表；可切换心房 / 心室、阈值 / 感知 / 阻抗 / 比例、电池）。标红点 =
 * 与上次对比超出阈值（C-FU-010，接口 abnormal 位）；器械更换 / 升级处画分隔线
 * （E21-T02 植入记录推导，电极修正不画——沿用电极的曲线连续）。
 *
 * 页签本身随功能开关 param_trend 显隐（detail-tabs-logic）；后端趋势接口随 C
 * 后续任务落地（patient.md §7 契约），未就绪时按加载失败处理、可重试。
 */
const props = defineProps<{ patientId: string }>();

const loading = ref(false);
const loadFailed = ref(false);
const view = ref<null | ParamTrendView>(null);
const implants = ref<ImplantRecordView[]>([]);

// 类别（阈值 / 感知 / 阻抗 / 起搏比例 / 电池）与腔室（全部 / 心房 / 心室）；
// 电池视图按参数选择（电池电压 / AT/AF 负荷单位不同，折线图单选其一）
const kind = ref<ParamTrendKind>('threshold');
const chamber = ref<ParamTrendChamber>('all');
const batteryParam = ref<string>('battery_voltage');

/** 折线参数键：类别 + 腔室（规格 §6 示意图：阈值同图心房 / 心室两条）；电池单选。 */
const chartParams = computed(() =>
  kind.value === 'battery'
    ? [batteryParam.value]
    : trendKindParamKeys(kind.value, chamber.value),
);

/** 表格参数键：电池视图同时展示电池电压与 AT/AF 负荷两列（列间无单位冲突）。 */
const tableParams = computed(() =>
  kind.value === 'battery'
    ? TREND_BATTERY_PARAMS.map((item) => item.param)
    : trendKindParamKeys(kind.value, chamber.value),
);

const chartSeries = computed<SelectedTrendSeries[]>(() =>
  view.value ? trendSeriesFor(view.value, chartParams.value) : [],
);
const tableSeries = computed<SelectedTrendSeries[]>(() =>
  view.value ? trendSeriesFor(view.value, tableParams.value) : [],
);

const hasData = computed(() =>
  tableSeries.value.some((series) => series.points.length > 0),
);

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

watch(
  [chartSeries, implants],
  () => {
    renderEcharts(
      paramTrendChartOption(chartSeries.value, trendSeparators(implants.value)),
    );
  },
  { immediate: true },
);

async function load() {
  loading.value = true;
  loadFailed.value = false;
  try {
    const [trend, implantList] = await Promise.all([
      fetchPatientParamTrend(props.patientId),
      fetchPatientImplants(props.patientId).catch(() => ({ items: [] })),
    ]);
    view.value = trend;
    implants.value = implantList.items;
  } catch {
    // 错误提示由统一拦截器弹出（traceId 一并展示）；这里保留失败态与重试入口
    view.value = null;
    implants.value = [];
    loadFailed.value = true;
  } finally {
    loading.value = false;
  }
}

onMounted(load);

/** 历次参数表：行 = 一次随访（按随访日期升序），列 = 当前视图的参数（含单位）。 */
const tableColumns = computed(() => [
  { dataIndex: 'date', title: '随访日期', width: 130 },
  ...tableSeries.value.map((series) => ({
    dataIndex: series.param,
    title: `${series.label}${series.unit ? `(${series.unit})` : ''}`,
  })),
]);

const tableRows = computed(() =>
  trendTableRows(tableSeries.value).map((row) => ({
    ...row,
    key: row.recordId,
  })),
);

const legendNote =
  '红色点 = 与上次对比超出阈值（C-FU-010）；竖虚线 = 器械更换 / 升级分隔（沿用电极的曲线连续，C-FU-012）。';
</script>

<template>
  <div class="param-trend" data-testid="param-trend">
    <div class="filters">
      <RadioGroup v-model:value="kind" data-testid="trend-kind" size="small">
        <Radio
          v-for="option in TREND_KIND_OPTIONS"
          :key="option.kind"
          :value="option.kind"
        >
          {{ option.label }}
        </Radio>
      </RadioGroup>
      <!-- 腔室切换（电池不分腔室，换为电池参数选择） -->
      <RadioGroup
        v-if="kind !== 'battery'"
        v-model:value="chamber"
        data-testid="trend-chamber"
        size="small"
      >
        <Radio
          v-for="option in TREND_CHAMBER_OPTIONS"
          :key="option.chamber"
          :value="option.chamber"
        >
          {{ option.label }}
        </Radio>
      </RadioGroup>
      <RadioGroup
        v-else
        v-model:value="batteryParam"
        data-testid="trend-battery-param"
        size="small"
      >
        <Radio
          v-for="option in TREND_BATTERY_PARAMS"
          :key="option.param"
          :value="option.param"
        >
          {{ option.label }}
        </Radio>
      </RadioGroup>
    </div>

    <Spin :spinning="loading">
      <Empty
        v-if="!loading && loadFailed"
        description="参数趋势加载失败，请稍后重试"
      >
        <Button data-testid="trend-retry" type="primary" @click="load">
          重试
        </Button>
      </Empty>
      <Empty v-else-if="!loading && !hasData" description="暂无程控参数记录" />
      <div v-else-if="view" class="chart-block">
        <EchartsUI ref="chartRef" height="360px" data-testid="trend-chart" />
        <Table
          :columns="tableColumns"
          :data-source="tableRows"
          :pagination="{ pageSize: 20, showSizeChanger: false }"
          size="small"
          data-testid="trend-table"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex !== 'date'">
              <!-- 缺测显示 —；标红值带错误色（C-FU-010 abnormal 位） -->
              <span
                v-if="record.cells[column.dataIndex]"
                :class="{ abnormal: record.cells[column.dataIndex].abnormal }"
              >
                {{ record.cells[column.dataIndex].value }}
              </span>
              <span v-else class="missing">—</span>
            </template>
          </template>
          <template #emptyText> 暂无程控参数记录 </template>
        </Table>
        <p class="hint">
          {{ legendNote }}
        </p>
      </div>
      <!-- 首次加载中：Spin 包住占位（加载完成才有图与表） -->
      <div v-else class="loading-placeholder"></div>
    </Spin>
  </div>
</template>

<style scoped>
.param-trend {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chart-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 首次加载占位（保持 Spin 高度，避免闪烁） */
.loading-placeholder {
  min-height: 200px;
}

/* C-FU-010 标红值：与图表异常点同色（tokens colors.error） */
.abnormal {
  font-weight: 600;
  color: #ff4d4f;
}

.missing {
  color: #8c8c8c;
}

.hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8c8c8c;
}
</style>
