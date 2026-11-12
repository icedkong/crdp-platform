# 网络拓扑（等保测评资料 · 占位符版）

> E22-T13 资料四类之一（清单 `README.md` M-06）。真实 VPC / 子网 / 实例 ID、域名与 IP **不入仓库**
> （OQ-133），本草稿统一用占位符（`<平台域名>`、`<VPC-ID>` 等），正式版（M-15）由持账号运维替换并附资产清单。
>
> 事实源（CI 防漂移，改动时与本图同步）：`iac/cce-prod/cce-baseline.json`（`scripts/check_cce_baseline.py`）、
> `iac/rds-prod/params-baseline.json`（`scripts/check_rds_baseline.py`）、
> `iac/obs-archive/worm-baseline.json`（`scripts/check_worm_baseline.py`）；
> 链路口径与 `docs/ops/cce-prod.md` §1、RDS 口径与 `docs/ops/rds-prod.md` §1 一致。
> 安全设计的控制点落点见 `security-design.md` §3。

## 1. 总体拓扑（生产 · 华为云华北-北京四 cn-north-4）

```text
                          ┌─ 互联网用户（医院 A / B / C：浏览器）
                          ├─ 平台运营方（浏览器，平台端独立域名）
                          └─ 手机拍照上传 H5（一次性令牌）
        │
        ▼
  DNS（CNAME：{租户编码}.<平台域名> / 平台端域名 → WAF 接入地址）
        │
        ▼
  华为云 WAF（云模式，block：基础防护 / Webshell / 扫描器 / CC / 黑白名单 / 精准防护；
             TLS v1.2 + cipher_2，与 ELB 同一张 *.平台域名 通配符证书）
        │
        ▼
  ELB 独享型（HTTPS 443 终止：TLS 套件 tls-1-2-fs-with-1-3，禁弱套件；80 仅 301/302 跳转 443；
             跨可用区；后端 CCE 节点 NodePort 30080）
        │  回源安全组：仅 ELB 安全组 → 30080
        ▼
  CCE 集群（应用子网；节点池 ×2：cn-north-4a / cn-north-4b 各 1 节点，密钥对登录、无公网 IP，
           HSS 企业版 Agent 每节点）
  └─ crdp-server × 2 副本（topology.kubernetes.io/zone 打散；容器 UID 10001 非 root；
     滚动发布 maxUnavailable=0 / maxSurge=1；就绪探针 /actuator/health）
        │
        ├──────────────► RDS for MySQL 8.0 主备（数据子网；cn-north-4a + cn-north-4b，semisync、
        │                 reliability 切换；SSL 3306；白名单只放行本集群节点安全组；
        │                 平台库 crdp_platform + 每院一库 t_{租户编码}）
        ├──────────────► DCS Redis 主备（登录态与权限版本缓存；丢失只要求重新登录）
        ├──────────────► OBS 业务桶（每院一个：多可用区、SSE-KMS、版本控制、跨区域复制到上海一）
        ├──────────────► OBS 审计归档桶（单可用区；桶级 WORM 合规模式默认保留 15 年 +
        │                 对象级保留 = 归档日 + 15 年；30 天转归档 / 180 天转深度归档；无自动删除）
        └──────────────► DEW / KMS（每院一个主密钥 + 凭据管理：数据库 / OBS / 签名密钥凭据注入）

  出网：NAT 网关 + 固定 EIP ──HTTPS──► 腾讯云短信 API（短信内容不含患者信息，A-NFR-007）
  运维面：IAM（人员账号 / 权限）──► CBH 云堡垒机 ──► CCE 节点 SSH / kubectl / RDS 控制台
        （生产数据库禁止直连；堡垒机会话审计，NFR-SEC-007）
  可观测：LTS（应用与审计日志，带 tenantId）· CES（指标与告警：接口错误率、作业失败、
        RDS 复制状态、WAF / HSS 事件，E22-T03）

  跨区域容灾：RDS 跨区域备份（北京四 → 上海一 cn-east-3，全量 + Binlog，保留 30 天）
        + iac/ IaC 在上海一重建（演练实测 RPO / RTO，docs/ops/cross-region-drill.md）
        + OBS 业务桶跨区域复制；审计归档 WORM 只留北京四一份（已确认，见安全设计 §3.4）
```

## 2. 信任域与边界

