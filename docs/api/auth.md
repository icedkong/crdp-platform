# 认证、会话、菜单与权限码（auth）

> 后端：`crdp-server`（`HospitalAuthController`、`PlatformAuthController`、`AuthService`、`MenuQuery`）+ `crdp-framework/security`。
> 需求：PLT-AUTH-001..013、PLT-PERM-005/006/010、PLT-ROLE-006、NFR-SEC 相关。
> 页面：`docs/design/pages.md` §1（登录）、§0（菜单 / 路由）。

## 1. 仓库已有接口（契约已定，改动需评审）

| 方法 | 路径 | 权限 | 请求 | 响应 `data` |
|---|---|---|---|---|
| POST | `/api/hospital/auth/login` | 免登录 | `HospitalLoginRequest { tenantCode, username, password }` | `LoginResult { token, tokenType: "Bearer", expiresInSeconds }` |
| POST | `/api/hospital/auth/logout` | 登录 | — | 空 |
| GET | `/api/hospital/auth/me` | 登录 | — | `MeResponse { userId, username, displayName, sessionType, tenantCode, tenantName, roles[], permissionVersion }` |
| GET | `/api/hospital/auth/codes` | 登录 | — | `string[]` 权限码 |
| GET | `/api/hospital/menus` | 登录 | — | `MenuNode[]`（`id, name, path, component, type, meta, children`；`component` 为 `LAYOUT` 或 views 下相对路径） |
| POST | `/api/platform/auth/login` | 免登录 | `PlatformLoginRequest { username, password }` | `LoginResult` |
| POST | `/api/platform/auth/logout` | 登录 | — | 空 |
| GET | `/api/platform/auth/me` | 登录 | — | `MeResponse`（`tenantCode/tenantName` 为空） |
| GET | `/api/platform/auth/codes` | 登录 | — | `string[]` |
| GET | `/api/platform/menus` | 登录 | — | `MenuNode[]` |

字段校验（仓库已有）：

| 字段 | 规则 |
|---|---|
| `tenantCode` | `^[a-z][a-z0-9]{1,31}$`（只允许小写字母和数字） |
| `username` | `^[a-zA-Z][a-zA-Z0-9._-]{1,63}$` |
| `password` | 8–64 位 |

行为（仓库已有）：

- 租户不存在 / 已停用、账号不存在 / 停用、密码错误 → 统一 40101「租户、账号或密码不正确」（防探测，PLT-AUTH-004、PLT-AUTH-007）。
- 失败计数按「端 + 租户编码 + 账号」存 Redis，超过上限 42900；成功后清零。
- 租户数据源未注册 → 50002。
- 登录成功写登录日志（`auditLogger.loginAttempt`，PLT-AUD-002）。
- 有效菜单 = 角色授予的菜单 ∩ 已开通模块；权限码 = 有效菜单上的 `perm_code` 去重（PLT-ROLE-006）。**注意**：规格要求再与“平台允许分配范围”求交（PLT-SCOPE-001/002），目前仓库未实现，属于 E05/E06 任务。
- 会话为服务端 token（Redis `SessionStore`），不是 JWT；登出删除会话。
- 令牌为 256 位随机不透明值（base64url，43 字符），不含任何用户信息；每个请求按令牌查服务端会话，删除会话（登出或服务端吊销）后旧令牌下一次请求即 40100（E04-T02）。
- 会话链路的入口一致性（E04-T01 移交，E04-T02 实现）：配置了 `crdp.tenant.base-domain` 时，请求 Host 是 `{租户编码}.{平台域名}` 子域名的，会话必须是该租户的医院会话，否则按未认证处理（40100，不暴露差异）；IP、基域名等其余入口不约束。

## 2. 需要新增的接口（推断，待确认）

