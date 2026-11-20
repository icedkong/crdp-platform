import type { StatsDrillPage, StatsDrillQuery } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

export function fetchStatsDrill(query: StatsDrillQuery) {
  return requestClient.post<StatsDrillPage>(
    apiPath('/followup/stats/drill'),
    query,
  );
}
