# 通知：站内信、短信、Webhook（notify）

> 后端：`crdp-followup`（通知子域，事件来自 outbox）+ 平台短信通道（PLT-CFG-005）。表：`ntf_rule`、`ntf_message`（站内信）、`ntf_send_log`、`ntf_user_pref`、`ntf_setting`（迁移 V23）、`sys_webhook`。
> 页面：顶栏铃铛（components.md AppHeader）、`/system/notice`（通知设置：事件规则 / 发送设置 / 发送记录三页签）、`/profile`（通知偏好）、`/system/settings`（Webhook）。
> §1 已随 E09-T02 落地；§2 通知事件与规则、接收人规则已随 E09-T04 落地（settings 接口随 E09-T07）；§3.1 短信通道与 §3.2 回执回调已随 E09-T03 落地；§3 汇总作业（每日汇总、免打扰、频控）已随 E09-T05 落地；§4 发送记录与 §5 个人通知偏好已随 E09-T07 落地；§7 含 E09-T08 通知链路验收 IT（其余章节路径 **推断，待确认**）。需求：A-NTF-001…008、C-NTF-001…008。**A-NTF-009 通知患者首期不做**：任何通知只发医生、随访员、护士。

## 1. 站内信（A-NTF-002、C-NTF-002；E09-T02 已实现）

