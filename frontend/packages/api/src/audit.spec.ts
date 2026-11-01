/**
 * E08-T04 / PLT-AUD-005~007 审计中心共享纯逻辑单测：时间窗校验（≤ 3 个月，与后端 40001 同口径）、
 * 查询条件组装（空串剔除）、结果 / 访问方式 / 导出状态文案、下载提示。
 */
import { describe, expect, it } from 'vitest';

import {
  accessMethodText,
  AUDIT_MAX_SPAN_DAYS,
  auditKindText,
  auditResultColor,
  auditResultText,
  buildAuditSearchCondition,
  exportDownloadHint,
  jobExportStatusColor,
  jobExportStatusText,
  validateAuditTimeRange,
} from './audit';

describe('validateAuditTimeRange', () => {
  it('起止必填', () => {
    expect(validateAuditTimeRange(null, '2027-01-02T00:00:00+08:00')).toBe(
      '请选择开始与结束时间',
    );
    expect(validateAuditTimeRange('2027-01-01T00:00:00+08:00', '')).toBe(
      '请选择开始与结束时间',
    );
  });

  it('结束不能早于开始', () => {
    expect(
      validateAuditTimeRange(
        '2027-01-02T00:00:00+08:00',
        '2027-01-01T00:00:00+08:00',
      ),
    ).toBe('结束时间不能早于开始时间');
  });

  it('跨度超过 3 个月被拒（92 天口径，platform-spec §9）', () => {
    expect(
      validateAuditTimeRange(
        '2027-01-01T00:00:00+08:00',
        '2027-04-15T00:00:00+08:00',
      ),
    ).toContain('3 个月');
    expect(AUDIT_MAX_SPAN_DAYS).toBe(92);
  });

  it('恰好 3 个月通过', () => {
    expect(
      validateAuditTimeRange(
        '2027-01-01T00:00:00+08:00',
        '2027-04-01T00:00:00+08:00',
      ),
    ).toBeNull();
  });
});

describe('buildAuditSearchCondition', () => {
  it('去掉空串与 undefined，只保留命中项', () => {
    const condition = buildAuditSearchCondition({
      endTime: '2027-02-01T00:00:00+08:00',
      ip: '',
      module: ' system ',
      startTime: '2027-01-01T00:00:00+08:00',
      userName: undefined,
    });
    expect(condition).toEqual({
      endTime: '2027-02-01T00:00:00+08:00',
      module: 'system',
      pageNo: 1,
      pageSize: 20,
      startTime: '2027-01-01T00:00:00+08:00',
    });
  });

  it('平台端可带租户筛选', () => {
    const condition = buildAuditSearchCondition(
      { endTime: 'e', startTime: 's', tenantId: 1 },
      2,
      50,
    );
    expect(condition.tenantId).toBe(1);
    expect(condition.pageNo).toBe(2);
    expect(condition.pageSize).toBe(50);
  });
});

describe('文案映射', () => {
  it('结果：成功 / 被拒绝 / 失败', () => {
    expect(auditResultText('SUCCESS')).toBe('成功');
    expect(auditResultText('DENIED')).toBe('被拒绝');
    expect(auditResultText('FAIL')).toBe('失败');
    expect(auditResultText(null)).toBe('—');
    expect(auditResultColor('SUCCESS')).toBe('success');
    expect(auditResultColor('DENIED')).toBe('warning');
  });

  it('数据访问方式（PLT-AUD-003）', () => {
    expect(accessMethodText('VIEW_PLAIN')).toBe('查看明文');
    expect(accessMethodText('EXPORT')).toBe('导出');
    expect(accessMethodText('DOWNLOAD')).toBe('下载');
  });

  it('核心类型', () => {
    expect(auditKindText('OPERATION')).toBe('操作日志');
    expect(auditKindText('PLATFORM_ACCESS')).toBe('平台访问日志');
  });

  it('导出状态（PLT-AUD-007 状态机）', () => {
    expect(jobExportStatusText('PENDING_APPROVAL')).toBe('待审批');
    expect(jobExportStatusText('GENERATING')).toBe('生成中');
    expect(jobExportStatusText('READY')).toBe('可下载');
    expect(jobExportStatusText('REJECTED')).toBe('已驳回');
    expect(jobExportStatusColor('READY')).toBe('success');
  });
});

describe('exportDownloadHint', () => {
  const job = (status: string, extra: Record<string, unknown> = {}) =>
    ({ status, ...extra }) as any;

  it('待审批 / 生成中提示不能下载', () => {
    expect(exportDownloadHint(job('PENDING_APPROVAL'))).toBe(
      '待审批通过后可下载',
    );
    expect(exportDownloadHint(job('GENERATING'))).toBe(
      '文件生成中，请稍后刷新',
    );
  });

  it('可下载时给出 24 小时截止（分钟精度）', () => {
    expect(
      exportDownloadHint(
        job('READY', { expiresAt: '2027-01-02T10:30:00+08:00' }),
      ),
    ).toBe('请在 2027-01-02 10:30 前下载');
  });

  it('驳回与失败带原因', () => {
    expect(
      exportDownloadHint(job('REJECTED', { approveComment: '范围过大' })),
    ).toBe('已驳回：范围过大');
    expect(exportDownloadHint(job('FAILED', { failReason: '超时' }))).toBe(
      '生成失败：超时',
    );
  });
});
