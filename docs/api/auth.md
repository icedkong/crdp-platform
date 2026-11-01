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
| POST | `/api/hospital/auth/sms` | 免登录 | `HospitalSmsRequest { tenantCode, phone }`（phone `^1\d{10}$`） | 空（无论手机号是否已绑定均返回成功，防探测；60 秒冷却与每日 10 条超限返回 42900） |
| POST | `/api/hospital/auth/login/sms` | 免登录 | `HospitalSmsLoginRequest { tenantCode, phone, code }`（code 6 位数字） | `LoginResult`（短信验证码登录不叠加第二因素） |
| POST | `/api/hospital/auth/2fa/verify` | 免登录 | `TwoFactorVerifyRequest { challengeId, code }` | `LoginResult` |
| POST | `/api/platform/auth/2fa/verify` | 免登录 | `TwoFactorVerifyRequest { challengeId, code }` | `LoginResult` |
| GET | `/api/hospital/auth/captcha` | 免登录 | — | `CaptchaImage { captchaId, image }`（PNG data URL；按 IP 每小时限流 60 张） |
| GET | `/api/platform/auth/captcha` | 免登录 | — | `CaptchaImage`（同上，两端同构） |
| POST | `/api/hospital/auth/password` | 登录 | `ChangePasswordRequest { oldPassword, newPassword }` | 空（成功后本会话继续可用，其他会话全部失效） |
| POST | `/api/platform/auth/password` | 登录 | `ChangePasswordRequest` | 空（同上） |
| POST | `/api/hospital/auth/password/forgot` | 免登录 | `HospitalPasswordForgotRequest { tenantCode, username }` | 空（把重置验证码发到该账号**绑定手机号**；无论账号是否存在、是否绑定手机号均返回成功，防探测；60 秒冷却与每日上限超限 42900） |
| POST | `/api/hospital/auth/password/reset` | 免登录 | `HospitalPasswordResetRequest { tenantCode, username, code, newPassword }`（code 6 位数字；newPassword 8–64 位） | 空（验证码通过且新密码合规后重置；**该账号全部会话失效**，写审计 `USER_PASSWORD_RESET`） |
| POST | `/api/hospital/system/users/{id}/unlock` | `tenant:user:reset` | — | `{ userId, username, unlocked: true }`（管理员解锁登录锁定） |
| GET | `/api/hospital/auth/sessions` | 登录 | — | `OnlineSession[] { sessionId, userId, username, displayName, ip, issuedAt, lastActiveAt, current }`（本人在线会话；`sessionId` 由令牌哈希单向推导，不返回令牌） |
| DELETE | `/api/hospital/auth/sessions/{sessionId}` | 登录 | — | `OnlineSession`（下线自己的某个会话；被下线令牌下次请求 40100；写审计 `SESSION_REVOKE`） |
| GET | `/api/hospital/auth/tenant-state` | 登录 | — | `TenantStateView { tenantId, code, name, status, expireType, expireAt, daysLeft, graceReadOnly }`（本院服务状态，宽限期续费提示的数据源；`daysLeft` 含到期当天为 0，已到期或长期有效为 null。E05-T03 / PLT-TEN-012） |
| GET | `/api/hospital/auth/tenant?code=` | 免登录 | — | `TenantBrandView { tenantName, logoUrl, loginBgUrl, themeColor, themeColorHex, copyright, identified, loginMethods[] }`（登录页租户识别：显示名称取本院个性化 `cfg_brand.display_name`，未配置回落平台租户名称；租户不存在 / 未启用也返回通用默认值，不暴露存在性；公开口按 IP 每小时限流，超限 42900。E06-T11 / PLT-CFG-008、PLT-AUTH-004） |
| GET | `/api/hospital/system/sessions` | `tenant:session:view` | — | `OnlineSession[]`（本院全部在线会话，`current` 恒 false；见 `system.md` §8） |
| POST | `/api/hospital/system/sessions/{sessionId}/kick` | `tenant:session:kick` | — | `OnlineSession`（强制下线，写审计 `SESSION_KICK`；被下线令牌下次请求 40100） |
| POST | `/api/hospital/auth/reauth` | 登录 | `ReauthRequest { password }`（8–64 位） | `ReauthResult { reauthToken, expiresInSeconds }`（E04-T08 已实现，两端同构；见下方「敏感操作二次认证」） |
| POST | `/api/platform/auth/reauth` | 登录 | `ReauthRequest` | `ReauthResult`（平台端同构） |
| GET | `/api/platform/auth/sessions` | 登录 | — | `OnlineSession[]`（平台端同构） |
| DELETE | `/api/platform/auth/sessions/{sessionId}` | 登录 | — | `OnlineSession`（平台端同构） |

