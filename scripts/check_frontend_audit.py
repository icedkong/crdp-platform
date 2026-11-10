#!/usr/bin/env python3
"""前端生产依赖漏洞门禁（G7 / M3 审查，E01-T04「高危漏洞阻断合并」）。

跑 `pnpm audit --prod --audit-level high`（真实阻断，不是 `|| true`），HIGH / CRITICAL
即失败；已评估接受的例外按公告号登记在 ALLOWLIST（带原因，上线前清零）——例外之外
任何新高危都会把 CI 打红。仓库根运行：python3 scripts/check_frontend_audit.py

主仓库：https://github.com/icedkong/crdp-platform
"""

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FRONTEND = ROOT / "frontend"

BLOCKING_SEVERITIES = {"high", "critical"}

# 已评估接受的例外（上线前清零；新增例外必须在 PR 里写明评估结论）：
#   GHSA-mrhx-c7xc-m3w5  zod <4.6.6（high，可耗尽内存）：Vben 上游 packages/@core/ui-kit/form-ui
#   锁 zod v3（catalog ^3.25.76，@vee-validate/zod 适配器同代）；升 zod v4 属上游大版本迁移，
#   随 Vben 升级统一处理。暴露面：前端表单校验库，输入来自本院登录用户的表单页，非公开服务。
ALLOWLIST: dict[str, str] = {
    "GHSA-mrhx-c7xc-m3w5": "Vben 上游 form-ui 锁 zod v3，升 v4 随上游大版本迁移；上线前清零",
}

GHSA_OF_URL = re.compile(r"/(GHSA-[a-z0-9-]+)$")


def main() -> int:
    proc = subprocess.run(
        ["pnpm", "audit", "--prod", "--audit-level", "high", "--json"],
        cwd=FRONTEND,
        capture_output=True,
        text=True,
    )
    # pnpm audit 有发现时退出码非 0；解析失败（如断网）也按失败处理，不静默放行
    try:
        report = json.loads(proc.stdout or "{}")
    except json.JSONDecodeError:
        print("pnpm audit 输出无法解析（stdout 前 500 字符）：")
        print((proc.stdout or "")[:500])
        print(proc.stderr[:500])
        return 1

    advisories = report.get("advisories") or {}
    failures: list[str] = []
    accepted: list[str] = []
    for advisory in advisories.values():
        severity = str(advisory.get("severity", "")).lower()
        if severity not in BLOCKING_SEVERITIES:
            continue
        ghsa = GHSA_OF_URL.search(str(advisory.get("url", "")))
        key = ghsa.group(1) if ghsa else str(advisory.get("id"))
        line = (
            f"  [{severity}] {advisory.get('module_name')} "
            f"{advisory.get('vulnerable_versions')} → 修复版 {advisory.get('patched_versions')} "
            f"| {key} | {advisory.get('title')}"
        )
        if key in ALLOWLIST:
            accepted.append(line + f"\n      已接受：{ALLOWLIST[key]}")
        else:
            failures.append(line)

    if accepted:
        print(f"已接受的高危例外 {len(accepted)} 条（上线前清零，见 ALLOWLIST）：")
        print("\n".join(accepted))

    if failures:
        print(f"\n发现未接受的高危 / 严重漏洞 {len(failures)} 条，阻断合并（E01-T04）：")
        print("\n".join(failures))
        print("\n处理：优先升级补丁版；确实无法升级的按 ALLOWLIST 登记原因并另行评估。")
        return 1

    print("前端生产依赖高危 / 严重漏洞门禁通过（除已登记例外）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
