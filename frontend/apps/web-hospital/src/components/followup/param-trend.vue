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
import {
  Button,
  Card,
  Empty,
  Pagination,
  Radio,
  RadioGroup,
  Spin,
  Table,
} from 'ant-design-vue';

import { fetchPatientImplants } from '#/api/core/implant';
import { fetchPatientParamTrend } from '#/api/core/patient';
import { useBreakpoint } from '#/reference/use-breakpoint';
import { pageSlice } from '#/views/followup/patient/detail-responsive-logic';

import {
  paramTrendChartOption,
  trendCardLines,
  trendSeparators,
} from './param-trend-logic';

defineOptions({ name: 'ParamTrend' });

/**
 * E21-T13 / C-FU-012：患者档案「参数趋势」页签（pages.md §4.1：折线图 + 下方
 * 历次参数表；可切换心房 / 心室、阈值 / 感知 / 阻抗 / 比例、电池）。标红点 =
 * 与上次对比超出阈值（C-FU-010，接口 abnormal 位）；器械更换 / 升级处画分隔线
 * （E21-T02 植入记录推导，电极修正不画——沿用电极的曲线连续）。
 *
 * E24-T10 / pages.md §4.7「手机：图占满宽，表格改卡片（随 C 在 M5 做）」：
 * 手机形态折线图保留（容器通栏），历次参数表转卡片（随访日期为标题、参数逐行、
 * 标红值错误色、缺测 —），卡片分页与表格每页 20 同口径（detail-responsive-logic
 * 的 pageSlice，切类别 / 腔室 / 电池参数回第 1 页）。
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

// E24-T10 / §4.7 手机：历次参数表转卡片（图保留通栏），分页与表格每页 20 同口径
const { isMobile } = useBreakpoint();
const cardPage = ref(1);
const cardRows = computed(() => pageSlice(tableRows.value, cardPage.value));
watch([kind, chamber, batteryParam], () => {
  cardPage.value = 1;
});

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
        <!-- E24-T10 / §4.7 手机：历次参数表改卡片（随访日期为标题、参数逐行、
             标红值错误色、缺测 —），分页与表格每页 20 同口径 -->
        <div v-if="isMobile" class="cards" data-testid="trend-card-list">
          <Card
            v-for="row in cardRows"
            :key="row.recordId"
            :bordered="false"
            class="trend-card"
            data-testid="trend-card"
            size="small"
          >
            <template #title>{{ row.date }}</template>
            <div
              v-for="line in trendCardLines(row, tableSeries)"
              :key="line.label"
              class="card-line"
            >
              <span class="card-label">{{ line.label }}</span>
              <!-- 缺测显示 —；标红值带错误色（C-FU-010 abnormal 位，与表格同口径） -->
              <span :class="{ abnormal: line.abnormal }">{{ line.value }}</span>
            </div>
          </Card>
          <div v-if="tableRows.length > 20" class="card-pagination">
            <span class="card-total">共 {{ tableRows.length }} 次</span>
            <Pagination
              :current="cardPage"
              data-testid="trend-card-pagination"
              :page-size="20"
              simple
              :total="tableRows.length"
              @change="(page: number) => (cardPage = page)"
            />
          </div>
        </div>
        <Table
          v-else
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

/* E24-T10 / §4.7 手机卡片（口径与患者详情沟通 / 操作日志卡片一致） */
.cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.card-line {
  display: flex;
  gap: 8px;
  font-size: 13px;
  line-height: 22px;
}

.card-label {
  min-width: 120px;
  color: rgb(0 0 0 / 45%);
}

.card-pagination {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  margin-top: 4px;
}

.card-total {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
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
