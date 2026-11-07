/**
 * E17-T01 / A-FU-027（Q41）、C-FU-011（随 E21）：对比规则配置接口
 * （docs/api/followup.md §4，权限 followup:abnormal:config，页面 /system/abnormal-rule）。
 * 功能开关 compare_highlight 关闭时后端 40301（前端按通用错误处理隐藏入口，AGENTS.md 规则 7）。
 */
import type { CompareRule, CompareRuleUpdate } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 规则清单（formCode 缺省返回全部表单）。 */
export function fetchCompareRules(formCode?: string) {
  return requestClient.get<CompareRule[]>(
    apiPath('/followup/compare-rules'),
    formCode ? { params: { formCode } } : undefined,
  );
}

/** 修改单条规则：开关 + 调阈值（params 键集按规则类型校验；后端写 COMPARE_RULE_UPDATE 审计）。 */
export function updateCompareRule(id: number, payload: CompareRuleUpdate) {
  return requestClient.put<CompareRule>(
    apiPath(`/followup/compare-rules/${id}`),
    payload,
  );
}
