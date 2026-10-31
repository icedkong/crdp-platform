/**
 * 开通进度页的纯逻辑（E02-T08 / PLT-TEN-008）：按钮可用性与步骤展示映射。
 */

/** 仅"开通未完成"（PROVISIONING，含失败中止）的租户可放弃回收；TRIAL 起走正式注销（PLT-TEN-020）。 */
export function canRecycle(status: string): boolean {
  return status === 'PROVISIONING';
}

/** 存在失败步骤或租户仍停在开通中即可重试（编排可重入，从失败步骤续）。 */
export function canRetry(status: string, failedSteps: number): boolean {
  return status === 'PROVISIONING' || failedSteps > 0;
}
