<script lang="ts" setup>
import type {
  BaselineCompletenessCardView,
  CompletenessMissingField,
  CompletenessTimeField,
  FollowupCompletenessCardView,
} from '@crdp/api';

import type { EchartsUIType } from '@vben/plugins/echarts';

import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import {
  BASELINE_TIME_FIELD_OPTIONS,
  FOLLOWUP_TIME_FIELD_OPTIONS,
  followupYearOptions,
  formatRatePct,
  missingFieldRoute,
} from '@crdp/api';
import { sizes } from '@crdp/tokens';
import {
  Card,
  Col,
  DatePicker,
  Empty,
  message,
  Row,
  Select,
  Space,
  Spin,
  Table,
} from 'ant-design-vue';

import {
  fetchBaselineCompletenessCard,
  fetchFollowupCompletenessCard,
} from '#/api/core/dashboard';

import {
  completenessBandChartOption,
  followupCompletenessYearChartOption,
} from './logic';

defineOptions({ name: 'DashboardCompletenessArea' });

/**
 * E19-T05 / A-DASH-010、A-DASH-011、C-DASH-009 · 首页数据完整度区（pages.md §2、
 * A 规格 §9「主要页面 4」）：基线资料完整度与随访资料完整度左右并列——
 * 左侧平均值与中位数 + 分布柱图（可切换为按分类的完整度表）+ 最常缺失字段前 10；
 * 右侧平均值与已统计记录数 + 按随访年度的表格加柱图（记录数 / 平均 / 中位数 / 分布）+
 * 最常缺失字段前 10。两块共用筛选：患者类型、时间范围（时间维度各卡自带：
 * 基线出院 / 登记、随访实际随访日期 / 出院；病区为首页页面级 wardId）。
 *
 * 点击缺失字段进入缺该字段的患者列表（验收「可钻取到缺失字段」）：路由 query 带
 * missingField / missingKind + 卡片筛选，患者列表组装 completenessDrill——后端与卡片
 * 同一计算，缺失人数 = 列表 total。卡片旁注明「完整度只统计已填写的资料，随访做没做
 * 见每年随访率」。统计时点 = 当前数据实时计算（Q39 / Q40，无 asOf）。
 */
const props = withDefaults(
  defineProps<{
    /** 是否显示基线资料完整度卡片（E19-T06 布局解析；两块仍并列时各占半行）。 */
    showBaseline?: boolean;
    /** 是否显示随访资料完整度卡片（E19-T06 布局解析）。 */
    showFollowup?: boolean;
    /** 页面级病区筛选（A-DASH-005）：全部卡片共用，未选 = 本人全部可见病区。 */
    wardId?: number;
  }>(),
  { showBaseline: true, showFollowup: true, wardId: undefined },
);

const router = useRouter();
const RangePicker = DatePicker.RangePicker;

/** 半行并排（A 规格 §9「相邻的两张半行卡片并排显示」）：只显示一块时占整行。 */
const halfSpan = computed(() =>
  props.showBaseline && props.showFollowup ? 12 : 24,
);

// ---------------------------------------------------------------- 共用筛选（两块共用：患者类型、时间范围）

const patientType = ref<string | undefined>();
const range = ref<[string, string] | null>(null);
const baselineTimeField = ref<CompletenessTimeField>('DISCHARGE');
const followupTimeField = ref<CompletenessTimeField>('VISIT');
const followupYear = ref<number | undefined>();

function sharedFilter() {
  return {
    from: range.value?.[0],
    patientType: patientType.value,
    to: range.value?.[1],
    wardId: props.wardId,
  };
}

// ---------------------------------------------------------------- 基线资料完整度（A-DASH-010）

const baselineLoading = ref(false);
const baseline = ref<BaselineCompletenessCardView | null>(null);
/** 分布柱图 ⇄ 分类明细（A 规格 §9「可切换为按 8 个分类的完整度表」）。 */
const baselineView = ref<'BANDS' | 'CATEGORIES'>('BANDS');

const baselineChartRef = ref<EchartsUIType>();
const { renderEcharts: renderBaseline } = useEcharts(baselineChartRef);

