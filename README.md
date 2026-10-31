# 临床科研数据一体化平台

多租户医院随访 SaaS 的 M0 工程骨架。产品英文缩写是 `crdp`。能编译、能启动、能用虚构账号登录，并看到后端下发的菜单。没有随访业务。

方案依据是《整体技术方案 V0.2》和《平台底座需求规格说明 V0.3》。取舍写在 [docs/adr/0001-m0-engineering-skeleton.md](docs/adr/0001-m0-engineering-skeleton.md)。前端外壳改为官方 Vben Admin 5（仅 Ant Design Vue），见 [docs/adr/0002-official-vben-admin-5.md](docs/adr/0002-official-vben-admin-5.md)。

## 代码仓库

主仓库是 [https://github.com/icedkong/crdp-platform](https://github.com/icedkong/crdp-platform)。后续开发、问题跟踪和持续集成都在这个 GitHub 仓库进行。默认分支是 `main`。

## 结构

```
backend/                 Maven 多模块，JDK 21，Spring Boot 3
  crdp-server/             唯一可部署包
  crdp-framework/          租户路由、认证、数据范围、审计、密码服务接口
  crdp-platform/           平台库：租户、模块、菜单、平台账号
  crdp-system/             租户库：用户、角色、数据范围
  crdp-form/               表单引擎占位
  crdp-followup/           随访子域包结构，无业务代码
frontend/                pnpm workspace，官方 Vben Admin 5（仅 Ant Design Vue，见 ADR 0002）
  apps/web-hospital/     医院端，租户编码登录
  apps/web-platform/     平台端
  apps/mobile-upload/    手机上传占位
  packages/api/          与后端约定的类型
  packages/tokens/       设计令牌
  packages/form-engine/  占位
  packages/rules/        权限码格式
  packages/viewer/       占位
deploy/mysql/init/       本地创建 t_demoa、t_demob
docs/adr/                架构决定
```

依赖方向：`crdp-followup` → `crdp-form` → `crdp-framework`。`crdp-platform` 和 `crdp-system` 只依赖 `crdp-framework`。

数据分成平台库 `crdp_platform` 和每家医院一个库 `t_{租户编码}`。租户库的表不带 `tenant_id`。请求上的租户来自登录令牌，路由数据源据此选连接池。没有租户上下文时不允许碰数据库。

## 本地启动

需要 Docker、JDK 21、Maven、Node.js 20+ 和 pnpm 10。

```bash
chmod +x scripts/dev.sh
./scripts/dev.sh
```

脚本会启动 MySQL 8.0 和 Redis，打包并运行后端，再同时启动医院端和平台端。

| 服务 | 地址 |
| --- | --- |
| 医院端 | http://127.0.0.1:14173 |
| 平台端 | http://127.0.0.1:14174 |
| 后端 | http://127.0.0.1:18080 |
| OpenAPI | http://127.0.0.1:18080/swagger-ui.html |
| 健康检查 | http://127.0.0.1:18080/actuator/health |

只想分开启动时：

```bash
docker compose up -d
mvn -f backend/pom.xml -pl crdp-server -am -DskipTests package
java -jar backend/crdp-server/target/crdp-server.jar --spring.profiles.active=local
```

```bash
cd frontend
pnpm install
pnpm dev
```

后端默认剖面是 `local`。另外还有 `dev`、`test`、`prod`。`prod` 不带默认口令，也不跑演示种子，并且拒绝 `crdp.crypto.mode=local`。

## 虚构演示账号

这些账号只存在于 local/dev 的种子数据里。口令都是 `Demo@2026`。没有真实人员和患者。

| 入口 | 租户 | 用户名 | 能看到什么 |
| --- | --- | --- | --- |
| 医院端 | `demoa` 虚构示例医院甲 | `nurse.demo` | 工作台、随访、系统管理 |
| 医院端 | `demoa` 或 `demob` | `doctor.demo` | 工作台、患者档案、随访任务 |
| 医院端 | `demob` 虚构示例医院乙 | `nurse.demo` | 与甲院相同的菜单，库是 `t_demob` |
| 平台端 | 无 | `platform.admin` | 平台管理 |

医院账号不能打开平台接口，反过来也不行。租户编码不存在、账号不存在、密码错误的提示相同。

演示账号绑定了虚构手机号（1390000xxxx 段）：`nurse.demo` 13900000001、`doctor.demo` 13900000002、`platform.admin`
13900000003，可在医院端用「短信验证码」页签体验验证码登录与双因素。短信通道要到 E09-T03 才接腾讯云，
此前真实发送不可用：local/dev 已关闭平台强制双因素保证演示可登录，医院端短信页签的「获取验证码」会因通道缺失报错。

本地数据库口令写在 `docker-compose.yml` 里，用户 `crdp_app`，口令 `DevOnly#app2026`，root 口令 `DevOnly#root2026`。它们只用于本机，不能拿到生产。

## 常用检查

```bash
mvn -f backend/pom.xml spotless:check test
python3 scripts/check_flyway_versions.py
cd frontend && pnpm lint && pnpm test && pnpm build
```

租户路由的单测在 `TenantRoutingDataSourceTest`：甲院和乙院的连接池不能混用，缺少上下文时直接拒绝。

## 约定

- 权限码 `{模块}:{资源}:{动作}`，创建后不改。例子：`followup:task:view`、`platform:tenant:view`。
- 模块目前只有 `base`（平台基础）和 `followup`（随访）。不建 `rct` 和 `archive`。
- Flyway 脚本只新增、不修改。平台库和租户库各自的版本号不能重复。`scripts/check_flyway_versions.py` 会查这个。
- 受控表（患者及子表）的查询必须带 `@DataScope`，或者在调度入口用 `@IgnoreScope` 写明原因。否则拦截器拒绝。
- 业务代码不直接调用算法库，走 `CryptoService`。M0 的本地实现不能用于生产。
- 日志和异常里不写口令、令牌和患者明文。M0 也没有患者明文。
- 统一响应 `{code, message, data, traceId}`。成功码是 0。响应头 `X-Trace-Id`。

## 镜像

给以后的华为云 CCE 用，仓库里没有云账号。

```bash
docker build -f backend/Dockerfile -t crdp-server:local backend
docker build -f frontend/apps/web-hospital/Dockerfile -t crdp-web-hospital:local frontend
docker build -f frontend/apps/web-platform/Dockerfile -t crdp-web-platform:local frontend
```

后端容器监听 18080，前端容器以非 root 用户监听 8080，并把 `/api` 转到名为 `crdp-server` 的服务。生产剖面要自己注入 `CRDP_PLATFORM_JDBC_URL`、`CRDP_PLATFORM_DB_USERNAME`、`CRDP_PLATFORM_DB_PASSWORD`、`CRDP_REDIS_HOST`。

## 还没做

开通编排、KMS、二次验证、强制改密、数据范围的 SQL 改写、字段脱敏、审计哈希链、表单引擎、患者和随访业务，都留在对应 Epic。清单见技术方案第 16 章。M0 对应其中的 E01 脚手架，不包含华为云资源开通。
