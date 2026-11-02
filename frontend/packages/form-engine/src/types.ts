/**
 * E11-T07：FormRenderer 的对外类型（.vue `<script setup>` 内的声明不可再导出，集中放这里）。
 */

import type { FieldIssue } from '@crdp/rules';

/** validate() 返回值与 validate 事件负载（提交闸门口径）。 */
export interface RendererValidateResult {
  /** 硬错误（范围 / 必填 / HARD 规则 / 未填「其他」说明）：非空则不能提交（42200 同构）。 */
  hardErrors: FieldIssue[];
  missingRequired: FieldIssue[];
  ok: boolean;
  warnings: FieldIssue[];
}