表：租户库 `ntf_message`（迁移 V21）——`recipient_id`（sys_user）+ `event_type/title/summary` + `patient_id` + `patient_name_cipher`（信封加密，purpose=`ntf-patient-name`）+ `target_type/target_id` + `dedup_key`（唯一键，outbox 至少一次语义下重复投递不重发）+ `read_flag/read_at`。投递入口 `NoticeInboxService.deliver(...)`：E09-T04 通知规则引擎经 outbox 分发器调用，租户上下文内执行。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/notify/messages?unread=&pageNo=&pageSize=` | 登录 | 本人站内信（id 倒序，pageNo 从 1、pageSize 默认 20 上限 100，越界 40001）；行 `{ id, eventType, title, summary, patientId, patientName, target: { type: TASK\|RECORD\|PATIENT, id }, read, createdAt }`；无值字段不出现 |
| GET | `/api/hospital/notify/messages/unread-count` | 登录 | `{ unread }` 铃铛角标，前端 60s 轮询（页面可见时也刷新，tokens `notifyPollMs`） |
| POST | `/api/hospital/notify/messages/{id}/read` | 登录 | 标一条已读（幂等）；别人的消息 / 不存在一律 **40400** 不暴露存在，并写 `NOTICE_INBOX_READ` DENIED 操作审计 |
| POST | `/api/hospital/notify/messages/read-all` | 登录 | `{ marked }` 只影响本人未读行 |

患者姓名**读取时**解密并按接收人当前字段策略脱敏（`@MaskedByPolicy`，HIDDEN 整字段不出现），落库为密文、不写死脱敏值。收件箱按 recipient 隔离（ntf_message 不在 ControlledTables）；点击跳转由前端按 target 路由（TASK→`/followup/task?taskId=`、PATIENT→`/followup/patient?patientId=`、RECORD→`/followup/record/{id}` 随 E15 隐藏菜单），**落点接口按数据范围再校验**（越范围 40400，fu_task 已登记 ControlledTables，接口随 E14/E15 落地）。平台侧 INBOX 派发行（OQ-44 / OQ-58）的消费与平台端收件箱随 E09-T03/T07 接入（见 OQ-59）。

## 2. 通知事件与规则、接收人规则（A-NTF-001、A-NTF-004、C-NTF-001、C-NTF-004；E09-T04 已实现）

表：租户库 `ntf_rule`（迁移 V22，逐事件一行 + 目录默认值种子）——`event_type`（唯一）+ `enabled` + `advance_days` + `send_time` + `channels`（CSV：`INBOX,SMS`）+ `recipients`（CSV：`OWNER,WARD_STAFF,CC_WARD_LEADER`）+ `sms_template_code` + `updated_by/updated_at`；发送台账 `ntf_send_log`（同迁移）——`event_type + recipient_id + channel + send_status(QUEUED/SENT/FAILED/SKIPPED) + title/detail + dedup_key(outbox 事件 ID+接收人+渠道，唯一键) + sent_at`（查询 / 重试接口随 E09-T07）。`ward.default_owner_id`（A-WARD-001 默认随访负责人）同迁移物化，逾期抄送按它解析（OQ-60），维护界面随 E13-T08。

**事件目录**（`NotifyEventType`，code 即 outbox 事件类型、`ntf_rule.event_type`、`ntf_message.event_type` 三处同口径；A / C 差异——C 首期无窗口预告——由本院停用对应事件实现，不写租户分支）：`WINDOW_PREVIEW`(窗口预告，默认提前 7 天) / `TASK_OPENED`(任务开启=提醒开始，08:30) / `WINDOW_OPEN`(窗口开启，08:30；与提醒开始同天由事件产生方合并) / `DUE_SOON`(即将逾期，默认提前 2 天) / `TASK_OVERDUE`(已逾期，站内信+短信，可抄送病区负责人) / `DAILY_DIGEST`(每日汇总，站内信+短信，接收人由 E09-T05 作业按待办随负载下发) / `TASK_REASSIGNED`(任务改派，即时，接收人=新负责人) / `CALLBACK_DUE`(约定回访到时，即时，接收人=记录人) / `UNREACHED_FOLLOWUP`(未联系到跟进) / `LOST_CANDIDATE`(失访候选)。`advanceDays / sendTime` 描述触发时机，由事件产生方（E14 每日调度）与汇总作业（E09-T05）读取；规则引擎在事件到达时即时投递。

**事件负载契约**（发布方在业务事务内写 `sys_outbox`，信封同 system.md §9：`tenantCode/operatorId/occurredAt`，未知字段忽略）：`taskId / ownerId`（TASK_REASSIGNED=新负责人、CALLBACK_DUE=记录人）`/ wardId / patientId / patientName（只落站内信密文列，短信与台账永不落患者信息）/ nodeName / windowStartDate / windowEndDate / reason / recipientIds`（显式接收人清单，出现时优先于规则解析，DAILY_DIGEST 使用）。

**规则引擎**（`NotifyEventSubscriber implements OutboxSubscriber`，在事件所属租户上下文内消费，至少一次语义 → `ntf_message` 与 `ntf_send_log` 双 dedup 幂等；负载非法 JSON 抛错交分发器退避重试）：事件停用 → 跳过；接收人解析（`NotifyRecipientResolver`）→ ① 负载显式 `recipientIds`（只留启用用户）优先；② `OWNER` 责任人，发送前**按病区复核**（启用 + 负责该病区或全院范围角色——已不负责该病区的医生不再收到）；③ 负责人缺失 / 复核不通过 → `WARD_STAFF` 病区全部启用成员兜底（A 是医生与随访员、C 含护士：两院都是经「负责病区」关联的医护，差异在关联了谁）；④ `CC_WARD_LEADER` 追加病区默认随访负责人（OQ-60）。任何路径只解析 sys_user 医护。分发：INBOX 立即投 `NoticeInboxService.deliver`（dedup=事件ID+接收人+INBOX）并记台账 SENT；SMS 只记 QUEUED——按人并入每日汇总、免打扰与频控由 E09-T05 汇总作业统一执行（同日只发一条汇总短信），模板参数在发送时按当日待办生成。租户停用期间分发器不扇出（OQ-43）；followup 模块关闭的消费侧闸门随事件产生方（E14+）一并接入。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/notify/rules` | 目录全集 10 项（缺行回落默认值）：`eventType / label / enabled / advanceDays / sendTime(HH:mm) / channels: [INBOX, SMS] / recipients: [OWNER, WARD_STAFF, CC_WARD_LEADER] / smsTemplateCode`，无值字段不出现 |
| PUT | `/api/hospital/notify/rules/{eventType}` | 全量覆盖（校验：渠道 / 接收人非空且只认已知编码、sendTime `HH:mm`、advanceDays 0–365、渠道含 SMS 必配 APPROVED 的 NOTIFY 模板、不含 SMS 须为 null）；写 `NOTICE_RULE_UPDATE` 审计（ objectType=NTF_RULE，含前后值） |
| GET | `/api/hospital/notify/sms-templates` | 只读：平台审核过的 NOTIFY 场景模板 `{code, name, content}`（PLT-CFG-006 租户只能选不能写） |
| GET | `/api/hospital/notify/settings` | 生效发送设置 `{dndStart, dndEnd, smsDailyLimit, overdueSmsOffAllowed}`（E09-T07 落地；缺行回落默认 21:00–08:00 / 3 / false） |
| PUT | `/api/hospital/notify/settings` | 全量覆盖（校验：HH:mm、起止不同、上限 1–20），写 `NOTICE_SETTINGS_UPDATE` 审计；免打扰与上限由 §3 汇总作业在每轮发送时读现行值执行（E09-T05 已落地），`overdueSmsOffAllowed` 即时生效 |

