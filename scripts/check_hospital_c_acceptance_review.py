#!/usr/bin/env python3
"""医院 C 需求澄清与验收用例评审（E21-T31，产品）文档防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E21-T31 行：类型 = 产品、Epic = E21 医院 C 扩展、验收原句
   「验收用例评审通过」、无依赖；
2. `docs/reviews/hospital-c-acceptance-review.md` 评审记录：头部任务行（任务号 / 验收原句 /
   排期 W38）、澄清记录表（CL-xx 连续、每行含问题与定论去处）、验收用例表（C-ACC-xx 连续、
   条数声明一致、每行含依据 / 用例要点 / 承接 / 评审结论，结论全部以「通过」开头）、
   任务覆盖核对表（E21-T01…T32 与 C 侧产品 / 测试任务 E23-T20/T21/T22/T23/T27 每任务一行）、
   评审结论（含「通过」与澄清去处：OQ-131 回写、Q18 / 三项假设的 UAT 复核清单）；
3. 追溯一致性（评审「通过」的机器面判据）：
   - 用例依据列引用的 C-xx-nnn 需求编号全部存在于 `docs/requirements/hospital-c-spec.md`；
   - tasks.csv 中 E21 全部任务与 C 侧配置任务（E23-T20/T21/T22/T27）的关联需求编号，
     全部被至少一条用例的依据列覆盖（测试 / UAT 类任务本身是用例执行方，不要求关联需求）；
   - 引用的 Q-nn 全部在规格 §13「已确认事项 / 暂缓事项」表中；引用的 OQ-nn 全部在
     `docs/open-questions.md` 中登记；
   - 规格规模锚点：§12 功能清单 101 项（P0 82 / P1 18 / P2 1）+ C-NFR 8 条 = 109 编号，
     与评审文档「评审对象」声明的数字一致；
4. 接线：`docs/README.md` 索引含评审文档路径、`docs/open-questions.md` 含 OQ-131 行、
   `.github/workflows/ci.yml` 含本校验步骤。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REVIEW = ROOT / "docs/reviews/hospital-c-acceptance-review.md"
SPEC = ROOT / "docs/requirements/hospital-c-spec.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"

# 评审文档中的用例行：| C-ACC-01 | 级别 | 依据 | 用例要点 | 承接 | 结论 |
CASE_ROW = re.compile(r"^\|\s*C-ACC-(\d{2})\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|", re.M)
# 澄清记录行：| CL-01 | 来源 | 问题 | 定论 / 暂行口径 | 去处 |
CLARIFY_ROW = re.compile(r"^\|\s*CL-(\d{2})\s*\|([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|", re.M)
# 任务覆盖核对表行：| E21-T01 | C-ACC-xx … | 说明 |
TASK_COVER_ROW = re.compile(r"^\|\s*(E2[13]-T\d{2})\s*\|([^|]*)\|([^|]*)\|", re.M)
REQ_ID = re.compile(r"\bC-(?:FLD|PAT|FU|SCAN|WARD|NTF|DASH|NFR)-\d{3}\b")
Q_REF = re.compile(r"\bQ(\d{2})\b")
OQ_REF = re.compile(r"\bOQ-(\d{2,3})\b")
# 承接列里允许的「非用例」标记（测试 / UAT / 自身任务是用例执行方或评审方）
NO_CASE_MARK = "—"

# 需要在覆盖核对表登记的任务（E21 全部 + C 侧产品 / 测试配置任务）
EXPECTED_COVER_TASKS = [f"E21-T{n:02d}" for n in range(1, 33)] + [
    "E23-T20",
    "E23-T21",
    "E23-T22",
    "E23-T23",
    "E23-T27",
]
# 本身是用例执行方 / 评审方的任务：不要求关联需求被用例覆盖，也不要求承接用例编号
EXECUTOR_TASKS = {
    "E21-T24",
    "E21-T25",
    "E21-T26",
    "E21-T27",
    "E21-T28",
    "E21-T29",
    "E21-T30",
    "E21-T31",
    "E23-T23",
    "E23-T27",
}


def fail(msg: str) -> None:
    print(f"[FAIL] {msg}")
    sys.exit(1)


def load_tasks() -> dict[str, dict[str, str]]:
    """读 tasks.csv，返回 任务编号 → 行（BOM 容错）。"""
    with TASKS_CSV.open(encoding="utf-8-sig", newline="") as f:
        rows = {
            r["任务编号"]: r
            for r in csv.DictReader(f)
            if r.get("任务编号", "").startswith(("E21-", "E23-"))
        }
    return rows


def main() -> None:
    if not REVIEW.exists():
        fail(f"评审记录不存在：{REVIEW}")
    review = REVIEW.read_text(encoding="utf-8")
    spec = SPEC.read_text(encoding="utf-8")
    oq = OPEN_QUESTIONS.read_text(encoding="utf-8")
    ci = CI_YML.read_text(encoding="utf-8")
    readme = README.read_text(encoding="utf-8")
    tasks = load_tasks()

    # ---- 1. tasks.csv E21-T31 行 ----
    row = tasks.get("E21-T31")
    if row is None:
        fail("tasks.csv 缺 E21-T31 行")
    if row["类型"] != "产品":
        fail(f"E21-T31 类型应为「产品」，实际「{row['类型']}」")
    if row["Epic"] != "E21 医院 C 扩展":
        fail(f"E21-T31 Epic 应为「E21 医院 C 扩展」，实际「{row['Epic']}」")
    if row["验收标准"] != "验收用例评审通过":
        fail(f"E21-T31 验收原句应为「验收用例评审通过」，实际「{row['验收标准']}」")

    # ---- 2. 评审文档头部 ----
    for anchor in (
        "E21-T31",
        "验收用例评审通过",
        "hospital-c-spec.md",
        "S19 · W38",
    ):
        if anchor not in review:
            fail(f"评审文档头部缺锚点：{anchor}")

    # ---- 3. 澄清记录表 ----
    clar_ids = [int(m.group(1)) for m in CLARIFY_ROW.finditer(review)]
    if not clar_ids:
        fail("评审文档缺澄清记录表（CL-xx 行）")
    if clar_ids != list(range(1, len(clar_ids) + 1)):
        fail(f"澄清记录编号不连续：{clar_ids}")
    for m in CLARIFY_ROW.finditer(review):
        _, src, problem, ruling, dest = m.groups()
        if not src.strip() or not problem.strip() or not ruling.strip() or not dest.strip():
            fail(f"澄清记录 CL-{m.group(1)} 存在空单元格（来源 / 问题 / 定论 / 去处均必填）")

    # ---- 4. 验收用例表 ----
    case_ids = [int(m.group(1)) for m in CASE_ROW.finditer(review)]
    if not case_ids:
        fail("评审文档缺验收用例表（C-ACC-xx 行）")
    if case_ids != list(range(1, len(case_ids) + 1)):
        fail(f"用例编号不连续：{case_ids}")
    count_claims = re.findall(r"共\s*(\d+)\s*条", review)
    if str(len(case_ids)) not in count_claims:
        fail(f"用例条数声明（{count_claims}）与实际行数 {len(case_ids)} 不一致")
    case_rows = list(CASE_ROW.finditer(review))
    for m in case_rows:
        cid, level, basis, body, owner, verdict = m.groups()
        if not basis.strip() or not body.strip() or not owner.strip():
            fail(f"C-ACC-{cid} 存在空单元格（依据 / 用例要点 / 承接均必填）")
        if not verdict.strip().startswith("通过"):
            fail(f"C-ACC-{cid} 评审结论应为「通过…」，实际「{verdict.strip()}」")
        if "阻断" not in level and "重要" not in level:
            fail(f"C-ACC-{cid} 级别含「阻断 / 重要」字样，实际「{level.strip()}」")

    # ---- 5. 追溯：依据需求编号存在且任务关联需求全覆盖 ----
    basis_union = "\n".join(m.group(3) for m in case_rows)
    cited_reqs = set(REQ_ID.findall(basis_union))
    spec_ids = set(REQ_ID.findall(spec))
    unknown = sorted(cited_reqs - spec_ids)
    if unknown:
        fail(f"用例依据引用了规格中不存在的需求编号：{unknown}")

    required: set[str] = set()
    for tid in EXPECTED_COVER_TASKS:
        if tid in EXECUTOR_TASKS:
            continue
        r = tasks.get(tid)
        if r is None:
            fail(f"tasks.csv 缺任务行：{tid}")
        required |= set(REQ_ID.findall(r["关联需求"]))
    uncovered = sorted(required - cited_reqs)
    if uncovered:
        fail(f"任务关联需求未被任何用例依据覆盖：{uncovered}")

    # ---- 6. Q / OQ 引用存在 ----
    confirmed_qs = set(
        int(n) for n in re.findall(r"^\|\s*Q(\d{2})\s*\|", spec, re.M)
    )
    for q in {int(n) for n in Q_REF.findall(review)}:
        if q not in confirmed_qs:
            fail(f"评审引用的 Q{q:02d} 不在规格 §13 已确认 / 暂缓表中")
    registered_oqs = set(
        int(n) for n in re.findall(r"^\|\s*OQ-(\d{2,3})\s*\|", oq, re.M)
    )
    for o in {int(n) for n in OQ_REF.findall(review)}:
        if o not in registered_oqs:
            fail(f"评审引用的 OQ-{o:03d} 未在 open-questions.md 登记")

    # ---- 7. 任务覆盖核对表 ----
    cover_rows = {m.group(1): m for m in TASK_COVER_ROW.finditer(review)}
    missing = [t for t in EXPECTED_COVER_TASKS if t not in cover_rows]
    if missing:
        fail(f"覆盖核对表缺任务行：{missing}")
    valid_case_ids = {f"C-ACC-{n:02d}" for n in case_ids}
    for tid, m in cover_rows.items():
        refs = set(re.findall(r"C-ACC-\d{2}", m.group(2)))
        if NO_CASE_MARK not in m.group(2) and not refs:
            fail(f"覆盖核对表 {tid} 行既无用例编号也非「—」标记")
        bad = sorted(refs - valid_case_ids)
        if bad:
            fail(f"覆盖核对表 {tid} 行引用了不存在的用例编号：{bad}")

    # ---- 8. 评审结论与 UAT 移交 ----
    for anchor in (
        "OQ-131",
        "Q18",
        "E23-T23",
        "E23-T20",
    ):
        if anchor not in review:
            fail(f"评审结论 / 移交缺锚点：{anchor}")
    if not re.search(r"^#+ .*评审.*结论", review, re.M):
        fail("评审文档缺「评审结论」章节")
    if "**评审结论：通过**" not in review:
        fail("评审文档缺「**评审结论：通过**」结论声明")

    # ---- 9. 规模锚点：规格 §12 计数与文档声明一致 ----
    sec12 = spec.split("## 12.")[1].split("## 13.")[0]
    p0 = len(re.findall(r"\|\s*P0\s*\|", sec12))
    p1 = len(re.findall(r"\|\s*P1\s*\|", sec12))
    p2 = len(re.findall(r"\|\s*P2\s*\|", sec12))
    nfr = len(re.findall(r"\|\s*C-NFR-\d{3}\s*\|", spec))
    if (p0, p1, p2) != (82, 18, 1) or p0 + p1 + p2 != 101 or nfr != 8:
        fail(
            "规格规模锚点漂移：§12 应为 101 项（P0 82 / P1 18 / P2 1）、C-NFR 8 条，"
            f"实际 P0 {p0} / P1 {p1} / P2 {p2} / NFR {nfr}——若规格合法变更，同步更新评审文档声明"
        )
    for num in ("101", "109"):
        if num not in review:
            fail(f"评审文档「评审对象」应声明规模 {num}（与规格锚点一致）")

    # ---- 10. 接线 ----
    if "reviews/hospital-c-acceptance-review.md" not in readme:
        fail("docs/README.md 索引未登记评审文档")
    if not re.search(r"^\|\s*OQ-131\s*\|", oq, re.M):
        fail("open-questions.md 缺 OQ-131 行（评审澄清的暂行口径回写）")
    if "check_hospital_c_acceptance_review.py" not in ci:
        fail("ci.yml 未接入本校验脚本")

    print(
        f"[OK] 医院 C 需求澄清与验收用例评审校验通过：澄清 {len(clar_ids)} 条、"
        f"用例 {len(case_ids)} 条（依据覆盖任务关联需求 {len(required)} 个编号）、"
        f"覆盖核对 {len(cover_rows)} 个任务、Q/OQ 引用与规格规模锚点一致、接线完整"
    )


if __name__ == "__main__":
    main()
