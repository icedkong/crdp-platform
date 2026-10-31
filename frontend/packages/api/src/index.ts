export type SessionMode = 'hospital' | 'platform';

export interface ApiResponse<T> {
  code: number;
  data: T;
  message: string;
  traceId: string;
}

export interface LoginResult {
  expiresInSeconds: number;
  token: string;
  tokenType: string;
}

export interface Me {
  displayName: string;
  permissionVersion: number;
  roles: string[];
  sessionType: 'HOSPITAL' | 'PLATFORM';
  tenantCode: null | string;
  tenantName: null | string;
  userId: number;
  username: string;
}

export interface MenuMeta {
  hideInMenu: boolean;
  icon?: null | string;
  order: number;
  permission?: null | string;
  title: string;
}

export interface MenuNode {
  children: MenuNode[];
  component: string;
  id: number;
  meta: MenuMeta;
  name: string;
  path: string;
  type: 'BUTTON' | 'DIR' | 'MENU';
}

export interface Hello {
  database: string;
  message: string;
  tenantCode: string;
  tenantName: string;
}

export function authBase(mode: SessionMode) {
  return mode === 'hospital' ? '/api/hospital' : '/api/platform';
}

export { menuNodesToRoutes, menuNodeToRoute, toIconifyIcon } from './vben-menu';
export type { VbenMenuRoute } from './vben-menu';
