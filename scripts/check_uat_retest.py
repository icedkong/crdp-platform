#!/usr/bin/env python3
"""UAT 支持与缺陷复测（医院 A：E19-T10 / 医院 C：E21-T30，测试）防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/testing/uat-retest-hospital-a.md` 复测手册：复测流程（准入 → 复测 →
   结论回写 → 关闭 / 打回）、结论三档与关闭动作、闭环判据（阻断 / 严重清零）、
   待人工清单、衔接与验证留痕齐备；验收句与 tasks.csv E19-T10 行一致（含
   排期 W35–W36 / 2027-07-12 与「超出里程碑窗口」口径）；复测面预演表覆盖
   UAT-SELF-01~06 与 A 组冒烟；
2. `docs/testing/uat-retest-hospital-c.md` C 复测手册（E21-T30 · W44–W45）：
   窗口 / 排期与 tasks.csv 一致、C 侧路由（打回 E23-T24 / T25、周次 W44 / W45、
   看板 --status --hospital c 按周次过滤，OQ-153①）、W45 清零硬期限（不延入
   W46 国庆周）、遗留清单含 OQ-148 结论与复核清单 5 项、复测链路预演三件套；
3. `docs/testing/uat-retest-template.csv` 与
   `docs/testing/uat-retest-hospital-c-template.csv` 两模板：表头与
   `uat_retest.py` 的列定义完全一致、示例行按对应 --hospital 校验通过且全部
   带「示例」标记（AGENTS §5.5；C 模板周次 W44 / W45、修复任务 E23-T24 / T25）；
4. `scripts/uat_retest.py` 护栏在位：默认 dry-run（--apply 才写库）、token 只经
   git credential fill、复测评论幂等标记 RETEST-ID、只回写已入库 UAT-ID、
   utf-8-sig 兼容、疑似真实手机号 / 证件号拦截（复用 uat_intake 同一实现）、
   关闭口径（通过 completed / 不通过保持打开 / 遗留 not_planned 且仅一般与建议）、
   `--hospital a|c` 院区路由（周次 / fix_task / 署名 / 缺省模板 / 看板过滤）；
   源码无硬编码凭据；
5. 索引同步：ci.yml 含工具自检与本校验步骤（注释覆盖 A / C 两册）、
   docs/README.md、UAT 手册（A §6 工具与 §10.5 预演留痕；C §6 工具与 §11
   衔接行指向 C 复测手册）、两本 A 修复手册 §7 衔接行、open-questions.md
   OQ-153、AGENTS.md 脚本清单。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/uat-retest-hospital-a.md"
MANUAL_C = ROOT / "docs/testing/uat-retest-hospital-c.md"
TEMPLATE = ROOT / "docs/testing/uat-retest-template.csv"
TEMPLATE_C = ROOT / "docs/testing/uat-retest-hospital-c-template.csv"
TOOL = ROOT / "scripts/uat_retest.py"
INTAKE = ROOT / "scripts/uat_intake.py"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
AGENTS = ROOT / "AGENTS.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"
UAT_MANUAL = ROOT / "docs/testing/uat-hospital-a.md"
UAT_MANUAL_C = ROOT / "docs/testing/uat-hospital-c.md"
REMEDIATION_FS2 = ROOT / "docs/testing/uat-remediation-hospital-a.md"
REMEDIATION_FS1 = ROOT / "docs/testing/uat-remediation-hospital-a-fullstack1.md"

GHP_TOKEN_PATTERN = re.compile(r"gh[pousr]_[A-Za-z0-9]{16,}")
# 复测面预演表行：| UAT-SELF-nn（…）| … 或 | A-n（…）| …（编号在行首单元格开头）
REHEARSAL_ROW = re.compile(r"^\|\s*(UAT-SELF-\d{2}|A-[123])[（\s|]", re.M)


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def tasks_row(task_id: str) -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == task_id:
                return row
    return None


def e19_t10_row() -> dict[str, str] | None:
    return tasks_row("E19-T10")


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    tool = load_module(TOOL, "uat_retest")
    intake = load_module(INTAKE, "uat_intake")

    # ---- 1. 复测手册 ----
    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    check("复测手册存在", bool(manual))
    row = e19_t10_row()
    check("tasks.csv 有 E19-T10 行", row is not None)
    if row:
        check(
            "手册窗口与任务标题一致（W33–W35）",
            "W33–W35" in (row.get("标题") or "") and "W33–W35" in manual,
        )
        check("手册验收句与 tasks.csv 一致（UAT 缺陷复测关闭）", (row.get("验收标准") or "") in manual)
        check(
            "手册排期与 tasks.csv 一致（S18 · W35–W36、2027-07-12 起）",
            "S18 · W35–W36（2027-07-12 起" in manual
            and (row.get("开始周") or "").strip() == "W35"
            and (row.get("开始日期") or "").strip() == "2027-07-12",
        )
        check(
            "手册写明超出里程碑窗口口径",
            (row.get("超出里程碑窗口") or "").strip() == "是" and "超出里程碑窗口" in manual,
        )
        check("手册写明依赖（E23-T08）", "E23-T08" in manual)
        check("手册写明类型为测试任务", "测试，5 人天" in manual)
    for anchor in [
        "## 1. 目标与验收口径",
        "## 2. UAT 支持侧职责（测试角色，窗口内）",
        "## 3. 复测流程（修复版本 → 复测 → 结论回写 → 关闭 / 打回）",
        "## 4. 复测结论三档与关闭动作",
        "## 5. 复测清单与工具（机制交付 · 本任务仓库侧完成）",
        "## 6. 复测面预演（本轮，窗口前完成",
        "## 7. 闭环判据与看板",
        "## 8. 待人工 / 外部事项清单（窗口 W33–W35 内执行）",
        "## 9. 与其他任务的衔接",
        "## 10. 验证留痕",
    ]:
        check(f"手册章节「{anchor}」在位", anchor in manual)
    # 结论三档与关闭动作
    for concl in ("**通过**", "**不通过**", "**遗留（延期上线，书面接受）**"):
        check(f"结论三档含「{concl}」", concl in manual)
    check("关闭动作口径（completed）在位", "completed" in manual)
    check("关闭动作口径（not planned）在位", "not planned" in manual)
    check(
        "阻断 / 严重不允许遗留（判据 2 清零）",
        "阻断 / 严重不允许遗留" in manual or "仅**一般 / 建议级**" in manual,
    )
    check(
        "手册含判据 2 整句（阻断（severity:high）与严重（severity:medium）全部关闭）",
        "阻断（severity:high）与严重（severity:medium）全部关闭" in manual,
    )
    # 复测面预演表：UAT-SELF-01~06 + A 组冒烟 3 条
    rows_found = set(REHEARSAL_ROW.findall(manual))
    check(
        "预演表覆盖 UAT-SELF-01~06 全部六条修复",
        {f"UAT-SELF-{i:02d}" for i in range(1, 7)} <= rows_found,
    )
    check("预演表覆盖 A 组冒烟 A-1 / A-2 / A-3", {"A-1", "A-2", "A-3"} <= rows_found)
    check("预演结果声明 9/9 通过", "9/9 通过" in manual)
    check("预演注明全新库全量迁移口径", "全新库应用全量迁移" in manual)
    for marker in [
        "uat_retest.py",
        "uat-retest-template.csv",
        "uat-hospital-a.md",
        "uat-remediation-hospital-a.md",
        "uat-remediation-hospital-a-fullstack1.md",
        "uat_intake.py",
        "GitHub Issues",
        "OQ-122",
        "OQ-124",
        "AGENTS §5.5",
        "E23-T09",
        "E23-T10",
        "E23-T11",
        "E19-T09",
        "E21-T30",
        "--status",
        "--round 2",
    ]:
        check(f"手册含关键口径「{marker}」", marker in manual)
    for sev in intake.SEVERITIES:
        check(f"手册含分级「{sev}」", sev in manual)
    check("手册含打回口径（新轮次）", "新轮次" in manual)

    # ---- 2. 复测清单模板 ----
    trows, errs = tool.load_retests(TEMPLATE)
    check("模板表头与工具列定义一致", not errs)
    check("模板行全部通过校验", not tool.validate_rows(trows))
    check(
        "模板示例行 ≥2 且复测结果带「示例」",
        len(trows) >= 2 and all("示例" in (r["reproduce_result"] or "") for r in trows),
    )
    with TEMPLATE.open(encoding="utf-8-sig") as f:
        header = next(csv.reader(f))
    check("模板表头顺序与 EXPECTED_COLUMNS 逐列一致", header == tool.EXPECTED_COLUMNS)
    check(
        "模板示例结论覆盖 通过 / 遗留（关闭动作示范）",
        {r["conclusion"] for r in trows} == {"通过", "遗留"},
    )

    # ---- 3. 复测工具护栏（源码静态 + 运行时断言） ----
    src = TOOL.read_text(encoding="utf-8") if TOOL.exists() else ""
    check("复测工具存在", bool(src))
    for guard, why in [
        ("--apply", "默认 dry-run，显式 --apply 才写缺陷库"),
        ("RETEST-ID", "复测评论幂等标记"),
        ("UAT-ID", "只回写已入库发现"),
        ("utf-8-sig", "兼容 Excel BOM"),
        ("lint_sensitive_text", "疑似真实手机号 / 身份证号拦截（复用 uat_intake）"),
        ("state_reason", "关闭动作带 state_reason（completed / not_planned）"),
        ("E23-T09", "打回路由修复任务（全栈 1）"),
        ("E23-T10", "打回路由修复任务（全栈 2）"),
        ("E19-T10", "复测任务署名"),
        ("--status", "闭环判据看板"),
        ("--selftest", "内嵌自检"),
    ]:
        check(f"复测工具护栏「{guard}」（{why}）", guard in src)
    check(
        "复测工具取 token 复用 uat_intake 的 git credential fill 实现（不自行取凭据）",
        "intake.gh_token" in src and "gh_token = intake.gh_token" in src,
    )
    check("复测工具源码无硬编码 GitHub token", not GHP_TOKEN_PATTERN.search(src))
    # 运行时语义断言（防只改注释式漂移；跨模块实例按值比较）
    check(
        "severity 映射与 uat_intake 完全一致（运行时断言）",
        tool.SEVERITIES == intake.SEVERITIES
        and tool.SEVERITIES
        == {"阻断": "severity:high", "严重": "severity:medium", "一般": "severity:low", "建议": "severity:info"},
    )
    check(
        "敏感拦截与 uat_intake 同一实现（行为断言：同输入同输出）",
        tool.lint_sensitive_text("13800138000") == intake.lint_sensitive_text("13800138000")
        and bool(tool.lint_sensitive_text("11010119900307867X"))
        and not tool.lint_sensitive_text("13800（示例）"),
    )
    base = dict(
        zip(
            tool.EXPECTED_COLUMNS,
            [
                "UAT-W34-001",
                "严重",
                "通过",
                "E23-T09",
                "2027-07-08",
                "UAT 环境（示例）",
                "uat-fix-b1",
                "按原步骤不可复现（示例）。",
                "UAT 复测记录 W34-11（示例）",
                "",
            ],
        )
    )
    check("通过 → close(completed)（运行时断言）", tool.decide(base) == ("close", "completed"))
    check(
        "不通过 → reopen（运行时断言）",
        tool.decide(dict(base, conclusion="不通过")) == ("reopen", None),
    )
    check(
        "遗留 → close(not_planned)（运行时断言）",
        tool.decide(dict(base, severity="一般", conclusion="遗留")) == ("close", "not_planned"),
    )
    check(
        "遗留仅允许一般 / 建议级（运行时断言）",
        tool.CLOSE_ALLOWED_SEVERITIES == ("一般", "建议")
        and "阻断" not in tool.CLOSE_ALLOWED_SEVERITIES,
    )
    body = tool.to_comment_body(base, 1)
    check(
        "评论含幂等标记与打回路由（运行时断言）",
        all(t in body for t in ("RETEST-ID: UAT-W34-001 #1", "E23-T09", "E19-T10", "severity:medium")),
    )

    # ---- 4. 索引与流程接线 ----
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接入复测工具自检", "uat_retest.py --selftest" in ci)
    check("ci.yml 接入本校验", "check_uat_retest.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 收录复测手册", "uat-retest-hospital-a.md" in readme)
    uat_manual = UAT_MANUAL.read_text(encoding="utf-8") if UAT_MANUAL.exists() else ""
    check("UAT 手册引用复测工具（§6）", "uat_retest.py" in uat_manual)
    check("UAT 手册 §11 E19-T10 行指向复测手册", "uat-retest-hospital-a.md" in uat_manual)
    check("UAT 手册 §10.5 收录复测面预演留痕", "10.5 E19-T10 复测面预演" in uat_manual)
    fs2 = REMEDIATION_FS2.read_text(encoding="utf-8") if REMEDIATION_FS2.exists() else ""
    check("修复手册（全栈 2）衔接行指向复测手册", "uat-retest-hospital-a.md" in fs2)
    fs1 = REMEDIATION_FS1.read_text(encoding="utf-8") if REMEDIATION_FS1.exists() else ""
    check("修复手册（全栈 1）衔接行指向复测手册", "uat-retest-hospital-a.md" in fs1)
    agents = AGENTS.read_text(encoding="utf-8") if AGENTS.exists() else ""
    check("AGENTS.md 脚本清单含复测工具", "uat_retest.py" in agents)
    check("AGENTS.md 脚本清单含本校验", "check_uat_retest.py" in agents)

    # ---- 5. 医院 C 复测手册（E21-T30 · W44–W45） ----
    manual_c = MANUAL_C.read_text(encoding="utf-8") if MANUAL_C.exists() else ""
    check("C 复测手册存在", bool(manual_c))
    row_c = tasks_row("E21-T30")
    check("tasks.csv 有 E21-T30 行", row_c is not None)
    if row_c:
        check(
            "C 手册窗口与任务标题一致（W44–W45）",
            "W44–W45" in (row_c.get("标题") or "") and "W44–W45" in manual_c,
        )
        check("C 手册验收句与 tasks.csv 一致（UAT 缺陷复测关闭）", (row_c.get("验收标准") or "") in manual_c)
        check(
            "C 手册排期与 tasks.csv 一致（S22 · W44–W45、2027-09-13 起）",
            "S22 · W44–W45（2027-09-13 起" in manual_c
            and (row_c.get("开始周") or "").strip() == "W44"
            and (row_c.get("结束周") or "").strip() == "W45"
            and (row_c.get("开始日期") or "").strip() == "2027-09-13",
        )
        check(
            "C 手册写明未超出里程碑窗口口径",
            (row_c.get("超出里程碑窗口") or "").strip() != "是" and "未标超出里程碑窗口" in manual_c,
        )
        check("C 手册写明依赖（E23-T23）", "E23-T23" in manual_c)
        check("C 手册写明类型为测试任务", "测试，4 人天" in manual_c)
    for anchor in [
        "## 1. 目标与验收口径",
        "## 2. UAT 支持侧职责（测试角色，窗口内）",
        "## 3. 复测流程（修复版本 → 复测 → 结论回写 → 关闭 / 打回）",
        "## 4. 复测结论三档与关闭动作",
        "## 5. 复测清单与工具（机制交付 · 本任务仓库侧完成）",
        "## 6. 复测链路预演",
        "## 7. 闭环判据与看板",
        "## 8. 待人工 / 外部事项清单（窗口 W44–W45 内执行）",
        "## 9. 与其他任务的衔接",
        "## 10. 验证留痕",
    ]:
        check(f"C 手册章节「{anchor}」在位", anchor in manual_c)
    for concl in ("**通过**", "**不通过**", "**遗留（延期上线，书面接受）**"):
        check(f"C 结论三档含「{concl}」", concl in manual_c)
    check("C 关闭动作口径（completed）在位", "completed" in manual_c)
    check("C 关闭动作口径（not planned）在位", "not planned" in manual_c)
    check(
        "C 阻断 / 严重不允许遗留（判据 2 清零）",
        "阻断 / 严重不允许遗留" in manual_c or "仅**一般 / 建议级**" in manual_c,
    )
    check(
        "C 手册含判据 2 整句（阻断（severity:high）与严重（severity:medium）全部关闭）",
        "阻断（severity:high）与严重（severity:medium）全部关闭" in manual_c,
    )
    check(
        "C 手册写明 W45 清零硬期限（不延入 W46）",
        "不延入 W46" in manual_c and "2027-09-23" in manual_c,
    )
    for marker in [
        "uat_retest.py",
        "--hospital c",
        "uat-retest-hospital-c-template.csv",
        "uat-hospital-c.md",
        "uat-hospital-a.md",
        "uat_intake.py",
        "GitHub Issues",
        "OQ-122",
        "OQ-153",
        "OQ-148",
        "OQ-144",
        "AGENTS §5.5",
        "E23-T23",
        "E23-T24",
        "E23-T25",
        "E23-T26",
        "E21-T29",
        "E19-T10",
        "E21-T31",
        "E23-T27",
        "CFullChainAcceptanceIT",
        "V137",
        "--status",
        "--round 2",
        "RETEST-ID",
    ]:
        check(f"C 手册含关键口径「{marker}」", marker in manual_c)
    for sev in intake.SEVERITIES:
        check(f"C 手册含分级「{sev}」", sev in manual_c)
    check("C 手册写明看板按周次过滤（OQ-153①）", "按 UAT-ID 周次 W44 / W45 过滤" in manual_c)
    check("C 手册含打回口径（新轮次）", "新轮次" in manual_c)
    check(
        "C 手册遗留清单挂接 OQ-148 与复核清单（C 手册 §7-4）",
        "OQ-148 确认结论" in manual_c and "复核清单 5 项" in manual_c,
    )
    # 复测链路预演三件套（§6 / §10）
    check("C 预演含基准回归项（CFullChainAcceptanceIT 5/5）", "5/5" in manual_c)
    check("C 预演含工具自检结果（63/63）", "63/63" in manual_c)
    check("C 预演含判据看板实拉（--status --hospital c，0 条）", "--status --hospital c" in manual_c)
    check("C 预演写明当前复测对象为空（窗口未到）", "窗口未到" in manual_c)

    # ---- 6. C 复测清单模板 ----
    trows_c, errs_c = tool.load_retests(TEMPLATE_C)
    check("C 模板表头与工具列定义一致", not errs_c)
    check("C 模板行全部按 --hospital c 校验通过", not tool.validate_rows(trows_c, "c"))
    check(
        "C 模板示例行 ≥2 且复测结果带「示例」",
        len(trows_c) >= 2 and all("示例" in (r["reproduce_result"] or "") for r in trows_c),
    )
    with TEMPLATE_C.open(encoding="utf-8-sig") as f:
        header_c = next(csv.reader(f))
    check("C 模板表头顺序与 EXPECTED_COLUMNS 逐列一致", header_c == tool.EXPECTED_COLUMNS)
    check(
        "C 模板示例结论覆盖 通过 / 遗留（关闭动作示范）",
        {r["conclusion"] for r in trows_c} == {"通过", "遗留"},
    )
    check(
        "C 模板周次 W44 / W45 且修复任务 E23-T24 / E23-T25",
        all(
            r["uat_id"].split("-")[1] in ("W44", "W45")
            and r["fix_task"] in ("E23-T24", "E23-T25")
            for r in trows_c
        ),
    )
    check("C 模板不含 A 侧任务 / 周次（不串院）", all(r["fix_task"] not in ("E23-T09", "E23-T10") for r in trows_c))

    # ---- 7. C 侧工具护栏（运行时断言） ----
    src_c = TOOL.read_text(encoding="utf-8") if TOOL.exists() else ""
    for guard, why in [
        ("--hospital", "院区路由（A / C，OQ-153①）"),
        ("split_by_hospital", "看板按 UAT-ID 周次过滤出院区"),
        ("E23-T24", "C 侧打回路由修复任务（全栈 1）"),
        ("E23-T25", "C 侧打回路由修复任务（全栈 2）"),
        ("E21-T30", "C 侧复测任务署名"),
    ]:
        check(f"复测工具 C 护栏「{guard}」（{why}）", guard in src_c)
    check(
        "C 路由周次与 uat_intake 同源（运行时断言）",
        all(tool.HOSPITALS[h]["weeks"] == intake.HOSPITALS[h]["weeks"] for h in ("a", "c"))
        and sorted(tool.HOSPITALS) == ["a", "c"],
    )
    base_c = dict(
        zip(
            tool.EXPECTED_COLUMNS,
            [
                "UAT-W44-001",
                "严重",
                "通过",
                "E23-T24",
                "2027-09-16",
                "UAT 环境（示例）",
                "cuat-fix-b1",
                "按原步骤不可复现（示例）。",
                "UAT 复测记录 W44-21（示例）",
                "",
            ],
        )
    )
    body_c = tool.to_comment_body(base_c, 1, "c")
    check(
        "C 评论含幂等标记与打回路由（运行时断言）",
        all(t in body_c for t in ("RETEST-ID: UAT-W44-001 #1", "E23-T24", "E21-T30", "severity:medium")),
    )
    check(
        "C 评论不串 A 侧任务（运行时断言）",
        "E23-T09" not in body_c and "E19-T10" not in body_c,
    )
    check(
        "A 评论不串 C 侧任务（运行时断言）",
        "E23-T24" not in body and "E21-T30" not in body,
    )
    check(
        "C 周次越窗 / 越院拦截（运行时断言）",
        not tool.validate_rows([base_c], "c")
        and any("窗口" in e for e in tool.validate_rows([base_c], "a"))
        and any("窗口" in e for e in tool.validate_rows([base], "c"))
        and any("fix_task" in e for e in tool.validate_rows([dict(base_c, fix_task="E23-T09")], "c"))
        and any("fix_task" in e for e in tool.validate_rows([dict(base, fix_task="E23-T24")], "a")),
    )
    fake_pool = [
        {"number": 1, "state": "open", "body": "UAT-ID: UAT-W44-001", "labels": []},
        {"number": 2, "state": "open", "body": "UAT-ID: UAT-W45-012", "labels": []},
        {"number": 3, "state": "open", "body": "UAT-ID: UAT-W34-001", "labels": []},
        {"number": 4, "state": "open", "body": "手工建单，无标记", "labels": []},
    ]
    inc_c, exc_c, unm_c = tool.split_by_hospital(fake_pool, tool.HOSPITALS["c"]["weeks"])
    inc_a, exc_a, _unm_a = tool.split_by_hospital(fake_pool, tool.HOSPITALS["a"]["weeks"])
    check(
        "看板周次过滤行为（运行时断言：C 收 W44/W45、A 收 W33–W35、无标记计入单列）",
        [i["number"] for i in inc_c] == [1, 2, 4]
        and [i["number"] for i in exc_c] == [3]
        and [i["number"] for i in inc_a] == [3, 4]
        and [i["number"] for i in exc_a] == [1, 2]
        and [i["number"] for i in unm_c] == [4],
    )
    check(
        "C 缺省模板指向 C 模板（运行时断言）",
        tool.HOSPITALS["c"]["template"] == Path("docs/testing/uat-retest-hospital-c-template.csv")
        and tool.HOSPITALS["a"]["template"] == Path("docs/testing/uat-retest-template.csv"),
    )

    # ---- 8. C 索引与流程接线 ----
    check("docs/README.md 收录 C 复测手册", "uat-retest-hospital-c.md" in readme)
    uat_manual_c = UAT_MANUAL_C.read_text(encoding="utf-8") if UAT_MANUAL_C.exists() else ""
    check("C UAT 手册引用复测工具 C 路由（§6）", "uat_retest.py" in uat_manual_c and "--hospital c" in uat_manual_c)
    check("C UAT 手册 §11 E21-T30 行指向 C 复测手册", "uat-retest-hospital-c.md" in uat_manual_c)
    check("A 复测手册 §9 E21-T30 行指向 C 复测手册（框架复用落地）", "uat-retest-hospital-c.md" in manual)
    check("ci.yml 复测步骤注释覆盖 E21-T30（A / C 两册）", "E21-T30" in ci)
    oq = OPEN_QUESTIONS.read_text(encoding="utf-8") if OPEN_QUESTIONS.exists() else ""
    check("open-questions.md OQ-153 收录 C 复测路由落地", "uat-retest-hospital-c.md" in oq)

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("✓ " if ok else "✗ ") + n)
    print(f"UAT 复测（E19-T10 / E21-T30）校验：{len(checks) - len(failed)}/{len(checks)} 通过")
    if failed:
        print("失败项：" + "；".join(failed), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
