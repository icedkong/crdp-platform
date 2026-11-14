#!/usr/bin/env python3
"""UAT 对接（E23-T08 医院 A / E23-T23 医院 C）文档与入缺陷库机制防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/testing/uat-hospital-a.md` UAT 手册：关键口径齐备（窗口 W33–W35 与
   tasks.csv / tasks.md 固定周次一致、组织、环境与数据红线、日程、签字判据、
   签字单、待人工清单、与 E23-T09/T10 / E19-T10 / E23-T11 衔接）；
2. `docs/testing/uat-hospital-c.md` C UAT 手册（W44–W45）：窗口 / 排期与 tasks.csv
   一致、激活链与数据红线、用例集自洽（65 条映射评审 C-ACC）、UAT 复核清单、
   OQ-148 披露、与 E23-T24/T25 / E21-T30 / E23-T26 衔接；
3. `docs/testing/uat-findings-template.csv` 模板：表头与 `uat_intake.py` 的列定义
   完全一致、示例行校验通过且全部带「示例」标记（AGENTS §5.5）；
4. `scripts/uat_intake.py` 护栏在位：默认 dry-run（--apply 才写库）、token 只经
   git credential fill、幂等标记 UAT-ID、utf-8-sig 兼容、疑似真实手机号 / 证件号
   拦截、`--hospital a|c` 院区路由（周次 / 修复 / 复测任务）；源码无硬编码凭据；
5. 手册 §5 用例集自洽（A / C 两册）：总数与分级统计声明一致、各组编号连续、
   级别合法（防局部改漏一处用例表仍通过）；
6. 索引同步：ci.yml 含自检与本校验步骤、docs/README.md、open-questions.md
   OQ-122 / OQ-153。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import importlib.util
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/uat-hospital-a.md"
MANUAL_C = ROOT / "docs/testing/uat-hospital-c.md"
TEMPLATE = ROOT / "docs/testing/uat-findings-template.csv"
INTAKE = ROOT / "scripts/uat_intake.py"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"

GHP_TOKEN_PATTERN = re.compile(r"gh[pousr]_[A-Za-z0-9]{16,}")
# 手册 §5 用例行：| A-1 | P0 | 角色 | … |；组字母 A–M
CASE_ROW_PATTERN = re.compile(r"^\|\s*([A-M])-(\d{1,2})\s*\|\s*(P[012])\s*\|", re.M)
# 用例总数声明：共 N 条（P0 x / P1 y / P2 z）
CASE_COUNT_CLAIM_PATTERN = re.compile(r"共\s*(\d+)\s*条（P0\s*(\d+)\s*/\s*P1\s*(\d+)\s*/\s*P2\s*(\d+)\s*）")


def load_intake_module():
    spec = importlib.util.spec_from_file_location("uat_intake", INTAKE)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def e23_t08_row() -> dict[str, str] | None:
    return tasks_row("E23-T08")


def e23_t23_row() -> dict[str, str] | None:
    return tasks_row("E23-T23")


def tasks_row(task_id: str) -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == task_id:
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    intake = load_intake_module()

    # ---- 1. UAT 手册 ----
    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    check("UAT 手册存在", bool(manual))
    row = e23_t08_row()
    check("tasks.csv 有 E23-T08 行", row is not None)
    if row:
        start_week = (row.get("开始周") or "").strip()  # W34（任务排期）
        start_date = (row.get("开始日期") or "").strip()  # 2027-07-05
        check("手册窗口与任务标题一致（W33–W35）", "W33–W35" in (row.get("标题") or "") and "W33–W35" in manual)
        # 手册头部窗口整句（唯一锚点，防正文多处 W33–W35 改漏一处仍通过）
        check(
            "手册头部窗口整句（W33–W35（2027-06-28 ~ 2027-07-16））",
            "窗口：**W33–W35（2027-06-28 ~ 2027-07-16）**" in manual,
        )
        # 任务自身排期（S17 · W34–W35，与 tasks.csv 开始周 / 开始日期一致）
        check(
            "手册写明任务排期（W34–W35、2027-07-05 起，与 tasks.csv 一致）",
            "S17 · W34–W35（2027-07-05 起）" in manual and start_week == "W34" and start_date == "2027-07-05",
        )
        # 日程表结构锚点：三周各带当周起始日期（防局部改漏）
        check("手册日程表 W33 行含窗口起始日期", "W33（2027-06-28 当周）" in manual)
        check("手册日程表 W34 行含起始日期", "W34（2027-07-05 当周）" in manual)
        check("手册日程表 W35 行含起始日期", "W35（2027-07-12 当周）" in manual)
        check("手册写明验收（UAT 签字确认）", (row.get("验收标准") or "") in manual)
        check("手册写明依赖（E23-T02）", "E23-T02" in manual)
    for marker in [
        "hospital-a-spec.md",
        "tech-plan.md",
        "pages.md",
        "hospital-a-sample-intake.md",
        "deidentify_sample.py",
        "responsive-walkthrough.md",
        "device-matrix.md",
        "uat_intake.py",
        "uat-findings-template.csv",
        "GitHub Issues",
        "签字单",
        "OQ-112",
        "OQ-122",
        "OQ-55",
        "E23-T05",
        "E23-T06",
        "E23-T07",
        "E23-T09",
        "E23-T10",
        "E19-T10",
        "E23-T11",
        "E23-T23",
        "AGENTS §5.5",
    ]:
        check(f"手册含关键口径「{marker}」", marker in manual)
    for sev in intake.SEVERITIES:
        check(f"手册含分级「{sev}」", sev in manual)
    # 环境与数据红线整句
    check("手册环境红线（真实患者数据不进 UAT）", "真实患者数据不进 UAT" in manual)
    # 签字判据结构锚点（§7 整句，防局部改漏）
    check(
        "手册签字判据含「P0/P1 执行率 100%」整句",
        "P0/P1 用例执行率 100%" in manual,
    )
    check(
        "手册签字判据含「阻断 / 严重全部关闭」整句",
        "阻断（severity:high）与严重（severity:medium）全部关闭" in manual,
    )
    check("手册含结论三档（通过 / 有条件通过 / 不通过）", "有条件通过" in manual and "不通过" in manual)

    # ---- 2. 手册 §5 用例集自洽 ----
    rows_found = CASE_ROW_PATTERN.findall(manual)
    ids = [f"{g}-{n}" for g, n, _ in rows_found]
    levels = [lvl for _, _, lvl in rows_found]
    claim = CASE_COUNT_CLAIM_PATTERN.search(manual)
    check("手册声明用例总数（共 N 条（P0/P1/P2））", claim is not None)
    if claim and rows_found:
        total, p0, p1, p2 = (int(x) for x in claim.groups())
        dist = Counter(levels)
        check(
            f"用例计数与声明一致（{len(ids)} 条 = 声明 {total}）",
            len(ids) == total and dist.get("P0", 0) == p0 and dist.get("P1", 0) == p1 and dist.get("P2", 0) == p2,
        )
    check("用例编号不重复", len(ids) == len(set(ids)))
    seq_ok = True
    by_group: dict[str, list[int]] = {}
    for g, n, _ in rows_found:
        by_group.setdefault(g, []).append(int(n))
    for g, nums in by_group.items():
        if nums != list(range(1, len(nums) + 1)):
            seq_ok = False
    check("各组用例编号从 1 连续（A–M）", seq_ok and sorted(by_group) == list("ABCDEFGHIJKLM"))
    # 分级说明存在
    check("手册说明 P0/P1/P2 级别定义", "**P0** 主流程" in manual and "**P2** 次要" in manual)

    # ---- 2b. 医院 C UAT 手册（E23-T23 · W44–W45） ----
    manual_c = MANUAL_C.read_text(encoding="utf-8") if MANUAL_C.exists() else ""
    check("C UAT 手册存在", bool(manual_c))
    row_c = e23_t23_row()
    check("tasks.csv 有 E23-T23 行", row_c is not None)
    if row_c:
        start_week_c = (row_c.get("开始周") or "").strip()  # W44（任务排期）
        start_date_c = (row_c.get("开始日期") or "").strip()  # 2027-09-13
        check(
            "C 手册窗口与任务标题一致（W44–W45）",
            "W44–W45" in (row_c.get("标题") or "") and "W44–W45" in manual_c,
        )
        check(
            "C 手册头部窗口整句（W44–W45（2027-09-13 ~ 2027-09-24））",
            "窗口：**W44–W45（2027-09-13 ~ 2027-09-24）**" in manual_c,
        )
        check(
            "C 手册写明任务排期（S22 · W44、2027-09-13 起，与 tasks.csv 一致）",
            "S22 · W44（2027-09-13 起）" in manual_c and start_week_c == "W44" and start_date_c == "2027-09-13",
        )
        check("C 手册日程表 W44 行含窗口起始日期", "W44（2027-09-13 当周）" in manual_c)
        check("C 手册日程表 W45 行含起始日期", "W45（2027-09-20 当周）" in manual_c)
        check("C 手册写明验收（UAT 签字确认）", (row_c.get("验收标准") or "") in manual_c)
        check("C 手册写明依赖（E23-T21）", "E23-T21" in manual_c)
    for marker in [
        "hospital-c-spec.md",
        "hospital-c-acceptance-review.md",
        "tech-plan.md",
        "pages.md",
        "hospital-c-plan-preset.md",
        "hospital-c-report-preset.md",
        "hospital-c-user-ward-role.md",
        "hospital-c-paper-archive-rehearsal.md",
        "responsive-walkthrough.md",
        "device-matrix.md",
        "uat_intake.py",
        "--hospital c",
        "uat-findings-template.csv",
        "GitHub Issues",
        "签字单",
        "OQ-122",
        "OQ-153",
        "OQ-148",
        "OQ-131",
        "OQ-144",
        "OQ-55",
        "V137",
        "V94",
        "C_PACER_FU_PLAN",
        "E23-T20",
        "E23-T21",
        "E23-T22",
        "E23-T24",
        "E23-T25",
        "E21-T29",
        "E21-T30",
        "E23-T26",
        "E23-T27",
        "E21-T31",
        "E23-T08",
        "E24-T10",
        "C-ACC-01…45",
        "c.card1.fu01",
        "c.card1.doc01",
        "c.card1.nur01",
        "c.cardio.dir",
        "c.multi.doc01",
        "c.both.dir",
        "c.mix.doc01",
        "AGENTS §5.5",
    ]:
        check(f"C 手册含关键口径「{marker}」", marker in manual_c)
    for sev in intake.SEVERITIES:
        check(f"C 手册含分级「{sev}」", sev in manual_c)
    check("C 手册环境红线（真实患者数据不进 UAT）", "真实患者数据不进 UAT" in manual_c)
    check(
        "C 手册签字判据含「P0/P1 执行率 100%」整句",
        "P0/P1 用例执行率 100%" in manual_c,
    )
    check(
        "C 手册签字判据含「阻断 / 严重全部关闭」整句",
        "阻断（severity:high）与严重（severity:medium）全部关闭" in manual_c,
    )
    check(
        "C 手册含结论三档（通过 / 有条件通过 / 不通过）",
        "有条件通过" in manual_c and "不通过" in manual_c,
    )
    # C 用例集自洽（与 A 册同法）
    rows_c = CASE_ROW_PATTERN.findall(manual_c)
    ids_c = [f"{g}-{n}" for g, n, _ in rows_c]
    levels_c = [lvl for _, _, lvl in rows_c]
    claim_c = CASE_COUNT_CLAIM_PATTERN.search(manual_c)
    check("C 手册声明用例总数（共 N 条（P0/P1/P2））", claim_c is not None)
    if claim_c and rows_c:
        total_c, p0_c, p1_c, p2_c = (int(x) for x in claim_c.groups())
        dist_c = Counter(levels_c)
        check(
            f"C 用例计数与声明一致（{len(ids_c)} 条 = 声明 {total_c}）",
            len(ids_c) == total_c
            and dist_c.get("P0", 0) == p0_c
            and dist_c.get("P1", 0) == p1_c
            and dist_c.get("P2", 0) == p2_c,
        )
    check("C 用例编号不重复", len(ids_c) == len(set(ids_c)))
    seq_ok_c = True
    by_group_c: dict[str, list[int]] = {}
    for g, n, _ in rows_c:
        by_group_c.setdefault(g, []).append(int(n))
    for g, nums in by_group_c.items():
        if nums != list(range(1, len(nums) + 1)):
            seq_ok_c = False
    check("C 各组用例编号从 1 连续（A–M）", seq_ok_c and sorted(by_group_c) == list("ABCDEFGHIJKLM"))
    check(
        "C 手册说明 P0/P1/P2 级别定义",
        "**P0** 主流程" in manual_c and "**P2** 次要" in manual_c,
    )
    # C 专属口径：UAT 复核清单（评审 §6 移交 5 项）与 OQ-148 披露
    check("C 手册含 UAT 复核清单节", "UAT 复核清单" in manual_c)
    for ref in ["CL-01", "CL-02", "CL-03", "CL-05", "Q18", "105"]:
        check(f"C 复核清单含条目锚点「{ref}」", ref in manual_c)
    check(
        "C 手册披露 OQ-148 替代路径",
        "补录不自动完成" in manual_c and "替代路径" in manual_c,
    )
    check("C 手册复核清单挂接签字判据（§7-4）", "复核清单 5 项的确认结果" in manual_c)
    # 预演与回填留痕结构
    check("C 手册含本机预演留痕（§10.1）", "### 10.1" in manual_c)
    check("C 手册含 UAT 执行回填留痕（§10.2）", "### 10.2" in manual_c and "（回填" in manual_c)

    # ---- 3. 发现清单模板 ----
    trows, errs = intake.load_findings(TEMPLATE)
    check("模板表头与工具列定义一致", not errs)
    check("模板行全部通过校验", not intake.validate_rows(trows))
    check("模板示例行 ≥2 且标题带「示例」", len(trows) >= 2 and all("示例" in r["title"] for r in trows))
    with TEMPLATE.open(encoding="utf-8-sig") as f:
        header = next(csv.reader(f))
    check("模板表头顺序与 EXPECTED_COLUMNS 逐列一致", header == intake.EXPECTED_COLUMNS)

    # ---- 4. 入库工具护栏（源码静态检查） ----
    src = INTAKE.read_text(encoding="utf-8") if INTAKE.exists() else ""
    check("入库工具存在", bool(src))
    for guard, why in [
        ("--apply", "默认 dry-run，显式 --apply 才写缺陷库"),
        ("UAT-ID", "幂等标记"),
        ("utf-8-sig", "兼容 Excel BOM"),
        ("MOBILE_CN_PATTERN", "疑似真实手机号拦截"),
        ("IDCARD_CN_PATTERN", "疑似身份证号拦截"),
        ("severity:high", "分级 label 映射"),
        ("--hospital", "院区路由（A / C）"),
        ("E23-T09", "A 侧 UAT 缺陷修复任务路由"),
        ("E23-T10", "A 侧 UAT 缺陷修复任务路由"),
        ("E19-T10", "A 侧复测任务路由"),
        ("E23-T24", "C 侧 UAT 缺陷修复任务路由"),
        ("E23-T25", "C 侧 UAT 缺陷修复任务路由"),
        ("E21-T30", "C 侧复测任务路由"),
    ]:
        check(f"入库工具护栏「{guard}」（{why}）", guard in src)
    # token 只经 git credential fill：直接匹配 gh_token 里的 argv 列表（注释 / 文档字符串不算数）
    check(
        "入库工具取 token 走 git credential fill（argv 断言）",
        re.search(r'"git",\s*"credential",\s*"fill"', src) is not None,
    )
    check("入库工具源码无硬编码 GitHub token", not GHP_TOKEN_PATTERN.search(src))

    # 语义校验（运行时导入，防只改字典不改注释式漂移）
    check(
        "分级 → label 映射与手册 §6 一致（运行时断言）",
        intake.SEVERITIES
        == {"阻断": "severity:high", "严重": "severity:medium", "一般": "severity:low", "建议": "severity:info"},
    )
    check(
        "修复 / 复测路由进 Issue 正文（A 默认，运行时断言）",
        all(t in intake.to_issue_body(dict(zip(intake.EXPECTED_COLUMNS, [
            "UAT-W34-001", "t（示例）", "阻断", "other", "/x", "随访员", "d", "1. x", "e", "a",
            "UAT 执行记录表 W34-01", "f",
        ]))) for t in ("E23-T09", "E23-T10", "E19-T10")),
    )
    # C 侧路由（运行时断言：周次 / 修复 / 复测 / 署名，与手册 §6 一致）
    c_example = dict(zip(intake.EXPECTED_COLUMNS, [
        "UAT-W44-001", "t（示例）", "阻断", "other", "/x", "随访员", "d", "1. x", "e", "a",
        "UAT 执行记录表 W44-01", "f",
    ]))
    c_body = intake.to_issue_body(c_example, "c")
    check(
        "修复 / 复测路由进 Issue 正文（C 路由，运行时断言）",
        all(t in c_body for t in ("E23-T24", "E23-T25", "E21-T30")) and "E23-T23" in c_body,
    )
    check(
        "C 路由不串 A 侧任务（运行时断言）",
        "E23-T09" not in c_body and "E19-T10" not in c_body,
    )
    check(
        "院区路由枚举与周次（运行时断言）",
        sorted(intake.HOSPITALS) == ["a", "c"]
        and intake.HOSPITALS["a"]["weeks"] == (33, 34, 35)
        and intake.HOSPITALS["c"]["weeks"] == (44, 45),
    )
    check(
        "周次越窗拦截（W44 不在 A 窗口 / W34 不在 C 窗口，运行时断言）",
        any("窗口" in e for e in intake.validate_rows([c_example], "a"))
        and any("窗口" in e for e in intake.validate_rows([dict(zip(intake.EXPECTED_COLUMNS, [
            "UAT-W34-001", "t（示例）", "阻断", "other", "/x", "随访员", "d", "1. x", "e", "a",
            "UAT 执行记录表 W34-01", "f",
        ]))], "c"))
        and not intake.validate_rows([c_example], "c"),
    )
    check(
        "模块枚举与手册 §5 分组数一致（A / C 两册各 13 组，运行时断言）",
        len(intake.MODULES) == 13 and len(by_group) == 13 and len(by_group_c) == 13,
    )

    # ---- 5. 索引与流程接线 ----
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接入 UAT 对接校验", "check_uat_docs.py" in ci)
    check("ci.yml 接入入库工具自检", "uat_intake.py --selftest" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 收录 UAT 手册", "uat-hospital-a.md" in readme)
    check("docs/README.md 收录 C UAT 手册", "uat-hospital-c.md" in readme)
    oq = OPEN_QUESTIONS.read_text(encoding="utf-8") if OPEN_QUESTIONS.exists() else ""
    check("open-questions.md 有 OQ-122（UAT 对接暂行口径）", "OQ-122" in oq)
    check("open-questions.md 有 OQ-153（C UAT 对接暂行口径）", "| OQ-153 |" in oq)

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("✓ " if ok else "✗ ") + n)
    print(f"UAT 对接校验：{len(checks) - len(failed)}/{len(checks)} 通过")
    if failed:
        print("失败项：" + "；".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
