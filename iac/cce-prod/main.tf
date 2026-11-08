# E22-T05：CCE 生产集群、ELB（独享型）、WAF、通配符证书与 HSS 主机防护。
# 事实源是同目录 cce-baseline.json（本地 / CI 由 scripts/check_cce_baseline.py 校验），
# 可用区、TLS 套件、WAF 防护模式、HSS 版本、NodePort 等关键取值都从该 JSON 读取，避免两处漂移。
# 真实 apply 由持华为云账号的运维在维护窗口执行（AGENTS.md 第 9 节），步骤见 docs/ops/cce-prod.md。
#
# 链路：DNS（CNAME）→ 华为云 WAF（云模式，阻断）→ ELB 独享型（TLS 1.2+ 终止、HTTP→HTTPS 跳转）
#       → CCE 节点 NodePort → 应用 2 副本（跨可用区打散）。tech-plan §2 部署表 / §3 架构图。

locals {
  baseline = jsondecode(file("${path.module}/cce-baseline.json"))

  node_total = local.baseline.cce.nodes_per_az * length(local.baseline.availability_zones)
}

# ---------------------------------------------------------------------------
# 安全组：最小开放（tech-plan §4 入侵防范）。公网只到 ELB 的 80/443；
# 节点只接受来自 ELB 安全组的 NodePort 回源流量。
# ---------------------------------------------------------------------------

resource "huaweicloud_networking_secgroup" "elb" {
  name        = "${var.cluster_name}-elb"
  description = "crdp 生产 ELB 入口安全组（E22-T05）：仅放行 80/443"
}

resource "huaweicloud_networking_secgroup_rule" "elb_ingress" {
  for_each = { for r in local.baseline.security_groups.elb_ingress : r.port => r }

  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = each.value.protocol
  port_range_min    = each.value.port
  port_range_max    = each.value.port
  remote_ip_prefix  = each.value.source
  security_group_id = huaweicloud_networking_secgroup.elb.id
}

resource "huaweicloud_networking_secgroup" "node_app" {
  name        = "${var.cluster_name}-node-app"
  description = "crdp 生产节点回源安全组（E22-T05）：仅放行 ELB → NodePort"
}

resource "huaweicloud_networking_secgroup_rule" "node_app_ingress" {
  for_each = { for r in local.baseline.security_groups.node_ingress_from_elb : r.port => r }

  direction         = "ingress"
  ethertype         = "IPv4"
  protocol          = each.value.protocol
  port_range_min    = each.value.port
  port_range_max    = each.value.port
  remote_group_id   = huaweicloud_networking_secgroup.elb.id
  security_group_id = huaweicloud_networking_secgroup.node_app.id
}

# ---------------------------------------------------------------------------
# CCE 集群与节点池：每个可用区一个节点池，固定节点数（NFR-AVL-003 跨两个可用区；
# tech-plan §2 部署表：2 个工作节点分布在 2 个可用区）。节点登录只走密钥对。
# CCE 集群默认安全组（节点互通 / kubelet 等集群端口）由 CCE 维护，本 IaC 只附加回源安全组。
# ---------------------------------------------------------------------------

resource "huaweicloud_cce_cluster" "prod" {
  name                    = var.cluster_name
  flavor_id               = var.cluster_flavor_id
  cluster_type            = local.baseline.cce.cluster_type
  cluster_version         = var.cluster_version != "" ? var.cluster_version : null
  vpc_id                  = var.vpc_id
  subnet_id               = var.subnet_id
  container_network_type  = local.baseline.cce.container_network_type
  service_network_cidr    = var.service_network_cidr
  authentication_mode     = local.baseline.cce.authentication_mode
  delete_all              = "false" # 销毁集群不级联删除关联存储（销毁流程另见 platform-spec）
  tags                    = var.tags
}

