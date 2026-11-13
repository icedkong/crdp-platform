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
  applyEntryDefaults,
  bannersOf,
  canContinueBackfill,
  canEditSubmittedRecord,
  deathFieldKey,
  diffChanges,
  draftRecordKey,
  duplicateRecordConfirmText,
  entrySummaryText,
  hasChanges,
  headerSummary,
  headerTitle,
  implantDurationText,
  missingRequiredText,
  nextVisitFieldKey,
  nextVisitIssueText,
  nextVisitNote,
  nextVisitText,
  nextVisitWindowPreviewOf,
  readonlyBanner,
  rendererContextOf,
  submitConfirmText,
  submitSuccessMessage,
  transcribeAppendedText,
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

describe('e21-T11 / C-FU-026：修改已提交记录的按钮权限（与后端闸门同口径）', () => {
  it('开关开启（医院 C）：必须 followup:record:edit-submitted，仅有录入码不可修改', () => {
    expect(
      canEditSubmittedRecord({
        editSubmittedFeatureOn: true,
        hasEdit: true,
        hasEditSubmitted: false,
      }),
    ).toBe(false);
    expect(
      canEditSubmittedRecord({
        editSubmittedFeatureOn: true,
        hasEdit: true,
        hasEditSubmitted: true,
      }),
    ).toBe(true);
  });

  it('开关关闭（A / B）：followup:record:edit 即可（followup.md §6）', () => {
    expect(
      canEditSubmittedRecord({
        editSubmittedFeatureOn: false,
        hasEdit: true,
        hasEditSubmitted: false,
      }),
    ).toBe(true);
  });
});

describe('e21-T11 / C-FU-024：重复记录提示文案', () => {
  it('照抄 pages.md §5.1（推断，待确认）：「该患者在 {日期} 已有一条随访记录，确定继续？」', () => {
    expect(duplicateRecordConfirmText('2026-11-02')).toBe(
      '该患者在 2026-11-02 已有一条随访记录，确定继续？',
    );
  });
});

describe('e21-T11 / C-FU-008：植入时长与头幅自动带入行', () => {
  it('植入时长「1 年 5 个月」按自然月差（当日未到扣 1 个月）', () => {
    expect(implantDurationText('2025-06-10', () => '2026-11-12')).toBe(
      '1 年 5 个月',
    );
    expect(implantDurationText('2026-11-12', () => '2026-11-12')).toBe(
      '不足 1 个月',
    );
    expect(implantDurationText(null)).toBe('');
  });

  it('头幅行各段缺省省略：随访编号 · 第 N 次 · 当前器械 · 植入时长', () => {
    expect(
      entrySummaryText({
        device: {
          chambers: 2,
          current: true,
          hasRaLead: true,
          implantDate: '2025-06-10',
          implantId: '501',
          type: 'DDD',
          typeLabel: '双腔起搏器(DDD)',
        },
        sequenceNo: 3,
        today: () => '2026-11-12',
        visitNo: '105-2026-001',
      }),
    ).toBe(
      '随访编号 105-2026-001 · 第 3 次 · 当前器械 双腔起搏器(DDD) · 植入时长 1 年 5 个月',
    );
    expect(entrySummaryText({})).toBe('');
  });
});

describe('e21-T11 / C-FU-008：录入缺省（随访医生 = 当前用户）', () => {
  const schema = {
    categories: [
      {
        fields: [
          {
            code: 'visit_doctor',
            key: 'f305',
            label: '随访医生',
            type: 'select',
          },
        ],
        key: 'fu_contact',
        label: '随访概况',
        order: 1,
      },
    ],
    code: 'C_PROGRAM_FU',
    name: '程控随访（示例）',
    rules: [],
    usage: 'FOLLOWUP',
    version: 1,
  } as unknown as FormSchema;

  it('模板含 visit_doctor 字段且当前无值时写入当前用户', () => {
    expect(
      applyEntryDefaults(
        schema,
        {},
        { doctorId: '7', doctorName: '张医生（示例）' },
      ),
    ).toEqual({ f305: '7' });
  });

  it('已有值不覆盖；无该字段（A 表单）原样返回', () => {
    expect(
      applyEntryDefaults(
        schema,
        { f305: '9' },
        {
          doctorId: '7',
          doctorName: '张医生（示例）',
        },
      ),
    ).toEqual({ f305: '9' });
    expect(
      applyEntryDefaults(
        { ...schema, categories: [] } as unknown as FormSchema,
        {},
        { doctorId: '7', doctorName: '张医生（示例）' },
      ),
    ).toEqual({});
  });
});