字段校验（仓库已有）：

| 字段 | 规则 |
|---|---|
| `tenantCode` | `^[a-z][a-z0-9]{1,31}$`（只允许小写字母和数字） |
| `username` | `^[a-zA-Z][a-zA-Z0-9._-]{1,63}$` |
| `password` | 8–64 位 |
| `captchaId` / `captchaCode` | 可选；连续失败 3 次后必填（图形验证码 4 位，一次一密） |

行为（仓库已有）：

- 租户不存在 / 已停用、账号不存在 / 停用、密码错误 → 统一 40101「租户、账号或密码不正确」（防探测，PLT-AUTH-004、PLT-AUTH-007）。
- 失败计数按「端 + 租户编码 + 账号」存 Redis，超过上限 42900；成功后清零。
- 租户数据源未注册 → 50002。
- 登录成功写登录日志（`auditLogger.loginAttempt`，PLT-AUD-002）。
- 有效菜单 = 用户全部（启用）角色授权的并集 ∩ 已开通模块 ∩ 平台允许租户管理员分配的范围（PLT-ROLE-006；三层交集集中在 `EffectivePermissionService`，E06-T06 起）；权限码 = 有效菜单上的 `perm_code` 去重。平台账号不受租户可分配范围限制，模块取平台库已登记全集。多角色的数据范围同样取并集（任一角色为 ALL 即不过滤，`ScopeSnapshot`）。
- 会话为服务端 token（Redis `SessionStore`），不是 JWT；登出删除会话。
- 令牌为 256 位随机不透明值（base64url，43 字符），不含任何用户信息；每个请求按令牌查服务端会话，删除会话（登出或服务端吊销）后旧令牌下一次请求即 40100（E04-T02）。
- 会话链路的入口一致性（E04-T01 移交，E04-T02 实现）：配置了 `crdp.tenant.base-domain` 时，请求 Host 是 `{租户编码}.{平台域名}` 子域名的，会话必须是该租户的医院会话，否则按未认证处理（40100，不暴露差异）；IP、基域名等其余入口不约束。
- 短信验证码与双因素（E04-T04 / PLT-AUTH-002、003）：
  - 验证码 6 位数字、**5 分钟有效**（`crdp.security.sms-code-ttl-seconds`）、输错 5 次作废；重发间隔 60 秒、同一手机号每日 10 条（Redis 只存验证码 SHA-256 摘要）。
  - 验证码按「租户编码 + 手机号」隔离，A 院的码不能在 B 院用；验证码错误 / 过期 / 被作废统一 **40102**「验证码错误或已过期」。
  - 短信登录：手机号需已绑定且本院唯一；先验证码后查账号，未绑定手机号与凭据失败同口径 **40101**。短信登录本身即「持有手机」凭据，不再叠加第二因素。
  - 双因素：平台账号按配置强制（`crdp.security.platform-two-factor-enabled`，默认 true；短信通道 E09-T03 接入前 local/dev 关闭以保证演示可用）；医院端按租户 `cfg_param` 的 `security.two-factor.roles`（逗号分隔角色编码）配置。密码第一步通过后 `login` 返回 `{ challengeId, methods: ["SMS"] }`（token 为 null），challengeId 5 分钟有效、一次性，向账号绑定手机号自动发送验证码；第二步 `POST /auth/2fa/verify` 通过后换会话令牌。challenge 缺失 / 过期 / 已用返回 **40100**。
  - 账号绑定手机号信封加密存储（租户库 `sys_user.phone` purpose=`sys-user-phone`、平台库 `plt_account.phone` purpose=`plt-account-phone`），登录按盲索引 `phone_idx` 查询；审计与日志只落脱敏手机号（`139****0001`）。短信发送通道为占位（E09-T03 接腾讯云短信），未接入时发送动作报 50000。
  - 短信登录失败计数按「HS:租户编码:手机号」分桶，上限同密码登录（42900），成功后清零。