## 3. 发送规则与短信通道（后端，通道随 E09-T03 落地）

- 短信按人合并为汇总短信，**不含任何患者信息**（A-NTF-003、C-NTF-003、A-NFR-007），只写“您有 N 项随访任务待处理”之类。
- 免打扰时段内产生的短信推迟到 08:00 后；超出每日上限的并入次日汇总（A-NTF-005、C-NTF-005）。
- 无负责人时按 A-NTF-004 / C-NTF-004 扩大接收人（病区成员兜底，§2）。
- **每日汇总作业（E09-T05 已落地）**：`NotifyDigestService` + `NotifyDigestScheduler`（每分钟级 tick `crdp.notify.digest.tick-ms`，默认 60 秒）。逐租户读本院 DAILY_DIGEST 规则的 `send_time`（默认 08:30，容差 5 分钟，每日只触发一次——内存标记、重启清零，每日上限是持久兜底）执行一轮：① 按人把 `ntf_send_log` 全部 QUEUED 短信行合并成**一条**汇总短信（模板 NOTIFY_DAILY_SUMMARY、参数只有待办总数，5 项待随访 + 2 项逾期 = 「您有 7 项…」）；② 免打扰（`ntf_setting`，默认 21:00–08:00，起点含终点不含）时段内短信一律不发、行保持 QUEUED 并入次轮，**站内信汇总不受免打扰约束**；③ 每人每日上限（默认 3）：当日 SENT 短信数已满则本轮并入次日；④ 手机号取 `sys_user.phone` 密文经 CryptoService 解密（未绑定 / 解密失败记 FAILED，接收人停用记 SKIPPED）；⑤ 成功并入行整体记 SENT（带 sent_at），服务商失败记 FAILED（detail 只含失败类别，走 §4 重试）；⑥ 每人补投一条「每日汇总」站内信（dedup `digest:{日期}:{接收人}:INBOX`，当日事件侧已投过 DAILY_DIGEST 站内信则不重复）。今日待办口径暂为「该接收人的 QUEUED 短信台账行」；E14 任务域落地后可改为按 fu_task 统计（负载 `recipientIds` 随事件下发的通道已就绪，§2）。租户停用不执行（TenantDirectory 只列启用 / 试用 / 宽限期）；触发即先标记后执行，作业中断当日不补跑（QUEUED 行不丢，次日带上）。
- 事件经框架 outbox 分发器（E09-T01，`sys_outbox` PENDING → SENT / FAILED）进入通知子域：投递失败指数退避重试，超限转死信 `FAILED`。**站内信通道已随 E09-T02 落地**（`NoticeInboxService.deliver`，dedup_key 幂等）；**规则引擎（事件 → 规则 → 接收人 → 渠道）已随 E09-T04 落地**（`NotifyEventSubscriber`，§2）；**短信通道已随 E09-T03 落地**（`TencentSmsChannel`，E09-T05 汇总作业消费 `ntf_send_log` 的 QUEUED 行）；**Webhook 已随 E09-T06 落地**（§6）。
- 每条通知写 `ntf_send_log`（E09-T04 落表）：事件、接收人、渠道、时间、结果、失败原因；查询 / 重试随 E09-T07。
- 租户停用、模块关闭时不发送。

