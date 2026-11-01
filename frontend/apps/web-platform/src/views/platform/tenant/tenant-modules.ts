/**
 * 租户详情 · 模块页签的纯逻辑（E05-T05 / PLT-MOD-004、005、006、008、009）：开通 / 关闭操作的
 * 可用性与文案、关闭影响面展示、配置表单 ⇄ 提交载荷、开通记录展示。页面只做展示与编排。
 */
import type {
  ModuleOpenLog,
  TenantModuleCloseImpact,
  TenantModuleConfig,
  TenantModuleItem,
} from '@crdp/api';

import { moduleLogActionText, tenantModuleStatusText } from '@crdp/api';

/** 模块行的开通状态列（StatusTag 的中文输入；null = 未开通）。 */
export function moduleStatusText(item: TenantModuleItem): string {
  return tenantModuleStatusText(item.status);
}

/** 依赖列：空显示「—」。 */
export function dependsOnText(item: TenantModuleItem): string {
  return item.dependsOn.length === 0 ? '—' : item.dependsOn.join('、');
}

/** 版本列：已开通租户的当前迁移版本优先，未开通显示目录版本；空显示「—」。 */
export function versionText(item: TenantModuleItem): string {
  const feature = item.featureVersion || '—';
  const migration =
    (item.status !== null && item.currentMigrationVersion) ||
    item.migrationVersion ||
    '—';
  return `${feature} / ${migration}`;
}

/** 开通 / 关闭时间列：空显示「—」，精确到分钟。 */
export function timeText(value: null | string): string {
  if (!value) return '—';
  return value.slice(0, 16).replace('T', ' ');
}

/**
 * 「开通」按钮可用性（前端只挡明显不可用，最终校验在后端 40901）：
 * 基础模块必开、下架模块不能新开通、已开通 / 开通中无需再开通。
 */
export function canOpen(item: TenantModuleItem): boolean {
  if (item.baseModule) return false;
  if (item.shelfStatus === 'OFF') return false;
  return (
    item.status === null ||
    item.status === 'CLOSED' ||
    item.status === 'OPEN_FAILED'
  );
}

/** 「关闭」按钮可用性：基础模块必选不可关闭，只有已开能关。 */
export function canClose(item: TenantModuleItem): boolean {
  if (item.baseModule) return false;
  return item.status === 'OPEN';
}

/** 「配置」按钮可用性：只有已开通的模块有可改的租户级配置（PLT-MOD-008）。 */
export function canConfigure(item: TenantModuleItem): boolean {
  return item.status === 'OPEN';
}

/** 关闭原因校验（PLT-MOD-005：二次确认并填写原因）：必填 ≤200 字。 */
export function validateCloseReason(reason: string): string | undefined {
  if (!reason.trim()) {
    return '必须填写关闭原因';
  }
  if (reason.trim().length > 200) {
    return '关闭原因过长（最多 200 个字符）';
  }
  return undefined;
}

/** 开通原因可留空；填了 ≤200 字（留痕）。 */
export function validateOpenReason(reason: string): string | undefined {
  if (reason.trim().length > 200) {
    return '开通原因过长（最多 200 个字符）';
  }
  return undefined;
}

/** 关闭影响面（PLT-MOD-005）→ 二次确认文案行。 */
export function impactLines(impact: TenantModuleCloseImpact): string[] {
  const lines = [
    `在线用户：${impact.onlineUserCount} 个`,
    `进行中随访任务：${
      impact.pendingTaskCount === null
        ? '暂无数据（任务表未建）'
        : `${impact.pendingTaskCount} 个`
    }`,
  ];
  if (impact.dependentModules.length > 0) {
    lines.push(
      `仍被已开通模块依赖：${impact.dependentModules.join('、')}（须先关闭它们）`,
    );
  } else {
    lines.push(
      '关闭后菜单与接口立即收回（模块接口返回 40301），数据与角色授权保留',
    );
  }
  return lines;
}

/** 配置表单模型：输入态（空串 = 用默认值）。 */
export interface ConfigFormModel {
  /** 配置项编码 → 输入值。 */
  values: Record<string, string>;
}

/** 接口返回 → 配置表单模型：未覆盖的项预填默认值（提交时只送有覆盖的项）。 */
export function toConfigFormModel(config: TenantModuleConfig): ConfigFormModel {
  const values: Record<string, string> = {};
  for (const item of config.items) {
    values[item.code] = item.value ?? item.defaultValue;
  }
  return { values };
}

/**
 * 配置表单校验：每项必填 ≤255。返回「配置项编码 → 错误信息」（空对象 = 通过）。
 */
export function validateConfigForm(
  model: ConfigFormModel,
  config: TenantModuleConfig,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const item of config.items) {
    const value = (model.values[item.code] ?? '').trim();
    if (!value) {
      errors[item.code] = `${item.name}必填`;
    } else if (value.length > 255) {
      errors[item.code] = `${item.name}过长（最多 255 个字符）`;
    }
  }
  return errors;
}

/** 配置表单 → 提交载荷（PLT-MOD-008：只送覆盖值）。 */
export function toConfigUpdateRequest(model: ConfigFormModel): {
  values: Record<string, string>;
} {
  const values: Record<string, string> = {};
  for (const [code, value] of Object.entries(model.values)) {
    values[code] = value.trim();
  }
  return { values };
}

/** 开通记录行（PLT-MOD-009）：动作、租户、原因与时间。 */
export function logLines(log: ModuleOpenLog): string[] {
  const lines = [
    `${moduleLogActionText(log.action)} · ${log.tenantName}（${log.tenantCode}）`,
  ];
  if (log.reason) {
    lines.push(`原因：${log.reason}`);
  }
  if (log.detail) {
    lines.push(log.detail);
  }
  lines.push(timeText(log.createdAt));
  return lines;
}

/** 开通记录动作 → 徽标颜色（StatusTag 用；关闭为中性，开通成功色，升级处理色）。 */
export function logActionTone(
  action: string,
): 'default' | 'processing' | 'success' {
  if (action === 'OPEN') return 'success';
  if (action === 'UPGRADE') return 'processing';
  return 'default';
}
