/**
 * E04-T06 / PLT-AUTH-008 在线会话视图（E06-T11 个人中心「在线会话」页签接入）。
 * 与后端 `SessionAdminService.OnlineSessionView` 同构；接口不返回令牌，sessionId 是令牌哈希单向推导的会话号。
 */
export interface OnlineSessionView {
  /** 是否当前会话（本人列表返回；下线自己其他会话用）。 */
  current: boolean;
  displayName: string;
  ip: null | string;
  issuedAt: string;
  lastActiveAt: string;
  sessionId: string;
  userId: number;
  username: string;
}