### 3.1 平台短信通道（PLT-CFG-005/006，E09-T03）

- **模板**：平台库 `plt_sms_template`（迁移 V31）——`code`（AUTH_LOGIN_CODE / AUTH_RESET_CODE / AUTH_BIND_CODE / NOTIFY_DAILY_SUMMARY / NOTIFY_OVERDUE 等）、`scene`（参数白名单依据）、`content`（`{1}..{n}` 变量占位）、`provider_template_id`（腾讯云模板 ID，E01-T13 报备审核通过后回填，**未回填不能发**）、`sign_name`（模板级签名，空用通道默认 `crdp.sms.sign-name`）、`status`（APPROVED 才可发）。短信模板全部平台内置（签名与模板主体是平台，租户只能选，PLT-CFG-006 的“租户自定义模板”落在站内信 / Webhook 文案，随 E09-T04）。
- **发送**：`TencentSmsChannel.send(SmsSendCommand)`——通道开关 / 密钥自检 → 手机号校验 → 模板校验 → 签名 → **敏感信息两道闸** → 腾讯云 SendSms（TC3-HMAC-SHA256 自签，不引 SDK）→ 台账。`SmsCodeSender`（登录 / 重置 / 换绑验证码）已接到本通道（替换 M0 占位实现）。密钥经配置注入（生产环境变量 `CRDP_SMS_*`，技术方案第 4 章：DEW 凭据管理），不落库不进仓库；未配置（`crdp.sms.enabled=false` 默认）发送明确报 `CHANNEL_DISABLED`，不假装成功。
- **敏感信息两道闸（验收“短信不含患者敏感信息”）**：① 场景参数白名单（`SmsScene`）——AUTH_* 只放 4–8 位数字验证码、NOTIFY 只放 1–6 位数字（数量 / 天数）与 `yyyy-MM-dd` 日期，姓名 / 诊断 / 病案号等自由文本结构上进不来；② 渲染反扫——占位符替换后的完整内容按「大陆手机号 / 15 或 18 位证件号 / ≥10 位连续数字」拒绝（兜模板文案自身与参数拼接成型）。违规抛 `SENSITIVE_CONTENT`，**不触服务商**。
- **发送与回执台账（验收“回执入库”）**：平台库 `plt_sms_send_log`（迁移 V31）——每条发送 SUBMITTED（带腾讯云流水号）/ SUBMIT_FAILED（失败类别，如 SENSITIVE_CONTENT、CHANNEL_DISABLED、PROVIDER_REJECTED）；手机号只存脱敏值（138****0000），模板参数不落库。回执按 sid（= SendSms 返回的 SerialNo）回填 DELIVERED / RECEIVE_FAILED；对不上发送行的回执落 `UNMATCHED_RECEIPT` 行（purpose=UNMATCHED）不丢对账依据。

### 3.2 腾讯云状态回执回调（公开口）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/notify/sms/receipt` | 免登录（@PublicApi 登记，按 IP 每小时限流 `crdp.sms.receipt-ip-hourly-limit` 默认 600，超限 42900） | 腾讯云控制台配置的回执推送地址。报文兼容三种形态（根数组 / `sms_status_callback` 数组 / `sms_status_callback.content` 数组），逐条入库（见 §3.1 台账）；应答按腾讯云约定 `{"result":0,"errmsg":"OK"}`（非 200 / 非 0 会触发重推）。回执只含流水号 / 状态 / 时间 / 手机号（入库脱敏），不含患者信息 |

