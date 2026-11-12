/**
 * E15-T02 / A-FU-005：随访工作台纯逻辑（pages.md §5）。
 *
 * 锁定：横幅文案照抄 §5.1（逾期 / 窗口外）；补录任务横幅；只读与已提交横幅；
 * 暂存差异只带变更键（null = 清空）；「是否死亡」按字段编码 death 定位；
 * 提交成功文案两态；下次随访只读行；顶栏标题与摘要。
 */
import type {
  FormSchema,
  RecordDeviceContext,
  RecordTaskContext,
} from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  bannersOf,
  deathFieldKey,
  diffChanges,
  draftRecordKey,
  hasChanges,
  headerSummary,
  headerTitle,
  missingRequiredText,
  nextVisitText,
  readonlyBanner,
  rendererContextOf,
  submitConfirmText,
  submitSuccessMessage,
} from './logic';

const context = (over: Partial<RecordTaskContext> = {}): RecordTaskContext => ({
  mrn: 'MRN*',
  nextPlanDate: '2027-11-05',
  nodeName: '第4年随访',
  occurrence: 4,
  patientId: '301',
  patientName: '周*（示例）',
  planDate: '2026-11-05',
  taskId: '9001',
  taskStatus: 'IN_PROGRESS',
  taskType: 'FORM',
  windowEnd: '2026-11-12',
  windowStart: '2026-10-29',
  ...over,
});

describe('e15-T06 / C-FU-022 计划外随访横幅与文案', () => {
  it('计划外记录（无任务上下文）显示「不影响后续节点」横幅（pages.md §5.1 文案照抄）', () => {
    const banners = bannersOf(
      null,
      '2026-11-05',
      () => '2026-11-05',
      'UNPLANNED',
    );
    expect(banners).toHaveLength(1);
    expect(banners[0]).toMatchObject({
      key: 'unplanned',
      text: '计划外随访：随访时间点为“其他”，不影响后续节点。',
      tone: 'info',
    });
  });

  it('补录记录（无任务、source=BACKFILL）不显示计划外横幅', () => {
    expect(
      bannersOf(null, '2025-11-05', () => '2026-11-05', 'BACKFILL'),
    ).toEqual([]);
  });

  it('顶栏标题与摘要区分计划外 / 补录（无任务上下文）', () => {
    const patient = { patientName: '周*（示例）' };
    expect(headerTitle(null, patient, 'UNPLANNED')).toBe(
      '计划外随访 · 周*（示例）',
    );
    expect(headerTitle(null, patient, 'BACKFILL')).toBe(
      '历史补录 · 周*（示例）',
    );
    expect(headerSummary(null, '2026-06-10', 'UNPLANNED')).toBe(
      '计划外 · 实际随访日期 2026-06-10',
    );
    expect(headerSummary(null, '2025-01-15', 'BACKFILL')).toBe(
      '补录 · 实际随访日期 2025-01-15',
    );
  });

  it('提交确认与成功文案：计划外记录不联动任务、不影响后续节点', () => {
    expect(submitConfirmText(false, 'UNPLANNED')).toBe(
      '提交后计划外随访记录生效（不影响后续节点，C-FU-022）。',
    );
    expect(submitSuccessMessage(false, false, 'UNPLANNED')).toBe(
      '已提交计划外随访记录',
    );
    expect(submitSuccessMessage(true, false, 'UNPLANNED')).toBe(
      '已提交计划外随访记录；随访计划终止，不再创建后续任务',
    );
    // 补录文案保持原口径（A-FU-014 不占用年度任务）
    expect(submitConfirmText(false, 'BACKFILL')).toContain('不占用年度任务');
  });
});

describe('bannersOf（pages.md §5.1 横幅，文案照抄）', () => {
  const today = () => '2026-11-05';

  it('窗口内进行中无横幅', () => {
    expect(bannersOf(context(), null, today)).toEqual([]);
  });

  it('逾期：窗口已结束仍提交记「逾期完成」', () => {
    const banners = bannersOf(
      context({ taskStatus: 'OVERDUE', windowEnd: '2026-11-01' }),
      null,
      today,
    );
    expect(banners).toHaveLength(1);
    expect(banners[0]).toMatchObject({
      key: 'overdue',
      text: '窗口已于 2026-11-01 结束：现在提交记为“逾期完成”。',
      tone: 'error',
    });
  });

  it('已完成任务的窗口不弹逾期横幅（完成是历史事实）', () => {
    expect(
      bannersOf(
        context({ taskStatus: 'COMPLETED', windowEnd: '2026-11-01' }),
        null,
        today,
      ),
    ).toEqual([]);
  });

  it('窗口外：随访日期不在窗口内', () => {
    const banners = bannersOf(context(), '2026-11-20', today);
    expect(banners).toHaveLength(1);
    expect(banners[0]).toMatchObject({
      key: 'outside',
      text: '随访日期不在窗口 2026-10-29 ~ 2026-11-12 内：提交后标记为窗口外（逾期完成）。',
      tone: 'warning',
    });
  });

  it('随访日期在窗口内不弹窗口外横幅；无上下文无横幅', () => {
    expect(bannersOf(context(), '2026-11-05', today)).toEqual([]);
    expect(bannersOf(null, '2026-11-05', today)).toEqual([]);
  });

  it('补录任务（create_type = BACKFILL）给补录横幅', () => {
    const banners = bannersOf(
      context({ createType: 'BACKFILL', windowEnd: '2026-01-01' }),
      null,
      today,
    );
    expect(banners.map((banner) => banner.key)).toEqual([
      'overdue',
      'backfill',
    ]);
  });
});

