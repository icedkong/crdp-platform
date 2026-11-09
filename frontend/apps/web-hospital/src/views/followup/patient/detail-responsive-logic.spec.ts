/**
 * E24-T04 / NFR-RESP-002 · 患者详情手机卡片纯逻辑（pages.md §4.10：
 * 沟通记录 / 报告单表格在手机转卡片；§4.6 报告单手机列表卡片）。数据全部虚构。
 */
import type { ReportRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  contactCardLines,
  formatDateTime,
  pageSlice,
  reportCardLines,
} from './detail-responsive-logic';

function contactRow(
  patch: Partial<Parameters<typeof contactCardLines>[0]> = {},
) {
  return {
    contactTime: '2026-10-28 09:30',
    creatorName: '王随访（示例）',
    methodText: '电话',
    nextContact: '2027-10-28 09:00',
    note: '患者诉偶有胸闷（示例）',
    resultText: '接通',
    ...patch,
  };
}

function reportRow(patch: Partial<ReportRow> = {}): ReportRow {
  return {
    conclusion: null,
    createdAt: '2026-10-28T10:12:00+08:00',
    creatorName: '张医生（示例）',
    digitizeStatus: null,
    fileCount: 2,
    findings: null,
    firstFile: null,
    id: 'r1',
    locations: [],
    name: 'RPT-A-0103（示例）',
    note: null,
    patientId: '301',
    reportDate: '2026-10-28',
    typeCode: 'ECHO',
    typeName: '心脏超声',
    version: 1,
    ...patch,
  };
}

describe('e24-t04 contactCardLines（沟通记录卡片字段行）', () => {
  it('时间为卡片标题，方式 / 结果 / 约定下次联系 / 备注 / 操作人逐行（pages.md §4.8 列）', () => {
    expect(contactCardLines(contactRow())).toEqual([
      { label: '方式', value: '电话' },
      { label: '结果', value: '接通' },
      { label: '约定下次联系', value: '2027-10-28 09:00' },
      { label: '备注', value: '患者诉偶有胸闷（示例）' },
      { label: '操作人', value: '王随访（示例）' },
    ]);
  });
});

describe('e24-t04 reportCardLines（报告单卡片字段行）', () => {
  it('名称为卡片标题，类型 / 报告时间（日期）/ 上传（人 · 时间到分）三行', () => {
    expect(reportCardLines(reportRow())).toEqual([
      { label: '类型', value: '心脏超声' },
      { label: '报告时间', value: '2026-10-28' },
      { label: '上传', value: '张医生（示例） · 2026-10-28 10:12' },
    ]);
  });

  it('上传人缺省显示「—」，名称缺省由模板回落类型名（这里不拼名称）', () => {
    const lines = reportCardLines(reportRow({ creatorName: null }));
    expect(lines[2]?.value).toBe('— · 2026-10-28 10:12');
  });
});

describe('e24-t04 formatDateTime（ISO → 本地展示口径）', () => {
  it('带时区 ISO 取前 16 位并替换 T；纯日期补零时辰', () => {
    expect(formatDateTime('2026-10-28T10:12:00+08:00')).toBe(
      '2026-10-28 10:12',
    );
    expect(formatDateTime('2026-10-28')).toBe('2026-10-28 00:00');
  });
});

describe('e24-t04 pageSlice（卡片分页切片，每页 20 与表格同口径）', () => {
  it('空列表返回空；正常页切片', () => {
    expect(pageSlice([], 1)).toEqual([]);
    const items = Array.from({ length: 45 }, (_, i) => i);
    expect(pageSlice(items, 1)).toHaveLength(20);
    expect(pageSlice(items, 3)).toEqual([40, 41, 42, 43, 44]);
  });

  it('页码越界回落最后一页（删除最后一条后当前页不空转）', () => {
    const items = Array.from({ length: 21 }, (_, i) => i);
    expect(pageSlice(items, 5)).toHaveLength(1); // 只有 2 页，回落第 2 页
    expect(pageSlice(items, 0)[0]).toBe(0); // 页码 < 1 回落第 1 页
  });
});
