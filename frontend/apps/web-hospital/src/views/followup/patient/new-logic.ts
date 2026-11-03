import type { DuplicateNotice, PatientDuplicateCheckResult } from '@crdp/api';

/**
 * E13-T01 新建患者页（/followup/patient/new，pages.md §0.1）的页面逻辑：
 * 表单校验与查重提示的纯逻辑在 @crdp/api（validateRegistration / duplicateNoticeOf），
 * 这里只做提交期错误映射（40902 并发重复）与路由跳转约定。
 */
import { duplicateNoticeOf } from '@crdp/api';

/** 统一响应里的业务错误（requestClient 抛出的错误体）。 */
export interface ApiErrorBody {
  code?: null | number;
  data?: null | PatientDuplicateCheckResult;
  message?: null | string;
}

/**
 * 提交失败映射：40902（唯一性冲突）转「阻止」提示（data 带范围内已有患者脱敏摘要）；
 * 其余错误返回 null（页面按通用错误提示 message）。
 */
export function noticeFromSubmitError(error: unknown): DuplicateNotice {
  const body = error as ApiErrorBody;
  if (body?.code === 40_902) {
    const notice = duplicateNoticeOf(body.data ?? { exists: true });
    return (
      notice ?? {
        kind: 'blocked',
        patient: null,
        text: body.message || '该病案号已在本院登记，请核对后重试',
      }
    );
  }
  return null;
}

/** 患者详情路由（隐藏路由由后端菜单下发；同一患者重复打开时多标签页切换）。 */
export function patientDetailPath(patientId: number | string): string {
  return `/followup/patient/${patientId}`;
}
