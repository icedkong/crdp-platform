const PART = /^[a-z][a-z0-9]*$/;

/** 权限码 `{模块}:{资源}:{动作}`，与平台底座规格一致。 */
export function permissionCode(
  module: string,
  resource: string,
  action: string,
) {
  for (const part of [module, resource, action]) {
    if (!PART.test(part)) {
      throw new Error(`权限码片段不合法: ${part}`);
    }
  }
  return `${module}:${resource}:${action}`;
}

export {
  compareValues,
  type CrossFieldCompareParams,
  type CrossFieldRule,
  type FieldIssue,
  type FormValidationResult,
  type ValidatableField,
  validateFormData,
  type ValidationRange,
} from './form-validation';
export { hasPermission } from './has-permission';
export {
  PERMISSION_REFRESH_CODES,
  permissionVersionChanged,
  shouldRefreshPermissions,
} from './permission-refresh';
