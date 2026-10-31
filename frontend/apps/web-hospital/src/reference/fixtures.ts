import type {
  AttachmentNavItem,
  CategoryProgress,
  DeviceEvent,
  LocationTreeNode,
  ReadonlyCategory,
  ReferencePatient,
  ReportLocationInput,
  TimelineNode,
} from './types';

import { colors } from '@crdp/tokens';

const TEMPLATE_VERSION = 1;

function thumb(label: string) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="${colors.primary}"/><text x="32" y="28" text-anchor="middle" font-size="10" fill="#ffffff">FAKE</text><text x="32" y="44" text-anchor="middle" font-size="9" fill="#ffffff">${label}</text></svg>`;
  return `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`;
}

function field(
  label: string,
  value?: string,
  extra?: { derived?: boolean; sensitive?: boolean },
) {
  return { label, value, ...extra };
}

const aCategories: ReadonlyCategory[] = [
  {
    fields: [
      field('姓名', '测*01（示例）', { sensitive: true }),
      field('性别', '男'),
      field('出生日期', '1963-04-12'),
      field('年龄', '63 岁', { derived: true }),
    ],
    key: 'basic',
    label: '基本信息',
  },
  {
    fields: [
      field('病案号', 'DEMO-MRN-0001', { sensitive: true }),
      field('入院日期', '2022-10-02'),
      field('主诉', '活动后胸闷（示例）'),
      field('现病史', undefined),
    ],
    key: 'case',
    label: '病例基本信息',
  },
  {
    fields: [field('主要诊断', '冠心病（示例）'), field('心功能', 'II 级')],
    key: 'diagnosis',
    label: '诊断信息',
  },
  {
    fields: [field('血红蛋白', '128 g/L'), field('肌酐', undefined)],
    key: 'lab',
    label: '实验室检查',
  },
  {
    fields: [field('出院前 EF', '56 %'), field('出院前 LVDd', '52 mm')],
    key: 'echo',
    label: '超声检查',
  },
  {
    fields: [field('造影结论', '示例血管狭窄'), field('介入', '未填写')],
    key: 'angio',
    label: '术前造影',
  },
  {
    fields: [field('手术日期', '2022-10-08'), field('术式', '示例搭桥')],
    key: 'surgery',
    label: '手术信息',
  },
  {
    fields: [field('ICU 天数', '2'), field('并发症', '无（示例）')],
    key: 'peri',
    label: '围手术期',
  },
  {
    fields: [field('出院带药', '阿司匹林（示例）'), field('剂量', undefined)],
    key: 'meds',
    label: '出院带药',
  },
];

const aProgress: CategoryProgress[] = [
  { attachmentCount: 0, filled: 4, key: 'basic', label: '基本信息', total: 4 },
  {
    attachmentCount: 1,
    filled: 3,
    key: 'case',
    label: '病例基本信息',
    total: 4,
  },
  {
    attachmentCount: 0,
    filled: 2,
    key: 'diagnosis',
    label: '诊断信息',
    total: 2,
  },
  { attachmentCount: 0, filled: 1, key: 'lab', label: '实验室检查', total: 2 },
  { attachmentCount: 1, filled: 2, key: 'echo', label: '超声检查', total: 2 },
  { attachmentCount: 1, filled: 1, key: 'angio', label: '术前造影', total: 2 },
  {
    attachmentCount: 0,
    filled: 2,
    key: 'surgery',
    label: '手术信息',
    total: 2,
  },
  { attachmentCount: 0, filled: 2, key: 'peri', label: '围手术期', total: 2 },
  { attachmentCount: 1, filled: 1, key: 'meds', label: '出院带药', total: 2 },
];

const cCategories: ReadonlyCategory[] = [
  {
    fields: [
      field('姓名', '测*02（示例）', { sensitive: true }),
      field('性别', '女'),
      field('出生日期', '1958-09-01'),
      field('年龄', '68 岁', { derived: true }),
      field('科室病区', '示例二病区'),
    ],
    key: 'basic',
    label: '基本信息',
  },
  {
    fields: [
      field('植入指征', '三度房室传导阻滞（示例）'),
      field('晕厥史', '无'),
    ],
    key: 'history',
    label: '病史与植入指征',
  },
  {
    fields: [field('QRS 宽度', '118 ms'), field('左室射血分数', undefined)],
    key: 'precheck',
    label: '术前检查',
  },
  {
    fields: [
      field('当前器械', 'CRT-D（示例品牌）'),
      field('设备序列号', 'DEMO-SN-0001'),
      field('植入时长', '1 年 5 个月', { derived: true }),
      field('接近 ERI 标记', '正常'),
    ],
    key: 'device-summary',
    label: '当前器械摘要',
  },
];

const cProgress: CategoryProgress[] = [
  { filled: 5, key: 'basic', label: '基本信息', total: 5 },
  { filled: 2, key: 'history', label: '病史与植入指征', total: 2 },
  { filled: 1, key: 'precheck', label: '术前检查', total: 2 },
  { filled: 4, key: 'device-summary', label: '当前器械摘要', total: 4 },
  { filled: 2, key: 'implants', label: '植入记录', total: 2 },
];

function loc(
  partial: Omit<ReportLocationInput, 'anchorTemplateVersion'> & {
    anchorLabel?: string;
    recordDate?: string;
    recordLabel?: string;
  },
): AttachmentNavItem['locations'][number] {
  return partial;
}

export const attachments: AttachmentNavItem[] = [
  {
    fileCount: 2,
    home: 'baseline',
    locations: [
      loc({
        anchorKey: 'echo',
        anchorLabel: '超声检查',
        anchorType: 'category',
        linkType: 'baseline',
      }),
      loc({
        anchorKey: 'case',
        anchorLabel: '病例基本信息',
        anchorType: 'category',
        linkType: 'baseline',
      }),
    ],
    name: '超声示例-01',
    reportDate: '2026-10-28',
    reportId: 'rpt-echo-01',
    thumbnailUrl: thumb('超声'),
    typeCode: 'echo',
    typeName: '心脏超声',
  },
  {
    fileCount: 1,
    home: 'baseline',
    locations: [
      loc({
        anchorKey: 'case',
        anchorLabel: '病例基本信息',
        anchorType: 'category',
        linkType: 'baseline',
      }),
      loc({
        anchorKey: 'meds',
        anchorLabel: '出院带药',
        anchorType: 'category',
        linkType: 'baseline',
      }),
    ],
    name: '出院小结示例-01',
    reportDate: '2022-10-18',
    reportId: 'rpt-discharge-01',
    thumbnailUrl: thumb('小结'),
    typeCode: 'discharge',
    typeName: '出院小结',
  },
  {
    fileCount: 3,
    home: 'baseline',
    locations: [
      loc({
        anchorKey: 'angio',
        anchorLabel: '术前造影',
        anchorType: 'category',
        linkType: 'baseline',
      }),
    ],
    name: '造影示例-01',
    reportDate: '2022-10-06',
    reportId: 'rpt-angio-01',
    thumbnailUrl: thumb('造影'),
    typeCode: 'angio',
    typeName: '冠脉造影',
  },
  {
    fileCount: 1,
    home: 'both',
    locations: [],
    name: '未归档示例-01',
    reportDate: '2024-01-09',
    reportId: 'rpt-loose-01',
    thumbnailUrl: thumb('未指定'),
    typeCode: 'unknown',
    typeName: '其他',
  },
  {
    digitizeStatus: '已电子化',
    fileCount: 1,
    home: 'followup',
    locations: [
      loc({
        anchorKey: 'echo',
        anchorLabel: '超声复查',
        anchorType: 'section',
        linkRecordId: 'rec-3',
        linkType: 'followup_record',
        recordDate: '2025-11-20',
        recordLabel: '第3年随访',
      }),
      loc({
        anchorKey: 'echo',
        anchorLabel: '超声复查',
        anchorType: 'section',
        linkRecordId: 'rec-2',
        linkType: 'followup_record',
        recordDate: '2025-01-15',
        recordLabel: '第2年随访',
      }),
    ],
    name: '随访超声示例-01',
    reportDate: '2025-11-18',
    reportId: 'rpt-fu-echo-01',
    thumbnailUrl: thumb('随访'),
    typeCode: 'echo',
    typeName: '心脏超声',
  },
  {
    digitizeStatus: '待录入',
    fileCount: 1,
    home: 'followup',
    locations: [
      loc({
        anchorKey: 'implant-2',
        anchorLabel: '植入记录 #2',
        anchorType: null,
        linkRecordId: 'imp-2',
        linkType: 'implant_record',
        recordDate: '2025-03-18',
        recordLabel: '植入记录 #2',
      }),
    ],
    name: '植入记录示例-02',
    reportDate: '2025-03-18',
    reportId: 'rpt-implant-02',
    thumbnailUrl: thumb('植入'),
    typeCode: 'implant',
    typeName: '植入手术记录',
  },
];

const followSections = (highlight = 0): CategoryProgress[] => [
  { filled: 6, key: 'overview', label: '随访概况', total: 6 },
  { filled: 3, highlightCount: 0, key: 'events', label: '临床事件', total: 4 },
  {
    attachmentCount: 1,
    filled: 4,
    highlightCount: highlight,
    key: 'echo',
    label: '超声复查',
    total: 4,
  },
];

const sectionCategories = (highlight: number): ReadonlyCategory[] => [
  {
    fields: [
      field('随访日期', '2025-11-20'),
      field('随访方式', '电话'),
      field('随访年度', '第 3 年', { derived: true }),
    ],
    key: 'overview',
    label: '随访概况',
  },
  {
    fields: [field('再住院', '否'), field('新发事件', undefined)],
    key: 'events',
    label: '临床事件',
  },
  {
    fields: [
      field('EF 射血分数', highlight > 0 ? '45 % ▲' : '56 %'),
      field('LVDd', '52 mm'),
    ],
    key: 'echo',
    label: '超声复查',
  },
];

export const timelineA: TimelineNode[] = [
  {
    canOpen: true,
    categories: sectionCategories(0),
    doneDate: '2023-11-02',
    label: '第1年随访',
    planDate: '2023-11-05',
    progress: followSections(0),
    recordId: 'rec-1',
    sections: followSections(0),
    status: '已完成',
    windowEnd: '2023-11-12',
    windowStart: '2023-10-29',
  },
  {
    canOpen: true,
    categories: sectionCategories(1),
    doneDate: '2025-01-15',
    highlightCount: 1,
    label: '第2年随访',
    note: '补录（纸质），2025-01-15 录入',
    planDate: '2024-11-05',
    progress: followSections(1),
    recordId: 'rec-2',
    sections: followSections(1),
    status: '补录',
    windowEnd: '2024-11-12',
    windowStart: '2024-10-29',
  },
  {
    canOpen: true,
    categories: sectionCategories(2),
    doneDate: '2025-12-02',
    highlightCount: 2,
    label: '第3年随访',
    note: '窗口结束后完成，记逾期完成',
    planDate: '2025-11-05',
    progress: followSections(2),
    recordId: 'rec-3',
    sections: followSections(2),
    status: '窗口外',
    windowEnd: '2025-11-12',
    windowStart: '2025-10-29',
  },
  {
    canOpen: true,
    categories: sectionCategories(0),
    doneDate: '2026-06-10',
    label: '计划外随访',
    note: '症状就诊；不作对比对象',
    progress: followSections(0),
    recordId: 'rec-extra',
    sections: followSections(0),
    status: '已完成',
  },
  {
    canOpen: true,
    categories: sectionCategories(0),
    label: '第4年随访',
    note: '已暂存 1 次',
    planDate: '2026-11-05',
    progress: followSections(0),
    recordId: 'rec-4',
    sections: followSections(0),
    status: '进行中',
    windowEnd: '2026-11-12',
    windowStart: '2026-10-29',
  },
  {
    canOpen: false,
    categories: [],
    label: '第5年随访',
    note: '计划中，任务开启日才创建',
    planDate: '2027-11-05',
    progress: [],
    sections: [],
    status: '计划中',
    windowEnd: '2027-11-12',
    windowStart: '2027-10-29',
  },
];

export const deviceEventsC: DeviceEvent[] = [
  {
    date: '2024-05-12',
    detail: '入组“普通起搏器术后随访”',
    kind: 'first',
    title: '首次植入 · 双腔(DDD)',
  },
  {
    date: '2025-03-18',
    detail: '转入“ICD/CRT-D 术后随访”，从升级日重新起算',
    kind: 'upgrade',
    title: '升级 · CRT-D',
  },
];

export const patients: ReferencePatient[] = [
  {
    age: 63,
    baselineCompleteness: 92,
    categories: aCategories,
    dischargeDate: '2022-10-18',
    features: {
      eriCycle: false,
      implant: false,
      paramTrend: false,
      report: true,
      scanProgress: false,
    },
    followupCompleteness: 78,
    gender: '男',
    id: 'demo-a-01',
    legacyNo: 'OLD-A-000123',
    nameMasked: '测*01（示例）',
    namePlain: '测试患者01（示例）',
    nextVisit: { label: '第4年', planDate: '2026-11-05' },
    patientNo: 'DEMO-A-0001',
    phoneMasked: '138****0001',
    phonePlain: '13800000001',
    progress: aProgress,
    responsibleName: '王医生（示例）',
    status: '随访中',
    variant: 'a',
    wardName: '示例二病区',
  },
  {
    age: 70,
    baselineCompleteness: 40,
    categories: aCategories,
    dischargeDate: '2024-02-01',
    features: {
      eriCycle: false,
      implant: false,
      paramTrend: false,
      report: true,
      scanProgress: false,
    },
    followupCompleteness: 0,
    gender: '女',
    id: 'demo-a-02',
    legacyNo: 'OLD-A-000456',
    nameMasked: '测*03（示例）',
    namePlain: '测试患者03（示例）',
    nextVisit: { label: '第1年', planDate: '2026-12-01' },
    patientNo: 'DEMO-A-0002',
    phoneMasked: '138****0003',
    phonePlain: '13800000003',
    progress: aProgress,
    responsibleName: '李医生（示例）',
    status: '待随访',
    variant: 'a',
    wardName: '示例一病区',
  },
  {
    age: 68,
    baselineCompleteness: 88,
    categories: cCategories,
    device: {
      eri: '正常',
      implantDuration: '1 年 5 个月',
      typeLabel: 'CRT-D（示例品牌）',
    },
    features: {
      eriCycle: true,
      implant: true,
      paramTrend: true,
      report: true,
      scanProgress: true,
    },
    followupCompleteness: 64,
    gender: '女',
    id: 'demo-c-01',
    implants: [
      {
        date: '2025-03-18',
        device: 'CRT-D · 示例品牌X',
        id: 'imp-2',
        kind: '升级 → CRT-D',
        trigger: '转入 ICD/CRT-D 计划，从升级日重新起算',
      },
      {
        date: '2024-05-12',
        device: '双腔(DDD) · 示例品牌X',
        id: 'imp-1',
        kind: '首次植入',
        trigger: '入组普通起搏器计划',
      },
    ],
    leads: [
      { date: '2024-05-12', site: '右心耳', status: '沿用' },
      { date: '2024-05-12', site: '右室心尖', status: '沿用（趋势连续）' },
      { date: '2025-03-18', site: '冠状静脉窦', status: '新增' },
    ],
    nameMasked: '测*02（示例）',
    namePlain: '测试患者02（示例）',
    nextVisit: { label: '常规随访', planDate: '2026-11-20' },
    patientNo: 'DEMO-C-0001',
    phoneMasked: '138****0002',
    phonePlain: '13800000002',
    progress: cProgress,
    responsibleName: '赵医生（示例）',
    status: '随访中',
    variant: 'c',
    wardName: '示例二病区',
  },
];

export function findPatient(id: string) {
  return patients.find((item) => item.id === id);
}

export function patientsOf(variant: 'a' | 'c') {
  return patients.filter((item) => item.variant === variant);
}

export function locationTree(patient: ReferencePatient): LocationTreeNode[] {
  const baseline: LocationTreeNode = {
    children: patient.categories.map((category) => ({
      key: `baseline:${category.key}`,
      selectable: true,
      title: category.label,
      value: {
        anchorKey: category.key,
        anchorTemplateVersion: TEMPLATE_VERSION,
        anchorType: 'category',
        linkType: 'baseline',
      },
    })),
    key: 'baseline',
    selectable: false,
    title: '基线资料',
  };
  const followup: LocationTreeNode = {
    children: timelineA
      .filter((node) => node.recordId)
      .map((node) => ({
        children: node.sections.map((section) => ({
          key: `fu:${node.recordId}:${section.key}`,
          selectable: true,
          title: section.label,
          value: {
            anchorKey: section.key,
            anchorTemplateVersion: TEMPLATE_VERSION,
            anchorType: 'section' as const,
            linkRecordId: node.recordId,
            linkType: 'followup_record' as const,
          },
        })),
        key: `fu:${node.recordId}`,
        selectable: true,
        title: `${node.label}${node.planDate ? ` ${node.planDate}` : ''}`,
        value: {
          anchorTemplateVersion: TEMPLATE_VERSION,
          anchorType: null,
          linkRecordId: node.recordId,
          linkType: 'followup_record' as const,
        },
      })),
    key: 'followup',
    selectable: false,
    title: '随访记录',
  };
  const nodes = [baseline, followup];
  if (patient.features.implant && patient.implants) {
    nodes.push({
      children: patient.implants.map((row) => ({
        children: [
          {
            key: `imp:${row.id}:device`,
            selectable: true,
            title: '器械信息',
            value: {
              anchorKey: 'device',
              anchorTemplateVersion: TEMPLATE_VERSION,
              anchorType: 'section',
              linkRecordId: row.id,
              linkType: 'implant_record',
            },
          },
          {
            key: `imp:${row.id}:lead`,
            selectable: true,
            title: '电极',
            value: {
              anchorKey: 'lead',
              anchorTemplateVersion: TEMPLATE_VERSION,
              anchorType: 'section',
              linkRecordId: row.id,
              linkType: 'implant_record',
            },
          },
        ],
        key: `imp:${row.id}`,
        selectable: true,
        title: `${row.kind} ${row.date}`,
        value: {
          anchorTemplateVersion: TEMPLATE_VERSION,
          anchorType: null,
          linkRecordId: row.id,
          linkType: 'implant_record',
        },
      })),
      key: 'implants',
      selectable: false,
      title: '植入记录',
    });
  }
  return nodes;
}

export function attachmentsAt(
  items: AttachmentNavItem[],
  anchorKey: string,
  linkType?: AttachmentNavItem['locations'][number]['linkType'],
) {
  return items.filter((item) =>
    item.locations.some(
      (location) =>
        location.anchorKey === anchorKey &&
        (linkType ? location.linkType === linkType : true),
    ),
  );
}

export const contactRows = [
  {
    at: '2026-10-20 09:30',
    by: '演示护士（示例）',
    method: '电话',
    next: '2026-10-27',
    note: '约定复查（示例）',
    result: '已联系',
  },
  {
    at: '2026-09-02 15:10',
    by: '演示护士（示例）',
    method: '电话',
    next: '2026-09-03',
    note: '无人接听（示例）',
    result: '未接通',
  },
];

export const logRows = [
  {
    action: '查看明文 · 姓名（已审计）',
    at: '2026-10-31 14:02',
    by: '演示护士（示例）',
    detail: '样板日志，不是真实审计',
  },
  {
    action: '修改已提交记录 · 第3年 EF 45 → 46',
    at: '2026-10-11 11:20',
    by: '王医生（示例）',
    detail: '原因：抄录错误（示例）',
  },
];
