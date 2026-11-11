#!/usr/bin/env python3
"""医院 B 回归：A 功能不受影响（E23-T28）文档与自动化用例防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E23-T28 行：类型 = 测试、依赖含 E23-T17、验收原句「A 回归用例通过」；
2. `docs/testing/hospital-b-regression-a.md` A 回归手册：头部任务行（依赖 / 验收原句 / W38 排期）、
   用例表自洽（编号 RA-0… 连续、执行面合法、条数声明一致）、回归面锚点（B 上线共享面变更清单：
   V80 草稿进 A 库、TaskCreationPlanner 共享代码修复、B 侧激活动作与 node_rate 角色布局、
   V136 命名空间；Q35 / Q44 / A-FU-016 / OQ-128、S-x 基线引用、数据红线「示例」标记、
   生产执行流程、由人执行清单、自动化对照、防漂移）；
3. `backend/.../followup/BRegressionAUnaffectedIT.java`：门控 CRDP_IT_MYSQL_PORT、local 剖面、
   自建自清标记 IT-E23T28、虚构数据「示例」标记、B 上线态置位（@BeforeAll），且手册「机器 CI」
   执行面的每条用例编号都在 IT 内登记（分节注释 RA-x 锚点；RA-0/RA-5/RA-7 为生产人工或引用型，
   不要求独立方法）；
4. 互引与接线：`docs/ops/hospital-b-go-live.md`（§9 衔接行指向本手册与 IT）、
   `docs/README.md` 索引、`GoliveSmokeIT`（RA-5 引用承载在册）、ci.yml 含本校验步骤。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/hospital-b-regression-a.md"
IT = (
    ROOT
    / "backend/crdp-server/src/test/java/com/crdp/server/followup/BRegressionAUnaffectedIT.java"
)
GOLIVE_RUNBOOK = ROOT / "docs/ops/hospital-b-go-live.md"
GOLIVE_SMOKE_IT = (
    ROOT / "backend/crdp-server/src/test/java/com/crdp/server/followup/GoliveSmokeIT.java"
)
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"

# 用例行：| RA-0 | 级别 | 执行面 | 角色 | 步骤 | 预期（依据） |（§7 自动化对照表首列同为 RA-x，
# 以级别列 = 阻断区分用例表行）
CASE_ROW_PATTERN = re.compile(r"^\|\s*(RA-\d{1,2})\s*\|\s*阻断\s*\|\s*([^|]+)\|", re.M)
# 执行面列取值（手册 §2 分工；引用型 = 由既有 IT 承载，不要求独立方法）
FACES = {"生产人工", "机器 CI + 生产人工", "机器 CI（引用）"}
# 不要求在 IT 内有独立 RA-x 分节锚点的用例（生产人工 / 引用型承载）
NON_ANCHOR_CASES = {"RA-0", "RA-5", "RA-7"}
# 条数声明：共 N 条
COUNT_CLAIM_PATTERN = re.compile(r"共\s*(\d+)\s*条")
# IT 内用例登记锚点（分节注释 // ----- RA-1 xxx，与方法体内行内 // RA-3：xxx 两种形态）
IT_CASE_PATTERN = re.compile(r"//\s*(?:-{2,}\s*)?(RA-\d{1,2})\b", re.M)


def e23_t28_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T28":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    it = IT.read_text(encoding="utf-8") if IT.exists() else ""

    # ---- 1. tasks.csv 行 ----
    row = e23_t28_row()
    check("tasks.csv 有 E23-T28 行", row is not None)
    if row:
        check(
            "任务标题与类型（B 回归：A 功能不受影响 · 测试）",
            "B 回归" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "测试",
        )
        check("依赖 E23-T17（B 验收，与手册头部一致）", "E23-T17" in (row.get("依赖") or ""))
        check("验收原句（A 回归用例通过）", (row.get("验收标准") or "").strip() == "A 回归用例通过")

    # ---- 2. A 回归手册 ----
    check("A 回归手册存在（docs/testing/hospital-b-regression-a.md）", bool(manual))
    check(
        "手册头部任务行（E23-T28 · 依赖 E23-T17 · 验收「A 回归用例通过」）",
        "任务 E23-T28" in manual and "E23-T17" in manual and "**A 回归用例通过**" in manual,
    )
    cases = CASE_ROW_PATTERN.findall(manual)
    ids = [case_id for case_id, _ in cases]
    faces = {case_id: face.strip() for case_id, face in cases}
    expected_ids = [f"RA-{i}" for i in range(len(ids))]
    check("用例编号连续（RA-0 起无跳号）", ids == expected_ids and len(ids) >= 8)
    check(f"用例执行面合法（{' / '.join(sorted(FACES))}）", all(f in FACES for f in faces.values()))
    claims = COUNT_CLAIM_PATTERN.findall(manual)
    check("用例条数声明一致", len(claims) == 1 and int(claims[0]) == len(ids) and len(ids) > 0)
    check(
        "回归基线锚点（A 冒烟 S-x 引用 + B 上线 §4 第 6 项只读核对）",
        "hospital-a-golive-smoke.md" in manual and "S-6" in manual and "S-7" in manual and "只读核对" in manual,
    )
    check(
        "A 侧行为口径锚点（Q35 出院日期起算 / Q44 补录 / A-FU-016 长期循环 / A-FU-002~005）",
        all(k in manual for k in ("Q35", "Q44", "A-FU-016", "A-FU-002")),
    )
    check(
        "共享面风险清单（V80 惰性 OQ-128 / TaskCreationPlanner 修复 / 激活动作 / V136 命名空间 / node_rate 布局）",
        "V80" in manual
        and "OQ-128" in manual
        and "TaskCreationPlanner" in manual
        and "V136" in manual
        and "default_visible" in manual,
    )
    check(
        "A 默认不显示锚点（A-DASH-013 / node_rate 不显示）",
        "A-DASH-013" in manual and "无节点随访率卡片" in manual,
    )
    check(
        "数据红线（回归只用「示例」虚构患者）",
        "示例" in manual and "（示例）" in manual,
    )
    check(
        "手册含生产执行流程与由人执行清单",
        "生产回归执行流程" in manual and "由人执行" in manual,
    )
    check(
        "手册自动化对照（BRegressionAUnaffectedIT 门控与本校验接线）",
        "BRegressionAUnaffectedIT" in manual and "check_hospital_b_regression.py" in manual,
    )
    check(
        "手册声明时序差异（B 上线形态之下复跑 A 基线，区别于既有 IT）",
        "时序" in manual and "GoliveSmokeIT" in manual,
    )

    # ---- 3. 回归 IT ----
    check("BRegressionAUnaffectedIT 存在", bool(it))
    if it:
        check(
            "IT 门控 CRDP_IT_MYSQL_PORT 且 local 剖面",
            "CRDP_IT_MYSQL_PORT" in it and '"local"' in it,
        )
        check("IT 自建自清标记（IT-E23T28）", "IT-E23T28" in it)
        check("IT 虚构数据标记（示例）", "示例" in it)
        check(
            "IT 置位 B 上线态（@BeforeAll 激活 + 角色布局开启 node_rate）",
            "BeforeAll" in it and "disable" in it and "publish" in it and "node_rate" in it,
        )
        it_cases = set(IT_CASE_PATTERN.findall(it))
        machine_cases = {case_id for case_id, face in faces.items() if "机器 CI" in face}
        missing = sorted(machine_cases - it_cases - NON_ANCHOR_CASES)
        check(
            "手册机器执行面用例全部在 IT 登记（RA-x 分节锚点）：" + (",".join(missing) or "齐全"),
            not missing,
        )
        stray = sorted(it_cases - machine_cases)
        check("IT 无手册之外的多余用例锚点：" + (",".join(stray) or "无"), not stray)

    # ---- 4. 互引与接线 ----
    golive = GOLIVE_RUNBOOK.read_text(encoding="utf-8") if GOLIVE_RUNBOOK.exists() else ""
    check(
        "B 上线 runbook §9 衔接行指向本手册与 IT",
        "hospital-b-regression-a.md" in golive and "BRegressionAUnaffectedIT" in golive,
    )
    smoke_it = (
        GOLIVE_SMOKE_IT.read_text(encoding="utf-8") if GOLIVE_SMOKE_IT.exists() else ""
    )
    check("RA-5 引用承载在册（GoliveSmokeIT 存在）", bool(smoke_it))
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接入本校验（check_hospital_b_regression.py）", "check_hospital_b_regression.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引本手册", "hospital-b-regression-a.md" in readme)

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
