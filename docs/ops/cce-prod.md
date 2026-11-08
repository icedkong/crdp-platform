# CCE 生产环境三级基线运维手册（集群、ELB、WAF、证书与 HSS）

> 任务 E22-T05。按 `AGENTS.md` 第 9 节，涉及华为云真实资源的操作**不在代码仓库内执行**：
> 本手册与 `iac/cce-prod/` 是可评审的交付物，实际开通与核验由持云账号的运维执行并按 §6 留痕。
>
> 依据：`docs/requirements/tech-plan.md` §2 部署表（CCE 2 节点跨 2 可用区、ELB 独享型 + WAF、
> *.主域名 通配符证书、HSS 每节点 Agent）、§3 登录（租户子域名需通配符证书）、§4 等保三级
> 主要落地点（传输 / 入侵防范 / 恶意代码防范）、§14（99.9%：应用 2 副本跨可用区、滚动发布）；
> `docs/requirements/platform-spec.md` NFR-AVL-003 / 005、NFR-SEC-001 / 006 / 007、NFR-CMP-001；
> `docs/open-questions.md` OQ-55（无华为云账号，IaC 先行、云上动作「待账号」）。
> 依赖：E22-T03（CES 告警与 LTS 日志，尚未落地——告警项见 §5「待接入」）；
> E01-T06（北京四 VPC / 子网）同样尚未落地，网络 ID 由运维在开通时提供。

## 1. 链路与事实源

```
浏览器 → DNS（CNAME）→ 华为云 WAF（云模式，block）→ ELB 独享型（TLS 1.2+ 终止、80→443 跳转）
       → CCE 节点 NodePort 30080 → crdp-server 2 副本（跨可用区打散）
```

事实源：`iac/cce-prod/cce-baseline.json`（CI 由 `scripts/check_cce_baseline.py` 校验，
防止基线 JSON、Terraform、K8s 清单与 `backend/Dockerfile` 四处漂移）。下表与其一致，改动时两处同步。

## 2. 三级基线对照表（验收「生产环境按三级基线配置」逐项口径）

| 等保 / 需求出处 | 控制点 | 生产配置 | 事实源字段 |
|---|---|---|---|
| NFR-AVL-003 | 计算高可用 | CCE 集群，`cn-north-4a/b` 各 1 个节点池、每池 1 节点；应用 2 副本按 `topology.kubernetes.io/zone` 打散 | `availability_zones`、`cce.nodes_per_az`、`workload.topology_spread_zone` |
| NFR-AVL-005 / tech-plan §14 | 滚动发布不停机 | 2 副本 + `maxUnavailable: 0` / `maxSurge: 1`，就绪探针 `/actuator/health` | `workload.rolling_*`、`workload.readiness_probe` |
| NFR-SEC-001 / NFR-CRY-001 | 传输保密 | ELB HTTPS 监听 TLS 套件 `tls-1-2-fs-with-1-3`（TLS 1.2/1.3 + 前向保密，禁弱套件）；80 端口仅 `REDIRECT_TO_LISTENER` 跳转；WAF 侧 TLS v1.2 + `cipher_2`（与 PCI 3DS/DSS 同档） | `elb.tls_policy`、`elb.http_redirect_from_port`、`waf.tls`、`waf.cipher` |
| tech-plan §3 | 租户子域名 | `*.平台域名` 通配符证书（SCM 购买 / 导入），ELB 与 WAF 同一张；平台端独立域名一并接入 WAF | `elb.certificate_type: wildcard` |
| tech-plan §4 入侵防范 | WAF | 云模式阻断（`protection_mode: block`）：基础防护、常规检测、Webshell、扫描器 / 爬虫引擎、CC、黑白名单、精准防护全开 | `waf.options.*` |
| tech-plan §2 部署表 | 主机防护 | HSS 企业版（漏洞管理 + 安全基线 + 入侵检测），每节点一席、逐节点开防护；节点密钥对登录 | `hss.version: hss.version.enterprise`、`cce.node_login: key_pair` |
| tech-plan §2 部署表 | 恶意代码 | HSS **不开展病毒查杀任务**；上传文件防护由应用层类型白名单承担（残余风险见 tech-plan §10） | `hss.scheduled_antivirus_scan: false` |
| tech-plan §4 入侵防范 | 最小开放 | ELB 安全组对公网仅 80/443；节点回源安全组仅接受 ELB 安全组 → NodePort 30080；RDS 白名单只放行本集群节点（E22-T04 手册 §3.4） | `security_groups.*` |
| tech-plan §4 身份鉴别 | 应用容器 | 容器以 UID 10001 非 root 运行（与 `backend/Dockerfile` 的 `USER 10001` 一致）、禁提权、丢弃全部 capabilities | `workload.run_as_*` |
| NFR-SEC-006 | 依赖与镜像 | 镜像存 SWR 私有仓库并开启漏洞扫描（随 E01-T05 SWR 落地；CI 侧依赖扫描已在流水线） | runbook §5 |

