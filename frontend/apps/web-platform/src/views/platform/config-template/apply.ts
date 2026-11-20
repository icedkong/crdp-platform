/** 配置导入确认与目标绑定（OQR-02 / A-FLD-014）。实际差异由后端只读预检生成。 */
import type { ConfigApplyPreview, TenantListItem } from '@crdp/api';

export interface ApplyFormModel {
  confirmed: boolean;
  tenantId: null | number;
}

export function toApplyFormModel(): ApplyFormModel {
  return { confirmed: false, tenantId: null };
}

/** 切换目标或模板后旧响应不能再提交。 */
export interface LoadedConfigPreview extends ConfigApplyPreview {
  templateId: number;
  tenantId: number;
}

export function canSubmitConfigPreview(
  model: ApplyFormModel,
  preview: LoadedConfigPreview | null,
  templateId: number | undefined,
): boolean {
  return (
    model.confirmed &&
    !!preview?.applicable &&
    !!preview.planHash &&
    preview.tenantId === model.tenantId &&
    preview.templateId === templateId
  );
}

/** 真实差异、版本保护与重新确认提示（OQ-91 / 94）。 */
export const IMPORT_RULE_NOTES: string[] = [
  '预览读取目标租户，逐行显示新增、更新、保留、丢弃和冲突，不展示配置值。',
  '已有计划版本及节点保留，变化导入为未发布的新版本，须另行审核发布。',
  '已有表单版本快照不同会阻止应用，请在源端发布新版本后重新导出。',
  '角色菜单与目标租户可分配范围取交集，超出范围的授权丢弃。',
  '用户账号、组织架构与科室范围不随模板复制；新行用户引用置空。',
  '术者、病区 / 中心只复制结构，取值不导入。',
  '应用成功后目标租户全部用户的权限版本递增。',
  '目标配置或权限范围发生变化时必须重新预览、重新确认。',
];

/** 目标租户选项：排除源租户（后端 40001）；注销中 / 已注销置灰（应用无意义）。 */
export interface TargetTenantOption {
  disabled: boolean;
  disabledReason: string;
  label: string;
  value: number;
}

export function targetTenantOptions(
  tenants: TenantListItem[],
  sourceTenantId: null | number,
): TargetTenantOption[] {
  return tenants
    .filter((tenant) => tenant.id !== sourceTenantId)
    .map((tenant) => {
      const cancelled =
        tenant.status === 'CANCELLED' || tenant.status === 'CANCELLING';
      return {
        disabled: cancelled,
        disabledReason: cancelled ? '该租户已注销或注销中' : '',
        label: `${tenant.name}（${tenant.code}）`,
        value: tenant.id,
      };
    });
}

/** 应用前校验（前端先行；后端仍按 40001 / 40901 / 40400 校验）。 */
export function validateApplyModel(
  model: ApplyFormModel,
  sourceTenantId: null | number,
): string[] {
  const errors: string[] = [];
  if (model.tenantId === null) {
    errors.push('请选择目标租户');
  } else if (model.tenantId === sourceTenantId) {
    errors.push('目标租户不能是模板的源租户');
  }
  if (!model.confirmed) {
    errors.push('请先阅读导入预览并勾选确认');
  }
  return errors;
}

/** 应用结果总计数文案（结果步骤顶部结论）。 */
export function applyResultSummaryText(result: {
  droppedRows: number;
  insertedRows: number;
  mergedRows: number;
  skippedRows: number;
}): string {
  return `已应用：新增 ${result.insertedRows} 行、覆盖 ${result.mergedRows} 行、跳过 ${result.skippedRows} 行、丢弃 ${result.droppedRows} 行。`;
}
