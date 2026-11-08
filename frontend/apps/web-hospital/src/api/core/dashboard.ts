/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001：首页卡片接口
 * （docs/api/stats.md §2，后端 DashboardCardController；页面 pages.md §2 首页）。
 *
 * 权限 followup:dashboard:view（按钮 plt_menu 234，平台库 V59 / 种子 V129）；
 * 数据范围由后端 @DataScope 出行（范围内无患者返回全 0 / 空序列，不报错）。
 * 病区筛选参数 wardId 随 E19-T03 病区筛选器接入，接口先行支持。
 */
import type { KpiCardView, TrendCardParams, TrendCardView } from '@crdp/api';

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
