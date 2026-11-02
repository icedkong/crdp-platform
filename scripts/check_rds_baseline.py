#!/usr/bin/env python3
"""校验 RDS 生产实例基线（iac/rds-prod/params-baseline.json）与 IaC 不漂移。

E22-T04 的交付物是 IaC 与文档草稿（AGENTS.md 第 9 节：涉及真实云资源的部分不在本地执行），
所以「本地全绿」落在三处静态校验上：
1. 参数基线 JSON 可解析、字段齐全、取值符合规格（tech-plan / OQ / NFR 出处见各条校验）；
2. 禁改参数（复制与备份相关、实例级参数）不得混入参数模板；
3. main.tf 确实从同一份 JSON 取值（jsondecode(file(...))）并声明了主备、备份与跨区域备份资源，
   防止 HCL 与 JSON 两处各写一套。

主仓库：https://github.com/icedkong/crdp-platform
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "iac/rds-prod/params-baseline.json"
MAIN_TF = ROOT / "iac/rds-prod/main.tf"

SOURCE_REGION = "cn-north-4"  # tech-plan §1：华为云华北-北京四
DESTINATION_REGION = "cn-east-3"  # tech-plan §14：跨区域备份北京四 → 上海一（已确认）

# 参数模板必须出现的条目（值固定，出处见 docs/ops/rds-prod.md 参数基线表）
REQUIRED_PARAMS = {
    "character_set_server": "utf8mb4",
    "collation_server": "utf8mb4_0900_ai_ci",
    "log_bin_trust_function_creators": "ON",
    "long_query_time": "1",
}

# 禁止出现在参数模板里的参数：RDS 托管的复制 / 备份参数、只读开关，
# 以及已在实例级固定（time_zone、lower_case_table_names）不能再在模板里二义设置的参数。
FORBIDDEN_PARAMS = {
    "log_bin": "RDS 自动备份要求 binlog 常开（tech-plan §2 核实记录），不允许关",
    "skip_log_bin": "RDS 自动备份要求 binlog 常开（tech-plan §2 核实记录），不允许关",
    "sql_log_bin": "会话级 binlog 开关由 RDS 托管，不应进基线",
    "server_id": "主备复制参数由 RDS 托管",
    "gtid_mode": "主备复制参数由 RDS 托管",
    "enforce_gtid_consistency": "主备复制参数由 RDS 托管",
    "read_only": "实例读写状态由 RDS / 运维流程控制，不应进参数模板",
    "super_read_only": "实例读写状态由 RDS / 运维流程控制，不应进参数模板",
    "time_zone": "时区在实例级设置（time_zone = UTC+08:00），不在参数模板重复定义",
    "lower_case_table_names": "实例级创建参数（默认 1），不在参数模板重复定义",
}

# main.tf 必须包含的声明：基线单源引用 + 主备 + 备份策略 + 跨区域备份 + 参数组
REQUIRED_TF_TOKENS = [
    'jsondecode(file("${path.module}/params-baseline.json"))',
    "huaweicloud_rds_parametergroup",
    "huaweicloud_rds_instance",
    "huaweicloud_rds_cross_region_backup_strategy",
    "backup_strategy",
    "param_group_id",
    "ha_replication_mode",
]

TIME_WINDOW = re.compile(r"^\d{2}:\d{2}-\d{2}:\d{2}$")


def fail(problems: list[str], message: str) -> None:
    problems.append(message)


def check_instance(instance: dict, problems: list[str]) -> None:
    if instance.get("engine") != "MySQL":
        fail(problems, f"instance.engine 应为 MySQL（RDS for MySQL），实际 {instance.get('engine')!r}")
    if instance.get("engine_version") != "8.0":
        fail(problems, f"instance.engine_version 应为 8.0，实际 {instance.get('engine_version')!r}")
    if instance.get("time_zone") != "UTC+08:00":
        # OQ-56 ⑤：生产库时区为北京时间，与 JDBC connectionTimeZone=Asia/Shanghai 对齐
        fail(problems, f"instance.time_zone 应为 UTC+08:00（北京时间），实际 {instance.get('time_zone')!r}")
    if instance.get("lower_case_table_names") != "1":
        # RDS MySQL 默认 1；显式固化，避免与开发容器（Linux 默认 0）口径漂移
        fail(problems, f"instance.lower_case_table_names 应为 '1'，实际 {instance.get('lower_case_table_names')!r}")
    if instance.get("ha_replication_mode") != "semisync":
        # tech-plan §14：主备 RPO 约 0 —— 半同步复制
        fail(problems, f"instance.ha_replication_mode 应为 semisync，实际 {instance.get('ha_replication_mode')!r}")
    if instance.get("switch_strategy") != "reliability":
        fail(problems, f"instance.switch_strategy 应为 reliability（可靠性优先），实际 {instance.get('switch_strategy')!r}")
    azs = instance.get("availability_zones")
    if not isinstance(azs, list) or len(azs) != 2 or len(set(azs)) != 2:
        fail(problems, f"instance.availability_zones 应为两个不同可用区（主备跨可用区），实际 {azs!r}")
    else:
        for az in azs:
            if not az.startswith(SOURCE_REGION):
                fail(problems, f"可用区 {az!r} 不在 {SOURCE_REGION}（华北-北京四）内")
    if instance.get("ssl_enable") is not True:
        fail(problems, "instance.ssl_enable 应为 true（内网传输也走 SSL）")
    port = instance.get("port")
    if not isinstance(port, int) or not (1024 <= port <= 65535):
        fail(problems, f"instance.port 应为 1024–65535 的整数（RDS 限制），实际 {port!r}")


def check_backup(backup: dict, problems: list[str]) -> None:
    keep = backup.get("keep_days")
    if keep != 30:
        # NFR-AVL-004：备份保留 30 天
        fail(problems, f"backup.keep_days 应为 30（NFR-AVL-004），实际 {keep!r}")
    if not isinstance(keep, int) or not (1 <= keep <= 732):
        fail(problems, f"backup.keep_days 取值范围 1–732，实际 {keep!r}")
    start = backup.get("start_time")
    if not isinstance(start, str) or not TIME_WINDOW.match(start):
        fail(problems, f"backup.start_time 应为 hh:mm-HH:MM（RDS 备份窗口），实际 {start!r}")


def check_cross_region(cross: dict, problems: list[str]) -> None:
    if cross.get("enabled") is not True:
        fail(problems, "cross_region_backup.enabled 应为 true（E22-T04 验收：跨区域备份开启）")
    if cross.get("backup_type") != "all":
        # tech-plan §2 核实记录：全量与 Binlog 都可同步；区域内 RPO ≤ 5 分钟依赖增量
        fail(problems, f"cross_region_backup.backup_type 应为 all（全量 + Binlog），实际 {cross.get('backup_type')!r}")
    keep = cross.get("keep_days")
    if not isinstance(keep, int) or not (1 <= keep <= 1825):
        fail(problems, f"cross_region_backup.keep_days 取值范围 1–1825，实际 {keep!r}")
    if keep != 30:
        fail(problems, f"cross_region_backup.keep_days 与区域内备份对齐应为 30，实际 {keep!r}")
    dest = cross.get("destination_region")
    if dest != DESTINATION_REGION:
        fail(problems, f"cross_region_backup.destination_region 应为 {DESTINATION_REGION}（上海一，tech-plan §14），实际 {dest!r}")


def check_parameters(parameters: object, problems: list[str]) -> None:
    if not isinstance(parameters, list) or not parameters:
        fail(problems, "parameters 应为非空数组")
        return
    seen: dict[str, str] = {}
    for entry in parameters:
        if not isinstance(entry, dict):
            fail(problems, f"参数条目应为对象，实际 {entry!r}")
            continue
        for key in ("name", "value", "reason", "source"):
            if not entry.get(key):
                fail(problems, f"参数条目缺少 {key}：{entry!r}")
        name = entry.get("name")
        if not name:
            continue
        if name in seen:
            fail(problems, f"参数 {name} 重复定义")
        seen[name] = entry.get("value", "")
        if name in FORBIDDEN_PARAMS:
            fail(problems, f"参数 {name} 不允许进基线：{FORBIDDEN_PARAMS[name]}")
    for name, value in REQUIRED_PARAMS.items():
        if name not in seen:
            fail(problems, f"缺少必备参数 {name}（应为 {value}）")
        elif seen[name] != value:
            fail(problems, f"参数 {name} 应为 {value}，实际 {seen[name]!r}")


def check_main_tf(problems: list[str]) -> None:
    if not MAIN_TF.exists():
        fail(problems, f"缺少 {MAIN_TF.relative_to(ROOT)}")
        return
    text = MAIN_TF.read_text(encoding="utf-8")
    for token in REQUIRED_TF_TOKENS:
        if token not in text:
            fail(problems, f"main.tf 缺少声明片段：{token}")


def main() -> int:
    problems: list[str] = []
    if not BASELINE.exists():
        print(f"缺少 {BASELINE.relative_to(ROOT)}（E22-T04 参数基线）")
        return 1
    try:
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"params-baseline.json 不是合法 JSON：{exc}")
        return 1

    if baseline.get("source_region") != SOURCE_REGION:
        fail(problems, f"source_region 应为 {SOURCE_REGION}，实际 {baseline.get('source_region')!r}")

    check_instance(baseline.get("instance") or {}, problems)
    check_backup(baseline.get("backup") or {}, problems)
    check_cross_region(baseline.get("cross_region_backup") or {}, problems)
    check_parameters(baseline.get("parameters"), problems)
    check_main_tf(problems)

    if problems:
        print(f"RDS 生产基线校验未通过，共 {len(problems)} 处：")
        for message in problems:
            print(f"  - {message}")
        return 1
    print("RDS 生产基线校验通过：参数基线、主备 / 备份 / 跨区域配置与 IaC 一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
