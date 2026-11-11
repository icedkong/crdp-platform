import type { TenantCancelFlowView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  canAck,
  canApply,
  canApprove,
  CANCEL_STAGE_TEXT,
  canDestroy,
  canGenerateExport,
  canRevoke,
  canSeal,
  coolingRemainingDays,
  retentionDue,
  sizeText,
  stepIndex,
  toRetentionSaveItems,
  validateApplyForm,
  validateRetentionItems,
} from './cancel';

/** 构造流程视图（只填动作判断关心的字段）。 */
function flowOf(partial: Partial<TenantCancelFlowView>): TenantCancelFlowView {
  return {
    appliedAt: null,
    appliedByName: null,
    approveNote: null,
    approvedAt: null,
    approvedByName: null,
    confirmationFile: null,
    coolingUntil: null,
    reason: null,
    revokeReason: null,
    revokedAt: null,
    revokedByName: null,
    stage: null,
    tenantCode: 'tdemo',
    tenantId: 1,
    tenantName: '虚构示例医院（示例）',
    tenantStatus: 'DISABLED',
    ...partial,
  };
}

const NOW = new Date('2026-11-11T10:00:00+08:00');
const FUTURE = '2026-12-11T10:00:00+08:00';

describe('e05-T08 注销阶段与步骤', () => {
  it('步骤下标随阶段推进（签收后停在封存步，销毁后到最后）', () => {
    expect(stepIndex(null)).toBe(0);
    expect(stepIndex('PENDING_APPROVAL')).toBe(0);
    expect(stepIndex('COOLING')).toBe(2);
    expect(stepIndex('EXPORTED')).toBe(4);
    expect(stepIndex('ACKED')).toBe(5);
    expect(stepIndex('DESTROYED')).toBe(6);
    // 被驳回 / 撤销回到审批位（可重新发起）
    expect(stepIndex('REJECTED')).toBe(1);
    expect(stepIndex('REVOKED')).toBe(1);
  });

  it('七个阶段都有中文文案', () => {
    for (const stage of [
      'ACKED',
      'COOLING',
      'DESTROYED',
      'EXPORTED',
      'PENDING_APPROVAL',
      'REJECTED',
      'REVOKED',
    ] as const) {
      expect(CANCEL_STAGE_TEXT[stage].length).toBeGreaterThan(0);
    }
  });

  it('冷静期剩余天数向上取整，已满返回 0，未登记返回 null', () => {
    expect(coolingRemainingDays(FUTURE, NOW)).toBe(30);
    expect(coolingRemainingDays('2026-11-11T11:00:00+08:00', NOW)).toBe(1);
    expect(coolingRemainingDays('2026-11-11T09:00:00+08:00', NOW)).toBe(0);
    expect(coolingRemainingDays(null, NOW)).toBeNull();
  });
});

describe('e05-T08 动作可用性（前端引导，最终以后端校验为准）', () => {
  it('发起申请：无流程或被驳回 / 撤销后，且租户停用', () => {
    expect(canApply(flowOf({ stage: null }))).toBe(true);
    expect(canApply(flowOf({ stage: 'REVOKED' }))).toBe(true);
    expect(canApply(flowOf({ stage: 'REJECTED' }))).toBe(true);
    expect(canApply(flowOf({ stage: 'PENDING_APPROVAL' }))).toBe(false);
    expect(canApply(flowOf({ stage: 'DESTROYED' }))).toBe(false);
    // 非停用状态（如启用）即使无流程也不能发起
    expect(canApply(flowOf({ stage: null, tenantStatus: 'ENABLED' }))).toBe(
      false,
    );
  });

  it('审批只在待审批阶段；撤销只在冷静期未满时', () => {
    expect(canApprove(flowOf({ stage: 'PENDING_APPROVAL' }))).toBe(true);
    expect(canApprove(flowOf({ stage: 'COOLING' }))).toBe(false);
    expect(
      canRevoke(flowOf({ stage: 'COOLING', coolingUntil: FUTURE }), NOW),
    ).toBe(true);
    expect(
      canRevoke(
        flowOf({ stage: 'COOLING', coolingUntil: '2026-11-01T00:00:00+08:00' }),
        NOW,
      ),
    ).toBe(false);
    expect(
      canRevoke(flowOf({ stage: 'EXPORTED', coolingUntil: FUTURE }), NOW),
    ).toBe(false);
  });

  it('生成迁出包：冷静期满或已生成过；签收只在已生成阶段', () => {
    expect(
      canGenerateExport(
        flowOf({ stage: 'COOLING', coolingUntil: '2026-11-01T00:00:00+08:00' }),
        NOW,
      ),
    ).toBe(true);
    expect(
      canGenerateExport(
        flowOf({ stage: 'COOLING', coolingUntil: FUTURE }),
        NOW,
      ),
    ).toBe(false);
    expect(
      canGenerateExport(
        flowOf({ stage: 'EXPORTED', coolingUntil: FUTURE }),
        NOW,
      ),
    ).toBe(true, '过期 / 次数用完可重新生成');
    expect(canGenerateExport(flowOf({ stage: 'ACKED' }), NOW)).toBe(false);
    expect(canAck(flowOf({ stage: 'EXPORTED' }))).toBe(true);
    expect(canAck(flowOf({ stage: 'ACKED' }))).toBe(false);
  });

  it('封存在迁出包之后且有未封存项；销毁须已签收', () => {
    expect(canSeal(flowOf({ stage: 'EXPORTED' }), 1)).toBe(true);
    expect(canSeal(flowOf({ stage: 'ACKED' }), 1)).toBe(true);
    expect(canSeal(flowOf({ stage: 'EXPORTED' }), 0)).toBe(
      false,
      '没有未封存项',
    );
    expect(canSeal(flowOf({ stage: 'COOLING' }), 1)).toBe(false);
    expect(canDestroy(flowOf({ stage: 'ACKED' }))).toBe(true);
    expect(canDestroy(flowOf({ stage: 'EXPORTED' }))).toBe(false);
  });

  it('保留期满销毁：已封存且期满日已到', () => {
    expect(
      retentionDue(
        { retainUntil: '2026-11-10', status: 'SEALED' },
        '2026-11-11',
      ),
    ).toBe(true);
    expect(
      retentionDue(
        { retainUntil: '2026-11-11', status: 'SEALED' },
        '2026-11-11',
      ),
    ).toBe(true);
    expect(
      retentionDue(
        { retainUntil: '2026-11-12', status: 'SEALED' },
        '2026-11-11',
      ),
    ).toBe(false);
    expect(
      retentionDue(
        { retainUntil: '2026-11-10', status: 'REGISTERED' },
        '2026-11-11',
      ),
    ).toBe(false);
  });
});

