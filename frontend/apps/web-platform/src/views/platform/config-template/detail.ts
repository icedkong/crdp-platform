/**
 * 配置模板详情页的纯逻辑（E12-T04 / A-FLD-014）：头信息行、配置表节展示与包文件名。
 */
import type {
  ConfigPackageView,
  ConfigTableMeta,
  ConfigTemplateView,
} from '@crdp/api';

import { configModeText } from '@crdp/api';

/** 头信息行（Descriptions 展示顺序）。 */
export interface HeaderRow {
  key: string;
  label: string;
  value: string;
}

export function headerRows(template: ConfigTemplateView): HeaderRow[] {
  return [
    { key: 'name', label: '模板名称', value: template.name },
    {
      key: 'tenant',
      label: '源租户',
      value: `${template.tenantName}（${template.tenantCode}）`,
    },
    { key: 'version', label: '版本', value: `v${template.version}` },
    {
      key: 'formatVersion',
      label: '包结构版本',
      value: String(template.formatVersion),
    },
    {
      key: 'tableCount',
      label: '配置表数',
      value: String(template.tableCount),
    },
    { key: 'rowCount', label: '入包行数', value: String(template.rowCount) },
    {
      key: 'exportedAt',
      label: '导出时间',
      value: template.exportedAt.slice(0, 16).replace('T', ' '),
    },
    {
      key: 'packageHash',
      label: '包哈希（SHA-256）',
      value: template.packageHash,
    },
  ];
}

/** 配置表节行（详情表格）。 */
export interface TableMetaRow {
  /** 包内状态：正常 / 源模块未开通。 */
  presentText: string;
  /** 行数列：包内行数（已剔除 n 行）。 */
  rowsText: string;
  table: string;
  module: string;
  category: string;
  modeText: string;
}

export function tableMetaRow(section: ConfigTableMeta): TableMetaRow {
  const rowsText =
    section.excludedRowCount > 0
      ? `${section.rowCount}（已剔除 ${section.excludedRowCount} 行取值）`
      : String(section.rowCount);
  return {
    category: section.category,
    modeText: configModeText(section.mode),
    module: section.module,
    presentText: section.present ? '正常' : '源租户未开通该模块',
    rowsText,
    table: section.table,
  };
}

export function tableMetaRows(tables: ConfigTableMeta[]): TableMetaRow[] {
  return tables.map((section) => tableMetaRow(section));
}

/** 下载文件名：config-template-{源租户编码}-v{版本}.json。 */
export function packageFileName(
  template: Pick<ConfigTemplateView, 'tenantCode' | 'version'>,
): string {
  return `config-template-${template.tenantCode}-v${template.version}.json`;
}

/** 下载的包内容（缩进 2 格，便于人工核对）。 */
export function packageFileBody(pkg: ConfigPackageView): string {
  return `${JSON.stringify(pkg, null, 2)}\n`;
}
