#!/usr/bin/env python3
"""现场测评配合（E22-T14）手册、测评问题清单模板与入缺陷库机制防漂移校验（CI 恒跑，不联网）。

校验对象（验收「测评问题清单输出」的机器可验化身）：

1. `docs/tasks/tasks.csv` E22-T14 行：类型 = 测试、依赖 E22-T13、验收原句「测评问题清单输出」、
   排期 W39–W40（2027-08-09）；整改路由锚点 E22-T15 / T16 / T17 / T18 行在位；
2. `docs/ops/mlps/onsite-assessment.md` 配合手册：窗口 W39–W43（OQ-30 压缩 5 周）、
   验收拆解（不符合 + 部分符合全量 → CSV → 入缺陷库 GitHub Issues，OQ-137）、
   前置就绪（E22-T13 资料包 M-01~M-17）、现场环境与演示账号（虚构数据，AGENTS §5.5）、
   十分类演示要点映射、周日程（W39~W43 每周）、问题确认会与高风险即报即入、
   分级映射与整改路由（backend→E22-T15 / frontend→E22-T16）、复测闭环与退出标准、
   归档口径（报告原件不入仓库）、待人工清单、自动化对照；
3. `docs/ops/mlps/onsite-findings-template.csv` 模板：表头与 `mlps_onsite_intake.py` 的列定义
   完全一致、示例行校验通过且全部带「示例」标记、覆盖两种结论与多档风险；
4. `scripts/mlps_onsite_intake.py` 护栏在位：默认 dry-run（--apply 才写库）、token 只经
   git credential fill、幂等标记 MLPS-ID、finding_id 窗口约束（W39–W43）、疑似真实手机号 /
   证件号拦截、分级映射 高/中/低 ↔ severity:*、整改路由；源码无硬编码凭据；
5. 索引与接线：ci.yml 含自检与本校验步骤、docs/README.md、E22-T13 清单（mlps/README.md
   M-17）与自查记录 §5 的衔接、open-questions.md OQ-137。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/ops/mlps/onsite-assessment.md"
TEMPLATE = ROOT / "docs/ops/mlps/onsite-findings-template.csv"
INTAKE = ROOT / "scripts/mlps_onsite_intake.py"
MLPS_CHECKLIST = ROOT / "docs/ops/mlps/README.md"
SELF_ASSESSMENT = ROOT / "docs/ops/mlps/self-assessment.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"

GHP_TOKEN_PATTERN = re.compile(r"gh[pousr]_[A-Za-z0-9]{16,}")

# 等保 2.0（GB/T 22239-2019）三级安全要求分类（技术 5 + 管理 5）——演示要点映射须逐类覆盖
DENGBAO_CATEGORIES = (
    "安全物理环境",
    "安全通信网络",
    "安全区域边界",
    "安全计算环境",
    "安全管理中心",
    "安全管理制度",
    "安全管理机构",
    "安全管理人员",
    "安全建设管理",
    "安全运维管理",
)


def load_intake_module():
    if not INTAKE.exists():
        return None
    spec = importlib.util.spec_from_file_location("mlps_onsite_intake", INTAKE)
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


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    def read(p: Path) -> str:
        return p.read_text(encoding="utf-8") if p.exists() else ""

    manual = read(MANUAL)
    intake_src = read(INTAKE)
    checklist = read(MLPS_CHECKLIST)
    self_assessment = read(SELF_ASSESSMENT)
    ci = read(CI_YML)
    readme = read(README)
    oq = read(OPEN_QUESTIONS)

    # ---- 1. tasks.csv 行 ----
    row = tasks_row("E22-T14")
    check("tasks.csv 有 E22-T14 行", row is not None)
    if row:
        check(
            "任务标题与类型（现场测评 · 测试）",
            "现场测评" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "测试",
        )
        check("依赖 E22-T13（等保测评资料）", (row.get("依赖") or "").strip() == "E22-T13")
        check("验收原句（测评问题清单输出）", (row.get("验收标准") or "").strip() == "测评问题清单输出")
        check(
            "排期 W39–W40 / 2027-08-09",
            (row.get("开始周") or "").strip() == "W39" and (row.get("结束周") or "").strip() == "W40"
            and (row.get("开始日期") or "") == "2027-08-09",
        )
    for tid in ("E22-T15", "E22-T16", "E22-T17", "E22-T18"):
        check(f"整改闭环任务行在位「{tid}」", tasks_row(tid) is not None)

    # ---- 2. 配合手册 ----
    check("配合手册存在（docs/ops/mlps/onsite-assessment.md）", bool(manual))
    check(
        "手册头部任务行（E22-T14 · 测试 · 依赖 E22-T13 · 验收原句）",
        all(k in manual for k in ("E22-T14", "测试", "E22-T13", "测评问题清单输出")),
    )
    check(
        "窗口与 tasks.csv / OQ-30 一致（W39–W43 · 2027-08-09 ~ 2027-09-10）",
        all(k in manual for k in ("W39–W43", "2027-08-09", "2027-09-10", "OQ-30")),
    )
    check(
        "需求依据（A-NFR-007 · NFR-CMP-001 · GB/T 22239-2019）",
        all(k in manual for k in ("A-NFR-007", "NFR-CMP-001", "GB/T 22239-2019")),
    )
    check(
        "验收拆解（不符合 + 部分符合 全量 → CSV → 入缺陷库）",
        "不符合" in manual and "部分符合" in manual and "全量" in manual and "入缺陷库" in manual,
    )
    check("缺陷库载体 GitHub Issues（OQ-112 ① / OQ-137）", "GitHub Issues" in manual and "OQ-137" in manual and "OQ-112" in manual)
    check(
        "前置就绪指向 E22-T13 资料包（M-01~M-17 · check_mlps_docs.py）",
        "E22-T13" in manual and "M-01" in manual and "M-17" in manual and "check_mlps_docs.py" in manual,
    )
    check("前置就绪含由人补齐项（M-14~M-17）与自查复核（M-16）", "M-14" in manual and "M-16" in manual)
    check("已知偏差如实说明（D-01~D-08 · self-assessment.md）", "D-01" in manual and "D-08" in manual and "self-assessment.md" in manual)
    check("现场环境与生产同构 UAT 且数据虚构（AGENTS §5.5）", "UAT" in manual and "虚构" in manual and "§5.5" in manual)
    check(
        "演示账号口径（一次性口令 · 场内发放 · 结束禁用）",
        "一次性" in manual and "发放" in manual and "禁用" in manual,
    )
    for cat in DENGBAO_CATEGORIES:
        check(f"演示要点映射覆盖「{cat}」", cat in manual)
    for week in ("W39", "W40", "W41", "W42", "W43"):
        check(f"周日程覆盖「{week}」", week in manual)
    check("问题确认会（事实核对 / 申辩与补充证据）", "问题确认会" in manual and "事实核对" in manual)
    check("高风险即报即入（24 小时内，不等报告）", "24 小时" in manual and "即报" in manual)
    check("报告出具后 2 个工作日全量入库", "2 个工作日" in manual)
    check(
        "分级映射（高 / 中 / 低 ↔ severity:high|medium|low）",
        all(k in manual for k in ("severity:high", "severity:medium", "severity:low")),
    )
    check(
        "整改路由（backend → E22-T15 · frontend → E22-T16 · infra / management 会诊）",
        "E22-T15" in manual and "E22-T16" in manual and "运维会诊" in manual and "管理会诊" in manual,
    )
    check("闭环（E22-T17 复测 → 回写自查 → E22-T18 备案）", "E22-T17" in manual and "E22-T18" in manual)
    check(
        "退出标准（100% 入库 · 高风险全部整改复测 · 中风险有处置结论）",
        "100%" in manual and "高风险" in manual and "处置结论" in manual,
    )
    check("归档口径（报告原件与证据不入仓库）", "不入仓库" in manual)
    check("待人工 / 外部事项清单", "待人工" in manual)
    check("C 上线在测评通过后（E23-T26 依赖 E22-T17）", "E23-T26" in manual)
    check("自动化对照（check_mlps_onsite.py）", "check_mlps_onsite.py" in manual)

    # ---- 3. 模板 CSV ----
    module = load_intake_module()
    check("入库工具存在（scripts/mlps_onsite_intake.py）", bool(intake_src))
    template_ok = False
    if module is not None:
        rows, errs = module.load_findings(TEMPLATE)
        template_ok = not errs and TEMPLATE.exists()
        check("模板存在且表头与工具列定义一致（onsite-findings-template.csv）", not errs and TEMPLATE.exists())
        check("模板全部行通过工具校验", not module.validate_rows(rows) if rows else False)
        check("模板含 2 条示例问题", len(rows) == 2)
        check("模板示例行标题均带「示例」标记（AGENTS §5.5）", all("示例" in (r.get("title") or "") for r in rows))
        if rows:
            conclusions = {(r.get("conclusion") or "").strip() for r in rows}
            risks = {(r.get("risk_level") or "").strip() for r in rows}
            check("示例覆盖两种结论（不符合 / 部分符合）", conclusions == {"不符合", "部分符合"})
            check("示例覆盖至少两档风险（含高）", "高" in risks and len(risks) >= 2)
        # ---- 4. 工具护栏（源码静态核对） ----
        check("默认 dry-run（--apply 才写库，action=store_true）", '"--apply"' in intake_src and "store_true" in intake_src)
        check("token 只经 git credential fill（不回显不落盘）", '"credential"' in intake_src and '"fill"' in intake_src)
        check("幂等标记 MLPS-ID（独立行正则）", "MLPS-ID" in intake_src and "ID_MARKER_PATTERN" in intake_src)
        check("finding_id 窗口约束（W39–W43，机构现场测评周次）", "39" in getattr(module, "ID_PATTERN", re.compile("")).pattern and bool(getattr(module, "ID_PATTERN", re.compile("")).match("MLPS-W41-001")) and not bool(getattr(module, "ID_PATTERN", re.compile("")).match("MLPS-W35-001")))
        check("疑似真实手机号 / 身份证号拦截（AGENTS §5.5）", "MOBILE_CN_PATTERN" in intake_src and "IDCARD_CN_PATTERN" in intake_src)
        mapping = getattr(module, "RISK_LEVELS", {})
        check(
            "分级映射 高/中/低 → severity:high|medium|low（唯一事实源）",
            mapping.get("高") == "severity:high" and mapping.get("中") == "severity:medium" and mapping.get("低") == "severity:low",
        )
        routing = getattr(module, "ASSET_FIX_TASK", {})
        check(
            "整改路由（backend → E22-T15 · frontend → E22-T16）",
            "E22-T15" in routing.get("backend", "") and "E22-T16" in routing.get("frontend", ""),
        )
        check("结论口径（不符合 / 部分符合）", set(getattr(module, "CONCLUSIONS", set())) == {"不符合", "部分符合"})
        check("分类口径 = 等保十分类", tuple(getattr(module, "CATEGORIES", ())) == DENGBAO_CATEGORIES)
        check("自检入口 --selftest（CI 恒跑不联网）", '"--selftest"' in intake_src)
        check("入库工具源码无硬编码凭据", not GHP_TOKEN_PATTERN.search(intake_src))
    else:
        for name in (
            "模板存在且表头与工具列定义一致（onsite-findings-template.csv）",
            "模板全部行通过工具校验",
            "模板含 2 条示例问题",
            "模板示例行标题均带「示例」标记（AGENTS §5.5）",
            "默认 dry-run（--apply 才写库，action=store_true）",
            "token 只经 git credential fill（不回显不落盘）",
            "幂等标记 MLPS-ID（独立行正则）",
            "finding_id 窗口约束（W39–W43，机构现场测评周次）",
            "疑似真实手机号 / 身份证号拦截（AGENTS §5.5）",
            "分级映射 高/中/低 → severity:high|medium|low（唯一事实源）",
            "整改路由（backend → E22-T15 · frontend → E22-T16）",
            "结论口径（不符合 / 部分符合）",
            "分类口径 = 等保十分类",
            "自检入口 --selftest（CI 恒跑不联网）",
            "入库工具源码无硬编码凭据",
        ):
            check(name, False)
        print("  注：scripts/mlps_onsite_intake.py 缺失，模板与护栏项全部判失败", file=sys.stderr)

    # ---- 5. 索引与接线 ----
    check("ci.yml 接入自检（mlps_onsite_intake.py --selftest）", "mlps_onsite_intake.py --selftest" in ci)
    check("ci.yml 接入本校验（check_mlps_onsite.py）", "check_mlps_onsite.py" in ci)
    check("docs/README.md 收录现场测评手册（onsite-assessment.md）", "onsite-assessment.md" in readme)
    check(
        "E22-T13 资料清单 M-17 指向现场测评手册（衔接闭环）",
        "M-17" in checklist and "onsite-assessment.md" in checklist,
    )
    check(
        "自查记录 §5 已声明测评问题回写口径（E22-T14 输出 → 偏差登记）",
        "E22-T14" in self_assessment and "回写" in self_assessment,
    )
    oq_line = next((ln for ln in oq.splitlines() if ln.startswith("| OQ-137")), "")
    check("open-questions.md 有 OQ-137（现场测评暂行口径）", bool(oq_line))
    check(
        "OQ-137 关键口径（GitHub Issues · 高/中/低 · 不符合与部分符合全量 · 路由）",
        all(k in oq_line for k in ("GitHub Issues", "高", "不符合", "部分符合", "E22-T15", "E22-T16")),
    )

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("PASS  " if ok else "FAIL  ") + n)
    print(f"\n{len(checks) - len(failed)}/{len(checks)} 项通过")
    if failed:
        print("失败项：", file=sys.stderr)
        for n in failed:
            print("  - " + n, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
