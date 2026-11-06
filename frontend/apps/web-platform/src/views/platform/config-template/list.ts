/**
 * 配置模板列表页的纯逻辑（E12-T04 / A-FLD-014）：列展示、导出表单与校验。
 */
import type { ConfigTemplateView } from '@crdp/api';

/** 模板名称上限（后端 V49 name ≤128 字）。 */
export const TEMPLATE_NAME_MAX = 128;

/** 导出表单模型。 */
export interface ExportFormModel {
  /** 模板名称：可空，缺省「{租户名} 配置模板」（后端补默认值）。 */
  name: string;
  /** 源租户。 */
  tenantId: null | number;
}

export function toExportFormModel(): ExportFormModel {
  return { name: '', tenantId: null };
}

/** 导出前校验（前端先行；后端仍按 40001 / 40400 / 40901 校验）。 */
export function validateExportModel(model: ExportFormModel): string[] {
  const errors: string[] = [];
  if (model.tenantId === null) {
    errors.push('请选择源租户');
  }
  if (model.name.trim().length > TEMPLATE_NAME_MAX) {
    errors.push(`模板名称不能超过 ${TEMPLATE_NAME_MAX} 字`);
  }
  return errors;
}

/** 导出请求体（名称去空格，空串不上送、由后端补默认值）。 */
export function toExportRequest(model: ExportFormModel): {
  name?: string;
  tenantId: number;
} {
  const name = model.name.trim();
  const request: { name?: string; tenantId: number } = {
    tenantId: model.tenantId as number,
  };
  if (name) request.name = name;
  return request;
}

/** 源租户列：名称（编码）。 */
export function sourceTenantText(
  template: Pick<ConfigTemplateView, 'tenantCode' | 'tenantName'>,
): string {
  return `${template.tenantName}（${template.tenantCode}）`;
}

/** 版本列：v2。 */
export function versionText(template: Pick<ConfigTemplateView, 'version'>) {
  return `v${template.version}`;
}

/** 导出时间列：ISO-8601 精确到分钟。 */
export function exportedAtText(
  template: Pick<ConfigTemplateView, 'exportedAt'>,
): string {
  if (!template.exportedAt) return '—';
  return template.exportedAt.slice(0, 16).replace('T', ' ');
}

/** 包哈希列：截断显示（完整值在详情页）。 */
export function hashText(hash: string): string {
  if (!hash) return '—';
  return hash.length <= 12 ? hash : `${hash.slice(0, 12)}…`;
}

/** 序号列：当前页内从 1 连续编号。 */
export function rowNumber(
  index: number,
  pageNo: number,
  pageSize: number,
): number {
  return (pageNo - 1) * pageSize + index + 1;
}

/** 源租户下拉选项（导出对话框）。 */
export function tenantOptions(
  tenants: Array<{
    code: string;
    id: number;
    name: string;
    status: string;
  }>,
): Array<{ label: string; value: number }> {
  return tenants.map((tenant) => ({
    label: `${tenant.name}（${tenant.code}）`,
    value: tenant.id,
  }));
}