| 方法 | 路径 | 说明 | 需求 |
|---|---|---|---|
| GET | `/api/hospital/auth/tenant?code=` | 登录页识别租户，返回 `{ tenantName, logoUrl, loginMethods[], themeColor }`；租户不存在也返回通用默认值，不暴露存在性 | PLT-AUTH-004、PLT-CFG-008 |
| POST | `/api/hospital/auth/sms` | 发送登录验证码 `{ tenantCode, phone }`；60 秒冷却；无论号码是否存在均返回成功 | PLT-AUTH-002 |
| POST | `/api/hospital/auth/login/sms` | 验证码登录 `{ tenantCode, phone, code }` | PLT-AUTH-002 |
| POST | `/api/hospital/auth/2fa/verify` | 双因素第二步 `{ challengeId, code }`；`login` 在需要 2FA 时返回 `{ challengeId, methods[] }` 而不是 token | PLT-AUTH-003 |
| POST | `/api/hospital/auth/password` | 修改密码 `{ oldPassword, newPassword }`；首次登录强制改密时 `me` 返回 `mustChangePassword: true`，其他接口返回 40305（推断） | PLT-AUTH-006、PLT-AUTH-010 |
| POST | `/api/hospital/auth/password/forgot` / `.../reset` | 忘记密码（短信验证） | PLT-AUTH-011 |
| POST | `/api/hospital/auth/reauth` | 敏感操作二次认证，返回 5 分钟有效的 `reauthToken`，敏感接口通过请求头 `X-Reauth-Token` 携带；缺失时返回 40304 | PLT-AUTH-012 |
| GET | `/api/hospital/auth/sessions` | 我的在线会话 | PLT-AUTH-008 |
| DELETE | `/api/hospital/auth/sessions/{id}` | 下线自己的某个会话 | PLT-AUTH-008 |
| POST | `/api/hospital/system/sessions/{id}/kick` | 管理员强制下线（权限 `tenant:session:kick`，见 `system.md` §8） | PLT-AUTH-009 |
| GET | `/api/hospital/auth/features` | 当前租户功能开关 `{ [featureCode]: boolean }`；或把 `features` 并入 `me`（二选一，见 OQ-03） | 技术方案“各院功能不同怎么办” |
| — | 平台端 | `/api/platform/auth/password`、`/reauth`、`/sessions` 同构 | PLT-AUTH-005 |

### 2.1 `me` 建议扩展字段（推断，待确认）

```ts
interface MeResponseExt extends MeResponse {
  mustChangePassword?: boolean;
  features?: Record<string, boolean>;   // 若不单独提供 /auth/features
  wardIds?: number[];                    // 当前用户负责病区（仅用于默认筛选，不用于前端鉴权）
  tenantView?: { platformAccount: string; readonly: boolean; expiresAt: string }; // 平台人员进入租户视角时
  watermark: { text: string };           // “姓名（账号）· 医院名称”，前端补“预览时间（精确到分钟）”
}
```

### 2.2 权限变更生效（PLT-PERM-010）

- 角色、菜单、授权范围变化时，后端递增 `permissionVersion`；请求时发现会话里的版本落后，按新权限重新计算（或强制重新拉取）。
- 前端在收到 40300 或轮询 `me` 发现 `permissionVersion` 变化时，重新拉取 `codes` 与 `menus`。

## 3. 测试要点

- 三种失败返回同一 code 与 message；超过次数返回 42900。
- demoa 账号的 token 访问 `/api/platform/**` 返回 40300 / 40100（`requirePlatform` 失败），反之亦然。
- 未开通模块的菜单不出现在 `menus`，其权限码不出现在 `codes`。
- 登出后旧 token 访问返回 40100。
- 登录成功下发不透明令牌（非 JWT、两次登录不同），会话保存在服务端；登出 / 删除会话后旧令牌立即失效（`AuthServiceTest`、`SessionStoreTest`、`TokenAuthenticationFilterTest`）。
- 令牌租户与入口子域名不一致（demoa 的令牌用在 demob 入口、平台会话用在租户子域名）按未认证拒绝（`TokenAuthenticationFilterTest`）。