describe('e21-t08 医生指定下次随访时间（C-FU-005 / Q05）', () => {
  const schemaOf = (
    fields: {
      code: string;
      key: string;
    }[],
  ): FormSchema => ({
    categories: [
      {
        fields: fields as never,
        key: 'overview',
        label: '随访概况',
        order: 10,
      },
    ],
    code: 'C_PROGRAM_FU',
    name: '随访程控记录（示例）',
    rules: [],
    usage: 'FOLLOWUP',
    version: 1,
  });

  it('按字段编码定位稳定键；A / B 模板（无该编码）返回 null', () => {
    expect(
      nextVisitFieldKey(
        schemaOf([
          { code: 'followup_date', key: 'f303' },
          { code: 'next_visit_date', key: 'f333' },
        ]),
      ),
    ).toBe('f333');
    expect(
      nextVisitFieldKey(schemaOf([{ code: 'death', key: 'f019' }])),
    ).toBeNull();
    expect(nextVisitFieldKey(null)).toBeNull();
  });

  it('校验（pages.md §5.1 照抄）：不能早于或等于本次随访日期、不能早于今天', () => {
    const today = () => '2026-11-13';
    expect(nextVisitIssueText('2027-01-15', '2026-11-13', today)).toBe('');
    expect(nextVisitIssueText('', '2026-11-13', today)).toBe('');
    expect(nextVisitIssueText(null, '2026-11-13', today)).toBe('');
    expect(nextVisitIssueText('2026-11-13', '2026-11-13', today)).toBe(
      '下次随访时间不能早于或等于本次随访日期',
    );
    expect(nextVisitIssueText('2026-11-10', '2026-11-13', today)).toBe(
      '下次随访时间不能早于或等于本次随访日期',
    );
    // 早于今天但晚于本次随访日期（本次随访是过去日期）→ 不能早于今天
    expect(nextVisitIssueText('2026-11-12', '2026-11-10', today)).toBe(
      '下次随访时间不能早于今天',
    );
  });

  it('窗口预览：按当前任务节点的前后宽度围绕医生日期展开；无任务上下文 / 未填为 null', () => {
    // 任务窗口 plan 2026-11-05 ± 7 天 → 医生日期 2027-01-15 的预览窗口 ±7 天
    expect(nextVisitWindowPreviewOf('2027-01-15', context())).toEqual({
      start: '2027-01-08',
      end: '2027-01-22',
    });
    expect(nextVisitWindowPreviewOf('2027-01-15', null)).toBeNull();
    expect(nextVisitWindowPreviewOf(null, context())).toBeNull();
  });

  it('补充提示：补录「只保存」；常规 / 计划外为空', () => {
    expect(nextVisitNote('BACKFILL')).toBe('补录：只保存');
    expect(nextVisitNote('UNPLANNED')).toBeNull();
    expect(nextVisitNote(null)).toBeNull();
  });
});

// E21-T17 / C-SCAN-012：转录态提交成功追加文案（保存后报告单自动置「已电子化」）。
describe('e21-T17 转录完成追加文案（C-SCAN-012）', () => {
  it('transcribedReportId 非空 → 追加「报告单已标记为已电子化」；空 / null → 原样（不追加）', () => {
    expect(transcribeAppendedText('900')).toBe('；报告单已标记为「已电子化」');
    expect(transcribeAppendedText(null)).toBe('');
    expect(transcribeAppendedText(undefined)).toBe('');
  });
});

// E21-T14 / C-FU-015：提交成功面板的「继续补录下一次」可见性（implant 开关 = 医院 C）。
describe('e21-T14 / C-FU-015 连续补录入口', () => {
  it('补录记录 + 有权限 + implant 开启 + 能定位患者 → 提供', () => {
    expect(
      canContinueBackfill({
        canEdit: true,
        implantEnabled: true,
        patientId: '301',
        source: 'BACKFILL',
      }),
    ).toBe(true);
  });

  it('任务 / 计划外记录、无权限、A / B（implant 关闭）、患者定位失败 → 不提供', () => {
    expect(
      canContinueBackfill({
        canEdit: true,
        implantEnabled: true,
        patientId: '301',
        source: 'TASK',
      }),
    ).toBe(false);
    expect(
      canContinueBackfill({
        canEdit: false,
        implantEnabled: true,
        patientId: '301',
        source: 'BACKFILL',
      }),
    ).toBe(false);
    expect(
      canContinueBackfill({
        canEdit: true,
        implantEnabled: false,
        patientId: '301',
        source: 'BACKFILL',
      }),
    ).toBe(false);
    expect(
      canContinueBackfill({
        canEdit: true,
        implantEnabled: true,
        patientId: null,
        source: 'BACKFILL',
      }),
    ).toBe(false);
  });
});