resource "huaweicloud_cce_node_pool" "prod" {
  for_each = { for az in local.baseline.availability_zones : az => az }

  cluster_id         = huaweicloud_cce_cluster.prod.id
  name               = "${var.cluster_name}-pool-${replace(each.key, "${var.region}", "")}"
  flavor_id          = var.node_flavor_id
  availability_zone  = each.key
  initial_node_count = local.baseline.cce.nodes_per_az
  os                 = local.baseline.cce.node_os
  key_pair           = var.node_key_pair
  scale_enable       = local.baseline.cce.autoscaling
  security_groups    = [huaweicloud_networking_secgroup.node_app.id]

  root_volume {
    size       = var.node_root_volume_gb
    volumetype = var.node_root_volume_type
  }

  data_volumes {
    size       = var.node_data_volume_gb
    volumetype = var.node_data_volume_type
  }

  tags = var.tags
}

# 节点实例清单：ELB 后端成员与 HSS 主机防护都按实际节点逐一登记
data "huaweicloud_cce_nodes" "prod" {
  cluster_id = huaweicloud_cce_cluster.prod.id

  depends_on = [huaweicloud_cce_node_pool.prod]
}

locals {
  node_server_ids = [for n in data.huaweicloud_cce_nodes.prod.nodes : n.server_id]
  node_private_ips = [for n in data.huaweicloud_cce_nodes.prod.nodes : n.private_ip]
}

# ---------------------------------------------------------------------------
# 通配符证书（*.平台域名，tech-plan §3 登录节）：PEM 经变量注入，ELB 与 WAF 用同一张。
# ---------------------------------------------------------------------------

resource "huaweicloud_elb_certificate" "wildcard" {
  name        = "${var.cluster_name}-wildcard"
  type        = "server"
  certificate = var.tls_certificate_pem
  private_key = var.tls_private_key_pem
}

resource "huaweicloud_waf_certificate" "wildcard" {
  name        = var.waf_certificate_name
  certificate = var.tls_certificate_pem
  private_key = var.tls_private_key_pem
}

# ---------------------------------------------------------------------------
# ELB 独享型：跨可用区，EIP 挂公网；HTTPS 443（TLS 1.2+ 强套件、HTTP/2），
# HTTP 80 只做 REDIRECT_TO_LISTENER 跳转（强制 HTTPS）。
# ---------------------------------------------------------------------------

resource "huaweicloud_vpc_eip" "elb" {
  publicip {
    type = "5_bgp"
  }

  bandwidth {
    name        = "${var.cluster_name}-elb"
    size        = var.elb_bandwidth_size
    share_type  = "PER"
    charge_mode = "bandwidth"
  }
}

resource "huaweicloud_elb_loadbalancer" "prod" {
  name          = "${var.cluster_name}-elb"
  ipv4_subnet_id = var.elb_subnet_id
  ipv4_eip_id   = huaweicloud_vpc_eip.elb.id
  # 独享型（guaranteed）跨两个可用区（tech-plan §2 部署表：ELB 跨可用区）
  availability_zone = local.baseline.availability_zones
  charging_mode     = var.elb_charging_mode
  tags              = var.tags
}

resource "huaweicloud_elb_listener" "https" {
  name            = "${var.cluster_name}-https"
  protocol        = "HTTPS"
  protocol_port   = local.baseline.elb.https_port
  loadbalancer_id = huaweicloud_elb_loadbalancer.prod.id
  # NFR-SEC-001 / NFR-CRY-001：TLS 1.2 及以上、禁用弱套件（tls-1-2-fs-with-1-3 含前向保密与 TLS 1.3）
  tls_ciphers_policy = local.baseline.elb.tls_policy
  server_certificate = huaweicloud_elb_certificate.wildcard.id
  http2_enable       = local.baseline.elb.http2
}

resource "huaweicloud_elb_listener" "http_redirect" {
  name            = "${var.cluster_name}-http-redirect"
  protocol        = "HTTP"
  protocol_port   = local.baseline.elb.http_redirect_from_port
  loadbalancer_id = huaweicloud_elb_loadbalancer.prod.id
}