async function loadBaseline() {
  baselineLoading.value = true;
  try {
    const view = await fetchBaselineCompletenessCard({
      ...sharedFilter(),
      timeField: baselineTimeField.value,
    });
    baseline.value = view;
    if (view.patientCount > 0) {
      renderBaseline(completenessBandChartOption(view.distribution));
    }
  } catch {
    message.error('基线资料完整度加载失败，请稍后重试');
  } finally {
    baselineLoading.value = false;
  }
}

const baselineCategoryColumns = [
  { dataIndex: 'label', title: '分类' },
  {
    align: 'right' as const,
    dataIndex: 'averagePct',
    key: 'avg',
    title: '平均完整度',
  },
  { align: 'right' as const, dataIndex: 'patientCount', title: '患者数' },
];

const bandColumns = [
  { dataIndex: 'label', title: '完整度区间' },
  { align: 'right' as const, dataIndex: 'count', title: '人数' },
  { align: 'right' as const, key: 'pct', title: '占比' },
];

// ---------------------------------------------------------------- 随访资料完整度（A-DASH-011 / Q40）

const followupLoading = ref(false);
const followup = ref<FollowupCompletenessCardView | null>(null);

const followupChartRef = ref<EchartsUIType>();
const { renderEcharts: renderFollowup } = useEcharts(followupChartRef);

async function loadFollowup() {
  followupLoading.value = true;
  try {
    const view = await fetchFollowupCompletenessCard({
      ...sharedFilter(),
      timeField: followupTimeField.value,
      year: followupYear.value,
    });
    followup.value = view;
    if (view.years.length > 0) {
      renderFollowup(followupCompletenessYearChartOption(view.years));
    }
  } catch {
    message.error('随访资料完整度加载失败，请稍后重试');
  } finally {
    followupLoading.value = false;
  }
}

const yearColumns = [
  { dataIndex: 'label', title: '随访年度' },
  { align: 'right' as const, dataIndex: 'recordCount', title: '已完成记录数' },
  {
    align: 'right' as const,
    dataIndex: 'averagePct',
    key: 'avg',
    title: '平均完整度',
  },
  { align: 'right' as const, dataIndex: 'medianPct', title: '中位数' },
  { key: 'dist', title: '分布' },
];

/** 年度行分布：五段小字（100% ×n · 90–99% ×n ……只列非 0 段；全 0 显示「—」）。 */
function yearDistribution(row: {
  distribution: Array<{ count: number; label: string }>;
}): string {
  const parts = row.distribution.filter((band) => band.count > 0);
  if (parts.length === 0) return '—';
  return parts.map((band) => `${band.label} ×${band.count}`).join(' · ');
}

// ---------------------------------------------------------------- 钻取（验收「可钻取到缺失字段」）

/** 点缺失字段 → 缺该字段的患者列表（条件与卡片同一棵条件树，后端同一计算）。 */
function drillMissing(
  kind: 'BASELINE' | 'FOLLOWUP',
  field: CompletenessMissingField,
) {
  void router.push(
    missingFieldRoute(kind, field, {
      ...sharedFilter(),
      timeField:
        kind === 'BASELINE' ? baselineTimeField.value : followupTimeField.value,
      year: kind === 'FOLLOWUP' ? followupYear.value : undefined,
    }),
  );
}

// ---------------------------------------------------------------- 装配

function reload() {
  if (props.showBaseline) {
    void loadBaseline();
  }
  if (props.showFollowup) {
    void loadFollowup();
  }
}

watch(
  () => props.wardId,
  () => reload(),
);
watch(patientType, () => reload());
watch(range, () => reload());
watch(baselineTimeField, () => void loadBaseline());
watch(followupTimeField, () => void loadFollowup());
watch(followupYear, () => void loadFollowup());

onMounted(reload);

defineExpose({ reload });
</script>