- 图形验证码与失败锁定（E04-T05 / PLT-AUTH-001、007，等保参数表）：
  - 密码登录（两端）连续失败 **3 次** 后必须携带图形验证码（`GET /auth/captcha` 取图，`captchaId + captchaCode` 随登录提交）；验证码 4 位、5 分钟有效、**一次一密**（无论对错验证即作废，前端重新取图）；错误 / 过期 / 缺失统一 **40102**，且计入失败（防暴力尝试），不区分口令对错。
  - 连续失败 **5 次锁定 30 分钟**（`crdp.security.login-lock-seconds`），期间一律 **42900**，`data: { lockedSeconds }`；成功登录清零，管理员可提前解锁（`POST /api/hospital/system/users/{id}/unlock`，权限 `tenant:user:reset`，平台账号暂无解锁界面，靠 30 分钟自动解锁，见 OQ-39）。
  - 40101 的 `data` 恒带 `{ captchaRequired, remainingAttempts }`（未知账号同样计数、同样提示，不外露存在性）；`message` 保持统一「租户、账号或密码不正确」。
- 密码策略与首次登录改密（E04-T05 / PLT-AUTH-006、010）：
  - 平台下限：长度 ≥ 8（租户经 `cfg_param` `security.password.min-length` 只能加严）、大写 / 小写 / 数字 / 特殊字符至少 3 类、不得包含账号、有效期 **90 天**（到期前 7 天 `me.passwordExpiresInDays` 提醒，到期强制改密）、不得与最近 **5** 次密码相同（`sys_password_history` / `plt_password_history` 只追加）。
  - 初始密码 / 管理员重置（开通向导 `must_change_password=1`）/ 到期密码：登录正常发令牌，但 `me.mustChangePassword = true`，**除 `POST /auth/password`、`POST /auth/logout`、`GET /auth/me` 外一律 40305**（`MustChangePasswordFilter`）；双因素第二步换发的令牌保留该标记。
  - `POST /auth/password`：原密码不对 → 42200「原密码不正确」；新密码不满足策略 → 42200（message 列明全部不满足项，`data.fieldErrors[]`）；与最近 5 次相同 → 42200。成功后：清标记、刷新 `password_updated_at`、旧哈希入历史表、**该账号其他会话全部失效**（当前会话保留并解除 40305）、清登录失败计数、写审计（`USER_PASSWORD_CHANGE`，不落新旧口令）。
- 会话生命周期与并发策略（E04-T06 / PLT-AUTH-008，等保参数表）：
  - **空闲超时**：医院端 30 分钟（`crdp.security.session-idle-seconds`）、平台端 15 分钟（`platform-session-idle-seconds`），每个已认证请求滑动续期；租户可经 cfg_param `security.session.idle-minutes` 在平台默认之下加严（登录时快照进会话）。超时未活跃 → 会话删除，40100。
  - **绝对会话时长**：12 小时（`crdp.security.session-absolute-seconds`），从登录起算不随活跃滑动，到时重新登录（40100）。
  - **并发会话**：默认同一账号多端共存；租户配置 cfg_param `security.session.single-login=true` 后单端登录，新登录使旧会话全部下线（旧令牌下次请求 40100）。平台端默认多端（平台安全策略开关随 E06+）。
  - 登录响应 `expiresInSeconds` = 剩余空闲窗口（约 30 / 15 分钟）。
  - 修改或重置密码后其他会话全部失效（E04-T05，`revokeAllForUser`，当前会话保留）。
