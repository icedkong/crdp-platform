/**
 * E13-T07 / A-PAT-016、A-PAT-017、C-PAT-016：批量入组与批量指定责任医生的共享类型与纯逻辑
 * （接口契约 docs/api/followup.md §1、docs/api/patient.md §2；tasks.csv 验收「批量操作结果逐条可查」）。
 *
 * 两个批量接口的逐条结果同一形态（items 与请求顺序一致）：name / patientNo 只含后端脱敏值；
 * 越范围 / 不存在的患者 outcome=SKIP、reason=NOT_FOUND 且不带摘要（不暴露对象存在）。
 */
export interface BatchOutcomeItem {
  name: null | string;
  outcome: 'ENROLL' | 'SKIP' | 'SUCCESS';
  patientId: string;
  patientNo: null | string;
  reason: null | string;
  wardName: null | string;
}

/** 批量结果（succeeded 语义：预检 = 将加入，执行 = 成功）。 */
export interface BatchResult {
  items: BatchOutcomeItem[];
  skipped: number;
  succeeded: number;
}

/** 责任医生候选（doctor-options；id / wardId 由后端按字符串口径下发）。 */
export interface DoctorOption {
  displayName: string;
  id: string;
  username: string;
  wardId: string;
  wardName: string;
}

export interface DoctorOptions {
  doctors: DoctorOption[];
}

/** 跳过 / 阻止原因码 → 界面文案（后端 reason 码表，docs/api/patient.md §2 / followup.md §1）。 */
export const BATCH_SKIP_REASON_TEXT: Record<string, string> = {
  ALREADY_ENROLLED: '已在组',
  CONFLICT: '状态刚发生变化，请刷新后重试',
  DEAD: '已死亡',
  DOCTOR_NOT_IN_WARD: '该医生不负责患者所在病区',
  NOT_FOUND: '患者不存在或不在您的数据范围内',
  REMOVED: '已从该计划移除',
  TERMINATED: '已终止随访',
  WARD_CHANGED: '患者病区刚发生变化，请刷新后重试',
};

/** 单条结果的展示文案：成功 / 将加入 → 结果文案；跳过 → 原因文案（码未知时原样显示）。 */
export function batchOutcomeText(item: BatchOutcomeItem): string {
  if (item.outcome !== 'SKIP') {
    return item.outcome === 'ENROLL' ? '将加入' : '成功';
  }
  return item.reason
    ? (BATCH_SKIP_REASON_TEXT[item.reason] ?? item.reason)
    : '跳过';
}

/** 按原因码汇总计数（结果面板「成功 18、跳过 2（已在组 2）」的分组口径）。 */
export function countSkipByReason(items: BatchOutcomeItem[]): Array<{
  count: number;
  reason: string;
  text: string;
}> {
  const counts = new Map<string, number>();
  for (const item of items) {
    if (item.outcome === 'SKIP' && item.reason) {
      counts.set(item.reason, (counts.get(item.reason) ?? 0) + 1);
    }
  }
  return [...counts.entries()].map(([reason, count]) => ({
    count,
    reason,
    text: BATCH_SKIP_REASON_TEXT[reason] ?? reason,
  }));
}

/** 医生下拉候选的展示名（姓名 · 病区：跨病区同名医生可区分）。 */
export function doctorOptionLabel(doctor: DoctorOption): string {
  return `${doctor.displayName} · ${doctor.wardName}`;
}

/** 按所选患者病区并集过滤候选（A-PAT-017「只能选该病区的医生」的前端预筛；后端仍逐条校验）。 */
export function filterDoctorsByWards(
  doctors: DoctorOption[],
  wardIds: Array<number | string>,
): DoctorOption[] {
  const wards = new Set(wardIds.map(String));
  return doctors.filter((doctor) => wards.has(doctor.wardId));
}
