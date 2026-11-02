import type { NotifyPrefView } from '@crdp/api';

/**
 * E09-T07 / A-NTF-007、C-NTF-007 · 个人中心「通知偏好」页签逻辑（纯函数）。
 * 用户可关闭非逾期类事件的短信（只影响短信，站内信不提供个人关闭）；
 * 逾期类（TASK_OVERDUE）能否关闭由租户设置决定（后端 40001 兜底）。
 */

/**
 * 偏好视图 → 表单初值：勾选 = 已关闭该事件短信。不可关闭的项（如本院不允许时的
 * 逾期类）与已不在可选项里的历史残留不带入表单——后端会拒绝前者、后者对应的
 * 事件已无短信渠道，保存时自然清理。
 */
export function toPrefFormModel(view: NotifyPrefView): string[] {
  return view.smsOff.filter((eventType) => {
    const option = view.options.find((item) => item.eventType === eventType);
    return option === undefined || option.closeable;
  });
}

/** 可提交校验：不可关闭的事件被勾选（租户不允许关闭逾期类）给出提示文案。 */
export function validatePrefModel(
  checked: string[],
  view: NotifyPrefView,
): string | undefined {
  const blocked = checked.filter((eventType) => {
    const option = view.options.find((item) => item.eventType === eventType);
    return option !== undefined && !option.closeable;
  });
  return blocked.length > 0
    ? '本院不允许个人关闭逾期类短信（已逾期是必须触达的提醒）'
    : undefined;
}

/** 勾选清单里是否含不可关闭项（提交按钮禁用用）。 */
export function hasBlockedChoice(
  checked: string[],
  view: NotifyPrefView,
): boolean {
  return validatePrefModel(checked, view) !== undefined;
}

/** 选项提示文案：不可关闭时说明原因。 */
export function optionHint(
  option: { closeable: boolean; eventType: string },
  overdueSmsOffAllowed: boolean,
): string {
  if (option.eventType === 'TASK_OVERDUE' && !overdueSmsOffAllowed) {
    return '已逾期是必须触达的提醒，本院暂不允许个人关闭';
  }
  return '关闭后该事件不再发送短信，站内信不受影响';
}