- 在线会话管理（E04-T06 / PLT-AUTH-009）：`sessionId` = 令牌 SHA-256 前 16 个十六进制字符（单向，接口绝不返回令牌）；管理员按本院在线列表 + 会话号强制下线（`SESSION_KICK` 审计），本人可查看并下线自己的会话（`SESSION_REVOKE` 审计）；不存在的会话号统一 40400。
- 权限变更生效（E04-T06 / PLT-PERM-010；E06-T06 起授权用例全覆盖）：会话带登录时的权限版本号（初始 1）。授权用例（角色授权 / 成员 / 状态、用户角色与负责病区科室、可分配范围收回、模块开通关闭、平台菜单停用与删除）变更后调用 `PermissionVersionStore.bump(端别, 租户, 用户)` 递增受影响用户版本——医院端菜单停用 / 启用 / 删除同样逐租户递增「经启用角色持有该菜单」的用户（租户库不可达时跳过、下次同步收敛，OQ-46 ③ 同口径）。请求时 `TokenAuthenticationFilter` 发现会话版本落后：按库里的最新授权**重算会话**（令牌不变、菜单 / 权限码 / 数据范围 / 账号状态取新值，`SessionPermissionRefresher` + `EffectivePermissionService`）后继续执行——旧令牌里的权限快照自此失效；若账号已停用 / 不存在，会话删除、40100。前端依据 `me.permissionVersion` 变化或收到 40300 重新拉取 `codes` 与 `menus`（后端 E04-T06；前端刷新已在 E06-T01 接入：收到 40300 / 40301 或 `me.permissionVersion` 变化时重拉 `me + codes + menus` 并重建路由，40302 不触发——它是后端未声明数据范围的缺陷）。
- 端别隔离（E04-T03 / PLT-AUTH-005）：`/api/platform/**` 只接受平台会话，`/api/hospital/**` 只接受医院会话。`TokenAuthenticationFilter` 认证时按会话端别附加 `SESSION_PLATFORM` / `SESSION_HOSPITAL` 权限，`SecurityConfig` 的路径规则据此把跨端令牌统一拒绝为 40300，不依赖各控制器自行调用 `require*` 系列；控制器层 `requirePlatform` / `requireHospital`（40300）保留为内层防线。登录层面两端账号分库校验：平台账号只存于平台库 `plt_account`，医院账号只存于各租户库，互不能在对方端登录（失败同样统一 40101）。
- 忘记密码（E04-T07 / PLT-AUTH-011，医院端；平台端见 OQ-41）：
  - 第一步 `/auth/password/forgot`：重置验证码发到该账号**绑定手机号**（不是请求里的任意手机号）；未知租户 / 未知账号 / 停用账号 / 未绑定手机号一律**静默成功**（不触达短信通道）。未知账号同样消耗 60 秒冷却位（探针键），使「短时间内第二次请求是否 42900」与真实账号不可区分；冷却与每日上限同登录验证码（同一手机号 60 秒 / 每日 10 条，超限 42900）。
  - 重置验证码与登录验证码**分命名空间**（Redis `reset:租户:手机号` vs `login:租户:手机号`）：钓鱼拿到的登录验证码不能重置密码；A 院的码同样不能在 B 院用。
  - 第二步 `/auth/password/reset`：未知租户 / 未知账号 / 停用账号 / 未绑定手机号 / 验证码错误 / 过期统一 **40102**（不区分存在性）；重置尝试按「HR:租户编码:账号」分桶计数，连续失败达上限 42900（连续多次锁定 30 分钟），成功后清零。校验顺序：失败计数 → 账号（统一 40102）→ 验证码（一次一用）→ 密码策略（42200，列明全部不满足项）→ 历史密码（不得与最近 5 次相同，42200）；策略与历史的 42200 只在验证码通过后给出，避免借「弱密码是否被报 42200」探测账号存在——代价是策略不达标也会消耗验证码，需重新获取。
  - 重置成功：落新哈希、旧哈希入 `sys_password_history`、**该账号全部会话失效**（免登录流程没有「当前会话」可保留）、清登录失败计数、写审计 `USER_PASSWORD_RESET`（不落新旧口令与哈希）；被拒绝的重置尝试写登录日志（`aud_login_log`，message=`password-reset-rejected`）。用户自选新密码已满足策略，**不**置「首次登录强制改密」标记（该标记用于管理员下发的随机密码）。
