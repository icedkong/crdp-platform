/**
 * E21-T08 / C-FU-005（Q05）：「下次随访时间」字段（模板字段编码 next_visit_date，医院 C
 * 程控表单 f333，V90 字段字典随包）的录入辅助。A / B 表单无该编码字段，渲染器按字段编码识别、
 * 天然不触发（不硬编码稳定键——键位随模板版本可变，编码才是库名）。
 *
 * - 占位「不填则按计划默认（ERI 期优先）」照抄 pages.md §5.1；
 * - 间隔快捷 +1 / +2 / +3 / +6 / +12 个月（C 规格附录 A 随访 #33）：基准 = 本次随访日期（缺省今天），
 *   月末口径与后端 LocalDate.plusMonths 一致（@crdp/rules addDateUnit 共享实现）；
 * - 窗口预览「= 下次计划日期；窗口 X ~ Y」的日期由页面按当前节点窗口宽度围绕医生日期算好传入
 *   （渲染器不感知计划配置）；提交校验（不能早于或等于本次随访日期 / 不能早于今天）在记录页
 *   logic.ts，与后端登记口径（早于今天忽略）互补。
 */
import { addDateUnit } from '@crdp/rules';

/** 模板字段编码（识别用；C 程控表单 f333 的 code，docs/api/implant.md §3 键契约）。 */
export const NEXT_VISIT_FIELD_CODE = 'next_visit_date';

/** 占位文案（pages.md §5.1 照抄）。 */
export const NEXT_VISIT_PLACEHOLDER = '不填则按计划默认（ERI 期优先）';

/** 间隔快捷的月数（C 规格附录 A 随访 #33：+1 / +2 / +3 / +6 / +12 个月）。 */
export const NEXT_VISIT_QUICK_MONTHS = [1, 2, 3, 6, 12] as const;

/** 「下次随访时间」的录入辅助入参（页面算好传入；null = 模板无该字段或不适用）。 */
export interface NextVisitAffordance {
  /** 间隔快捷的基准日期（本次随访日期 ?? 今天，页面解析）。 */
  baseDate: null | string;
  /** 无窗口预览时的补充提示（补录「（补录：只保存）」等页面文案）。 */
  note?: null | string;
  /** 窗口预览（当前节点窗口宽度围绕医生日期展开；无任务上下文为 null）。 */
  windowPreview?: null | { end: string; start: string };
}

/** 间隔快捷选项（值 = 基准 + N 个月；基准缺省回落今天，today 可注入测试）。 */
export function nextVisitQuickOptions(
  affordance: NextVisitAffordance,
  today: () => string = () => new Date().toISOString().slice(0, 10),
): { label: string; value: string }[] {
  const base = affordance.baseDate ?? today();
  return NEXT_VISIT_QUICK_MONTHS.map((months) => ({
    label: `+${months} 个月`,
    value: addDateUnit(base, months, 'MONTH'),
  }));
}

/**
 * 字段旁提示（12px 次要色口径）：已填且带窗口预览 → 「= 下次计划日期；窗口 X ~ Y」（pages.md §5.1）；
 * 与页面补充提示（补录「只保存」等）用「；」连接；全空返回 ''（不渲染）。
 */
export function nextVisitHintOf(
  affordance: NextVisitAffordance,
  hasValue: boolean,
): string {
  const parts: string[] = [];
  if (hasValue && affordance.windowPreview) {
    parts.push(
      `= 下次计划日期；窗口 ${affordance.windowPreview.start} ~ ${affordance.windowPreview.end}`,
    );
  }
  if (affordance.note) {
    parts.push(affordance.note);
  }
  return parts.join('；');
}
