export type SessionMode = 'hospital' | 'platform'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresInSeconds: number
}

export interface Me {
  userId: number
  username: string
  displayName: string
  sessionType: 'HOSPITAL' | 'PLATFORM'
  tenantCode: string | null
  tenantName: string | null
  roles: string[]
  permissionVersion: number
}

export interface MenuMeta {
  title: string
  icon?: string | null
  order: number
  hideInMenu: boolean
  permission?: string | null
}

export interface MenuNode {
  id: number
  name: string
  path: string
  component: string
  type: 'DIR' | 'MENU' | 'BUTTON'
  meta: MenuMeta
  children: MenuNode[]
}

export interface Hello {
  tenantCode: string
  tenantName: string
  database: string
  message: string
}

export function authBase(mode: SessionMode) {
  return mode === 'hospital' ? '/api/hospital' : '/api/platform'
}