## 4. 发送记录（A-NTF-006、C-NTF-006；E09-T07 已实现）

页面：`/system/notice`「发送记录」页签（A 规格「通知发送记录」页：按事件、渠道、结果、日期筛选）。`ntf_send_log` 不在 ControlledTables（台账无患者维度，接口按权限码控制）。查询写 PLT-AUD-003 批量查询审计（`BATCH_QUERY` + 条件摘要 + 命中条数）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/notify/send-logs/search` | `followup:notice:config` | body `{ pageNo, pageSize(≤100), eventType?, channel?, sendStatus?, recipientId?, dateFrom?, dateTo? }`（全部可空；日期按记录时间闭区间按日）→ `{ items, total, pageNo, pageSize }`；行 `{ id, eventType, eventLabel, recipientId, recipientName, channel, sendStatus, title?, detail?, sentAt?, createdAt }`（title/detail 不含患者信息；接收人名来自 sys_user） |
| POST | `/api/hospital/notify/send-logs/{id}/retry` | `followup:notice:config` | 失败重试：FAILED → QUEUED（detail 保留原失败原因），返回 `{ item, requeued }`；短信由 §3 汇总作业（E09-T05）按人合并发送，**同样受免打扰与上限约束**（回到队列等待次轮，不丢）；非 FAILED 40001、不存在 40400、并发已重试幂等返回现行值；写 `NOTICE_SEND_RETRY` 审计 |

send_status 口径：`QUEUED`（短信待 E09-T05 汇总发送）/ `SENT`（站内信已投 ntf_message）/ `FAILED`（发送失败，可重试；当前产生方为 E09-T05 汇总作业）/ `SKIPPED`（事件停用、规则不含该渠道或个人偏好关闭——个人关闭短信记 SKIPPED，detail=「个人通知偏好已关闭该事件短信」）。

## 5. 个人通知偏好（A-NTF-007、C-NTF-007；E09-T07 已实现）

页面：`/profile`「通知偏好」页签。表：租户库 `ntf_user_pref`（迁移 V23，逐用户一行，`sms_off` CSV）。**只影响短信**——本人已关闭该事件的短信在规则引擎投递时记 SKIPPED 不并入每日汇总，站内信不受影响；逾期类（TASK_OVERDUE）在投递时按 §2 settings 兜底（设置收紧后历史偏好立即失效）。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/profile/notify-pref` | 登录 | `{ smsOff: eventType[], overdueSmsOffAllowed, options: [{ eventType, label, closeable }] }`；options 只列本院规则含短信渠道的事件（缺行回落默认值，与 §2 同口径），closeable=非逾期类或租户允许 |
| PUT | `/api/hospital/profile/notify-pref` | 登录 | body `{ smsOff: eventType[] }` 全量覆盖（只认已知编码）；本院不允许关闭逾期类时含 `TASK_OVERDUE` → **40001**；写 `NOTICE_PREF_UPDATE` 审计（本人） |

## 6. Webhook（A-NTF-008、C-NTF-008、PLT-CFG-007；`tenant:webhook:edit`；E09-T06 已实现）

表：租户库 `sys_webhook`（配置）+ `sys_webhook_delivery`（投递队列 / 台账，重试进度持久化）——迁移 followup V59；按钮（plt_menu 343）平台库 V47、TENANT_ADMIN 授予种子 V121。后端 `com.crdp.system.webhook`（crdp-system）+ `HospitalWebhookController` / `WebhookDeliveryScheduler`（crdp-server）。页面：`/system/settings`「Webhook」页签（pages.md §10）。**平台级 Webhook（platform:webhook:edit，/platform/webhook）不在本任务**（随平台配置页任务落地）。