<template>
  <div class="completeness-area">
    <!-- 两块共用筛选：患者类型、时间范围（病区为页面级；时间维度各卡自带） -->
    <div class="shared-filters">
      <Space wrap>
        <Select
          v-model:value="patientType"
          allow-clear
          :options="
            (baseline?.patientTypes ?? []).map((item) => ({
              label: item.label,
              value: item.code,
            }))
          "
          placeholder="患者类型"
          :style="{ width: 150 }"
          data-testid="completeness-patient-type"
        />
        <RangePicker
          v-model:value="range"
          data-testid="completeness-range"
          value-format="YYYY-MM-DD"
        />
      </Space>
    </div>

    <!-- 第五行数据完整度：左右两块并列（A 规格 §9「主要页面 4」） -->
    <Row :gutter="sizes.gutter">
      <Col v-if="showBaseline" :lg="halfSpan" :xs="24">
        <Card class="completeness-card" title="基线资料完整度">
          <template #extra>
            <Space wrap>
              <Select
                v-model:value="baselineTimeField"
                :options="BASELINE_TIME_FIELD_OPTIONS"
                :style="{ width: 130 }"
                data-testid="baseline-time-field"
                size="small"
              />
            </Space>
          </template>
          <Spin :spinning="baselineLoading">
            <Empty
              v-if="
                !baselineLoading &&
                (baseline === null || baseline.patientCount === 0)
              "
              description="暂无患者数据"
            />
            <template v-else-if="baseline !== null">
              <div class="summary" data-testid="baseline-summary">
                <span class="summary-item">
                  平均
                  <span class="summary-value">{{
                    formatRatePct(baseline.averagePct)
                  }}</span>
                </span>
                <span class="summary-item">
                  中位数
                  <span class="summary-value">{{
                    formatRatePct(baseline.medianPct)
                  }}</span>
                </span>
                <span class="summary-item">
                  患者
                  <span class="summary-value">{{ baseline.patientCount }}</span>
                </span>
              </div>
              <div class="view-toggle">
                <a
                  :class="{ active: baselineView === 'BANDS' }"
                  data-testid="baseline-view-bands"
                  @click="baselineView = 'BANDS'"
                >
                  分布
                </a>
                <a
                  :class="{ active: baselineView === 'CATEGORIES' }"
                  data-testid="baseline-view-categories"
                  @click="baselineView = 'CATEGORIES'"
                >
                  按分类
                </a>
              </div>
              <EchartsUI
                v-show="baselineView === 'BANDS'"
                ref="baselineChartRef"
                height="220px"
                data-testid="baseline-band-chart"
              />
              <Table
                v-if="baselineView === 'BANDS'"
                :columns="bandColumns"
                :data-source="baseline.distribution"
                :pagination="false"
                row-key="band"
                size="small"
                data-testid="baseline-band-table"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'pct'">
                    {{ formatRatePct(record.pct) }}
                  </template>
                </template>
              </Table>
              <Table
                v-if="baselineView === 'CATEGORIES'"
                :columns="baselineCategoryColumns"
                :data-source="baseline.categories"
                :pagination="false"
                row-key="categoryKey"
                size="small"
                data-testid="baseline-category-table"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'avg'">
                    <span class="pct-main">{{
                      formatRatePct(record.averagePct)
                    }}</span>
                  </template>
                </template>
              </Table>
              <div
                v-if="baseline.topMissing.length > 0"
                class="missing"
                data-testid="baseline-top-missing"
              >
                <div class="missing-title">最常缺失字段前 10</div>
                <div
                  v-for="field in baseline.topMissing"
                  :key="field.fieldKey"
                  class="missing-row"
                  role="button"
                  :data-testid="`baseline-missing-${field.fieldKey}`"
                  tabindex="0"
                  @click="drillMissing('BASELINE', field)"
                >
                  <span class="missing-label">{{ field.label }}</span>
                  <span class="missing-count">
                    缺 {{ field.missingCount }} 人 ·
                    {{ formatRatePct(field.missingRatePct) }}
                  </span>
                </div>
              </div>
              <div v-else class="missing-title">暂无缺失字段</div>
            </template>
          </Spin>
        </Card>
      </Col>
      <Col v-if="showFollowup" :lg="halfSpan" :xs="24">
        <Card class="completeness-card" title="随访资料完整度">
          <template #extra>
            <Space wrap>
              <Select
                v-model:value="followupTimeField"
                :options="FOLLOWUP_TIME_FIELD_OPTIONS"
                :style="{ width: 140 }"
                data-testid="followup-time-field"
                size="small"
              />
              <Select
                v-model:value="followupYear"
                allow-clear
                :options="followupYearOptions(followup?.yearOptions ?? [])"
                placeholder="随访年度"
                :style="{ width: 110 }"
                data-testid="followup-year"
                size="small"
              />
            </Space>
          </template>
          <Spin :spinning="followupLoading">
            <Empty
              v-if="
                !followupLoading &&
                (followup === null || followup.recordCount === 0)
              "
              description="暂无已完成的随访记录"
            />
            <template v-else-if="followup !== null">
              <div class="summary" data-testid="followup-summary">
                <span class="summary-item">
                  平均
                  <span class="summary-value">{{
                    formatRatePct(followup.averagePct)
                  }}</span>
                </span>
                <span class="summary-item">
                  已统计记录
                  <span class="summary-value">{{ followup.recordCount }}</span>
                </span>
                <span class="summary-item">
                  患者
                  <span class="summary-value">{{ followup.patientCount }}</span>
                </span>
              </div>
              <Table
                :columns="yearColumns"
                :data-source="followup.years"
                :pagination="false"
                row-key="year"
                size="small"
                data-testid="followup-year-table"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'avg'">
                    <span class="pct-main">{{
                      formatRatePct(record.averagePct)
                    }}</span>
                  </template>
                  <template v-else-if="column.key === 'medianPct'">
                    {{ formatRatePct(record.medianPct) }}
                  </template>
                  <template v-else-if="column.key === 'dist'">
                    <span class="dist-text">{{
                      yearDistribution(record)
                    }}</span>
                  </template>
                </template>
              </Table>
              <EchartsUI
                v-show="followup.years.length > 0"
                ref="followupChartRef"
                class="year-chart"
                height="220px"
                data-testid="followup-year-chart"
              />
              <div
                v-if="followup.topMissing.length > 0"
                class="missing"
                data-testid="followup-top-missing"
              >
                <div class="missing-title">最常缺失字段前 10</div>
                <div
                  v-for="field in followup.topMissing"
                  :key="field.fieldKey"
                  class="missing-row"
                  role="button"
                  :data-testid="`followup-missing-${field.fieldKey}`"
                  tabindex="0"
                  @click="drillMissing('FOLLOWUP', field)"
                >
                  <span class="missing-label">{{ field.label }}</span>
                  <span class="missing-count">
                    缺 {{ field.missingCount }} 条 ·
                    {{ formatRatePct(field.missingRatePct) }}
                  </span>
                </div>
              </div>
              <div v-else class="missing-title">暂无缺失字段</div>
            </template>
          </Spin>
        </Card>
      </Col>
    </Row>

    <!-- 注（A 规格 §9「主要页面 4」）：与随访率的口径区分 -->
    <div class="note">完整度只统计已填写的资料，随访做没做见每年随访率</div>
  </div>
