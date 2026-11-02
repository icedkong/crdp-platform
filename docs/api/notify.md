# 通知：站内信、短信、Webhook（notify）

> 后端：`crdp-followup`（通知子域，事件来自 outbox）+ 平台短信通道（PLT-CFG-005）。表：`ntf_rule`、`ntf_message`（站内信）、`ntf_send_log`、`ntf_user_pref`、`sys_webhook`。
> 页面：顶栏铃铛（components.md AppHeader）、`/system/notice`（通知设置）、`/profile`（通知偏好）、`/system/settings`（Webhook）。
> §1 已随 E09-T02 落地（其余章节路径 **推断，待确认**）。需求：A-NTF-001…008、C-NTF-001…008。**A-NTF-009 通知患者首期不做**：任何通知只发医生、随访员、护士。

## 1. 站内信（A-NTF-002、C-NTF-002；E09-T02 已实现）

表：租户库 `ntf_message`（迁移 V21）——`recipient_id`（sys_user）+ `event_type/title/summary` + `patient_id` + `patient_name_cipher`（信封加密，purpose=`ntf-patient-name`）+ `target_type/target_id` + `dedup_key`（唯一键，outbox 至少一次语义下重复投递不重发）+ `read_flag/read_at`。投递入口 `NoticeInboxService.deliver(...)`：E09-T04 通知规则引擎经 outbox 分发器调用，租户上下文内执行。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/notify/messages?unread=&pageNo=&pageSize=` | 登录 | 本人站内信（id 倒序，pageNo 从 1、pageSize 默认 20 上限 100，越界 40001）；行 `{ id, eventType, title, summary, patientId, patientName, target: { type: TASK\|RECORD\|PATIENT, id }, read, createdAt }`；无值字段不出现 |
| GET | `/api/hospital/notify/messages/unread-count` | 登录 | `{ unread }` 铃铛角标，前端 60s 轮询（页面可见时也刷新，tokens `notifyPollMs`） |
| POST | `/api/hospital/notify/messages/{id}/read` | 登录 | 标一条已读（幂等）；别人的消息 / 不存在一律 **40400** 不暴露存在，并写 `NOTICE_INBOX_READ` DENIED 操作审计 |
| POST | `/api/hospital/notify/messages/read-all` | 登录 | `{ marked }` 只影响本人未读行 |

患者姓名**读取时**解密并按接收人当前字段策略脱敏（`@MaskedByPolicy`，HIDDEN 整字段不出现），落库为密文、不写死脱敏值。收件箱按 recipient 隔离（ntf_message 不在 ControlledTables）；点击跳转由前端按 target 路由（TASK→`/followup/task?taskId=`、PATIENT→`/followup/patient?patientId=`、RECORD→`/followup/record/{id}` 随 E15 隐藏菜单），**落点接口按数据范围再校验**（越范围 40400，fu_task 已登记 ControlledTables，接口随 E14/E15 落地）。平台侧 INBOX 派发行（OQ-44 / OQ-58）的消费与平台端收件箱随 E09-T03/T07 接入（见 OQ-59）。

## 2. 通知规则（`/system/notice`，`followup:notice:config`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/hospital/notify/rules` | 逐事件：`eventType`（窗口预告、任务开启、窗口开启、即将逾期、已逾期、每日汇总、任务改派、约定回访到时、未联系到跟进、失访候选）、`enabled`、`advanceDays`、`sendTime`、`channels: [INBOX, SMS]`、`recipients`（负责人 / 病区全部医生与随访员（C 含护士）/ 抄送病区负责人）、`smsTemplateCode` |
| PUT | `/api/hospital/notify/rules/{eventType}` | 修改；写审计 |
| GET | `/api/hospital/notify/sms-templates` | 只能选平台审核过的模板（只读） |
| PUT | `/api/hospital/notify/settings` | 免打扰时段（默认 21:00–08:00）、每人每天短信上限（默认 3）、逾期类是否允许个人关闭 |

## 3. 发送规则（后端，无独立接口）

- 短信按人合并为汇总短信，**不含任何患者信息**（A-NTF-003、C-NTF-003），只写“您有 N 项随访任务待处理”之类。
- 免打扰时段内产生的短信推迟到 08:00 后；超出每日上限的并入次日汇总（A-NTF-005、C-NTF-005）。
- 无负责人时按 A-NTF-004 / C-NTF-004 扩大接收人。
- 事件经框架 outbox 分发器（E09-T01，`sys_outbox` PENDING → SENT / FAILED）进入通知子域：投递失败指数退避重试，超限转死信 `FAILED`。**站内信通道已随 E09-T02 落地**（`NoticeInboxService.deliver`，dedup_key 幂等；E09-T04 规则引擎接入后消费事件）；短信随 E09-T03、Webhook 随 E09-T06。
- 每条通知写 `ntf_send_log`：事件、接收人、渠道、时间、结果、失败原因。
- 租户停用、模块关闭时不发送。

## 4. 发送记录（A-NTF-006、C-NTF-006）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/hospital/notify/send-logs/search` | `followup:notice:config` | 按事件、渠道、结果、日期、接收人查询 |
| POST | `/api/hospital/notify/send-logs/{id}/retry` | `followup:notice:config` | 失败重试（同样受免打扰与上限约束） |

## 5. 个人通知偏好（A-NTF-007、C-NTF-007，P1）

`GET/PUT /api/hospital/profile/notify-pref`：`{ smsOff: eventType[] }`；逾期类能否关闭由 §2 settings 决定，不允许时 40001。

## 6. Webhook（A-NTF-008、C-NTF-008，P1；`tenant:webhook:edit`）

`GET/POST/PUT/DELETE /api/hospital/system/webhooks`：`{ url, secret（只写不读）, events: [TASK_OPENED, TASK_OVERDUE, RECORD_SUBMITTED, PATIENT_DEAD(A)] , enabled }`；`POST .../{id}/test`。
负载只含 `{ eventType, objectType, objectId, tenantCode, occurredAt }`，**不含患者信息**；HMAC-SHA256 签名头 `X-CRDP-Signature`；失败指数退避重试，记发送记录。

## 7. 测试要点

- 21:30 产生的短信 08:00 后才发；第 4 条并入次日。
- 短信正文与 Webhook 负载里检查不到姓名、手机号、证件号、病案号。
- 接收人失去病区权限后，旧站内信打开 → 40400，列表里姓名改为脱敏。