# 80 端口的唯一用途：整监听器跳转到 HTTPS（等保传输保密性：明文 HTTP 不承载业务）
resource "huaweicloud_elb_l7policy" "to_https" {
  name                 = "${var.cluster_name}-http-to-https"
  action               = "REDIRECT_TO_LISTENER"
  listener_id          = huaweicloud_elb_listener.http_redirect.id
  redirect_listener_id = huaweicloud_elb_listener.https.id
}

resource "huaweicloud_elb_pool" "app" {
  name        = "${var.cluster_name}-app"
  protocol    = local.baseline.elb.backend_protocol
  lb_method   = local.baseline.elb.lb_method
  listener_id = huaweicloud_elb_listener.https.id
  type        = "instance"
  vpc_id      = var.vpc_id
}

resource "huaweicloud_elb_monitor" "app" {
  pool_id     = huaweicloud_elb_pool.app.id
  protocol    = local.baseline.elb.health_check_protocol
  interval    = 5
  timeout     = 3
  max_retries = 3
  url_path    = local.baseline.elb.health_check_path
}

# 后端 = 各节点在 NodePort 上的应用端口（service.yaml type: NodePort）
resource "huaweicloud_elb_member" "app" {
  count = length(local.node_private_ips)

  pool_id       = huaweicloud_elb_pool.app.id
  address       = local.node_private_ips[count.index]
  protocol_port = local.baseline.workload.node_port
  subnet_id     = var.subnet_id
}

# ---------------------------------------------------------------------------
# WAF 云模式：阻断策略 + 基线防护项；域名经 CNAME 接入，回源 ELB 的 443。
# ---------------------------------------------------------------------------

resource "huaweicloud_waf_policy" "prod" {
  name = "${var.cluster_name}-baseline"

  # 生产基线：检测到攻击直接阻断（观察期调规则用 log，调完必须改回）
  protection_mode = local.baseline.waf.protection_mode

  options {
    basic_web_protection  = local.baseline.waf.options.basic_web_protection
    general_check         = local.baseline.waf.options.general_check
    webshell              = local.baseline.waf.options.webshell
    crawler_engine        = local.baseline.waf.options.crawler_engine
    crawler_scanner       = local.baseline.waf.options.crawler_scanner
    cc_attack_protection  = local.baseline.waf.options.cc_attack_protection
    blacklist             = local.baseline.waf.options.blacklist
    precise_protection    = local.baseline.waf.options.precise_protection
  }
}

resource "huaweicloud_waf_domain" "prod" {
  for_each = toset([var.hospital_wildcard_domain, var.platform_domain])

  domain           = each.value
  certificate_id   = huaweicloud_waf_certificate.wildcard.id
  certificate_name = var.waf_certificate_name
  policy_id        = huaweicloud_waf_policy.prod.id
  protect_status   = 1 # 防护开启（0 暂停 / -1 bypass）
  # WAF 侧最低 TLS 版本与套件档位（与 PCI 3DS/DSS 要求同档）
  tls    = local.baseline.waf.tls
  cipher = local.baseline.waf.cipher

  server {
    client_protocol = "HTTPS"
    server_protocol = "HTTPS"
    address         = huaweicloud_vpc_eip.elb.address
    port            = local.baseline.elb.https_port
    type            = "ipv4"
  }
}

# ---------------------------------------------------------------------------
# HSS 主机防护：企业版配额（漏洞管理 + 安全基线 + 入侵检测），逐节点开启防护。
# 病毒查杀任务不开展（tech-plan §2 部署表），上传防护由应用层类型白名单承担（§4）。
# ---------------------------------------------------------------------------

resource "huaweicloud_hss_quota" "prod" {
  count = local.node_total

  version     = local.baseline.hss.version
  period_unit = var.hss_period_unit
  period      = var.hss_period
  auto_renew  = "true"
  tags        = var.tags
}

resource "huaweicloud_hss_host_protection" "prod" {
  count = length(local.node_server_ids)

  host_id                   = local.node_server_ids[count.index]
  version                   = local.baseline.hss.version
  charging_mode             = local.baseline.hss.charging_mode
  quota_id                  = huaweicloud_hss_quota.prod[count.index].id
  is_wait_host_available    = true # 等节点 Agent 在线（最长 30 分钟）
}
