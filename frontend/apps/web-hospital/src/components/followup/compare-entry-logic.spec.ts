// E16-T11 / A-ATT-019（pages.md §4.6「对照录入」、§5.1 对照录入分屏）：
// 报告单「对照录入」目标解析纯逻辑——按位置打开基线分节编辑或随访记录表单
// （?compare=报告单 id），有多个位置时给选择清单；同一表单去重（整条与分组同记录
// 只留一项、优先带分组的标签）；无权限 / 不可达位置（未指定记录、implant_record
// 随 E21-T32）不出现在清单里。
import type { ReportLocationTag, ReportRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  COMPARE_QUERY_KEY,
  compareReportIdOf,
  compareRouteOf,
  compareTargetsOf,
  transcribableStatus,
  TRANSCRIBE_QUERY_KEY,
  transcribeActiveOf,
  transcribeRouteOf,
  transcribeTargetsOf,
} from './compare-entry-logic';

const ALL = { canEditBaseline: true, canEditRecord: true };

function tag(partial: Partial<ReportLocationTag>): ReportLocationTag {
  return {
    linkType: 'followup_record',
    templateVersion: 3,
    ...partial,
  };
}

function row(partial: Partial<ReportRow> & { id: string }): ReportRow {
  return {
    conclusion: null,
    createdAt: '2026-11-01T10:00:00+08:00',
    creatorName: '张医生（示例）',
    digitizeStatus: null,
    fileCount: 1,
    findings: null,
    firstFile: { fileId: '901', format: 'jpg' },
    locations: [],
    name: null,
    note: null,
    patientId: '301',
    reportDate: '2026-10-28',
    typeCode: 'echo',
    typeName: '心脏超声',
    version: 1,
    ...partial,
  };
}

describe('e16-T11 compareReportIdOf（?compare= 解析）', () => {
  it('合法字符串原样返回；缺失 / 空串 / 数组 → null', () => {
    expect(compareReportIdOf({ [COMPARE_QUERY_KEY]: 'r9' })).toBe('r9');
    expect(compareReportIdOf({})).toBeNull();
    expect(compareReportIdOf({ [COMPARE_QUERY_KEY]: '' })).toBeNull();
    expect(compareReportIdOf({ [COMPARE_QUERY_KEY]: ['r9'] })).toBeNull();
  });
});

describe('e16-T11 compareRouteOf（A-ATT-019 路由）', () => {
  it('基线位置 → 基线分节编辑，带 compare 与锚点分类 cat', () => {
    expect(
      compareRouteOf(
        '301',
        tag({
          anchorKey: 'basic_info',
          anchorLabel: '病例基本信息',
          anchorType: 'category',
          linkType: 'baseline',
        }),
        'r1',
      ),
    ).toBe(
      `/followup/patient/301/baseline/edit?${COMPARE_QUERY_KEY}=r1&cat=basic_info`,
    );
  });

  it('随访记录位置 → 随访表单页（recordId 路由）', () => {
    expect(
      compareRouteOf(
        '301',
        tag({
          anchorKey: 'echo',
          anchorLabel: '超声复查',
          anchorType: 'section',
          linkRecordId: '7001',
        }),
        'r1',
      ),
    ).toBe(`/followup/record/7001?${COMPARE_QUERY_KEY}=r1`);
  });

  it('无 linkRecordId 的随访位置与 implant_record（随 E21-T32）不可达 → null', () => {
    expect(compareRouteOf('301', tag({}), 'r1')).toBeNull();
    expect(
      compareRouteOf(
        '301',
        tag({ linkRecordId: '7002', linkType: 'implant_record' }),
        'r1',
      ),
    ).toBeNull();
  });
});

