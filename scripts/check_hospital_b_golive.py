#!/usr/bin/env python3
"""医院 B 上线（E23-T19：B 上线 W38）runbook 防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E23-T19 行：类型 = 运维、依赖 E23-T16 之外只含 E23-T17、
   验收原句「B 上线」、排期 W38；
2. `docs/ops/hospital-b-go-live.md` 上线切换 runbook：头部任务行（依赖 / 验收原句 /
   W38 排期）、与 A 上线（E23-T11）的三处差异声明（无正式数据导入、生产入口复用
   按租户编码登录无 DNS 切换、转正已随 E23-T15 完成）、放行闸门（G1~G6 且引用
   既有手册）、切换动作清单（PLT-TEN-010 / PLT-TEN-011~013 口径）、中止与回滚
   （租户级收敛 action=DISABLE 不影响 A、数据不回滚）、冒烟清单（BG-x 连续、条数
   声明一致、数据红线「示例」虚构患者）、观察期滚动（hypercare 复用）、由人执行
   清单、自动化对照（本校验接线）；
3. 机器侧锚点为引用型（与 `docs/testing/hospital-b-acceptance.md` B-7 / B-8 同口径，
   不新建 IT）：runbook 引用 `BAcceptanceBackfillNodeRateIT` / `BPresetPlanVerifyIT` /
   `BPresetWardUserVerifyIT` / `TenantBOnboardingMySqlIT` / `GoliveSmokeIT`；
4. 互引与接线：`docs/testing/hospital-b-acceptance.md`（E23-T19 前置声明）、
   `docs/ops/hospital-b-onboarding.md` §8、`docs/ops/hospital-b-plan-preset.md`、
   `docs/ops/hospital-b-user-ward-role.md`、`docs/ops/hospital-a-go-live.md` §8、
   `docs/ops/hospital-a-hypercare.md` §8 均指向本 runbook 或保持 E23-T19 复用口径；
   `docs/README.md` 索引；ci.yml 含本校验步骤。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNBOOK = ROOT / "docs/ops/hospital-b-go-live.md"
ACCEPTANCE = ROOT / "docs/testing/hospital-b-acceptance.md"
ONBOARDING = ROOT / "docs/ops/hospital-b-onboarding.md"
PLAN_PRESET = ROOT / "docs/ops/hospital-b-plan-preset.md"
WARD_USER = ROOT / "docs/ops/hospital-b-user-ward-role.md"
A_GOLIVE = ROOT / "docs/ops/hospital-a-go-live.md"
HYPERCARE = ROOT / "docs/ops/hospital-a-hypercare.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"

# 冒烟用例行：| BG-0 | 阻断 | 生产人工 | 角色 | 步骤 | 预期（依据） |
SMOKE_ROW_PATTERN = re.compile(r"^\|\s*(BG-\d{1,2})\s*\|\s*阻断\s*\|\s*([^|]+)\|", re.M)
# 条数声明：共 N 条
COUNT_CLAIM_PATTERN = re.compile(r"共\s*(\d+)\s*条")
# 机器侧锚点 IT（引用型，不新建）
ANCHOR_ITS = (
    "BAcceptanceBackfillNodeRateIT",
    "BPresetPlanVerifyIT",
    "BPresetWardUserVerifyIT",
    "TenantBOnboardingMySqlIT",
    "GoliveSmokeIT",
)


def e23_t19_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T19":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    runbook = RUNBOOK.read_text(encoding="utf-8") if RUNBOOK.exists() else ""
    acceptance = ACCEPTANCE.read_text(encoding="utf-8") if ACCEPTANCE.exists() else ""
    onboarding = ONBOARDING.read_text(encoding="utf-8") if ONBOARDING.exists() else ""
    plan_preset = PLAN_PRESET.read_text(encoding="utf-8") if PLAN_PRESET.exists() else ""
    ward_user = WARD_USER.read_text(encoding="utf-8") if WARD_USER.exists() else ""
    a_golive = A_GOLIVE.read_text(encoding="utf-8") if A_GOLIVE.exists() else ""
    hypercare = HYPERCARE.read_text(encoding="utf-8") if HYPERCARE.exists() else ""

    # ---- 1. tasks.csv 行 ----
    row = e23_t19_row()
    check("tasks.csv 有 E23-T19 行", row is not None)
    if row:
        check(
            "任务标题与类型（B 上线（W38） · 运维）",
            "B 上线" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "运维",
        )
        check("依赖 E23-T17（B 验收，与验收手册前置声明一致）", "E23-T17" in (row.get("依赖") or ""))
        check("验收原句（B 上线）", (row.get("验收标准") or "").strip() == "B 上线")
        check("排期 W38（开始周）", "W38" in (row.get("开始周") or ""))

    # ---- 2. 上线切换 runbook ----
    check("runbook 存在（docs/ops/hospital-b-go-live.md）", bool(runbook))
    check(
        "runbook 头部任务行（E23-T19 · 依赖 E23-T17 · 验收「B 上线」 · W38）",
        "任务 E23-T19" in runbook
        and "E23-T17" in runbook
        and "**B 上线**" in runbook
        and "W38" in runbook,
    )
    check(
        "与 A 上线差异声明（无正式数据导入 · 第 14 章无历史数据导入）",
        "无正式数据导入" in runbook and "历史数据导入" in runbook,
    )
    check(
        "入口复用口径（同一医院端入口 · 租户编码登录 · 无 DNS 切换动作）",
        "租户编码" in runbook and "无 DNS" in runbook,
    )
    check(
        "转正口径（已随 E23-T15 §4 步骤 5 转正 · 现场 If TRIAL 补转正 · PLT-TEN-010）",
        "E23-T15" in runbook and "PLT-TEN-010" in runbook and "TRIAL" in runbook,
    )
    check(
        "放行闸门齐全（G1~G6）且引用 B 验收与 A 保障手册",
        all(g in runbook for g in ("G1", "G2", "G3", "G4", "G5", "G6"))
        and "hospital-b-acceptance.md" in runbook
        and "hospital-a-hypercare.md" in runbook,
    )
    check(
        "闸门引用生产基线与恢复预案（cce-prod.md / tenant-restore.md）",
        "cce-prod.md" in runbook and "tenant-restore.md" in runbook,
    )
    check(
        "切换动作清单锚点（到期设置 PLT-TEN-011~013 · 通知医院 B · 观察期启动）",
        "PLT-TEN-011" in runbook and "通知医院 B" in runbook and "观察期" in runbook,
    )
    check(
        "回滚租户级收敛（action=DISABLE 停用 B · A 不受影响 · 数据不回滚）",
        "DISABLE" in runbook and "A 不受影响" in runbook and "数据不回滚" in runbook,
    )
    smoke_rows = SMOKE_ROW_PATTERN.findall(runbook)
    smoke_ids = [case_id for case_id, _ in smoke_rows]
    expected_ids = [f"BG-{i}" for i in range(len(smoke_ids))]
    check("冒烟用例编号连续（BG-0 起无跳号）", smoke_ids == expected_ids and len(smoke_ids) >= 8)
    claims = COUNT_CLAIM_PATTERN.findall(runbook)
    check(
        "冒烟用例条数声明一致",
        len(claims) == 1 and int(claims[0]) == len(smoke_ids) and len(smoke_ids) > 0,
    )
    check(
        "冒烟数据红线（只用「（示例）」虚构患者 · 不碰真实患者明文）",
        "（示例）" in runbook and "真实患者明文" in runbook,
    )
    check(
        "机器侧锚点为引用型（五个既有 IT 全部引用，不新建）",
        all(it in runbook for it in ANCHOR_ITS),
    )
    check(
        "上线后衔接（E23-T28 B 回归 · hypercare 观察期滚动 · C 上线 E23-T26）",
        "E23-T28" in runbook and "E23-T26" in runbook and "滚动" in runbook,
    )
    check("runbook 含由人执行清单", "由人执行" in runbook)
    check(
        "runbook 自动化对照（本校验脚本接线声明）",
        "check_hospital_b_golive.py" in runbook,
    )

    # ---- 3. 互引与接线 ----
    check(
        "B 验收手册声明 E23-T19 以其为前置",
        "E23-T19" in acceptance and "前置" in acceptance,
    )
    check(
        "E23-T15 onboarding §8 指向本 runbook",
        "hospital-b-go-live.md" in onboarding and "E23-T19" in onboarding,
    )
    check(
        "E23-T16 plan-preset 衔接指向本 runbook",
        "hospital-b-go-live.md" in plan_preset and "E23-T19" in plan_preset,
    )
    check(
        "E23-T18 user-ward-role 衔接指向本 runbook",
        "hospital-b-go-live.md" in ward_user and "E23-T19" in ward_user,
    )
    check(
        "A go-live runbook §8 行提及 B 专册（hospital-b-go-live.md）",
        "hospital-b-go-live.md" in a_golive and "E23-T19" in a_golive,
    )
    check(
        "hypercare §8 保持 E23-T19 观察期复用口径",
        "E23-T19" in hypercare and "滚动" in hypercare,
    )
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接入本校验（check_hospital_b_golive.py）", "check_hospital_b_golive.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引本 runbook", "hospital-b-go-live.md" in readme)

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