- 敏感操作二次认证（E04-T08 / PLT-AUTH-012，两端同构；`crdp.security.reauth-token-ttl-seconds`，默认 300 秒）：
  - 敏感接口在 Controller 方法上标注 `@RequireReauth`（拦截器 `ReauthInterceptor`），请求须携带请求头 **`X-Reauth-Token`**：缺失、过期（Redis TTL 到期）或**换个会话携带**（令牌绑定签发会话的会话号）一律 **40304**「敏感操作需二次认证，请重新验证身份」，并写 result=DENIED 操作日志（audit.md §1，与 40300 同口径）。校验顺序：权限码（40300）在前、二次认证（40304）在后。
  - `POST /auth/reauth`：会话内用密码再次验证（医院端在本院库、平台端在平台库），通过签发 reauthToken（256 位随机不透明值，与登录令牌同规格）并返回 `expiresInSeconds`；窗口内**可多次使用**（导出提交后紧接着下载），不做一次性消费。登出 / 被下线 / 重新登录后旧 reauthToken 自然失效（绑定会话号，无需显式吊销）。
  - 防爆破：密码错误与账号中途消失统一 **42200**「密码不正确」（`data.remainingAttempts`），失败计入**与登录同一只失败桶**（`H:租户:账号` / `P:账号`）——连续失败达上限同样 42900 锁 30 分钟且波及登录本身；验证通过清零计数。通过 / 失败各写一条登录日志（`action=REAUTH`、`loginMethod=PASSWORD`，PLT-AUD-002 口径），不落口令材料。
  - 已接入：审计导出提交与下载（两端，audit.md §2/§3）、重置用户密码（`system.md` §2）与重置平台账号密码（`platform.md`）；患者明文查看接口随 E07-T05 落地时接入（见 OQ-57）。前端 `ReauthModal`（两端）统一弹密码框、令牌只存内存（5 分钟窗口复用，收到 40304 自动作废重验一次，取消静默中止）。

## 2. 需要新增的接口（推断，待确认）

<!-- E04-T04 已实现并移至第 1 节：/auth/sms、/auth/login/sms、/auth/2fa/verify（两端） -->
<!-- E04-T05 已实现并移至第 1 节：/auth/captcha（两端）、/auth/password（两端）、/system/users/{id}/unlock -->
<!-- E04-T07 已实现并移至第 1 节：/auth/password/forgot、/auth/password/reset（医院端；平台端忘记密码暂不做，见 OQ-41） -->
<!-- E06-T11 已实现并移至第 1 节：/auth/tenant?code=（登录页租户识别，PLT-CFG-008、PLT-AUTH-004） -->
<!-- E04-T08 已实现并移至第 1 节：/auth/reauth（两端，敏感操作二次认证 PLT-AUTH-012，X-Reauth-Token / 40304） -->
| GET | `/api/hospital/auth/features` | 当前租户功能开关 `{ [featureCode]: boolean }`；或把 `features` 并入 `me`（二选一，见 OQ-03） | 技术方案“各院功能不同怎么办” |

### 2.1 `me` 建议扩展字段（推断，待确认）

```ts
// E04-T05 已实现：mustChangePassword 恒返回；passwordExpiresInDays 仅在到期前 7 天窗口内返回（可为负）
interface MeResponseExt extends MeResponse {
  mustChangePassword: boolean;
  passwordExpiresInDays?: number;
  features?: Record<string, boolean>;   // 若不单独提供 /auth/features
  wardIds?: number[];                    // 当前用户负责病区（仅用于默认筛选，不用于前端鉴权）
  tenantView?: { platformAccount: string; readonly: boolean; expiresAt: string }; // 平台人员进入租户视角时
  watermark: { text: string };           // “姓名（账号）· 医院名称”，前端补“预览时间（精确到分钟）”
}
```

### 2.2 权限变更生效（PLT-PERM-010）——已在 E04-T06 实现，行为见第 1 节

- 角色、菜单、授权范围变化时，授权用例递增 `permissionVersion`（`PermissionVersionStore`，Redis `crdp:permver:*`）；请求时发现会话里的版本落后，后端按新权限重新计算会话后放行（令牌不变，旧权限快照失效）。
- 前端在收到 40300 或轮询 `me` 发现 `permissionVersion` 变化时，重新拉取 `codes` 与 `menus`（E06-T01 已接入：收到 40300 / 40301 或 `me.permissionVersion` 变化时重拉并重建路由；40302 不触发）。

