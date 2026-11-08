/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001；E19-T03 / A-DASH-003、004、005、008、C-DASH-003、008；
 * E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004：
 * 首页卡片接口（docs/api/stats.md §2，后端 DashboardCardController；页面 pages.md §2 首页）。
 *
 * 权限 followup:dashboard:view（按钮 plt_menu 234，平台库 V59 / 种子 V129）；
 * 数据范围由后端 @DataScope 出行（范围内无患者返回全 0 / 空序列，不报错）。
 * 病区筛选（A-DASH-005）：wardId 为页面级参数全部卡片共用，候选 GET /wards 按登录快照取可见病区。
 * 随访率卡片（Q38 口径）数据来自节点随访事实表每日刷新，响应回显 asOf；逾期完成与补录计入已随访。
 */
import type {
  DashboardWardsView,
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

/** 病区筛选候选（A-DASH-005 / C-DASH-008）：默认本人全部可见病区。 */
export function fetchDashboardWards() {
  return requestClient.get<DashboardWardsView>(
    apiPath('/followup/dashboard/wards'),
  );
}
