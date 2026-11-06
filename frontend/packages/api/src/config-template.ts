/**
 * 配置模板（E12 / A-FLD-014，docs/api/platform.md §7.1）：接口类型与展示口径。
 * 后端 E12-T01（导出 / 列表 / 详情 / 包 / 注册表）、E12-T02（应用到租户）、E12-T03（敏感拦截）已交付；
 * 管理页与导入预览的页面逻辑（E12-T04）在 web-platform 的 views/platform/config-template。
 */

/** 复制方式（后端 ConfigCopyMode）：不复制 = 不登记进注册表。 */
export type ConfigCopyMode = 'COPY' | 'STRUCTURE_ONLY';

/** 复制方式文案（详情、注册表、应用预览共用）。 */
export const CONFIG_COPY_MODE_TEXT: Record<ConfigCopyMode, string> = {
  COPY: '复制',
  STRUCTURE_ONLY: '只复制结构',
};

/** 未知取值原样返回（后端新增档位时前端不空白）。 */
export function configModeText(mode: null | string | undefined): string {
  if (mode === null || mode === undefined || mode === '') return '—';
  return CONFIG_COPY_MODE_TEXT[mode as ConfigCopyMode] ?? mode;
}

/** 模板头信息（导出结果，列表行同构）。 */
export interface ConfigTemplateView {
  exportedAt: string;
  formatVersion: number;
  id: number;
  name: string;
  /** 包 JSON 的 SHA-256（经 DigestService）。 */
  packageHash: string;
  /** 入包配置行合计。 */
  rowCount: number;
  tableCount: number;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
  /** 同租户递增版本（v1 起）。 */
  version: number;
}

/** 配置表节元信息（详情视图，不带行数据）。 */
export interface ConfigTableMeta {
  /** A-FLD-014 复制清单类别名。 */
  category: string;
  columns: string[];
  /** 被行排除谓词剔除的行数（如术者取值）。 */
  excludedRowCount: number;
  mode: string;
  module: string;
  /** 源租户库是否存在该表（模块未开通 → false，行数为 0）。 */
  present: boolean;
  /** 入包行数（STRUCTURE_ONLY 恒 0）。 */
  rowCount: number;
  sourceRowCount: number;
  table: string;
}

/** 模板详情：头信息 + 每张配置表的元信息。 */
export interface ConfigTemplateDetail {
  tables: ConfigTableMeta[];
  template: ConfigTemplateView;
}

/** 配置表注册表条目（「会导出什么」的预览口径，随包代码）。 */
export interface ConfigRegistryEntry {
  category: string;
  description: string;
  /** 行排除谓词（列名 → 值；全部命中即排除该行，空 = 不排除）。 */
  excludeWhen: Record<string, string>;
  mode: ConfigCopyMode;
  module: string;
  table: string;
}

/** 配置包（GET /{id}/package：gzip 解压后的 JSON 结构，带行数据；下载写审计）。 */
export interface ConfigPackageView {
  exportedAt: string;
  formatVersion: number;
  tables: Array<ConfigTableMeta & { rows: Array<Record<string, unknown>> }>;
  tenantCode: string;
  tenantName: string;
}

/** 单表应用结果（E12-T02）。 */
export interface ConfigApplyTableResult {
  dropped: number;
  imported: boolean;
  inserted: number;
  merged: number;
  mode: string;
  /** 跳过 / 丢弃原因（如目标库缺表、STRUCTURE_ONLY 不落行）。 */
  note: null | string;
  packageRows: number;
  skipped: number;
  table: string;
}

/** 应用结果：模板与目标租户头信息 + 总计数 + 分表明细。 */
export interface ConfigApplyResult {
  appliedAt: string;
  droppedRows: number;
  insertedRows: number;
  mergedRows: number;
  skippedRows: number;
  sourceTenantCode: string;
  sourceTenantId: number;
  tables: ConfigApplyTableResult[];
  templateHash: string;
  templateId: number;
  templateName: string;
  templateVersion: number;
  tenantCode: string;
  tenantId: number;
  tenantName: string;
}