## 3. 测试要点

- 三种失败返回同一 code 与 message；超过次数返回 42900。
- 医院端账号的 token 访问 `/api/platform/**` 返回 40300（`SecurityConfig` 端别路径规则），平台端 token 访问 `/api/hospital/**` 反之亦然（E04-T03；`TenantIsolationAcceptanceIT`、`ProvisionedTenantIsolationIT` 覆盖）。
- 平台账号在医院端登录、医院账号在平台端登录均统一 40101；两端的失败计数按「端 + 账号」分桶，互不影响（E04-T03，`AuthServiceTest`）。
- 未开通模块的菜单不出现在 `menus`，其权限码不出现在 `codes`。
- 登出后旧 token 访问返回 40100。
- 登录成功下发不透明令牌（非 JWT、两次登录不同），会话保存在服务端；登出 / 删除会话后旧令牌立即失效（`AuthServiceTest`、`SessionStoreTest`、`TokenAuthenticationFilterTest`）。
- 令牌租户与入口子域名不一致（demoa 的令牌用在 demob 入口、平台会话用在租户子域名）按未认证拒绝（`TokenAuthenticationFilterTest`）。
- 短信验证码 5 分钟有效、60 秒冷却、每日 10 条、输错 5 次作废（`SmsCodeStoreTest`）；短信登录与租户隔离、双因素按角色配置与平台强制、challenge 一次性（`AuthServiceTest`、`TwoFactorChallengeStoreTest`）；端到端走 HTTP 的用例在 `SmsAndTwoFactorIT`（CI 起 docker compose 后运行）。
- E04-T05：密码策略（`PasswordPolicyTest`）、图形验证码一次一密与限流（`CaptchaStoreTest`）、3 次验证码 / 5 次锁定 / 解锁（`LoginRateLimiterTest`、`AuthServiceTest`）、强制改密标记随会话与双因素保留（`AuthServiceTest`）、40305 放行清单（`MustChangePasswordFilterTest`）、改密校验顺序与会话处理（`PasswordChangeServiceTest`）；端到端（验证码→锁定→管理员解锁、初始密码强制改密、改密后其他会话失效、密码到期、平台端镜像）在 `PasswordAndLockoutIT`。前端策略提示与失败 data 解析在 `packages/api` 的 `password-policy.spec.ts`、`login-rejection.spec.ts`。
- E04-T06：空闲 / 绝对超时与滑动续期、两级索引与踢线、权限版本重算保留令牌（`SessionStoreTest`、`PermissionVersionStoreTest`）；过滤器版本落后重算 / 账号失效删会话（`TokenAuthenticationFilterTest`）；在线列表、强制下线、本人会话与 40400 / 40300（`SessionAdminServiceTest`）；单端登录、IP 与空闲窗口、登录带当前版本（`AuthServiceTest`）；端到端（在线用户→强制下线→旧令牌 40100、改权限 + 递增版本→旧令牌下次请求即按新权限执行、单端登录、平台端本人会话）在 `SessionAdminAndPermissionVersionIT`（CRDP_IT_MYSQL_PORT 存在时运行）。
- E04-T07：发码到绑定手机号与防探测静默（`PasswordResetServiceTest`）、未知账号 / 停用 / 未绑定与验证码错误统一 40102、登录验证码不能重置密码（命名空间隔离）、验证码一次一用、策略与历史密码校验、全部会话吊销、审计不含口令材料；端到端（忘记密码→重置→旧密码 40101 / 新密码登录 / 旧令牌 40100、未知账号冷却行为一致、弱密码 42200 且验证码被消耗、登录码不可重置）在 `ForgotPasswordIT`（CRDP_IT_MYSQL_PORT 存在时运行）。前端步骤门控（获取验证码 / 提交重置）在 `apps/web-hospital` 的 `forgot-password-flow.spec.ts`。
- E04-T08（敏感操作二次认证）：令牌签发与会话绑定、TTL 取配置、缺失 / 伪造 / 换会话一律无效（`ReauthStoreTest`）；密码错误 42200 带剩余次数并计入登录失败桶、锁定期间正确密码也 42900、账号中途消失同口径拒绝、通过写 REAUTH 登录日志（`ReauthServiceTest`）；拦截器对 `@RequireReauth` 方法缺头 40304 + DENIED 审计、有效头放行、未标注方法与匿名请求不受影响、权限码在前（`ReauthInterceptorTest`）；端到端（错误密码→正确密码签发、导出提交 / 下载 / 重置密码缺头 40304、DENIED 审计落库、同窗口复用、跨会话携带无效、平台端同构）在 `ReauthFlowIT`（CRDP_IT_MYSQL_PORT 存在时运行）。前端内存令牌（窗口 / 过期 / 作废）、40304 识别、重验一次与取消中止在 `packages/api` 的 `reauth.spec.ts`。
- E04-T09（认证安全加固用例，攻击者视角端到端，`AuthSecurityHardeningIT`，CRDP_IT_MYSQL_PORT 存在时运行）：
  - **爆破**：口令爆破完整阶梯（3 次验证码墙 → 5 次锁定，锁定期间正确口令也不放行，桶按账号隔离不影响同院他人）；同一攻击序列打真实账号与未知账号，每次响应 code / message / data 全等（`lockedSeconds` 随时间递减除外，非存在性信号）；短信登录验证码 / 双因素验证码 / 重置验证码连续输错 5 次即作废（真码也登不进来，6 位码不可穷举）；重置入口连续失败按「HR:租户:账号」锁定 42900；取图接口按 IP 每小时 60 张限流。
  - **令牌泄露**：令牌不透明（两次登录不同、base64url 43 字符非 JWT、不携带账号 / 租户材料）；`me` / `codes` / `menus` / `sessions` 任何响应不回显令牌，会话号是 16 位十六进制单向摘要；本人按会话号吊销被窃会话后，被窃令牌下次请求 40100。
  - **重放**：短信验证码、双因素 challenge、重置验证码一次一用——成功后重放分别 40102 / 40100 / 40102，已签发会话不受影响。
  - 测试客户端注意：测试类路径的 Apache HttpClient 5 收到 429 会自动重发请求，把「验证码作废 42900」掩盖成 40102；本用例在 `@BeforeEach` 里对 TestRestTemplate `disableAutomaticRetries`，看服务端第一个响应。图形验证码「一次一密」组件级用例在 `CaptchaStoreTest`（端到端读不出图片里的码）；登出重放与跨端 / 跨子域令牌在 `TenantIsolationAcceptanceIT`。
