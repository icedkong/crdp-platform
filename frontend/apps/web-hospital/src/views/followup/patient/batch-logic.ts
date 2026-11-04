/**
 * E13-T07 / A-PAT-016 批量入组页（/followup/patient/batch-enroll，pages.md §8「批量入组」：
 * 左筛选（含高级检索）/ 中结果勾选 / 右选计划与责任医生；确认后显示成功与跳过明细）的页面逻辑。
 *
 * 患者候选复用检索接口（E13-T04 / T06，普通筛选 + 高级检索条件树，后端自动追加病区条件）；
 * 预检与执行走批量入组接口（确认前显示将加入人数与将跳过人数，A-PAT-016；结果逐条可查，
 * tasks.csv 验收）。从患者列表勾选跳转时经 sessionStorage 传递选中 id（最多 500 个，
 * 不适合 URL；id 为内部编号非敏感，会话级保存即可）。
 */
import type { BatchResult, PatientSearchRow, PlanView } from '@crdp/api';

/** 入组只允许已发布（含派生「随访中」）计划——后端同口径，前端下拉同步过滤。 */
export function enrollablePlanOptions(
  plans: PlanView[],
): Array<{ label: string; value: string }> {
  return plans
    .filter((plan) => plan.status === 'PUBLISHED' || plan.status === 'RUNNING')
    .map((plan) => ({
      label: `${plan.name}（v${plan.version}）`,
      value: plan.id,
    }));
}

/** 患者列表勾选 → 批量入组页的会话级传递键。 */
export const BATCH_ENROLL_SELECTION_KEY = 'crdp:batch-enroll:selection';

/** 暂存选中患者 id（跳转批量入组页；去重、上限 500 与后端一致）。 */
export function stashBatchSelection(ids: Array<number | string>): void {
  const unique = [...new Set(ids.map(String))];
  window.sessionStorage.setItem(
    BATCH_ENROLL_SELECTION_KEY,
    JSON.stringify(unique.slice(0, 500)),
  );
}

/** 取出并清除暂存的选中 id（进入页面调用一次；无暂存返回空）。 */
export function takeStashedSelection(): string[] {
  const raw = window.sessionStorage.getItem(BATCH_ENROLL_SELECTION_KEY);
  window.sessionStorage.removeItem(BATCH_ENROLL_SELECTION_KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

/** 批量入组提交前校验（返回错误文案；null = 可提交）。 */
export function validateBatchEnrollForm(
  planId: null | string,
  selectedCount: number,
): null | string {
  if (selectedCount === 0) return '请先在结果列表勾选患者';
  if (!planId) return '请选择目标随访计划';
  if (selectedCount > 500) return '单次批量最多 500 名患者';
  return null;
}

/** 预检摘要文案（A-PAT-016「确认前显示将加入人数与将跳过人数」）。 */
export function previewSummaryText(result: BatchResult): string {
  return `将加入 ${result.succeeded} 名、将跳过 ${result.skipped} 名`;
}

/** 列表行 → 勾选键（Table rowKey 与 selectedRowKeys 同源）。 */
export function rowKeysOf(rows: PatientSearchRow[]): string[] {
  return rows.map((row) => row.id);
}

/** 当前页勾选变化：全量选中集合并 / 去除（跨页保留，pages.md §3 批量口径）。 */
export function mergeSelection(
  selected: string[],
  pageRows: PatientSearchRow[],
  incoming: string[],
): string[] {
  const pageKeys = new Set(pageRows.map((row) => row.id));
  const merged = new Set(selected.filter((id) => !pageKeys.has(id)));
  for (const id of incoming) {
    merged.add(id);
  }
  return [...merged];
}