describe('readonlyBanner（只读 / 已提交）', () => {
  it('只读角色与已提交各一条', () => {
    expect(readonlyBanner('DRAFT').text).toBe('只读角色：不能录入');
    expect(readonlyBanner('SUBMITTED').key).toBe('submitted');
  });
});

describe('diffChanges / hasChanges（E25-T01 暂存差异）', () => {
  it('只带变更键；清空为 null；未变不送', () => {
    expect(diffChanges({ a: 1, b: 2 }, { a: 1, b: 3, c: 4 })).toEqual({
      b: 3,
      c: 4,
    });
    expect(diffChanges({ a: 1 }, { a: undefined })).toEqual({ a: null });
    expect(diffChanges({ a: 1 }, { a: 1 })).toEqual({});
  });

  it('hasChanges 与 diffChanges 同口径', () => {
    expect(hasChanges({ a: 1 }, { a: 1 })).toBe(false);
    expect(hasChanges({ a: 1 }, { a: 2 })).toBe(true);
  });
});

describe('deathFieldKey（「是否死亡」按字段编码定位）', () => {
  const schema = {
    categories: [
      {
        key: 'fu_contact',
        label: '联系与生存',
        order: 10,
        fields: [
          { code: 'followup_date', key: 'f214' },
          { code: 'death', key: 'f215' },
        ],
      },
    ],
    code: 'A_ANNUAL_FU',
    name: '年度随访记录',
    rules: [],
    usage: 'FOLLOWUP',
    version: 1,
  } as unknown as FormSchema;

  it('返回 death 字段的稳定键；无该字段返回 null', () => {
    expect(deathFieldKey(schema)).toBe('f215');
    expect(deathFieldKey(null)).toBeNull();
  });
});

describe('提交与提示文案（照抄）', () => {
  it('提交成功两态', () => {
    expect(submitSuccessMessage(false)).toBe('已提交；按计划生成下次任务');
    expect(submitSuccessMessage(true)).toBe(
      '已提交；随访计划终止，不再创建后续任务',
    );
  });

  it('必填缺失提示带数量', () => {
    expect(missingRequiredText(3)).toBe(
      '还有 3 个必填项未填写，已在左侧导航标出',
    );
  });
});

describe('顶栏与下次随访行', () => {
  it('标题含脱敏姓名；摘要含节点 / 计划 / 窗口', () => {
    expect(headerTitle(context())).toBe('随访录入 · 周*（示例）');
    expect(headerSummary(context())).toBe(
      '第4年随访 · 计划 2026-11-05 · 窗口 2026-10-29 ~ 2026-11-12',
    );
    expect(headerTitle(null)).toBe('随访录入');
    expect(headerTitle(null, { patientName: '周*（示例）' })).toBe(
      '历史补录 · 周*（示例）',
    );
    expect(headerSummary(null)).toBe('');
    expect(headerSummary(null, '2025-06-01')).toBe(
      '补录 · 实际随访日期 2025-06-01',
    );
  });

  it('提交文案：补录记录不联动任务完成（A-FU-014）', () => {
    expect(submitSuccessMessage(false)).toBe('已提交；按计划生成下次任务');
    expect(submitSuccessMessage(false, false)).toBe('已提交补录记录');
    expect(submitSuccessMessage(true, false)).toBe(
      '已提交补录记录；随访计划终止，不再创建后续任务',
    );
    expect(submitConfirmText(true)).toBe(
      '提交后任务完成，按计划生成下次随访任务。',
    );
    expect(submitConfirmText(false)).toBe(
      '提交后补录记录生效（不占用年度任务，A-FU-014）。',
    );
  });

  it('下次随访只读行（按计划自动生成）；无缓存为空', () => {
    expect(nextVisitText(context())).toBe(
      '下次随访 · 计划 2027-11-05（按计划自动生成）',
    );
    expect(nextVisitText(context({ nextPlanDate: null }))).toBe('');
    expect(nextVisitText(null)).toBe('');
  });
});

describe('draftRecordKey（E25-T02 本地草稿键：租户 + 用户 + 记录隔离）', () => {
  it('键含租户、用户、kind 与记录 ID', () => {
    expect(draftRecordKey('hosp-a', '101', '701')).toBe(
      'hosp-a:101:followup:701',
    );
  });

  it('会话信息缺失时各段为空串，仍保持四段结构', () => {
    expect(draftRecordKey(undefined, undefined, '701')).toBe('::followup:701');
    expect(draftRecordKey('hosp-a', 2024, '9')).toBe('hosp-a:2024:followup:9');
  });
});

describe('rendererContextOf（E21-T03 器械上下文：按器械条件显示的 ctx）', () => {
  const crtD: RecordDeviceContext = {
    chambers: 3,
    current: true,
    hasRaLead: true,
    implantDate: '2026-03-10',
    implantId: '701',
    type: 'CRT_D',
    typeLabel: 'CRT-D',
  };

  it('器械上下文包成 { device }（ctx 点路径 device.* 取值）', () => {
    expect(rendererContextOf(crtD)).toEqual({ device: crtD });
  });

  it('无器械（A / B 租户、取出后）为 null——ctx 断链，条件字段隐藏', () => {
    expect(rendererContextOf(null)).toBeNull();
  });
});
