/**
 * 模块目录页的纯逻辑（E05-T04 / PLT-MOD-001、002、003）：上架状态、版本与依赖展示、维护表单校验、
 * 同步结果文案。
 */
import type { ModuleCatalogItem, ModuleUpdateRequest } from '@crdp/api';

import { moduleShelfText, moduleSyncActionText } from '@crdp/api';

/** 上架状态列（PLT-MOD-001）：StatusTag 的中文输入；未知值原样返回。 */
export function shelfStatusText(status: string): string {
  return moduleShelfText(status);
}

/** 基础模块列：必选不可关闭（平台基础）。 */
export function baseModuleText(baseModule: boolean): string {
  return baseModule ? '是' : '否';
}

/** 依赖列：空显示「—」。 */
export function dependsOnText(dependsOn: string[]): string {
  return dependsOn.length === 0 ? '—' : dependsOn.join('、');
}

/** 版本列（PLT-MOD-003）：功能版本 / 迁移版本；未同步（空）显示「—」。 */
export function versionText(
  item: Pick<ModuleCatalogItem, 'featureVersion' | 'migrationVersion'>,
): string {
  if (!item.featureVersion && !item.migrationVersion) return '—';
  return `${item.featureVersion || '—'} / ${item.migrationVersion || '—'}`;
}

/** 同步时间列：未同步显示「—」，已同步精确到分钟。 */
export function syncedAtText(
  item: Pick<ModuleCatalogItem, 'syncedAt'>,
): string {
  if (!item.syncedAt) return '—';
  return item.syncedAt.slice(0, 16).replace('T', ' ');
}

/** 菜单清单的端别与类型文案。 */
export function menuSideText(side: string): string {
  if (side === 'HOSPITAL') return '医院端';
  if (side === 'PLATFORM') return '平台端';
  return side;
}

export function menuTypeText(type: string): string {
  if (type === 'DIR') return '目录';
  if (type === 'MENU') return '菜单';
  if (type === 'BUTTON') return '按钮';
  return type;
}

/** 基础模块必选，不能下架（平台规格 §3）；维护对话框里禁用「下架」选项。 */
export function canTakeOffShelf(
  item: Pick<ModuleCatalogItem, 'baseModule'>,
): boolean {
  return !item.baseModule;
}

/** 维护表单模型（对话框状态；status 由单选按钮写回，提交前经 toUpdateRequest 收窄类型）。 */
export interface ModuleEditModel {
  description: string;
  name: string;
  status: string;
}

export function toEditModel(item: ModuleCatalogItem): ModuleEditModel {
  return {
    description: item.description ?? '',
    name: item.name,
    status: item.status,
  };
}

/** 表单 → 提交请求（PLT-MOD-002：只允许名称、说明、上架状态）。 */
export function toUpdateRequest(model: ModuleEditModel): ModuleUpdateRequest {
  return {
    description: model.description,
    name: model.name,
    status: model.status === 'OFF' ? 'OFF' : 'ON',
  };
}

/** 维护表单校验（PLT-MOD-002）：名称必填 ≤64，说明 ≤255；返回错误信息（空数组 = 通过）。 */
export function validateEditModel(model: ModuleEditModel): string[] {
  const errors: string[] = [];
  if (!model.name.trim()) {
    errors.push('模块名称必填');
  } else if (model.name.trim().length > 64) {
    errors.push('模块名称过长（最多 64 个字符）');
  }
  if (model.description.trim().length > 255) {
    errors.push('模块说明过长（最多 255 个字符）');
  }
  return errors;
}

// 开通记录（E05-T05 / PLT-MOD-009）展示逻辑与租户模块页签共用，这里转出供本页使用。
export { logActionTone, logLines } from '../tenant/tenant-modules';

/** 同步结果提示（PLT-MOD-002）：「随访：已更新（功能 0.1.0 / 迁移 2）」。 */
export function syncOutcomeLine(outcome: {
  action: string;
  code: string;
  featureVersion: string;
  migrationVersion: string;
}): string {
  return `${outcome.code}：${moduleSyncActionText(outcome.action)}（功能 ${
    outcome.featureVersion || '—'
  } / 迁移 ${outcome.migrationVersion || '—'}）`;
}
