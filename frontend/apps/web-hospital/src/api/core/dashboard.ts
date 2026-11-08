/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001；E19-T03 / A-DASH-003、A-DASH-004、A-DASH-005、A-DASH-008、C-DASH-003、008；
 * E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004；E19-T05 / A-DASH-010、A-DASH-011、C-DASH-009：
 * 首页卡片接口（docs/api/stats.md §2，后端 DashboardCardController；页面 pages.md §2 首页）。
 *
 * 权限 followup:dashboard:view（按钮 plt_menu 234，平台库 V59 / 种子 V129）；
 * 数据范围由后端 @DataScope 出行（范围内无患者返回全 0 / 空序列，不报错）。
 * 病区筛选（A-DASH-005）：wardId 为页面级参数全部卡片共用，候选 GET /wards 按登录快照取可见病区。
 * 随访率卡片（Q38 口径）数据来自节点随访事实表每日刷新，响应回显 asOf；逾期完成与补录计入已随访。
 * 完整度两卡（Q39 / Q40 口径）按当前数据实时计算（无 asOf）；最常缺失字段钻取患者列表
 * （患者检索 completenessDrill 与卡片同一计算）。
 */
import type {
  BaselineCompletenessCardView,
  CompletenessParams,
  DashboardWardsView,
  FollowupCompletenessCardView,
  KpiCardView,
  NodeRateCardView,
  NodeRateParams,
  TaskBoardParams,
  TaskBoardView,
  TaskTableParams,
  TaskTableView,
  TrendCardParams,
  TrendCardView,
  YearlyRateCardView,
  YearlyRateParams,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 指标卡（kpi）：A / C 指标集按 implant 开关，每项内联钻取目标与筛选参数。 */
export function fetchKpiCard(wardId?: number) {
  return requestClient.get<KpiCardView>(
    apiPath('/followup/dashboard/cards/kpi'),
    {
      params: { wardId },
    },
  );
}

/** 趋势图（trend）：时间维度 / 粒度 / 分组 / 时间范围可切换，默认最近 24 个月。 */
export function fetchTrendCard(params: TrendCardParams) {
  return requestClient.get<TrendCardView>(
    apiPath('/followup/dashboard/cards/trend'),
    {
      params,
    },
  );
}

/** 任务看板（task_board）：四列各前 20 张卡片；scope 我的 / 本病区 / 全部 + 负责人筛选（A-DASH-004）。 */
export function fetchTaskBoardCard(params: TaskBoardParams) {
  return requestClient.get<TaskBoardView>(
    apiPath('/followup/dashboard/cards/task_board'),
    { params },
  );
}

/** 任务表（task_table）：今日 / 超时 / 我负责页签（页签显示数量）+ 当前页签前 10 行。 */
export function fetchTaskTableCard(params: TaskTableParams) {
  return requestClient.get<TaskTableView>(
    apiPath('/followup/dashboard/cards/task_table'),
    { params },
  );
}

/** 每年随访率（yearly_rate，A-DASH-006）：按随访年度 N 一行；cohortYear 出院年份队列筛选。 */
export function fetchYearlyRateCard(params: YearlyRateParams) {
  return requestClient.get<YearlyRateCardView>(
    apiPath('/followup/dashboard/cards/yearly_rate'),
    { params },
  );
}

/**
 * 节点随访率（node_rate，A-DASH-012 / C-DASH-004）：行 = 节点 × 第 N 次；planId 缺省预置计划。
 * 受 node_follow_rate 开关控制（关闭后端 40301）——调用方先按 GET /auth/features 判定再请求。
 */
export function fetchNodeRateCard(params: NodeRateParams) {
  return requestClient.get<NodeRateCardView>(
    apiPath('/followup/dashboard/cards/node_rate'),
    { params },
  );
}

/**
 * 基线资料完整度（baseline_completeness，A-DASH-010）：平均值 / 中位数、五段分布、8 分类明细、
 * 最常缺失字段前 10；筛选 patientType 患者类型、from+to 时间范围（timeField 出院 DISCHARGE
 * 默认 / 登记 REGISTERED）。按当前数据实时计算。
 */
export function fetchBaselineCompletenessCard(params: CompletenessParams) {
  return requestClient.get<BaselineCompletenessCardView>(
    apiPath('/followup/dashboard/cards/baseline_completeness'),
    { params },
  );
}

/**
 * 随访资料完整度（followup_completeness，A-DASH-011 / Q40）：平均值与已统计记录数、按随访年度
 * 表格、最常缺失字段前 10；筛选 patientType、from+to（timeField 实际随访日期 VISIT 默认 /
 * 出院 DISCHARGE）、year 随访年度。
 */
export function fetchFollowupCompletenessCard(params: CompletenessParams) {
  return requestClient.get<FollowupCompletenessCardView>(
    apiPath('/followup/dashboard/cards/followup_completeness'),
    { params },
  );
}

/** 病区筛选候选（A-DASH-005 / C-DASH-008）：默认本人全部可见病区。 */
export function fetchDashboardWards() {
  return requestClient.get<DashboardWardsView>(
    apiPath('/followup/dashboard/wards'),
  );
}
