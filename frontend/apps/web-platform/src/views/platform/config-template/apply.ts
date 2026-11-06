/**
 * 应用到租户（导入）的纯逻辑（E12-T04 / A-FLD-014，验收「导入前预览差异」）：
 * 导入前按模板包内容与导入规则（docs/api/platform.md §7.1）推演每张配置表会发生什么，
 * 供确认前预览；确切的新增 / 合并 / 跳过 / 丢弃计数以应用结果（POST /{id}/apply）回显为准。
 * 行级「包值 vs 目标租户现值」对照需要后端 dry-run 接口（E12-T02 未交付），口径见 OQ-94。
 */
import type { ConfigTableMeta, TenantListItem } from '@crdp/api';

import { configModeText } from '@crdp/api';

/** 单表导入动作（预览推演）。 */
export type ImportAction = 'IMPORT' | 'SKIP_ABSENT' | 'SKIP_STRUCTURE';

/** 单表导入预览行。 */
export interface TableImportPreview {
  action: ImportAction;
  /** 预计处理说明（差异预览列）。 */
  actionText: string;
  category: string;
  mode: string;
  modeText: string;
  module: string;
  packageRows: number;
  table: string;
  /** import = 会写入目标租户；skip = 不写入。 */
  tone: 'import' | 'skip';
}

/** 预览汇总（对话框顶部结论）。 */
export interface ImportPreviewSummary {
  /** 将写入的表数与行数合计。 */
  importRows: number;
  importTables: number;
  /** 不写入的表数（只复制结构 + 空节）。 */
  skipTables: number;
}

/** 应用表单模型。 */
export interface ApplyFormModel {
  /** 已阅读预览并确认应用（目标租户相同配置将被源值覆盖）。 */
  confirmed: boolean;
  tenantId: null | number;
}

export function toApplyFormModel(): ApplyFormModel {
  return { confirmed: false, tenantId: null };
}

/** 单表导入动作推演（platform.md §7.1 导入口径）。 */
export function importActionOf(
  section: Pick<ConfigTableMeta, 'mode' | 'present'>,
): ImportAction {
  if (section.mode === 'STRUCTURE_ONLY') return 'SKIP_STRUCTURE';
  if (!section.present) return 'SKIP_ABSENT';
  return 'IMPORT';
}

const ACTION_TEXT: Record<ImportAction, (rows: number) => string> = {
  IMPORT: (rows) =>
    `将导入 ${rows} 行：与目标自然键相同的行被包值覆盖（合并），其余新增`,
  SKIP_STRUCTURE: () => '只复制结构，不导入取值（表结构已随租户库迁移存在）',
  SKIP_ABSENT: () => '包内为空节（源租户未开通该模块），跳过',
};

/** 单表预览行。 */
export function tablePreview(section: ConfigTableMeta): TableImportPreview {
  const action = importActionOf(section);
  return {
    action,
    actionText: ACTION_TEXT[action](section.rowCount),
    category: section.category,
    mode: section.mode,
    modeText: configModeText(section.mode),
    module: section.module,
    packageRows: section.rowCount,
    table: section.table,
    tone: action === 'IMPORT' ? 'import' : 'skip',
  };
}

/** 全部配置表的预览行（按包内顺序）。 */
export function tablePreviews(tables: ConfigTableMeta[]): TableImportPreview[] {
  return tables.map((section) => tablePreview(section));
}

/** 预览汇总。 */
export function previewSummary(
  tables: Array<Pick<ConfigTableMeta, 'mode' | 'present' | 'rowCount'>>,
): ImportPreviewSummary {
  let importTables = 0;
  let importRows = 0;
  for (const section of tables) {
    if (importActionOf(section) === 'IMPORT') {
      importTables += 1;
      importRows += section.rowCount;
    }
  }
  return { importRows, importTables, skipTables: tables.length - importTables };
}

/** 预览结论文案（对话框顶部）：全部为空时也给出可读结论。 */
export function previewSummaryText(summary: ImportPreviewSummary): string {
  if (summary.importTables === 0) {
    return '本包没有会写入目标租户的配置行（全部为只复制结构或空节）。';
  }
  return `将向目标租户写入 ${summary.importTables} 张配置表、共 ${summary.importRows} 行；其余 ${summary.skipTables} 张表不写入（只复制结构或空节）。`;
}

/**
 * 导入口径提示（platform.md §7.1 / OQ-91，应用前必读）：
 * 预览推演只到表级行数，合并 / 丢弃的确切计数以应用结果为准。
 */
export const IMPORT_RULE_NOTES: string[] = [
  '目标租户与包内自然键相同的配置行将被源值覆盖（源值胜，重复应用幂等）。',
  '随访计划节点按计划版本整组替换（先删后插），不会叠加重复节点。',
  '角色菜单与新租户可分配范围取交集，超出范围的授权丢弃。',
  '用户引用列（创建人 / 更新人 / 发布人）置空；用户账号与组织架构、科室范围不随模板复制。',
  '术者、病区 / 中心只复制结构，取值不导入。',
  '应用成功后目标租户全部用户的权限版本递增（角色授权可能变化）。',
  '表级预览不读目标租户库，确切的新增 / 合并 / 跳过 / 丢弃计数以应用结果为准。',
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
