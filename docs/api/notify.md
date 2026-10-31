# 通知：站内信、短信、Webhook（notify）

> 后端：`crdp-followup`（通知子域，事件来自 outbox）+ 平台短信通道（PLT-CFG-005）。表：`ntf_rule`、`ntf_message`（站内信）、`ntf_send_log`、`ntf_user_pref`、`sys_webhook`。
> 页面：顶栏铃铛（components.md AppHeader）、`/system/notice`（通知设置）、`/profile`（通知偏好）、`/system/settings`（Webhook）。
> 全部路径 **推断，待确认**。需求：A-NTF-001…008、C-NTF-001…008。**A-NTF-009 通知患者首期不做**：任何通知只发医生、随访员、护士。

## 1. 站内信（A-NTF-002、C-NTF-002）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/notify/messages?unread=&pageNo=&pageSize=` | 登录 | 本人站内信；`{ id, eventType, title, summary, target: { type: TASK|RECORD|PATIENT, id }, read, createdAt }` |
| GET | `/api/hospital/notify/messages/unread-count` | 登录 | 铃铛角标，前端 60s 轮询（或页面可见时） |
| POST | `/api/hospital/notify/messages/{id}/read` · `/read-all` | 登录 | 标为已读 |

患者姓名在**读取时**按接收人当前权限脱敏，不在落库时写死明文。点击跳转前后端再校验数据范围（越范围 → 40400）。

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