| 信任域 | 组成 | 边界规则 |
|---|---|---|
| 公网边界 | WAF、ELB（含 80/443 监听） | 唯一业务入口；WAF 阻断模式；ELB 安全组对公网仅 80/443，80 只跳转 |
| 应用区 | CCE 节点、crdp-server 副本 | 回源安全组仅接受 ELB 安全组 → NodePort 30080；节点无公网 IP、密钥对登录 |
| 数据区 | RDS、DCS Redis、OBS、DEW/KMS | RDS 白名单只放行集群节点安全组；KMS / OBS 经 IAM 授权子账号（归档桶专用账号只授该桶读写） |
| 运维区 | IAM、CBH 云堡垒机 | 云控制面与节点运维全部经堡垒机；生产数据库禁止直连 |
| 出网 | NAT 网关 + 固定 EIP | 仅短信 API（HTTPS）；出网地址固定可加白 |

## 3. 端口矩阵（最小开放）

| 源 | 目标 | 端口 / 协议 | 用途 |
|---|---|---|---|
| 公网 | ELB | 443 / HTTPS（TLS 1.2+） | 业务流量（医院端 + 平台端） |
| 公网 | ELB | 80 / HTTP | 仅 301/302 跳转到 443，无业务 |
| ELB 安全组 | CCE 节点 | 30080 / TCP（NodePort） | 回源唯一通路 |
| CCE 节点 | RDS | 3306 / MySQL（SSL） | 数据库连接（白名单放行） |
| CCE 节点 | OBS / DEW / DCS | 服务端口（内网 / TLS） | 对象存储、密钥与凭据、缓存 |
| CCE 节点（NAT 出网） | 腾讯云短信 API | 443 / HTTPS | 通知短信（固定 EIP） |
| CBH 堡垒机 | CCE 节点 / 控制台 | SSH / kubectl（ IAM 授权） | 运维通道（会话审计） |

除上表外无对公网开放端口；WAF、HSS、LTS、CES 为旁路安全与可观测组件，不开放业务端口。

## 4. 跨可用区与跨区域

- **可用区内冗余（cn-north-4a / cn-north-4b）**：CCE 节点池跨 AZ、应用副本按 AZ 打散；
  RDS 主备跨 AZ 半同步（RPO 约 0、分钟级自动切换）；ELB 跨 AZ。单 AZ 故障不中断服务（99.9% 口径）。
- **跨区域（北京四 → 上海一 cn-east-3）**：RDS 跨区域备份（异步，官方不保证及时性，区域级 RPO
  以演练实测为准）；IaC 重建 + 恢复演练半年一次（实测区域内 RPO ≤ 5 分钟、RTO ≤ 4 小时目标，
  `docs/ops/cross-region-drill.md`、存档 `docs/ops/drill-records/`）；OBS 业务桶跨区域复制。
  审计归档 WORM 桶不做异地副本（OBS WORM 桶不能作跨区域复制源，已确认接受）。

## 5. 环境矩阵

| 环境 | 用途 | 数据 | 说明 |
|---|---|---|---|
| 开发 | 日常开发（本机 compose） | 全部虚构（种子 demoa / demob，「（示例）」标记） | `./scripts/dev.sh` |
| 测试（含 UAT / 渗透靶机） | 集成、UAT、渗透测试靶机 | 虚构 + 脱敏样本（`scripts/deidentify_sample.py`） | 与生产同构独立环境（`docs/ops/pentest.md` §4） |
| 生产 | 医院 A / B / C 在用 | 真实患者数据 | **生产数据不出生产环境**；运维经堡垒机 |

三套环境同一套 IaC / 镜像基线；测试环境与生产环境数据物理隔离，生产数据不导入开发 / 测试环境
（`AGENTS.md` §5.5；样本接收与脱敏见 `docs/ops/hospital-a-sample-intake.md`）。

## 6. 正式版补齐口径（由人，M-15）

- 以本图与 §2 / §3 表为底稿，替换全部占位符：真实 VPC / 子网 ID、安全组 ID、CCE 集群与节点
  ECS ID、ELB EIP 与证书指纹、RDS 实例 ID、OBS 桶名、域名清单；
- 附资产清单（IP、域名、云服务实例台账、责任人）与云服务清单（华为云服务 + 腾讯云短信），
  纳入 P-11 供应链与云服务管理；
- 替换后核对与三份基线 JSON 一致（`terraform output` 与控制台截图留痕，存档运维 wiki）。