describe('e16-T11 compareTargetsOf（A-ATT-019 多位置选择清单）', () => {
  it('未指定位置 → 空清单（入口不出现）', () => {
    expect(compareTargetsOf(row({ id: 'r1', locations: [] }), ALL)).toEqual([]);
  });

  it('单一随访位置 → 单项目标，标签用位置文案', () => {
    const targets = compareTargetsOf(
      row({
        id: 'r1',
        locations: [
          tag({
            anchorKey: 'echo',
            anchorLabel: '超声复查',
            anchorType: 'section',
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
        ],
      }),
      ALL,
    );
    expect(targets).toHaveLength(1);
    expect(targets[0]?.label).toBe('第3年随访 · 超声复查');
    expect(targets[0]?.route).toBe(
      `/followup/record/7001?${COMPARE_QUERY_KEY}=r1`,
    );
  });

  it('整条记录与该记录分组 → 同一表单去重为一项，标签保留带分组的一条', () => {
    const targets = compareTargetsOf(
      row({
        id: 'r1',
        locations: [
          tag({
            anchorType: null,
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
          tag({
            anchorKey: 'echo',
            anchorLabel: '超声复查',
            anchorType: 'section',
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
        ],
      }),
      ALL,
    );
    expect(targets).toHaveLength(1);
    expect(targets[0]?.label).toBe('第3年随访 · 超声复查');
  });

  it('基线 + 多条随访记录 → 按位置顺序逐项列出（选择弹窗用）', () => {
    const targets = compareTargetsOf(
      row({
        id: 'r1',
        locations: [
          tag({
            anchorKey: 'basic_info',
            anchorLabel: '病例基本信息',
            anchorType: 'category',
            linkType: 'baseline',
          }),
          tag({
            anchorType: null,
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
          tag({
            anchorType: null,
            linkRecordId: '7002',
            recordLabel: '第2年随访',
          }),
        ],
      }),
      ALL,
    );
    expect(targets.map((item) => item.route)).toEqual([
      `/followup/patient/301/baseline/edit?${COMPARE_QUERY_KEY}=r1&cat=basic_info`,
      `/followup/record/7001?${COMPARE_QUERY_KEY}=r1`,
      `/followup/record/7002?${COMPARE_QUERY_KEY}=r1`,
    ]);
  });

  it('按目标表单权限过滤：无基线编辑权限只留随访，无记录编辑权限只留基线，都无 → 空', () => {
    const locations = [
      tag({
        anchorKey: 'basic_info',
        anchorLabel: '病例基本信息',
        anchorType: 'category',
        linkType: 'baseline',
      }),
      tag({ anchorType: null, linkRecordId: '7001', recordLabel: '第3年随访' }),
    ];
    const base = row({ id: 'r1', locations });
    expect(
      compareTargetsOf(base, { canEditBaseline: false, canEditRecord: true }),
    ).toHaveLength(1);
    expect(
      compareTargetsOf(base, { canEditBaseline: true, canEditRecord: false }),
    ).toHaveLength(1);
    expect(
      compareTargetsOf(base, { canEditBaseline: false, canEditRecord: false }),
    ).toEqual([]);
  });
});

// E21-T17 / C-SCAN-012、C-FU-017：转录到结构化记录——目标只认随访记录位置，
// 路由带 transcribe=1（与普通对照录入区分：只有转录态保存才把报告单置「已电子化」）。
describe('e21-T17 transcribeActiveOf / transcribableStatus（转录态与状态门槛）', () => {
  it('compare + transcribe=1 齐备才是转录态；缺任一 / 值不是 1 / 数组 → false', () => {
    expect(transcribeActiveOf({ compare: 'r9', transcribe: '1' })).toBe(true);
    expect(transcribeActiveOf({ compare: 'r9' })).toBe(false);
    expect(transcribeActiveOf({ transcribe: '1' })).toBe(false);
    expect(transcribeActiveOf({ compare: 'r9', transcribe: '0' })).toBe(false);
    expect(transcribeActiveOf({ compare: 'r9', transcribe: ['1'] })).toBe(
      false,
    );
  });

  it('待录入与未设置可转录；已电子化 / 无需录入不再给入口', () => {
    expect(transcribableStatus('PENDING')).toBe(true);
    expect(transcribableStatus(null)).toBe(true);
    expect(transcribableStatus('DONE')).toBe(false);
    expect(transcribableStatus('NO_ENTRY')).toBe(false);
  });
});

describe('e21-T17 transcribeRouteOf / transcribeTargetsOf（转录目标解析）', () => {
  it('随访记录位置 → 表单页带 compare 与 transcribe=1；基线 / 植入 / 未指定记录不可达', () => {
    expect(
      transcribeRouteOf(
        '301',
        tag({
          anchorType: null,
          linkRecordId: '7001',
          recordLabel: '第3年随访',
        }),
        'r1',
      ),
    ).toBe(
      `/followup/record/7001?${COMPARE_QUERY_KEY}=r1&${TRANSCRIBE_QUERY_KEY}=1`,
    );
    expect(
      transcribeRouteOf(
        '301',
        tag({
          anchorKey: 'basic_info',
          anchorType: 'category',
          linkType: 'baseline',
        }),
        'r1',
      ),
    ).toBeNull();
    expect(
      transcribeRouteOf(
        '301',
        tag({ linkRecordId: '7002', linkType: 'implant_record' }),
        'r1',
      ),
    ).toBeNull();
    expect(transcribeRouteOf('301', tag({}), 'r1')).toBeNull();
  });

  it('只收随访记录位置：基线位置不出现在转录清单（转录目标是结构化随访记录）', () => {
    const targets = transcribeTargetsOf(
      row({
        id: 'r1',
        locations: [
          tag({
            anchorKey: 'basic_info',
            anchorLabel: '病例基本信息',
            anchorType: 'category',
            linkType: 'baseline',
          }),
          tag({
            anchorType: null,
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
        ],
      }),
    );
    expect(targets).toHaveLength(1);
    expect(targets[0]?.route).toBe(
      `/followup/record/7001?${COMPARE_QUERY_KEY}=r1&${TRANSCRIBE_QUERY_KEY}=1`,
    );
  });

  it('同一记录整条 + 分组去重为一项，标签保留带分组的一条；多条记录逐项列出（选择弹窗用）', () => {
    const targets = transcribeTargetsOf(
      row({
        id: 'r1',
        locations: [
          tag({
            anchorType: null,
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
          tag({
            anchorKey: 'battery',
            anchorLabel: '电池',
            anchorType: 'section',
            linkRecordId: '7001',
            recordLabel: '第3年随访',
          }),
          tag({
            anchorType: null,
            linkRecordId: '7002',
            recordLabel: '第2年随访',
          }),
        ],
      }),
    );
    expect(targets).toHaveLength(2);
    expect(targets[0]?.label).toBe('第3年随访 · 电池');
    expect(targets[1]?.label).toBe('第2年随访');
  });

  it('位置中没有随访记录 → 空清单（调用方走「新建补录记录并加入位置」分支，OQ-131②）', () => {
    expect(
      transcribeTargetsOf(
        row({
          id: 'r1',
          locations: [
            tag({
              anchorKey: 'basic_info',
              anchorType: 'category',
              linkType: 'baseline',
            }),
          ],
        }),
      ),
    ).toEqual([]);
    expect(transcribeTargetsOf(row({ id: 'r1', locations: [] }))).toEqual([]);
  });
});
