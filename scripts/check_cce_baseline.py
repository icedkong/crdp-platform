#!/usr/bin/env python3
"""校验 CCE 生产环境三级基线（iac/cce-prod/cce-baseline.json）与 IaC / K8s 清单不漂移。

E22-T05 的交付物是 IaC 与文档草稿（AGENTS.md 第 9 节：涉及真实云资源的部分不在本地执行），
所以「生产环境按三级基线配置」的本地落点与 E22-T04（check_rds_baseline.py）同构，分四层静态校验：
1. 基线 JSON 可解析、字段齐全、取值符合规格出处（tech-plan §2 部署表、§3 登录（通配符证书）、
   §4 等保三级落地点（传输 / 入侵防范 / 恶意代码防范）；platform-spec NFR-AVL-003 / 005、
   NFR-SEC-001、NFR-CMP-001）；
2. K8s 工作负载基线（副本 / 滚动 / 探针 / 非 root / 可用区打散）与 backend/Dockerfile
   （EXPOSE 端口、USER）交叉核对；
3. main.tf 确实从同一份 JSON 取值（jsondecode(file(...))）并声明了 CCE 集群、节点池、
   ELB（独享型 + HTTPS 监听 + HTTP 跳转）、WAF（证书 / 策略 / 域名）、HSS（配额 / 主机防护）
   与最小开放安全组资源，防止 HCL 与 JSON 两处各写一套；
4. k8s/*.yaml 清单的关键取值（副本数、滚动参数、探针路径、NodePort、非 root）与基线一致。

主仓库：https://github.com/icedkong/crdp-platform
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IAC_DIR = ROOT / "iac/cce-prod"
BASELINE = IAC_DIR / "cce-baseline.json"
MAIN_TF = IAC_DIR / "main.tf"
DOCKERFILE = ROOT / "backend/Dockerfile"
DEPLOYMENT_YAML = IAC_DIR / "k8s/deployment.yaml"
SERVICE_YAML = IAC_DIR / "k8s/service.yaml"

SOURCE_REGION = "cn-north-4"  # tech-plan §1：华为云华北-北京四

# ELB 监听器 tls_ciphers_policy 只允许 TLS 1.2 及以上的强套件（NFR-SEC-001 / NFR-CRY-001：
# 传输 TLS 1.2+、禁用弱套件；取值域见 provider elb_listener 文档）
STRONG_TLS_POLICIES = {
    "tls-1-2",
    "tls-1-2-strict",
    "tls-1-2-fs",
    "tls-1-2-fs-with-1-3",
    "tls-1-2-strict-no-cbc",
}
WEAK_TLS_POLICIES = {
    "tls-1-0-inherit": "继承监听器默认（含 TLS 1.0），不满足 TLS 1.2+",
    "tls-1-0": "允许 TLS 1.0",
    "tls-1-1": "允许 TLS 1.1",
    "tls-1-0-with-1-3": "允许 TLS 1.0",
    "hybrid-policy-1-0": "兼容国密的同时放开了 TLS 1.0",
}

# HSS 版本：基础版没有漏洞管理与安全基线检查，不满足「入侵检测、漏洞与基线检查」口径
ALLOWED_HSS_VERSIONS = {
    "hss.version.advanced": "专业版",
    "hss.version.enterprise": "企业版",
    "hss.version.premium": "旗舰版",
    "hss.version.container.enterprise": "容器版（企业）",
}

# main.tf 必须包含的声明：基线单源引用 + 五类资源（CCE / ELB / WAF / HSS / 安全组）
REQUIRED_TF_TOKENS = [
    'jsondecode(file("${path.module}/cce-baseline.json"))',
    "huaweicloud_cce_cluster",
    "huaweicloud_cce_node_pool",
    "huaweicloud_elb_certificate",
    "huaweicloud_elb_loadbalancer",
    "huaweicloud_elb_listener",
    "huaweicloud_elb_l7policy",
    "huaweicloud_elb_pool",
    "huaweicloud_elb_monitor",
    "huaweicloud_elb_member",
    "huaweicloud_waf_certificate",
    "huaweicloud_waf_policy",
    "huaweicloud_waf_domain",
    "huaweicloud_hss_quota",
    "huaweicloud_hss_host_protection",
    "huaweicloud_networking_secgroup",
    "huaweicloud_networking_secgroup_rule",
    # 基线关键取值必须来自 JSON（防漂移）：TLS 套件、防护模式、HSS 版本
    "local.baseline.elb.tls_policy",
    "local.baseline.waf.protection_mode",
    "local.baseline.hss.version",
]

# K8s 清单里允许向公网（0.0.0.0/0）开放的端口：只有 ELB 的 80/443
PUBLIC_INGRESS_PORTS = {80, 443}


def fail(problems: list, message: str) -> None:
    problems.append(message)


def check_top(baseline: dict, problems: list) -> None:
    if baseline.get("source_region") != SOURCE_REGION:
        fail(problems, f"source_region 应为 {SOURCE_REGION}，实际 {baseline.get('source_region')!r}")
    azs = baseline.get("availability_zones")
    if not isinstance(azs, list) or len(azs) < 2 or len(set(azs)) != len(azs):
        # NFR-AVL-003：K8s 跨两个及以上可用区
        fail(problems, f"availability_zones 应为两个及以上不重复可用区（NFR-AVL-003），实际 {azs!r}")
    else:
        for az in azs:
            if not az.startswith(SOURCE_REGION):
                fail(problems, f"可用区 {az!r} 不在 {SOURCE_REGION}（华北-北京四）内")


def check_cce(cce: dict, problems: list) -> None:
    if cce.get("cluster_type") != "VirtualMachine":
        fail(problems, f"cce.cluster_type 应为 VirtualMachine，实际 {cce.get('cluster_type')!r}")
    if cce.get("container_network_type") != "vpc-router":
        fail(problems, f"cce.container_network_type 应为 vpc-router，实际 {cce.get('container_network_type')!r}")
    if cce.get("authentication_mode") != "rbac":
        fail(problems, f"cce.authentication_mode 应为 rbac，实际 {cce.get('authentication_mode')!r}")
    per_az = cce.get("nodes_per_az")
    if not isinstance(per_az, int) or per_az < 1:
        fail(problems, f"cce.nodes_per_az 应为 ≥1 的整数（每可用区至少 1 个工作节点），实际 {per_az!r}")
    if not cce.get("node_os"):
        fail(problems, "cce.node_os 不能为空（节点操作系统需固定，便于 HSS Agent 与基线检查）")
    if cce.get("node_login") != "key_pair":
        # 等保「身份鉴别」：节点登录只走密钥对，禁止密码
        fail(problems, f"cce.node_login 应为 key_pair（节点禁用密码登录），实际 {cce.get('node_login')!r}")
    if cce.get("autoscaling") is not False:
        fail(problems, "cce.autoscaling 应为 false（首期固定节点规模，扩缩由人工评审）")


def check_workload(workload: dict, problems: list) -> None:
    if workload.get("replicas") != 2:
        # tech-plan §2 部署表：应用 2 副本；§14：99.9% 可用性
        fail(problems, f"workload.replicas 应为 2（tech-plan §2 部署表），实际 {workload.get('replicas')!r}")
    if workload.get("rolling_max_unavailable") != 0:
        # NFR-AVL-005：滚动发布不停机
        fail(problems, f"workload.rolling_max_unavailable 应为 0（NFR-AVL-005 滚动发布不停机），"
                       f"实际 {workload.get('rolling_max_unavailable')!r}")
    surge = workload.get("rolling_max_surge")
    if not isinstance(surge, int) or surge < 1:
        fail(problems, f"workload.rolling_max_surge 应为 ≥1 的整数，实际 {surge!r}")
    for key in ("readiness_probe", "liveness_probe"):
        if workload.get(key) != "/actuator/health":
            fail(problems, f"workload.{key} 应为 /actuator/health，实际 {workload.get(key)!r}")
    node_port = workload.get("node_port")
    if not isinstance(node_port, int) or not (30000 <= node_port <= 32767):
        fail(problems, f"workload.node_port 应为 30000–32767（K8s NodePort 范围），实际 {node_port!r}")
    if workload.get("run_as_non_root") is not True:
        fail(problems, "workload.run_as_non_root 应为 true（容器以非 root 运行，与 Dockerfile USER 一致）")
    if workload.get("topology_spread_zone") is not True:
        # NFR-AVL-003：副本跨可用区打散，单可用区故障不丢全部副本
        fail(problems, "workload.topology_spread_zone 应为 true（副本按可用区打散）")

    # 与 backend/Dockerfile 交叉核对：容器端口与运行用户必须同源
    if DOCKERFILE.exists():
        text = DOCKERFILE.read_text(encoding="utf-8")
        expose = re.search(r"^EXPOSE\s+(\d+)\s*$", text, re.MULTILINE)
        user = re.search(r"^USER\s+(\d+)\s*$", text, re.MULTILINE)
        if not expose:
            fail(problems, "backend/Dockerfile 缺少 EXPOSE 行，无法核对容器端口")
        elif int(expose.group(1)) != workload.get("container_port"):
            fail(problems, f"workload.container_port={workload.get('container_port')!r} 与 Dockerfile "
                           f"EXPOSE {expose.group(1)} 不一致")
        if not user:
            fail(problems, "backend/Dockerfile 缺少 USER 行，无法核对运行用户")
        elif int(user.group(1)) != workload.get("run_as_user"):
            fail(problems, f"workload.run_as_user={workload.get('run_as_user')!r} 与 Dockerfile "
                           f"USER {user.group(1)} 不一致")
    else:
        fail(problems, "缺少 backend/Dockerfile，无法核对容器端口与运行用户")


def check_elb(elb: dict, problems: list) -> None:
    if elb.get("type") != "dedicated":
        # tech-plan §2 部署表：入口 = ELB（独享型）+ WAF
        fail(problems, f"elb.type 应为 dedicated（独享型），实际 {elb.get('type')!r}")
    if elb.get("https_port") != 443:
        fail(problems, f"elb.https_port 应为 443，实际 {elb.get('https_port')!r}")
    policy = elb.get("tls_policy")
    if policy in WEAK_TLS_POLICIES:
        fail(problems, f"elb.tls_policy={policy!r} 不允许：{WEAK_TLS_POLICIES[policy]}（NFR-SEC-001）")
    elif policy not in STRONG_TLS_POLICIES:
        fail(problems, f"elb.tls_policy 应为 {sorted(STRONG_TLS_POLICIES)} 之一，实际 {policy!r}")
    if elb.get("http2") is not True:
        fail(problems, "elb.http2 应为 true")
    if elb.get("certificate_type") != "wildcard":
        # tech-plan §3 登录：租户子域名需要 *.平台域名 通配符证书
        fail(problems, f"elb.certificate_type 应为 wildcard（*.平台域名 通配符证书），"
                       f"实际 {elb.get('certificate_type')!r}")
    redirect = elb.get("http_redirect_from_port")
    if redirect != 80:
        # 强制 HTTPS：80 端口只做跳转（等保传输保密性）
        fail(problems, f"elb.http_redirect_from_port 应为 80（HTTP 强制跳转 HTTPS），实际 {redirect!r}")
    if elb.get("backend_protocol") != "HTTP":
        fail(problems, f"elb.backend_protocol 应为 HTTP（TLS 在 ELB 终止），实际 {elb.get('backend_protocol')!r}")
    if elb.get("health_check_path") != "/actuator/health":
        fail(problems, f"elb.health_check_path 应为 /actuator/health，实际 {elb.get('health_check_path')!r}")
    if elb.get("lb_method") != "ROUND_ROBIN":
        fail(problems, f"elb.lb_method 应为 ROUND_ROBIN，实际 {elb.get('lb_method')!r}")


def check_waf(waf: dict, problems: list) -> None:
    if waf.get("enabled") is not True:
        fail(problems, "waf.enabled 应为 true（E22-T05：WAF 是三级基线入侵防范的组成部分）")
    if waf.get("mode") != "cloud":
        fail(problems, f"waf.mode 应为 cloud（云模式，DNS CNAME 接入 WAF 后回源 ELB），实际 {waf.get('mode')!r}")
    if waf.get("protection_mode") != "block":
        # 生产基线：检测到攻击直接阻断；log 模式只用于上线初期调观察窗
        fail(problems, f"waf.protection_mode 应为 block（生产阻断），实际 {waf.get('protection_mode')!r}")
    if waf.get("tls") != "TLS v1.2":
        fail(problems, f"waf.tls 应为 'TLS v1.2'（WAF 侧最低版本），实际 {waf.get('tls')!r}")
    if waf.get("cipher") != "cipher_2":
        # provider waf_domain 文档：pci_3ds / pci_dss 要求 TLS v1.2 + cipher_2，取同级基线
        fail(problems, f"waf.cipher 应为 cipher_2（与 PCI 3DS/DSS 同档套件），实际 {waf.get('cipher')!r}")
    options = waf.get("options")
    if not isinstance(options, dict) or not options:
        fail(problems, "waf.options 应为非空对象")
        return
    for name in (
        "basic_web_protection",
        "general_check",
        "webshell",
        "crawler_scanner",
        "cc_attack_protection",
        "blacklist",
        "precise_protection",
    ):
        if options.get(name) is not True:
            fail(problems, f"waf.options.{name} 应为 true（三级基线必开防护项）")


def check_hss(hss: dict, problems: list) -> None:
    version = hss.get("version")
    if version not in ALLOWED_HSS_VERSIONS:
        fail(problems, f"hss.version 应为 {sorted(ALLOWED_HSS_VERSIONS)} 之一"
                       f"（基础版无漏洞管理 / 基线检查），实际 {version!r}")
    if hss.get("protect_all_nodes") is not True:
        fail(problems, "hss.protect_all_nodes 应为 true（每个节点开防护，tech-plan §2 部署表）")
    if hss.get("scheduled_antivirus_scan") is not False:
        # tech-plan §2 部署表：HSS 首期只做入侵检测、漏洞与基线检查，不开展病毒查杀任务
        fail(problems, "hss.scheduled_antivirus_scan 应为 false（首期不开展病毒查杀，tech-plan §2）")
    if hss.get("charging_mode") not in ("prePaid", "postPaid"):
        fail(problems, f"hss.charging_mode 应为 prePaid / postPaid，实际 {hss.get('charging_mode')!r}")


def check_security_groups(groups: dict, problems: list) -> None:
    elb_ingress = groups.get("elb_ingress")
    if not isinstance(elb_ingress, list) or not elb_ingress:
        fail(problems, "security_groups.elb_ingress 应为非空数组")
        return
    public_ports = set()
    for rule in elb_ingress:
        port = rule.get("port")
        if rule.get("protocol") != "tcp":
            fail(problems, f"elb_ingress 规则 {rule!r} 协议应为 tcp")
        if rule.get("source") == "0.0.0.0/0":
            public_ports.add(port)
    if public_ports != PUBLIC_INGRESS_PORTS:
        # 入侵防范：对公网只开放 80/443，80 仅跳转（check_elb 已保证）
        fail(problems, f"对公网（0.0.0.0/0）只允许开放 {sorted(PUBLIC_INGRESS_PORTS)}，实际 {sorted(public_ports)}")
    node_ingress = groups.get("node_ingress_from_elb")
    if not isinstance(node_ingress, list) or not node_ingress:
        fail(problems, "security_groups.node_ingress_from_elb 应为非空数组")
    else:
        for rule in node_ingress:
            if rule.get("source") != "elb":
                fail(problems, f"node_ingress_from_elb 规则来源应为 elb 安全组，实际 {rule.get('source')!r}")
            if rule.get("port") not in (30000, 32767) and not (
                isinstance(rule.get("port"), int) and 30000 <= rule.get("port") <= 32767
            ):
                fail(problems, f"node_ingress_from_elb 端口应为 NodePort（30000–32767），实际 {rule.get('port')!r}")


def check_main_tf(problems: list) -> None:
    if not MAIN_TF.exists():
        fail(problems, f"缺少 {MAIN_TF.relative_to(ROOT)}")
        return
    text = MAIN_TF.read_text(encoding="utf-8")
    for token in REQUIRED_TF_TOKENS:
        if token not in text:
            fail(problems, f"main.tf 缺少声明片段：{token}")


def yaml_scalar(yaml_text: str, key: str, problems: list) -> str:
    """取 K8s 清单里 `key: value` 的最后一个匹配（策略字段在清单中唯一出现）。"""
    matches = re.findall(rf"^\s*{re.escape(key)}:\s*(\S+)\s*$", yaml_text, re.MULTILINE)
    if not matches:
        fail(problems, f"K8s 清单缺少字段 {key}")
        return ""
    return matches[-1]


def yaml_block(yaml_text: str, key: str):
    """取 K8s 清单里 `key:` 块的子内容（缩进比 key 行更深的所有行，遇到同级即停）。

    用缩进而不是「任意更深缩进的正则」界定，防止把同级下一个块（如 readinessProbe 之后的
    livenessProbe）误并进来，导致改坏前一个块仍被后一个块的同名行掩盖。
    """
    head = re.search(rf"^(\s*){re.escape(key)}:\s*$", yaml_text, re.MULTILINE)
    if not head:
        return None
    indent = len(head.group(1))
    deeper = " " * (indent + 1)
    lines: list = []
    for line in yaml_text[head.end() :].splitlines(keepends=True):
        if line.strip() and not line.startswith(deeper):
            break
        lines.append(line)
    return "".join(lines)


def check_k8s(workload: dict, problems: list) -> None:
    for path in (DEPLOYMENT_YAML, SERVICE_YAML):
        if not path.exists():
            fail(problems, f"缺少 {path.relative_to(ROOT)}")
    if not (DEPLOYMENT_YAML.exists() and SERVICE_YAML.exists()):
        return

    deployment = DEPLOYMENT_YAML.read_text(encoding="utf-8")
    if int(yaml_scalar(deployment, "replicas", problems) or 0) != workload.get("replicas"):
        fail(problems, "k8s/deployment.yaml replicas 与基线 workload.replicas 不一致")
    if yaml_scalar(deployment, "maxUnavailable", problems) != str(workload.get("rolling_max_unavailable")):
        fail(problems, "k8s/deployment.yaml maxUnavailable 与基线 workload.rolling_max_unavailable 不一致")
    if yaml_scalar(deployment, "maxSurge", problems) != str(workload.get("rolling_max_surge")):
        fail(problems, "k8s/deployment.yaml maxSurge 与基线 workload.rolling_max_surge 不一致")
    if yaml_scalar(deployment, "runAsNonRoot", problems) != "true":
        fail(problems, "k8s/deployment.yaml runAsNonRoot 应为 true")
    if yaml_scalar(deployment, "runAsUser", problems) != str(workload.get("run_as_user")):
        fail(problems, "k8s/deployment.yaml runAsUser 与基线 workload.run_as_user 不一致")
    if yaml_scalar(deployment, "allowPrivilegeEscalation", problems) != "false":
        fail(problems, "k8s/deployment.yaml allowPrivilegeEscalation 应为 false（容器提权防护）")
    if "topology.kubernetes.io/zone" not in deployment:
        fail(problems, "k8s/deployment.yaml 缺少 topology.kubernetes.io/zone 打散约束")
    for probe in ("livenessProbe", "readinessProbe"):
        block = yaml_block(deployment, probe)
        if block is None or "path: /actuator/health" not in block:
            fail(problems, f"k8s/deployment.yaml {probe} 应探活 /actuator/health")

    service = SERVICE_YAML.read_text(encoding="utf-8")
    if "type: NodePort" not in service:
        fail(problems, "k8s/service.yaml 应为 NodePort（ELB 以节点池 IP + NodePort 回源）")
    node_port = yaml_scalar(service, "nodePort", problems)
    if node_port != str(workload.get("node_port")):
        fail(problems, f"k8s/service.yaml nodePort={node_port!r} 与基线 workload.node_port 不一致")
    port = yaml_scalar(service, "port", problems)
    if port != str(workload.get("container_port")):
        fail(problems, f"k8s/service.yaml port={port!r} 与基线 workload.container_port 不一致")


def main() -> int:
    problems: list = []
    if not BASELINE.exists():
        print(f"缺少 {BASELINE.relative_to(ROOT)}（E22-T05 CCE 生产基线）")
        return 1
    try:
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"cce-baseline.json 不是合法 JSON：{exc}")
        return 1

    check_top(baseline, problems)
    check_cce(baseline.get("cce") or {}, problems)
    check_workload(baseline.get("workload") or {}, problems)
    check_elb(baseline.get("elb") or {}, problems)
    check_waf(baseline.get("waf") or {}, problems)
    check_hss(baseline.get("hss") or {}, problems)
    check_security_groups(baseline.get("security_groups") or {}, problems)
    check_main_tf(problems)
    check_k8s(baseline.get("workload") or {}, problems)

    if problems:
        print(f"CCE 生产基线校验未通过，共 {len(problems)} 处：")
        for message in problems:
            print(f"  - {message}")
        return 1
    print("CCE 生产基线校验通过：集群 / 工作负载 / ELB / WAF / HSS / 安全组与 IaC、K8s 清单一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
