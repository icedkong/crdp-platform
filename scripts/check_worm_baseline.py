#!/usr/bin/env python3
"""校验审计归档 OBS WORM 桶基线（iac/obs-archive/worm-baseline.json）与 IaC / 应用配置不漂移。

E22-T08 的交付物是 IaC、归档演练与文档（AGENTS.md 第 9 节：涉及真实云资源的部分不在本地执行），
验收「归档对象在保留期内不可删改」的本地证据落在三处，本脚本做静态防漂移（CI 恒跑）：

1. 基线 JSON 可解析、桶形态与保留策略符合规格（单可用区、桶级 WORM 合规 15 年、对象级 15 年、
   在线 12 个月、30 天转归档、180 天转深度归档、无 expiration、无跨区域副本；出处见各条校验）；
2. main.tf 确实从同一份 JSON 取值并声明了桶与 30 天转归档规则，且不含 provider 不支持的
   DEEP_ARCHIVE、任何 expiration（到期不自动删除）与 replication_configuration（只写北京四一份）；
3. 应用侧同口径：application.yml 在线 12 个月 / 15 年 / 默认 storage.type=none；
   ObsAuditArchiveStorage 走 COMPLIANCE + setObjectRetention；端口无删除语义。

桶级 WORM 开启与 180 天转深度归档两步 provider 不支持，由运维按 docs/ops/audit-worm-archive.md
落实（OQ-123），本脚本校验 runbook 覆盖了这些人工步骤与演练记录模板。

主仓库：https://github.com/icedkong/crdp-platform
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "iac/obs-archive/worm-baseline.json"
MAIN_TF = ROOT / "iac/obs-archive/main.tf"
APP_YML = ROOT / "backend/crdp-server/src/main/resources/application.yml"
ADAPTER = ROOT / "backend/crdp-framework/src/main/java/com/crdp/framework/storage/ObsAuditArchiveStorage.java"
PORT = ROOT / "backend/crdp-framework/src/main/java/com/crdp/framework/storage/AuditArchiveStorage.java"
RUNBOOK = ROOT / "docs/ops/audit-worm-archive.md"
CI = ROOT / ".github/workflows/ci.yml"

SOURCE_REGION = "cn-north-4"  # tech-plan §1：华为云华北-北京四；归档只写一份

# main.tf 必须出现的声明：基线单源引用 + 桶 + 单可用区 + 版本控制 + 30 天转归档（COLD）
REQUIRED_TF_TOKENS = [
    'jsondecode(file("${path.module}/worm-baseline.json"))',
    "huaweicloud_obs_bucket",
    "multi_az = local.baseline.bucket.multi_az",
    "versioning = local.baseline.bucket.versioning",
    "lifecycle_rule",
    "transition {",
    "local.baseline.lifecycle[0].days",
    "local.baseline.lifecycle[0].storage_class",
]

# main.tf 禁止出现：自动删除（到期不自动删除）、跨区域副本（只写北京四一份）、
# 多可用区（单可用区）、provider 不支持的 DEEP_ARCHIVE（由控制台 / API 配置，写在基线里即可）。
FORBIDDEN_TF_PATTERNS = {
    r"\bexpiration\b": "不得配置 expiration（归档对象到期后默认不自动删除，tech-plan §14）",
    r"noncurrent_version_(expiration|transition)": "不得配置非当前版本删除 / 转换（同上，不引入任何自动删除）",
    r"replication_configuration": "不得配置跨区域复制（只写北京四一份，tech-plan §14 已确认）",
    r"multi_az\s*=\s*true": "归档桶必须单可用区（tech-plan §14；归档 / 深度归档类别本身仅支持单 AZ）",
    r"DEEP_ARCHIVE": "provider transition 仅支持 WARM/COLD，180 天转深度归档由控制台 / API 配置（OQ-123）",
}

# runbook 必须覆盖的人工步骤与演练要素（运维按文档执行，脚本只防文档缺失）
REQUIRED_RUNBOOK_TOKENS = [
    "桶级 WORM",
    "COMPLIANCE",
    "15 年",
    "obsutil rm",  # 生产验证：删除尝试必须失败（验收「不可删改」的直接证据）
    "DEEP_ARCHIVE",
    "专用账号",  # ADR 0005：归档专用账号（DB 删分区权限收敛 + OBS 桶 AK/SK）
    "演练记录",
]


def fail(problems: list[str], message: str) -> None:
    problems.append(message)


def check_baseline(baseline: dict, problems: list[str]) -> None:
    if baseline.get("region") != SOURCE_REGION:
        fail(problems, f"region 应为 {SOURCE_REGION}（北京四），实际 {baseline.get('region')!r}")

    bucket = baseline.get("bucket") or {}
    if bucket.get("storage_class") != "STANDARD":
        fail(problems, f"bucket.storage_class 应为 STANDARD（写入期标准存储），实际 {bucket.get('storage_class')!r}")
    if bucket.get("acl") != "private":
        fail(problems, f"bucket.acl 应为 private，实际 {bucket.get('acl')!r}")
    if bucket.get("multi_az") is not False:
        fail(problems, "bucket.multi_az 应为 false（单可用区，tech-plan §14）")
    if bucket.get("versioning") is not True:
        fail(problems, "bucket.versioning 应为 true（WORM 开启后自动启用且不可停用）")

    worm = baseline.get("worm") or {}
    if worm.get("mode") != "COMPLIANCE":
        fail(problems, f"worm.mode 应为 COMPLIANCE（OBS 仅支持合规模式），实际 {worm.get('mode')!r}")
    for field, expected, meaning in (
        ("default_retention_years", 15, "桶级默认保留 15 年"),
        ("object_retention_years", 15, "对象级保留 = 归档日 + 15 年"),
        ("online_retention_months", 12, "在线保留 1 年"),
    ):
        if worm.get(field) != expected:
            fail(problems, f"worm.{field} 应为 {expected}（{meaning}，PLT-AUD-010），实际 {worm.get(field)!r}")
    if worm.get("retention_extend_only") is not True:
        fail(problems, "worm.retention_extend_only 应为 true（保留期只能延长，不能缩短）")

    lifecycle = baseline.get("lifecycle")
    if not isinstance(lifecycle, list) or len(lifecycle) != 2:
        fail(problems, f"lifecycle 应为两条（30 天 COLD + 180 天 DEEP_ARCHIVE），实际 {lifecycle!r}")
    else:
        first, second = lifecycle
        if first.get("days") != 30 or first.get("storage_class") != "COLD":
            fail(problems, f"lifecycle[0] 应为 30 天 → COLD（归档存储），实际 {first!r}")
        if first.get("managed_by") != "terraform":
            fail(problems, "lifecycle[0].managed_by 应为 terraform（30 天转归档在 IaC 内）")
        if second.get("days") != 180 or second.get("storage_class") != "DEEP_ARCHIVE":
            fail(problems, f"lifecycle[1] 应为 180 天 → DEEP_ARCHIVE（深度归档），实际 {second!r}")
        if second.get("managed_by") != "console-or-api":
            fail(problems, "lifecycle[1].managed_by 应为 console-or-api（provider 不支持，OQ-123）")

    if baseline.get("no_expiration") is not True:
        fail(problems, "no_expiration 应为 true（到期后默认不自动删除）")
    if baseline.get("no_replication") is not True:
        fail(problems, "no_replication 应为 true（只写北京四一份，不做异地副本）")
    if baseline.get("object_key_prefix") != "audit/":
        fail(problems, f"object_key_prefix 应为 audit/（AuditArchiveService 键前缀），实际 {baseline.get('object_key_prefix')!r}")


def check_main_tf(problems: list[str]) -> None:
    if not MAIN_TF.exists():
        fail(problems, f"缺少 {MAIN_TF.relative_to(ROOT)}")
        return
    text = MAIN_TF.read_text(encoding="utf-8")
    for token in REQUIRED_TF_TOKENS:
        if token not in text:
            fail(problems, f"main.tf 缺少声明片段：{token}")
    # 禁改项只查代码行：注释里说明「有意不配置」的词（expiration / replication_configuration / DEEP_ARCHIVE）不算配置
    code_only = "\n".join(line.split("#", 1)[0] for line in text.splitlines())
    for pattern, reason in FORBIDDEN_TF_PATTERNS.items():
        if re.search(pattern, code_only):
            fail(problems, f"main.tf 不应包含 {pattern}：{reason}")


def check_application_yaml(problems: list[str]) -> None:
    if not APP_YML.exists():
        fail(problems, f"缺少 {APP_YML.relative_to(ROOT)}")
        return
    text = APP_YML.read_text(encoding="utf-8")
    for token, meaning in (
        ("online-retention-months: 12", "在线保留 1 年"),
        ("worm-retention-years: 15", "WORM 锁定 15 年"),
        ("type: none", "归档桶默认未接入（本机 / CI 无云凭证，作业只做分区维护）"),
    ):
        if token not in text:
            fail(problems, f"application.yml 缺少 {token}（{meaning}）")


def check_adapter_and_port(problems: list[str]) -> None:
    if not ADAPTER.exists():
        fail(problems, f"缺少 {ADAPTER.relative_to(ROOT)}（E22-T08 OBS 归档桶适配器）")
    else:
        text = ADAPTER.read_text(encoding="utf-8")
        for token in ("COMPLIANCE", "setObjectRetention"):
            if token not in text:
                fail(problems, f"ObsAuditArchiveStorage 缺少 {token}（对象级合规保留）")
    if not PORT.exists():
        fail(problems, f"缺少 {PORT.relative_to(ROOT)}")
        return
    port_text = PORT.read_text(encoding="utf-8")
    if re.search(r"(void|byte\[\])\s+(delete|remove|overwrite)\w*\s*\(", port_text):
        fail(problems, "AuditArchiveStorage 端口不得出现 delete / remove / overwrite 方法（WORM 无删除语义）")


def check_runbook_and_ci(problems: list[str]) -> None:
    if not RUNBOOK.exists():
        fail(problems, f"缺少 {RUNBOOK.relative_to(ROOT)}（生产开通 / 演练 runbook）")
    else:
        text = RUNBOOK.read_text(encoding="utf-8")
        for token in REQUIRED_RUNBOOK_TOKENS:
            if token not in text:
                fail(problems, f"runbook 缺少要素：{token}")
    if not CI.exists():
        fail(problems, f"缺少 {CI.relative_to(ROOT)}")
    else:
        ci_text = CI.read_text(encoding="utf-8")
        if "check_worm_baseline.py" not in ci_text:
            fail(problems, "ci.yml 未接入 check_worm_baseline.py（防漂移校验须进 CI）")


def main() -> int:
    problems: list[str] = []
    if not BASELINE.exists():
        print(f"缺少 {BASELINE.relative_to(ROOT)}（E22-T08 WORM 基线）")
        return 1
    try:
        baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"worm-baseline.json 不是合法 JSON：{exc}")
        return 1

    check_baseline(baseline, problems)
    check_main_tf(problems)
    check_application_yaml(problems)
    check_adapter_and_port(problems)
    check_runbook_and_ci(problems)

    if problems:
        print(f"审计归档 WORM 基线校验未通过，共 {len(problems)} 处：")
        for message in problems:
            print(f"  - {message}")
        return 1
    print("审计归档 WORM 基线校验通过：桶形态、保留策略、生命周期与应用侧口径一致（PLT-AUD-010）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
