import type {
  PatientCorrectionCommand,
  PatientCorrectionView,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

export function fetchRegistration(id: string) {
  return requestClient.get<PatientCorrectionView>(
    apiPath(`/followup/patients/${id}/registration`),
  );
}

export function correctRegistration(
  id: string,
  body: PatientCorrectionCommand,
  identity = false,
) {
  return requestClient.put<PatientCorrectionView>(
    apiPath(`/followup/patients/${id}${identity ? '/identity' : ''}`),
    body,
  );
}

export interface RegistrationHistory {
  id: string;
  fieldKey: string;
  newValue: unknown;
  oldValue: unknown;
  operatorName: string;
  reason: null | string;
  changedAt: string;
}

export function fetchRegistrationHistory(id: string) {
  return requestClient.get<RegistrationHistory[]>(
    apiPath(`/followup/patients/${id}/registration/history`),
  );
}