describe('e05-T08 表单校验与载荷', () => {
  it('申请表单：原因与书面确认必填', () => {
    expect(
      validateApplyForm({
        confirmationFileId: null,
        confirmationFileName: '',
        reason: '',
      }),
    ).toEqual({
      confirmation: '必须上传医院书面确认（PDF / JPG / PNG）',
      reason: '必须填写注销原因',
    });
    expect(
      validateApplyForm({
        confirmationFileId: 3,
        confirmationFileName: 'a.pdf',
        reason: ' 终止合作 ',
      }),
    ).toEqual({});
    expect(
      validateApplyForm({
        confirmationFileId: 3,
        confirmationFileName: 'a.pdf',
        reason: '长'.repeat(501),
      }).reason,
    ).toContain('500');
  });

  it('保留项：范围 / 依据 / 未来期满日 / 表名字符集', () => {
    const today = '2026-11-11';
    expect(
      validateRetentionItems(
        [
          {
            legalBasis: 'GCP',
            retainUntil: '2027-01-01',
            scope: 'RCT 资料',
            tablesCsv: '',
          },
        ],
        today,
      ),
    ).toEqual(['']);
    expect(
      validateRetentionItems(
        [
          {
            legalBasis: '',
            retainUntil: '2027-01-01',
            scope: '',
            tablesCsv: '',
          },
        ],
        today,
      )[0],
    ).toContain('须填写范围');
    expect(
      validateRetentionItems(
        [
          {
            legalBasis: 'GCP',
            retainUntil: '2026-11-10',
            scope: 'RCT 资料',
            tablesCsv: '',
          },
        ],
        today,
      )[0],
    ).toContain('不能早于');
    expect(
      validateRetentionItems(
        [
          {
            legalBasis: 'GCP',
            retainUntil: '2027/01/01',
            scope: 'RCT',
            tablesCsv: '',
          },
        ],
        today,
      )[0],
    ).toContain('YYYY-MM-DD');
    expect(
      validateRetentionItems(
        [
          {
            legalBasis: 'GCP',
            retainUntil: '2027-01-01',
            scope: 'RCT',
            tablesCsv: 'aud log',
          },
        ],
        today,
      )[0],
    ).toContain('表名');
  });

  it('编辑行 → 保存载荷：tablesCsv 空串省略', () => {
    expect(
      toRetentionSaveItems([
        {
          legalBasis: 'GCP',
          retainUntil: '2099-12-31',
          scope: 'RCT',
          tablesCsv: '',
        },
        {
          legalBasis: '等保',
          retainUntil: '2099-12-31',
          scope: '审计日志',
          tablesCsv: 'aud_log, aud_login_log',
        },
      ]),
    ).toEqual([
      { legalBasis: 'GCP', retainUntil: '2099-12-31', scope: 'RCT' },
      {
        legalBasis: '等保',
        retainUntil: '2099-12-31',
        scope: '审计日志',
        tablesCsv: 'aud_log, aud_login_log',
      },
    ]);
  });

  it('字节数与迁出包摘要', () => {
    expect(sizeText(512)).toBe('1 KB');
    expect(sizeText(3 * 1024 * 1024)).toBe('3.0 MB');
  });
});
