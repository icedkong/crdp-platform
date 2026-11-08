<script lang="ts" setup>
import type { NodeRateCardView, RateRow, YearlyRateCardView } from '@crdp/api';

import type { EchartsUIType } from '@vben/plugins/echarts';

import { onMounted, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import { cohortYearOptions, formatRatePct } from '@crdp/api';
import { colors } from '@crdp/tokens';
import {
  Card,
  Empty,
  message,
  Select,
  Space,
  Spin,
  Table,
} from 'ant-design-vue';

import { fetchNodeRateCard, fetchYearlyRateCard } from '#/api/core/dashboard';
import { useFeature } from '#/reference/use-feature';

import { rateChartOption } from './logic';

defineOptions({ name: 'DashboardRateArea' });

/**
 * E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004 · 首页统计区随访率卡片（pages.md §2）：
 * 每年随访率（按随访年度 N 一行，出院年份队列筛选）+ 节点随访率（先选随访计划，
 * 默认预置计划；行 = 节点 × 第 N 次，按计划内顺序）。口径 Q38：随访率（已随访 ÷ 应随访）
 * 为唯一主指标，已随访含窗口内完成、逾期完成、补录与窗口外日期的记录；窗口内完成率
 * 仅作参考（小字）。两卡共用同一事实表查询（同一筛选下年度节点两处数字一致）。
 *
 * 病区隔离在后端（@DataScope 出行）；页面级病区筛选 wardId 由首页头部下发
 * （A-DASH-005），变更时联动重载。节点随访率卡片受 node_follow_rate 开关控制
 * （components.md §1.3 useFeature：医院 A 默认不显示、B / C 显示；关闭后端 40301）。
 * 数据时点 = 节点随访事实表最近一次每日刷新（asOf 回显；当日完成 / 补录次日刷新前
 * 以最近刷新为准，OQ-106①）。点击数字下钻患者列表需要患者检索支持按随访年度 / 节点
 * 筛选，随 E19-T08 口径核对后接入（OQ-109①）。
 */
const props = defineProps<{
  /** 页面级病区筛选（A-DASH-005）：全部卡片共用，未选 = 本人全部可见病区。 */
  wardId?: number;
}>();

const { isEnabled } = useFeature();
const nodeRateVisible = () => isEnabled('node_follow_rate');

// ---------------------------------------------------------------- 表格列（两卡同列，A 规格 §9 统计区）

const rowColumns = [
  { dataIndex: 'label', title: '年度', width: 120 },
  {
    align: 'right' as const,
    dataIndex: 'dueCount',
    title: '应随访',
    width: 100,
  },
  {
    align: 'right' as const,
    dataIndex: 'followedCount',
    title: '已随访',
    width: 100,
  },
  { align: 'right' as const, key: 'followRate', title: '随访率', width: 190 },
  { align: 'right' as const, key: 'lostRate', title: '失访率', width: 100 },
  {
    align: 'right' as const,
    dataIndex: 'deathCount',
    title: '死亡人数',
    width: 100,
  },
];

const nodeColumns = rowColumns.map((column) =>
  column.dataIndex === 'label' ? { ...column, title: '节点' } : column,
);

/** 随访率主列：醒目比率 + 小字「其中窗口内完成 / 窗口内完成率」（参考口径）。 */
function rateCell(row: RateRow) {
  return {
    main: formatRatePct(row.followRatePct),
    ref: `其中窗口内完成 ${row.inWindowCount} / ${formatRatePct(row.inWindowRatePct)}`,
  };
}

// ---------------------------------------------------------------- 每年随访率（A-DASH-006）

const yearlyLoading = ref(false);
const yearly = ref<null | YearlyRateCardView>(null);
const cohortYear = ref<number | undefined>();

const yearlyChartRef = ref<EchartsUIType>();
const { renderEcharts: renderYearly } = useEcharts(yearlyChartRef);

async function loadYearly() {
  yearlyLoading.value = true;
  try {
    const view = await fetchYearlyRateCard({
      cohortYear: cohortYear.value,
      wardId: props.wardId,
    });
    yearly.value = view;
    if (view.rows.length > 0) {
      renderYearly(rateChartOption(view.rows));
    }
  } catch {
    message.error('每年随访率加载失败，请稍后重试');
  } finally {
    yearlyLoading.value = false;
  }
}

// ---------------------------------------------------------------- 节点随访率（A-DASH-012 / C-DASH-004）

const nodeLoading = ref(false);
const nodeRate = ref<NodeRateCardView | null>(null);
const planId = ref<number | undefined>();
const nodeCohortYear = ref<number | undefined>();

const nodeChartRef = ref<EchartsUIType>();
const { renderEcharts: renderNode } = useEcharts(nodeChartRef);

async function loadNodeRate() {
  nodeLoading.value = true;
  try {
    const view = await fetchNodeRateCard({
      cohortYear: nodeCohortYear.value,
      planId: planId.value,
      wardId: props.wardId,
    });
    nodeRate.value = view;
    // 缺省选中预置计划（后端回显生效的 planId；用户切换后以所选为准）
    if (planId.value === undefined && view.planId > 0) {
      planId.value = view.planId;
    }
    if (view.rows.length > 0) {
      renderNode(rateChartOption(view.rows));
    }
  } catch {
    message.error('节点随访率加载失败，请稍后重试');
  } finally {
    nodeLoading.value = false;
  }
}

// ---------------------------------------------------------------- 装配

function reload() {
  void loadYearly();
  if (nodeRateVisible()) {
    void loadNodeRate();
  }
}

watch(
  () => props.wardId,
  () => reload(),
);
watch(cohortYear, () => void loadYearly());
watch(nodeCohortYear, () => void loadNodeRate());

onMounted(reload);

defineExpose({ reload });
</script>

<template>
  <div class="rate-area">
    <!-- 第四行统计区：每年随访率（A-DASH-006，表格 + 柱线图；随访率为醒目主列） -->
    <Card class="rate-card" title="每年随访率">
      <template #extra>
        <Space wrap>
          <Select
            v-model:value="cohortYear"
            allow-clear
            :options="cohortYearOptions(yearly?.cohortYears ?? [])"
            placeholder="出院年份队列"
            :style="{ width: 150 }"
            data-testid="yearly-cohort"
          />
        </Space>
      </template>
      <Spin :spinning="yearlyLoading">
        <Empty
          v-if="!yearlyLoading && (yearly === null || yearly.rows.length === 0)"
          description="暂无随访数据"
        />
        <template v-else-if="yearly !== null">
          <Table
            :columns="rowColumns"
            :data-source="yearly.rows"
            :pagination="false"
            row-key="label"
            size="small"
            data-testid="yearly-rate-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'followRate'">
                <span :style="{ color: colors.primary }" class="rate-main">{{
                  rateCell(record).main
                }}</span>
                <span class="rate-ref">{{ rateCell(record).ref }}</span>
              </template>
              <template v-else-if="column.key === 'lostRate'">
                {{ formatRatePct(record.lostRatePct) }}
              </template>
            </template>
          </Table>
          <EchartsUI
            v-show="yearly.rows.length > 0"
            ref="yearlyChartRef"
            class="rate-chart"
            height="300px"
            data-testid="yearly-rate-chart"
          />
          <div v-if="yearly.asOf" class="as-of">
            数据截至 {{ yearly.asOf }}（每日刷新）
          </div>
        </template>
      </Spin>
    </Card>

    <!-- 节点随访率（A-DASH-012 / C-DASH-004；node_follow_rate 开关控制显隐，医院 A 默认不显示） -->
    <Card v-if="nodeRateVisible()" class="rate-card" title="节点随访率">
      <template #extra>
        <Space wrap>
          <Select
            v-model:value="planId"
            :options="
              (nodeRate?.plans ?? []).map((item) => ({
                label: item.name,
                value: item.id,
              }))
            "
            :style="{ width: 220 }"
            placeholder="随访计划"
            data-testid="node-rate-plan"
            @change="loadNodeRate"
          />
          <Select
            v-model:value="nodeCohortYear"
            allow-clear
            :options="cohortYearOptions(nodeRate?.cohortYears ?? [])"
            placeholder="出院年份队列"
            :style="{ width: 150 }"
            data-testid="node-rate-cohort"
          />
        </Space>
      </template>
      <Spin :spinning="nodeLoading">
        <Empty
          v-if="
            !nodeLoading && (nodeRate === null || nodeRate.rows.length === 0)
          "
          description="暂无随访数据"
        />
        <template v-else-if="nodeRate !== null">
          <Table
            :columns="nodeColumns"
            :data-source="nodeRate.rows"
            :pagination="false"
            row-key="label"
            size="small"
            data-testid="node-rate-table"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'followRate'">
                <span :style="{ color: colors.primary }" class="rate-main">{{
                  rateCell(record).main
                }}</span>
                <span class="rate-ref">{{ rateCell(record).ref }}</span>
              </template>
              <template v-else-if="column.key === 'lostRate'">
                {{ formatRatePct(record.lostRatePct) }}
              </template>
            </template>
          </Table>
          <EchartsUI
            v-show="nodeRate.rows.length > 0"
            ref="nodeChartRef"
            class="rate-chart"
            height="300px"
            data-testid="node-rate-chart"
          />
          <div v-if="nodeRate.asOf" class="as-of">
            数据截至 {{ nodeRate.asOf }}（每日刷新）
          </div>
        </template>
      </Spin>
    </Card>
  </div>
</template>

<style scoped>
.rate-card {
  margin-bottom: 16px;
  border-radius: 8px;
}

.rate-main {
  margin-right: 8px;
  font-size: 16px;
  font-weight: 600;
}

.rate-ref {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.rate-chart {
  margin-top: 16px;
}

.as-of {
  margin-top: 8px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
  text-align: right;
}
</style>