- E04-T10（认证、会话与锁定接口用例，全部 CRDP_IT_MYSQL_PORT 存在时运行）：
  - `AuthApiContractIT`（PLT-AUTH-001）：登录成功契约（tokenType / expiresInSeconds≈剩余空闲窗口）、me / codes / menus 字段与去重口径、参数校验失败 40001（message 为「字段 原因」）、未知租户 / 未知账号 / 停用账号 / 口令错误（两端）统一 40101 且 data 恒带 `{ captchaRequired, remainingAttempts }`、成功登录清零失败计数、无 / 伪造令牌 40100、登出即失效且重复登出 40100、解锁接口无权限 40300 与越租户 / 不存在 40400（注：两演示租户种子相同、同账号同 id，越租户负例须用他院独有 id）。
  - `SessionIdleTimeoutIT`（PLT-AUTH-008，idle 压到 3 秒）：expiresInSeconds 即空闲窗口、空闲超时会话删除 40100、每个已认证请求滑动续期（持续活跃活得过初始窗口、停止后按最后活跃计时）、平台端独立计超时。
  - `SessionAbsoluteTimeoutIT`（PLT-AUTH-008，absolute 压到 6 秒、idle 放宽）：绝对时长从登录起算不随活跃滑动，持续活跃的会话到点仍 40100，须重新登录。
  - `LoginLockoutAutoUnlockIT`（PLT-AUTH-007，lock 压到 3 秒）：锁定只作用于「端 + 租户 + 账号」桶——demoa 锁死不影响 demob 同名账号（双租户负例）与医院端 / 平台端互不影响；第 5 次失败本身仍按前序口径拒绝（40102），锁定在其后的下一次请求生效 42900；锁定期满自动解锁（OQ-39：平台账号暂无解锁界面，靠的就是这条）。
