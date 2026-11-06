/**
 * E13-T02 / A-PAT-003、A-PAT-006：基线分节编辑整页（pages.md §4.3）的纯逻辑。
 *
 * - 分节定位：?cat=<key> 指定分类，缺省第一个分节（新标签页整页、按分类单独保存）；
 * - 切换分类：有未保存修改时先暂存草稿再切换（分节分别暂存互不冲突，tech-plan §7）；
 * - 保存结果文案：字段修改已留痕（A-PAT-005）+ 软校验警告提示（A-FLD-003）；
 * - 锁定只读：直接打开已锁定患者的编辑页时整页只读（按钮入口已置灰，此处兜底）。
 */
import type { BaselineSection } from '@crdp/api';

/** 目标分节：?cat 指定且存在则取之，否则取第一个（无分节返回 null，页面按 40400 处理）。 */
export function pickSection(
  sections: BaselineSection[],
  categoryKey: null | string | undefined,
): BaselineSection | null {
  if (sections.length === 0) return null;
  if (categoryKey) {
    const found = sections.find((section) => section.code === categoryKey);
    if (found) return found;
  }
  return sections[0] ?? null;
}

/** 保存成功文案（pages.md §4.3：字段修改写字段级历史；软校验警告不拦截）。 */
export function sectionSaveSuccessText(warningCount: number): string {
  const base = '分节已保存，字段修改已留痕（A-PAT-005）';
  return warningCount > 0
    ? `${base}；${warningCount} 项软校验提示请留意`
    : base;
}

/** 40901（锁定后保存 / 暂存）提示文案（A-PAT-006「锁定后只读」）。 */
export const BASELINE_LOCKED_SAVE_TEXT =
  '基线已锁定，不能保存：需有解锁权限并填写原因后先解锁（A-PAT-006）';

/** 40900（保存时他人已更新）提示文案：不自动合并，提示后刷新到最新版本。 */
export const BASELINE_CONFLICT_SAVE_TEXT =
  '该分节已被他人更新，已为你刷新到最新版本，请对照后重新保存';
