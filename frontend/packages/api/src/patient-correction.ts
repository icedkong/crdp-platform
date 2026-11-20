import type { FormSchema } from './form';

/** OQR-04：登记值为实体适配数据，身份区仅脱敏值；不提供明文预填。 */
export interface PatientCorrectionView {
  data: Record<string, unknown>;
  identity: Record<string, null | string>;
  identitySchema: FormSchema;
  locked: boolean;
  patientId: string;
  schema: FormSchema;
  status: Record<string, unknown>;
  statusSchema: FormSchema;
  version: number;
}

export interface PatientCorrectionCommand {
  baseVersion: number;
  changes: Record<string, unknown>;
  confirmed?: boolean;
  reason: string;
}

/** 敏感新值只进入本次请求，空白输入不代表清除已有值。 */
export function identityCorrectionChanges(
  entered: Record<string, string>,
  cleared: string[] = [],
): Record<string, null | string> {
  const changes: Record<string, null | string> = Object.fromEntries(
    Object.entries(entered)
      .map(([key, value]) => [key, value.trim()])
      .filter(([, value]) => value),
  );
  for (const key of cleared) changes[key] = null;
  return changes;
}
