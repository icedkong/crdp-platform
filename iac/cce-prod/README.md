# E22-T05 · CCE 生产集群、ELB、WAF、证书与 HSS 主机防护 IaC

> 按 `AGENTS.md` 第 9 节：涉及华为云真实资源的部分**不在本地执行**。本目录是可评审的 IaC 与三级基线，
> `terraform apply` 由持云账号的运维在维护窗口执行（步骤与验证清单见 `docs/ops/cce-prod.md`）。

## 交付范围（对照 tasks.csv E22-T05 验收「生产环境按三级基线配置」）

- **CCE 生产集群**：集群 + 每可用区一个节点池（`cn-north-4a/b` 各 1 节点，共 2 个工作节点，
  NFR-AVL-003「K8s 跨两个及以上可用区」）；节点密钥对登录、固定规模（不开自动扩缩）。
- **ELB（独享型）**：跨两个可用区挂 EIP；HTTPS 443 监听（TLS 套件 `tls-1-2-fs-with-1-3`，
  即 TLS 1.2/1.3 + 前向保密，NFR-SEC-001「TLS 1.2 及以上」、NFR-CRY-001「禁用弱套件」）、
  HTTP/2；80 端口只做 `REDIRECT_TO_LISTENER` 强制跳转；后端按节点 IP + NodePort 注册，
  健康检查 `/actuator/health`。
- **证书**：`*.平台域名` 通配符证书（tech-plan §3 登录节）由运维在 SCM 购买 / 导入后导出 PEM，
  经 `TF_VAR_*` 注入，ELB 与 WAF 用同一张；私钥不落盘、不入库。
- **WAF**：云模式（DNS CNAME 接入，回源 ELB 443），防护策略 `block`（生产阻断），
  TLS v1.2 + `cipher_2`（provider 文档：与 PCI 3DS/DSS 要求同档），基线防护项
  （基础防护 / 常规检测 / Webshell / 扫描器 / CC / 黑白名单 / 精准防护）全部开启。
- **HSS**：企业版配额（每节点一席，漏洞管理 + 安全基线 + 入侵检测），逐节点开启主机防护；
  **不开展病毒查杀任务**（tech-plan §2 部署表口径，上传防护由应用层类型白名单承担）。
- **安全组最小开放**（tech-plan §4 入侵防范）：ELB 安全组对公网只开 80/443；
  节点回源安全组只接受来自 ELB 安全组的 NodePort 30080。

## 文件

| 文件 | 作用 |
|---|---|
| `cce-baseline.json` | 三级基线与各组件配置（唯一事实源，校验脚本与 runbook 清单都对照它） |
| `versions.tf` | terraform ≥ 1.5、huaweicloud ≥ 1.65（`hss_host_protection` 1.63 / `hss_quota` 1.65 起提供） |
| `variables.tf` | 网络与规格输入（VPC / 子网沿用 E01-T06；证书 PEM、密钥对等敏感值经变量注入） |
| `main.tf` | 安全组、CCE 集群与节点池、ELB（证书 / 监听 / 跳转 / 后端 / 健康检查）、WAF（证书 / 策略 / 域名）、HSS（配额 / 主机防护） |
| `k8s/*.yaml` | 应用工作负载清单：2 副本、滚动发布不停机、`/actuator/health` 探针、非 root（与 Dockerfile USER 一致）、可用区打散、NodePort 回源 |
| `outputs.tf` | 集群 / 节点 / EIP / WAF 域名 / HSS 配额 ID（供验证清单与 E22-T09、E23 引用） |
| `terraform.tfvars.example` | 变量样例；真实值经环境变量或 `terraform.tfvars`（不入库）注入 |

## 使用（持云账号的运维执行）

```bash
cd iac/cce-prod
terraform init
terraform validate
# 证书 PEM 经 SCM 导出后以 TF_VAR 注入，不落盘、不进 shell 历史
export TF_VAR_tls_certificate_pem="$(cat fullchain.pem)"
export TF_VAR_tls_private_key_pem="$(cat privkey.pem)"
terraform plan -var-file=terraform.tfvars   # 人工核对：双可用区、TLS 套件、WAF block、HSS 企业版、80/443 最小开放
terraform apply
```

验收所需的「三级基线逐项核对」按 `docs/ops/cce-prod.md` 的清单执行并留痕；
依赖状态与前置（E01-T06 网络、DNS 接入、SCM 证书）也在该手册说明。
