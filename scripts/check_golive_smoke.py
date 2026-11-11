#!/usr/bin/env python3
"""上线核对与冒烟测试（E23-T12）文档与自动化用例防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E23-T12 行：类型 = 测试、依赖含 E23-T11、验收原句「冒烟用例通过」；
2. `docs/testing/hospital-a-golive-smoke.md` 冒烟手册：头部任务行（依赖 / 验收原句 / W36 排期）、
   用例表自洽（编号 S-0… 连续、执行面合法、条数声明一致、每行含步骤与预期）、口径锚点
   （runbook §3 的 60 分钟预算、§6 切换动作、§7 中止回滚、§8 衔接互引、hypercare 与 UAT 手册
   互引、数据红线「示例」标记、由人执行清单、自动化对照）；
3. `backend/.../followup/GoliveSmokeIT.java`：门控 CRDP_IT_MYSQL_PORT、local 剖面、
   自建自清标记 IT-E23T12、虚构数据「示例」标记，且手册「机器 CI」执行面的每条用例编号
   都在 IT 内登记（S-x 注释锚点），防止手册与自动化用例漂移；
4. 互引与接线：上线 runbook §8 指向本手册、`docs/README.md` 索引、ci.yml 含本校验步骤。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/hospital-a-golive-smoke.md"
IT = ROOT / "backend/crdp-server/src/test/java/com/crdp/server/followup/GoliveSmokeIT.java"
RUNBOOK = ROOT / "docs/ops/hospital-a-go-live.md"
HYPERCARE = ROOT / "docs/ops/hospital-a-hypercare.md"
UAT = ROOT / "docs/testing/uat-hospital-a.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"

# 用例行：| S-0 | 级别 | 执行面 | 角色 | 步骤 | 预期（依据） |
CASE_ROW_PATTERN = re.compile(r"^\|\s*(S-\d{1,2})\s*\|\s*([^|]+)\|\s*([^|]+)\|", re.M)
# 执行面列取值（机器 CI / 生产人工 的组合口径，与手册 §2 分工一致）
FACES = {"生产人工", "机器 CI + 生产人工"}
# 条数声明：共 N 条
COUNT_CLAIM_PATTERN = re.compile(r"共\s*(\d+)\s*条")
# IT 内用例登记锚点（测试方法上方注释 S-x；S-0/S-1 属生产人工，不要求）
IT_CASE_PATTERN = re.compile(r"//\s*(S-\d{1,2})[:：]")
PRODUCTION_ONLY_CASES = {"S-0", "S-1"}


def e23_t12_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T12":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    it = IT.read_text(encoding="utf-8") if IT.exists() else ""
    runbook = RUNBOOK.read_text(encoding="utf-8") if RUNBOOK.exists() else ""

    # ---- 1. tasks.csv 行 ----
    row = e23_t12_row()
    check("tasks.csv 有 E23-T12 行", row is not None)
    if row:
        check(
            "任务标题与类型（A 上线核对与冒烟测试 · 测试）",
            "上线核对与冒烟测试" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "测试",
        )
        check("依赖 E23-T11（与 runbook §8 衔接一致）", "E23-T11" in (row.get("依赖") or ""))
        check(
            "验收原句（冒烟用例通过）",
            (row.get("验收标准") or "").strip() == "冒烟用例通过",
        )

    # ---- 2. 冒烟手册 ----
    check("冒烟手册存在（docs/testing/hospital-a-golive-smoke.md）", bool(manual))
    check(
        "手册头部任务行（E23-T12 · 依赖 E23-T11 · 验收「冒烟用例通过」）",
        "任务 E23-T12" in manual and "E23-T11" in manual and "**冒烟用例通过**" in manual,
    )
    cases = CASE_ROW_PATTERN.findall(manual)
    ids = [case_id for case_id, _, _ in cases]
    faces = {case_id: face.strip() for case_id, _, face in cases}
    expected_ids = [f"S-{i}" for i in range(len(ids))]
    check("用例编号连续（S-0 起无跳号）", ids == expected_ids and len(ids) >= 10)
    check(f"用例执行面合法（{' / '.join(sorted(FACES))}）", all(f in FACES for f in faces.values()))
    claims = COUNT_CLAIM_PATTERN.findall(manual)
    check("用例条数声明一致", len(claims) == 1 and int(claims[0]) == len(ids) and len(ids) > 0)
    check(
        "S-0 = 生产入口与版本核对（runbook §6 动作 1 的验证即首个用例，§8 口径）",
        bool(ids) and ids[0] == "S-0" and "WAF" in manual.split("| S-1 ")[0],
    )
    check(
        "手册引用 runbook 口径（§3 预算 60 分钟 / §6 切换动作 / §7 中止回滚 / §8 衔接）",
        all(anchor in manual for anchor in ("§3", "60 分钟", "§6", "§7", "§8")),
    )
    check("手册互引 hypercare（E23-T13 观察期处置）与 UAT 手册（P0 主干来源）",
          "hospital-a-hypercare.md" in manual and "uat-hospital-a.md" in manual
          and HYPERCARE.exists() and UAT.exists())
    check(
        "数据红线（冒烟只用「示例」虚构数据，真实患者不进冒烟）",
        "示例" in manual and "不打开真实患者明文" in manual,
    )
    check("手册含由人执行清单与失败处置（§级）", "由人执行" in manual and "中止" in manual)
    check(
        "手册自动化对照（GoliveSmokeIT 门控与本校验接线）",
        "GoliveSmokeIT" in manual and "check_golive_smoke.py" in manual,
    )

    # ---- 3. 自动化冒烟 IT ----
    check("GoliveSmokeIT 存在", bool(it))
    if it:
        check("IT 门控 CRDP_IT_MYSQL_PORT 且 local 剖面", "CRDP_IT_MYSQL_PORT" in it and '"local"' in it)
        check("IT 自建自清标记（IT-E23T12）", "IT-E23T12" in it)
        check("IT 虚构数据标记（示例）", "示例" in it)
        it_cases = set(IT_CASE_PATTERN.findall(it))
        machine_cases = {case_id for case_id, face in faces.items() if "机器 CI" in face}
        missing = sorted(machine_cases - it_cases - PRODUCTION_ONLY_CASES)
        check(
            "手册机器执行面用例全部在 IT 登记（S-x 锚点）：" + (",".join(missing) or "齐全"),
            not missing,
        )
        stray = sorted(it_cases - machine_cases)
        check("IT 无手册之外的多余用例锚点：" + (",".join(stray) or "无"), not stray)

    # ---- 4. 互引与接线 ----
    check(
        "上线 runbook §8 指向本手册（hospital-a-golive-smoke.md）",
        "hospital-a-golive-smoke.md" in runbook and "E23-T12" in runbook,
    )
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接入本校验（check_golive_smoke.py）", "check_golive_smoke.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引本手册", "hospital-a-golive-smoke.md" in readme)

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(("PASS  " if ok else "FAIL  ") + name)
    print(f"\n{len(checks) - len(failed)}/{len(checks)} 项通过")
    if failed:
        print("失败项：", file=sys.stderr)
        for name in failed:
            print("  - " + name, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
