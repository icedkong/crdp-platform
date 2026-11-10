# 临床科研数据一体化平台

多租户医院随访 SaaS。产品英文缩写是 `crdp`。本机用 `./scripts/dev.sh` 可以拉起虚构数据演示（本地信封加密、模拟短信）。生产云和华为云 KMS 还没接上，**接入 KMS 之前禁止导入真实患者数据**。说明见 [docs/ops/demo-mode.md](docs/ops/demo-mode.md)。

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
  apps/mobile-upload/    手机扫码拍照上传 H5（E16-T10，端口 14175）
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

需要 Docker、JDK 21、Maven、Node.js 20+ 和 pnpm 10。一条命令：

```bash
chmod +x scripts/dev.sh
./scripts/dev.sh
```

脚本会启动 MySQL 8.0 和 Redis，用 `demo` 剖面打包并运行后端（本地 AES-256-GCM 信封加密、模拟短信、虚构种子，含平台视角默认规则），再同时启动医院端、平台端和手机上传页。

| 服务 | 地址 |
| --- | --- |
| 医院端 | http://127.0.0.1:14173 |
| 平台端 | http://127.0.0.1:14174 |
| 手机上传 | 脚本打印的 `CRDP_H5_UPLOAD_MOBILE_BASE_URL`（本机默认 http://127.0.0.1:14175） |
| 后端 | http://127.0.0.1:18080 |
| 模拟短信 | http://127.0.0.1:18080/dev/mock-sms |
| OpenAPI | http://127.0.0.1:18080/swagger-ui.html |
| 健康检查 | http://127.0.0.1:18080/actuator/health |

只想分开启动时：

```bash
docker compose up -d
mvn -f backend/pom.xml -pl crdp-server -am -DskipTests package
# demo：可部署的演示剖面。local 同样是本地加密 + 模拟短信，种子也含视角默认规则。
java -jar backend/crdp-server/target/crdp-server.jar --spring.profiles.active=demo
```

```bash
cd frontend
pnpm install
pnpm --parallel --filter @crdp/web-hospital --filter @crdp/web-platform --filter @crdp/mobile-upload dev
```

没有 Docker 时：自行启动 MySQL 8（监听 `127.0.0.1:13306`，库 `crdp_platform`，用户 `crdp_app` / `DevOnly#app2026`，root `DevOnly#root2026`，并执行 `deploy/mysql/init`）和 Redis（`127.0.0.1:16379`），再运行 `./scripts/dev.sh`。脚本发现这两个端口已经开着就会继续打包后端。

后端默认剖面仍是 `local`（与 `demo` 一样不走 KMS）。另外有 `dev`、`test`、`demo`、`prod`。`prod` 不带默认口令，不跑演示种子，并且在 KMS 接入前**启动即失败**。不要用 `prod` 做演示。

局域网里的手机要扫上传二维码时，二维码不能指向 `127.0.0.1`。启动前设置这台机器的局域网地址，例如：

```bash
export CRDP_H5_UPLOAD_MOBILE_BASE_URL=http://192.168.1.20:14175
./scripts/dev.sh
```

不设置时，脚本会尽量用本机出口 IPv4。手机打开的是开发服务器 `0.0.0.0:14175`，由它把 `/api` 转到本机后端。

## 虚构演示账号

这些账号只存在于 local/dev 的种子数据里。口令都是 `Demo@2026`。没有真实人员和患者。

| 入口 | 租户 | 用户名 | 能看到什么 |
| --- | --- | --- | --- |
| 医院端 | `demoa` 虚构示例医院甲 | `nurse.demo` | 工作台、随访、系统管理 |
| 医院端 | `demoa` 或 `demob` | `doctor.demo` | 工作台、患者档案、随访任务 |
| 医院端 | `demob` 虚构示例医院乙 | `nurse.demo` | 与甲院相同的菜单，库是 `t_demob` |
| 平台端 | 无 | `platform.admin` | 平台管理 |

医院账号不能打开平台接口，反过来也不行。租户编码不存在、账号不存在、密码错误的提示相同。

演示账号绑定了虚构手机号（1390000xxxx 段）：`nurse.demo` 13900000001、`doctor.demo` 13900000002、`platform.admin` 13900000003。`local` / `demo` 使用模拟短信：不发到真实手机。点「获取验证码」后打开 <http://127.0.0.1:18080/dev/mock-sms>，或在 `backend/crdp-server.log` 里搜「模拟短信」，页面和日志里能看到验证码，手机号是脱敏的。平台端演示关闭了强制双因素，密码可以直接登录。要改回腾讯云，设置 `crdp.sms.provider=tencent` 并打开 `crdp.sms.enabled`。

本地数据库口令写在 `docker-compose.yml` 里，用户 `crdp_app`，口令 `DevOnly#app2026`，root 口令 `DevOnly#root2026`。它们只用于本机，不能拿到生产。

加密：演示用本地主密钥做 AES-256-GCM 信封加密，患者敏感字段不是明文。`scripts/dev.sh` 使用 `CRDP_CRYPTO_LOCAL_KEY`（默认 `dev-only-not-a-production-key`）。这把钥匙和模拟短信都只服务虚构数据。**接入华为云 KMS 之前禁止导入真实患者数据。** `KmsKeyProvider` 仍在，留给以后把 `crdp.crypto.mode` 改回 `kms`。

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

后端容器监听 18080，默认 `SPRING_PROFILES_ACTIVE=demo`（本地信封加密，仅虚构数据）。前端容器以非 root 用户监听 8080，并把 `/api` 转到名为 `crdp-server` 的服务。演示要自己注入能连上的 `CRDP_PLATFORM_JDBC_URL`、`CRDP_PLATFORM_DB_USERNAME`、`CRDP_PLATFORM_DB_PASSWORD`、`CRDP_REDIS_HOST`。`prod` 在 KMS 接入前会拒绝启动。

## 演示和生产的边界

华为云 KMS、腾讯云短信、生产 RDS / OBS 都还没接。演示走 `demo` 剖面。细节和限制见 [docs/ops/demo-mode.md](docs/ops/demo-mode.md)。
