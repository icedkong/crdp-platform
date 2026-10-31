# ADR 0001：M0 工程骨架的关键选择

日期：2026-10-31

状态：已接受

本文记录 M0 工程骨架里已经落地的选择。依据是《整体技术方案 V0.2》和《平台底座需求规格说明 V0.3》。方案没有写死的地方，用常见做法补上，并在下面标明。

## 形态

一个 Git 仓库。后端是 Maven 多模块的 Spring Boot 单体，前端是 pnpm workspace。可部署单元只有 `fu-server`。医院端和平台端是两个前端应用，打到不同镜像里。

后端模块和依赖方向按第 15 章：

`fu-followup` → `fu-form` → `fu-framework`，`fu-platform` 和 `fu-system` 只依赖 `fu-framework`。启动类在 `fu-server`，扫描 `com.fu`。

## 技术栈

| 项 | 选择 | 说明 |
| --- | --- | --- |
| JDK / 框架 | 21 / Spring Boot 3.5.7 / Spring Security 6 | 方案指定 JDK 21、Spring Boot 3.x、Spring Security 6。3.5.7 是编写时的当前 3.x |
| 持久化 | MyBatis-Plus 3.5.14 | 方案指定。M0 用注解 SQL，没有 Mapper XML |
| 迁移 | Flyway 11（随 Boot 3.5） | 目录 `db/platform`、`db/tenant/base`、`db/tenant/followup`。local/dev 额外跑 `seed` |
| 数据库 | MySQL 8.0 | 本地用 Docker。平台库 `fu_platform`，演示租户库 `t_demoa`、`t_demob` |
| 缓存 | Redis 7 | 不透明令牌和登录失败计数 |
| 口令 | Argon2id | `Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`。需要 Bouncy Castle，Boot BOM 未管理该依赖，父 POM 固定 1.80.2 |
| 接口文档 | springdoc 2.8.13 | `/swagger-ui.html` |
| 前端 | Vue 3 + Vite + Ant Design Vue + Pinia | 见下一节 |

## 前端为什么没有整仓复制 Vben

方案写的是 Vben Admin v5，并建议 Ant Design Vue 版。编写时上游稳定标签是 **v5.7.0**。

M0 没有把 `vue-vben-admin` 整仓放进来。那个仓库还带 web-ele、web-naive、文档站和 playground，和本项目的两个应用、租户编码登录对不上。

当前应用壳按 Vben 5 的后端权限模式对接：

- 登录后分别取用户、权限码、菜单树
- 菜单字段是 `name`、`path`、`component`、`meta`
- `component` 用 `import.meta.glob` 匹配 `views` 下的文件
- 目录节点的 `component` 为 `LAYOUT`

组件库用 Ant Design Vue，和方案建议的 antd 版本一致。目录按第 15 章放了 `apps/web-hospital`、`apps/web-platform`、`apps/mobile-upload`，以及 `packages/api`、`form-engine`、`rules`、`viewer`。后三个和手机上传页只是占位。

如果以后要换成上游 Vben 应用壳，后端这三组接口可以保持不变。

## 多租户

- 令牌是 256 位随机值，存在 Redis，键为 `fu:sess:{token}`。载荷含 tenantId、userId、会话类型、权限版本号、菜单和权限码。不用 JWT。方案第 3 章就是这样写的。
- 登录先查平台库里的租户，再切到该院库查用户。平台账号和医院账号的入口分开。
- 租户不存在、账号不存在、密码错误返回同一句「租户、账号或密码不正确」。失败次数按租户编码加账号记在 Redis 里。
- `TenantRoutingDataSource` 按 `TenantContext` 选连接池。没有上下文就抛错，不回落到平台库。健康检查单独打平台库。
- 异步线程用 `TenantContextTaskDecorator` 传递上下文。调度扇出还没做。
- 本地演示把数据库口令以 `local:` 前缀放在 `plt_tenant_resource`。生产剖面禁止 `fu.crypto.mode=local`。KMS 解密留到 E03。
- 生产要求的「运行账号 / 迁移账号」拆分，本地合成一个 `fu_app`。开通编排本身留到 E02，启动时只会给已经登记且状态为启用或试用的租户建池并跑 Flyway。

## 权限和数据范围

权限码格式是 `{模块}:{资源}:{动作}`，例如 `followup:patient:view`、`platform:tenant:view`。

校验链有骨架：登录态、`@RequireModule`、`@RequiresPermission`。数据范围是 MyBatis 拦截器：

- 受控表（患者及子表）没有范围上下文时直接拒绝
- `@IgnoreScope` 放行
- 范围为「全部」时不改 SQL
- 受限但没有任何病区、科室或本人条件时追加 `AND (1 = 0)`
- 已有范围 id 时只追加注释 `/* fu-datascope:restricted */`

用 JSqlParser 把 SQL 改写成 `EXISTS` 关联 `pt_patient` 是 E07 的事。M0 还没有患者查询。

字段策略接口固定返回 `VISIBLE`。脱敏和明文查看留到 E07。

## 审计、密码和日志

`AuditLogger` 会把登录尝试写入租户库 `aud_login_log` 或平台库 `plt_audit_log`。哈希链、按月分区和 OBS 归档留到 E08。

`CryptoService` 在 local/dev/test 用 AES-256-GCM，密文带版本、算法、密钥版本和用途。`kms` 模式的实现会直接拒绝调用。业务代码还没有拿它去加密患者字段，因为 M0 没有患者字段。

日志带 `traceId`。local/dev/test 是文本，prod 是 JSON。请求日志不记正文。统一响应是 `{code, message, data, traceId}`，成功码为 0。

## 方案没写、这里拍板的默认

- Java 包名用 `com.fu`。
- 主键用数据库自增。雪花或 UUID 方案没指定。
- 平台角色和菜单的关联表 `plt_role_menu` 不在第 15 章概要清单里，但平台端动态菜单需要它，所以加了。
- 会话 8 小时，不滑动续期。权限版本号先写入令牌，请求时还不回源比较。那是 E04。
- 演示账号的 `must_change_password` 为 0，否则没有改密页就进不了工作台。首次改密是 E04。
- 敏感权限（明文、导出）种在菜单资源里，但不授给演示角色。
- 不引入 ShedLock、POI、Tika、OBS SDK。锁表 `shedlock` 已建。外发箱、通知、对象存储、Excel、调度都是会抛「尚未实现」的接口。
- 端口：后端 18080，医院端 14173，平台端 14174，MySQL 13306，Redis 16379。
- CI 跑格式检查、单测和前端构建。SAST、依赖漏洞和镜像扫描留到流水线接华为云的时候再加。

## 明确不做

患者、表单、计划、通知、文件、导入、统计，以及任何真实医院数据。种子里的医院、人员和病区都标明是虚构的。