## 3. 前置条件

1. **网络（E01-T06，未落地）**：北京四 VPC、应用子网、ELB 子网三个 ID 写入 tfvars；
   本 IaC 与 `iac/rds-prod` 同一 VPC（RDS 白名单放行节点安全组由运维在 RDS 侧配置）。
2. **通配符证书**：SCM 申请 / 导入 `*.平台域名` 证书（平台端独立域名若非同一注册域，需另购证书并
   加一张 `elb_certificate` / `waf_certificate`）；导出 PEM 后经 `TF_VAR_tls_certificate_pem` /
   `TF_VAR_tls_private_key_pem` 注入，不落盘、不进 shell 历史（与 rds-prod 的口令同口径）。
3. **CCE 节点附加安全组**：`main.tf` 的节点池 `security_groups` 是**附加**安全组（回源用），
   CCE 集群默认安全组（节点互通 / kubelet 等集群端口）由 CCE 维护；apply 前在控制台确认
   该参数行为与预期一致（tech-plan §2：CCE 规格购买前在控制台确认）。
4. **DNS**：WAF 域名接入后在 DNS 把 `{租户编码}.平台域名` / 平台端域名 CNAME 到 WAF 分配的
   接入地址（`waf_domain` 的 `access_code` 输出），接入状态 `access_status=1` 才放流量。

## 4. 开通步骤（持云账号运维）

```bash
cd iac/cce-prod
terraform init && terraform validate
export TF_VAR_tls_certificate_pem="$(cat fullchain.pem)"
export TF_VAR_tls_private_key_pem="$(cat privkey.pem)"
terraform plan -var-file=terraform.tfvars    # 人工核对 §2 表的关键取值
terraform apply
```

应用工作负载（kubectl 需通过 IAM / rbac 获取，运维经 CBH 堡垒机操作，NFR-SEC-007）：

```bash
kubectl apply -f iac/cce-prod/k8s/namespace.yaml
# CRDP_* 环境变量从 DEW 凭据管理装配成 Secret（数据库 / OBS / DEW 凭据、签名密钥；不进镜像、不进 Git）
kubectl -n crdp create secret generic crdp-server-env --from-env-file=crdp-prod.env
kubectl -n crdp create secret docker-registry swr-credentials ...   # SWR 长期凭证（或改用 IAM 委托）
kubectl apply -f iac/cce-prod/k8s/deployment.yaml -f iac/cce-prod/k8s/service.yaml
```

JDBC 连接串指向 `iac/rds-prod` 输出的 `private_dns_names` 并带
`useSSL=true&connectionTimeZone=Asia/Shanghai`（rds-prod 手册 §3.4）。

## 5. HSS 与可观测细节

- **配额与防护**：企业版按月购买、每节点一席（`count` 与节点数联动）；`is_wait_host_available=true`
  等节点 Agent 在线（最长 30 分钟）。CCE 节点默认安装 HSS Agent，apply 前在控制台确认节点
  Agent 在线再开防护。