- **配置面**：`{ name, url, secret?, events, enabled }`；secret 只写不读——新建留空系统生成 32 字节 Base64URL，PUT 传 null/空白保持、传新值即更换，**只在生成 / 更换的响应回显一次**（secretShownOnce），库里只存 CryptoService 密文（purpose=`webhook-secret`）；events 目录 `TASK_OPENED / TASK_OVERDUE / RECORD_SUBMITTED / PATIENT_DEAD`（A / C 差异由本院勾选体现——C 不勾患者死亡；RECORD_SUBMITTED / PATIENT_DEAD 的事件产生方随 E15 / 患者域上线自动接入）；数量上限 20 个；增删改写 `WEBHOOK_CREATE / UPDATE / DELETE` 审计（secret 明文不落审计），删除连投递台账一起清。
- **SSRF 防护（验收「内网地址拒绝」）**：`WebhookSsrfGuard` 在**保存时**与**每次投递前**双重校验——仅 HTTPS；主机名解析出的全部地址必须是公网（回环 127/8 与 ::1、任意本地 0/8、链路本地 169.254 与 fe80::/10（云元数据）、站点本地 10/8、172.16/12、192.168/16、IPv6 唯一本地 fc00::/7、IPv4 映射 IPv6、组播、100.64/10、198.18/15 任一命中即 40001）；解析失败拒绝；不允许 userinfo；出站 **禁跟随重定向**（防公网 302 → 内网）。`crdp.webhook.allow-private-addresses=true`（默认关）仅供本机联调 / 集成测试把接收方起在 127.0.0.1（同时放行明文 http——本地 HttpServer 起不了 TLS），生产不得打开。
- **投递链路**：sys_outbox 订阅方 `WebhookEventSubscriber` 只入队（(webhook_id, outbox_event_id) 唯一，分发器重放不重排）；HTTP 出站在 `WebhookDeliveryScheduler` 轮询（`crdp.webhook.delivery.tick-ms` 默认 15 秒；`crdp.webhook.delivery.scheduler-enabled=false` 可整体关闭，缺省开——生产 / 本地开发不受影响，仅 crdp-server 测试 classpath 默认关，防多缓存上下文共享租户库互相按生产口径 SSRF 拒绝）逐租户执行，不阻塞 outbox 分发线程。负载只含 `{ eventType, objectType, objectId, tenantCode, occurredAt(+08:00) }`，**不含患者信息**（对象 ID 字符串序列化，taskId / recordId / patientId 按事件映射 TASK / RECORD / PATIENT）。
- **签名（验收「签名可校验」）**：请求头 `X-CRDP-Timestamp`（Unix 秒）+ `X-CRDP-Signature: v1=hex(HMAC-SHA256(secret, 时间戳 + "." + 请求体))`（经 CryptoService.hmacSha256Hex，业务代码不直接调算法库）+ `X-CRDP-Event` / `X-CRDP-Delivery`（接收方幂等键，同一投递的重试共用）；接收方建议同时校验 ±5 分钟时钟偏差防重放；`v1=` 为算法版本前缀（换国密升 v2）。
- **重试（tech-plan「失败重试 3 次（退避）」）**：非 2xx / 网络异常 → 指数退避（`crdp.webhook.retry-base-ms` 默认 60 秒，逐次翻倍封顶 `max-backoff-ms` 默认 30 分钟）重试 `crdp.webhook.max-retries`（默认 3）次，共 1+3 次尝试，耗尽转 FAILED；attempt / next_attempt_at 落库（重启不丢退避进度；到点判定用 JVM 时钟，不与 MySQL NOW() 比较——容器时区与 JVM 不同时不错位）；地址复核不过（保存后 DNS 改指内网）直接 FAILED 不重试。
- **投递记录（PLT-CFG-007）**：按状态筛选、payload 与响应码 / 失败类别可见（失败类别不含 URL 与响应体），FAILED 可手动重投（退避从第 1 次重计）。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/system/webhooks` | 清单（不含 secret）；行 `{ id, name, url, events, enabled, lastDeliveryAt?, updatedAt? }` |
| GET | `/api/hospital/system/webhooks/events` | 可订阅事件目录 `{code, label}` |
| POST | `/api/hospital/system/webhooks` | 新建 → `{ webhook, secretShownOnce }`（密钥只回显这一次）；内网 / 非 HTTPS 地址 40001 |
| PUT | `/api/hospital/system/webhooks/{id}` | 修改（字段可空 = 保持；secret 传新值即更换并回显一次）；不存在 40400 |
| DELETE | `/api/hospital/system/webhooks/{id}` | 删除（队列与台账一并清除，写审计） |
| POST | `/api/hospital/system/webhooks/{id}/test` | 同步推一条 WEBHOOK_TEST（一次性，失败不进重试队列），响应 `{ status, responseStatus?, error?, timestamp, signature, signatureAlgorithm }`——回显签名头值供与接收方对账 |
| GET | `/api/hospital/system/webhooks/{id}/deliveries?status=&pageNo=&pageSize=` | 投递记录分页；行含 payload / attempt / responseStatus / error / sentAt |
| POST | `/api/hospital/system/webhooks/{id}/deliveries/{deliveryId}/redeliver` | FAILED → PENDING 重投；非 FAILED 40001、不存在 40400 |

## 7. 测试要点

- 21:30 产生的短信 08:00 后才发；第 4 条并入次日（E09-T05 `NotifyDigestServiceTest` 免打扰边界与频控、`NotifyDigestIT` 真库端到端：5+2 行合并 1 条、参数只有总数、免打扰行保持 QUEUED 次日发出、上限并入次日、失败记 FAILED、双租户互不影响）。
- E09-T08 验收「只发测试号码，免打扰生效」（`NotifyAcceptanceIT`，真库 + 完整应用，服务商 mock 绝不触真实腾讯云）：把 §2 事件链与 §3 汇总作业接成一条链并走**真实定时入口**（sys_outbox 逾期事件 → 分发器 → 规则引擎 QUEUED → `NotifyDigestScheduler.tick` 逐租户 → `poll` 按本院 send_time 到点 → `ntf_setting` 现行免打扰 / 频控 → 通道）。接收人只绑 1390000xxxx 虚构段测试号码，断言服务商收到的**全部**调用只落在这些测试号码、逐人各一条合并短信（参数只有数量）；未绑定手机号 FAILED、停用 SKIPPED 都不触服务商；免打扰窗口内整轮零调用、行保持 QUEUED，站内信汇总照投，窗口挪开后补跑合并发出。三条变异验证（免打扰失效 / 发到未绑定号码 / 数量口径错）均被用例指认。
- 短信正文与 Webhook 负载里检查不到姓名、手机号、证件号、病案号（NOTIFY 场景参数白名单 + 渲染反扫两道闸；汇总参数只有数量）。
- 接收人失去病区权限后，旧站内信打开 → 40400，列表里姓名改为脱敏。
- 发送记录按事件 / 渠道 / 结果 / 日期 / 接收人可查（E09-T07 `NotifySendLogsIT`）；失败重试回 QUEUED 且保留原失败原因。
- 个人关闭非逾期类短信后：该事件 SMS 记 SKIPPED、站内信照发；逾期类在租户不允许时 PUT 含 TASK_OVERDUE → 40001。
- Webhook（E09-T06 `WebhookSsrfGuardTest` / `WebhookAdminServiceTest` / `WebhookDeliveryServiceTest` / `WebhookEventSubscriberTest` + `WebhookConfigIT` / `WebhookDeliveryIT`）：内网 / 回环 / 链路本地 / 非 HTTPS / userinfo 保存即 40001（公网用字面量 IP 不依赖 DNS）；签名头在测试里用独立 JCE 实现验签（RFC 4231 向量锁 CryptoService.hmacSha256Hex）；负载无患者字段；退避 1×/2×/4×、耗尽 FAILED、重投重新计数；投递前地址复核不过不出网；test 接口回显签名与实际请求一致。