</template>

<style scoped>
.completeness-card {
  margin-bottom: 0;
  border-radius: 8px;
}

.summary {
  display: flex;
  flex-wrap: wrap;
  gap: 24px;
  margin-bottom: 12px;
}

.summary-item {
  font-size: 13px;
  color: rgb(0 0 0 / 45%);
}

.summary-value {
  margin-left: 4px;
  font-size: 20px;
  font-weight: 600;
  color: rgb(0 0 0 / 88%);
}

.view-toggle {
  display: flex;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 13px;
}

.view-toggle a {
  color: rgb(0 0 0 / 45%);
}

.view-toggle a.active {
  font-weight: 600;
}

.pct-main {
  font-weight: 600;
}

.dist-text {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.year-chart {
  margin-top: 12px;
}

.missing {
  margin-top: 12px;
}

.missing-title {
  margin-bottom: 4px;
  font-size: 13px;
  font-weight: 600;
}

.missing-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
  font-size: 13px;
  cursor: pointer;
}

.missing-row:hover {
  background: rgb(0 0 0 / 3%);
}

.missing-label {
  color: rgb(0 0 0 / 88%);
}

.missing-count {
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}

.shared-filters {
  margin-top: 8px;
  margin-bottom: 4px;
}

.note {
  margin-top: 4px;
  font-size: 12px;
  color: rgb(0 0 0 / 45%);
}
</style>
