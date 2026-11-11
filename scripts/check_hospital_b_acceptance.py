#!/usr/bin/env python3
"""医院 B 验收（E23-T17：补录任务与节点随访率）文档与自动化用例防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E23-T17 行：类型 = 测试、依赖含 E23-T16、关联需求 A-DASH-012、
   验收原句「验收用例通过」；
2. `docs/testing/hospital-b-acceptance.md` 验收手册：头部任务行（依赖 / 验收原句 / W38 排期）、
   用例表自洽（编号 B-0… 连续、执行面合法、条数声明一致、每行含步骤与预期）、口径锚点
   （§14 Q43/Q44 补录规则、A-DASH-012 口径 Q38、A-DASH-013 首页开启、OQ-128 ④ 开关定夺、
   数据红线「示例」标记、生产执行流程、由人执行清单、自动化对照）；
3. `backend/.../followup/BAcceptanceBackfillNodeRateIT.java`：门控 CRDP_IT_MYSQL_PORT、
   local 剖面、自建自清标记 IT-E23T17、虚构数据「示例」标记，且手册「机器 CI」执行面的
   每条用例编号都在 IT 内登记（分节注释 B-x 锚点；B-0/B-9 属生产人工、B-7/B-8 为引用型
   回归，不要求独立方法）；
4. 互引与接线：`docs/ops/hospital-b-plan-preset.md`（§5 衔接与 OQ-128 ④ 定夺回写）、
   `docs/ops/hospital-b-onboarding.md`（§8 衔接）、`docs/open-questions.md`（OQ-128 ④
   E23-T17 定夺关闭）、`docs/README.md` 索引、ci.yml 含本校验步骤。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/hospital-b-acceptance.md"
IT = (
    ROOT
    / "backend/crdp-server/src/test/java/com/crdp/server/followup/BAcceptanceBackfillNodeRateIT.java"
)
PRESET_RUNBOOK = ROOT / "docs/ops/hospital-b-plan-preset.md"
ONBOARDING_RUNBOOK = ROOT / "docs/ops/hospital-b-onboarding.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"

# 用例行：| B-0 | 级别 | 执行面 | 角色 | 步骤 | 预期（依据） |（§7 自动化对照表首列同为 B-x，
# 以级别列 = 阻断区分用例表行）
CASE_ROW_PATTERN = re.compile(r"^\|\s*(B-\d{1,2})\s*\|\s*阻断\s*\|\s*([^|]+)\|", re.M)
# 执行面列取值（手册 §2 分工；引用型 = 由既有 IT 或本 IT 内联回归承载，不要求独立方法）
FACES = {
    "生产人工（机器侧断言化身态）",
    "机器 CI + 生产人工",
    "机器 CI（回归引用）",
    "机器 CI（引用）",
    "生产人工",
}
# 不要求在 IT 内有独立 B-x 分节锚点的用例（生产人工 / 引用型承载）
NON_ANCHOR_CASES = {"B-0", "B-7", "B-8", "B-9"}
# 条数声明：共 N 条
COUNT_CLAIM_PATTERN = re.compile(r"共\s*(\d+)\s*条")
# IT 内用例登记锚点（分节注释 // ----- B-1 xxx，与方法体内行内 // B-3：xxx 两种形态）
IT_CASE_PATTERN = re.compile(r"//\s*(?:-{2,}\s*)?(B-\d{1,2})\b", re.M)


def e23_t17_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T17":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    it = IT.read_text(encoding="utf-8") if IT.exists() else ""
    preset = PRESET_RUNBOOK.read_text(encoding="utf-8") if PRESET_RUNBOOK.exists() else ""
    onboarding = (
        ONBOARDING_RUNBOOK.read_text(encoding="utf-8") if ONBOARDING_RUNBOOK.exists() else ""
    )
    oq = OPEN_QUESTIONS.read_text(encoding="utf-8") if OPEN_QUESTIONS.exists() else ""

    # ---- 1. tasks.csv 行 ----
    row = e23_t17_row()
    check("tasks.csv 有 E23-T17 行", row is not None)
    if row:
        check(
            "任务标题与类型（B 验收：补录任务与节点随访率 · 测试）",
            "补录任务与节点随访率" in (row.get("标题") or "")
            and (row.get("类型") or "").strip() == "测试",
        )
        check("依赖 E23-T16（与 E23-T16 runbook 衔接一致）", "E23-T16" in (row.get("依赖") or ""))
        check("关联需求 A-DASH-012", "A-DASH-012" in (row.get("关联需求") or ""))
        check("验收原句（验收用例通过）", (row.get("验收标准") or "").strip() == "验收用例通过")

    # ---- 2. 验收手册 ----
    check("验收手册存在（docs/testing/hospital-b-acceptance.md）", bool(manual))
    check(
        "手册头部任务行（E23-T17 · 依赖 E23-T16 · 验收「验收用例通过」· A-DASH-012）",
        "任务 E23-T17" in manual
        and "E23-T16" in manual
        and "**验收用例通过**" in manual
        and "A-DASH-012" in manual,
    )
    cases = CASE_ROW_PATTERN.findall(manual)
    ids = [case_id for case_id, _ in cases]
    faces = {case_id: face.strip() for case_id, face in cases}
    expected_ids = [f"B-{i}" for i in range(len(ids))]
    check("用例编号连续（B-0 起无跳号）", ids == expected_ids and len(ids) >= 10)
    check(f"用例执行面合法（{' / '.join(sorted(FACES))}）", all(f in FACES for f in faces.values()))
    claims = COUNT_CLAIM_PATTERN.findall(manual)
    check("用例条数声明一致", len(claims) == 1 and int(claims[0]) == len(ids) and len(ids) > 0)
    check(
        "口径锚点（Q43 / Q44 补录规则、Q38 计入口径、A-DASH-012 / A-DASH-013）",
        all(k in manual for k in ("Q43", "Q44", "Q38", "A-DASH-012", "A-DASH-013")),
    )
    check(
        "补录行为闭环四步（创建 / 不逾期 / 可直接录入 / 已完成（补录））",
        "补录" in manual and "不进入逾期" in manual and "可直接录入" in manual and "已完成（补录）" in manual,
    )
    check(
        "开关行载体定夺（OQ-128 ④ 关闭：配置模板承载 + 迁移补行不可行 + 兜底口径）",
        "OQ-128 ④" in manual and "配置模板承载" in manual and "唯一键" in manual and "兜底" in manual,
    )
    check(
        "数据红线（验收只用「示例」虚构患者）",
        "示例" in manual and "（示例）" in manual,
    )
    check(
        "手册含生产执行流程与由人执行清单",
        "生产验收执行流程" in manual and "由人执行" in manual,
    )
    check(
        "手册自动化对照（BAcceptanceBackfillNodeRateIT 门控与本校验接线）",
        "BAcceptanceBackfillNodeRateIT" in manual and "check_hospital_b_acceptance.py" in manual,
    )

    # ---- 3. 自动化验收 IT ----
    check("BAcceptanceBackfillNodeRateIT 存在", bool(it))
    if it:
        check(
            "IT 门控 CRDP_IT_MYSQL_PORT 且 local 剖面",
            "CRDP_IT_MYSQL_PORT" in it and '"local"' in it,
        )
        check("IT 自建自清标记（IT-E23T17）", "IT-E23T17" in it)
        check("IT 虚构数据标记（示例）", "示例" in it)
        check(
            "IT 引用 E23-T16 激活口径（BPresetPlanVerifyIT 化身复用）",
            "BPresetPlanVerifyIT" in it,
        )
        it_cases = set(IT_CASE_PATTERN.findall(it))
        machine_cases = {case_id for case_id, face in faces.items() if "机器 CI" in face}
        missing = sorted(machine_cases - it_cases - NON_ANCHOR_CASES)
        check(
            "手册机器执行面用例全部在 IT 登记（B-x 分节锚点）：" + (",".join(missing) or "齐全"),
            not missing,
        )
        stray = sorted(it_cases - machine_cases)
        check("IT 无手册之外的多余用例锚点：" + (",".join(stray) or "无"), not stray)

    # ---- 4. 互引与接线 ----
    check(
        "E23-T16 runbook §5 指向本手册且 OQ-128 ④ 定夺已回写",
        "hospital-b-acceptance.md" in preset and "E23-T17" in preset and "已定夺" in preset,
    )
    check(
        "E23-T15 onboarding §8 指向本手册",
        "hospital-b-acceptance.md" in onboarding and "E23-T17" in onboarding,
    )
    check(
        "open-questions OQ-128 ④ 记录 E23-T17 定夺（配置模板承载 / 不由迁移补行）",
        "E23-T17" in oq and "不由迁移补行" in oq,
    )
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接入本校验（check_hospital_b_acceptance.py）", "check_hospital_b_acceptance.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引本手册", "hospital-b-acceptance.md" in readme)

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