- **基线检查**：HSS「安全基线」用等保三级检查模板对全部节点执行，发现不合规项按 §6 记录处置。
- **病毒查杀**：不建扫描任务（tech-plan §2 口径）；测评「恶意代码防范」的说明材料见 tech-plan §10。
- **告警（E22-T03 待接入）**：HSS 告警、ELB 监控指标、应用日志（LTS）的告警通道依赖 E22-T03
  落地；本任务先完成资源侧，告警规则随后补齐并在 §6 追加核验。
- **运维通道**：生产集群 kubectl / 节点 SSH 全部经 CBH 云堡垒机，生产数据库禁止直连（tech-plan §4）。

## 6. 验证清单（三级基线逐项核对，执行后留痕到 §8）

- [ ] `terraform output` 输出节点 `node_server_ids` 共 2 个、分属两个可用区；`kubectl get nodes -L topology.kubernetes.io/zone` 一致。
- [ ] `kubectl -n crdp get deploy crdp-server`：2 副本就绪、滚动参数为 maxUnavailable=0 / maxSurge=1。
- [ ] `curl -sI http://<平台端域名>/` 返回 301/302 且 `Location: https://...`（80 只跳转）。
- [ ] `curl -sI https://<平台端域名>/actuator/health`：证书为通配符 `*.平台域名`、协议 TLS 1.2+（`openssl s_client -tls1_1` 应握手失败、`-tls1_2` 成功）、响应头含 `Strict-Transport-Security`（应用经 `server.forward-headers-strategy: framework` + Spring Security 默认 HSTS 输出）。
- [ ] WAF 控制台：两个域名防护状态「开启（阻断）」、接入状态「已接入」；用任意 Web 攻击样例（如 `?id=1' or '1'='1`）访问被 WAF 拦截并产生事件（只做验证用虚构请求，不留业务数据）。
- [ ] HSS 控制台：2 台主机全部「防护中（企业版）」、Agent 在线；漏洞与安全基线检查各执行一次，高危项清零或记处置。
- [ ] 安全组核对：ELB 组仅 80/443 对公网；节点回源组仅 ELB 组→30080；节点无公网 IP；RDS 白名单仅节点安全组。
- [ ] 镜像与依赖：SWR 中当前镜像漏洞扫描无高危（NFR-SEC-006）；CI 依赖扫描绿。
- [ ] 冒烟：医院端登录 → 写一条随访记录（虚构数据）→ 查询可见（走 WAF→ELB→CCE 全链路）。
- [ ] 发布演练：改镜像标签滚动更新一次，期间 `/actuator/health` 持续 200（NFR-AVL-005）。

## 7. 与后续任务的衔接

| 任务 | 依赖本任务的什么 |
|---|---|
| E22-T09 渗透测试 | 入口链路（WAF/ELB）与主机防护就位后的靶机环境；WAF 阻断事件作为测试旁证 |
| E22-T03 监控告警 | ELB / CCE / HSS 指标与日志对象清单（`terraform output` 的资源 ID） |
| E23-T11 / E23-T26 上线切换 | 生产入口（DNS 切换到 WAF CNAME）与应用副本就绪 |
| E22-T07 跨区域恢复演练 | IaC 重建入口（本目录 + `iac/rds-prod` 在上海一重建） |

## 8. 验证留痕模板

每次执行 §6 清单后填写（存档到运维 wiki，本仓库不留执行记录）：

| 项 | 值 |
|---|---|
| 日期 / 执行人 | |
| 集群 ID / 节点 ECS ID（AZ） | |
| ELB EIP / 证书指纹 | |
| TLS 检测结果（1.1 拒绝 / 1.2+ 通过 / HSTS） | |
| WAF 域名接入与阻断验证结果 | |
| HSS 主机防护 / 漏洞 / 基线检查结果 | |
| 滚动发布观测（健康检查中断次数） | |
| 未通过项与处置 | |
