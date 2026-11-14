#!/usr/bin/env python3
"""UAT 缺陷复测与关闭（医院 A：E19-T10 · W33–W35；医院 C：E21-T30 · W44–W45）。

按 `AGENTS.md` 第 9 节，UAT 窗口内的现场复测与关闭由测试角色在场执行
（A 窗口 2027-06-28 ~ 2027-07-16，见 docs/testing/uat-hospital-a.md §9；
C 窗口 2027-09-13 ~ 2027-09-24，见 docs/testing/uat-hospital-c.md §9）；
本脚本交付「复测结论回写缺陷库并关闭」与「闭环判据看板」的机制：
把复测清单（CSV，A 模板 `docs/testing/uat-retest-template.csv`、C 模板
`docs/testing/uat-retest-hospital-c-template.csv`）校验后逐条把结论回写为
GitHub Issue 评论并按结论执行关闭 / 打开（缺陷库 = icedkong/crdp-platform
的 Issues，OQ-122 ① 暂行载体），并可随时输出签字判据 2（阻断 / 严重清零）
的看板汇总。`--hospital a|c` 选择院区路由（打回修复任务、UAT-ID 合法周次、
评论署名与缺省模板，默认 a 保持向后兼容；两院编号空间不冲突，OQ-153 ①）。

安全护栏（与 uat_intake.py 同一套）：

1. 默认 dry-run：只校验、只打印将回写的评论与关闭动作，不联网；`--apply`
   才真正写缺陷库；
2. token 只经 `git credential fill`（host=github.com）取自凭据存储，仅进
   请求头，不回显、不落盘、不写入评论；
3. 幂等：复测评论含 `RETEST-ID: <uat_id> #<轮次>` 标记，回写前拉取该
   Issue 既有评论比对标记，同轮次已回写过则跳过，重复运行不重复评论；
4. 只回写已入库的发现：按 Issue 正文 `UAT-ID: <uat_id>` 标记定位
   （uat_intake 入库口径），找不到一律拒绝——复测不造新单；
5. 严禁真实数据入缺陷库（AGENTS §5.5）：复测文本不得出现疑似真实手机号
   / 身份证号；证据只引用复测记录行号 / 截图编号；
6. 关闭口径与手册判据一致：通过 → 关闭（completed）；不通过 → 打回保持
   打开（已关闭则重开）并路由修复任务（A：E23-T09 / E23-T10；C：
   E23-T24 / E23-T25）；遗留（延期上线，书面接受）→ 关闭（not planned），
   仅允许一般 / 建议级——阻断 / 严重必须复测通过关闭（判据「清零」）；
7. 院区路由（OQ-153 ①）：复测清单的 uat_id 周次须落在所选院区 UAT 窗口
   （A=W33–W35、C=W44–W45），fix_task 须为该院区修复任务——不串院；
   `--status` 看板按 UAT-ID 周次过滤出院区（C 侧判据数据来源）。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import io
import re
import sys
import tempfile
from pathlib import Path

REPO_DEFAULT = "icedkong/crdp-platform"
API_BASE = "https://api.github.com"
TEMPLATE_RELPATH = Path("docs/testing/uat-retest-template.csv")
TEMPLATE_RELPATH_C = Path("docs/testing/uat-retest-hospital-c-template.csv")
INTAKE_PATH = Path(__file__).resolve().parent / "uat_intake.py"

# 分级映射 / 敏感拦截 / UAT-ID 标记：以 uat_intake.py 为唯一事实源（OQ-122 ② 梯子）
_spec = importlib.util.spec_from_file_location("uat_intake", INTAKE_PATH)
intake = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(intake)

SEVERITIES: dict[str, str] = intake.SEVERITIES
SEVERITY_LABELS: dict[str, str] = {v: k for k, v in SEVERITIES.items()}
lint_sensitive_text = intake.lint_sensitive_text
UAT_ID_PATTERN = intake.ID_PATTERN
UAT_ID_MARKER_PATTERN = intake.ID_MARKER_PATTERN
gh_token = intake.gh_token
api = intake.api

# 复测清单列（模板 docs/testing/uat-retest-template.csv 的唯一事实源）
EXPECTED_COLUMNS = [
    "uat_id",
    "severity",
    "conclusion",
    "fix_task",
    "retest_date",
    "retest_env",
    "version",
    "reproduce_result",
    "evidence_ref",
    "comment",
]

# 复测结论三档（docs/testing/uat-retest-hospital-a.md §4，C 侧
# uat-retest-hospital-c.md §4 同口径）：通过 → 关闭（completed）；不通过 →
# 打回保持打开；遗留 → 关闭（not planned，仅一般 / 建议级——延期上线书面接受）
CONCLUSIONS = ("通过", "不通过", "遗留")
CLOSE_ALLOWED_SEVERITIES = ("一般", "建议")  # 遗留（延期上线）只允许低级别

# 院区路由（OQ-153 ①）：打回修复任务 / 复测任务署名 / UAT-ID 合法周次 /
# 缺省模板 / 看板判据出处。周次直接引用 uat_intake 的 HOSPITALS（唯一事实源，
# 不另立编号空间）；修复 / 复测任务与 docs/tasks/tasks.csv 一致
# （A = E23-T09 / E23-T10 修复 + E19-T10 复测；C = E23-T24 / E23-T25 修复 +
# E21-T30 复测；手册分别为 uat-hospital-a/c.md §6、复测手册两册 §4）
HOSPITALS: dict[str, dict] = {
    "a": {
        "weeks": intake.HOSPITALS["a"]["weeks"],
        "fix_tasks": ("E23-T09", "E23-T10"),
        "retest_task": "E19-T10（A UAT 支持与缺陷复测）",
        "retest_id": "E19-T10",
        "window_zh": "W33–W35",
        "manual": "uat-hospital-a.md",
        "template": TEMPLATE_RELPATH,
        "source": "来源：E19-T10 医院 A UAT 缺陷复测（W33–W35）；由 `scripts/uat_retest.py` 回写。",
    },
    "c": {
        "weeks": intake.HOSPITALS["c"]["weeks"],
        "fix_tasks": ("E23-T24", "E23-T25"),
        "retest_task": "E21-T30（C UAT 支持与缺陷复测）",
        "retest_id": "E21-T30",
        "window_zh": "W44–W45",
        "manual": "uat-hospital-c.md",
        "template": TEMPLATE_RELPATH_C,
        "source": (
            "来源：E21-T30 医院 C UAT 缺陷复测（W44–W45）；"
            "由 `scripts/uat_retest.py --hospital c` 回写。"
        ),
    },
}
DEFAULT_HOSPITAL = "a"

RETEST_DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# 复测评论幂等标记：RETEST-ID: UAT-W34-001 #1（轮次缺省 1；打回后复测走 #2 起）
RETEST_MARKER_PREFIX = "RETEST-ID: "
RETEST_MARKER_PATTERN = re.compile(
    r"^RETEST-ID:\s*(UAT-W\d{1,2}-\d{3})(?:\s*#(\d+))?\s*$", re.M
)


def load_retests(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    """读取复测清单 CSV（兼容 Excel 的 utf-8-sig），先校验表头。"""
    errors: list[str] = []
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        return [], [f"找不到复测清单：{path}"]
    rows = list(csv.DictReader(io.StringIO(raw)))
    header = list(rows[0].keys()) if rows else next(csv.reader(io.StringIO(raw)), [])
    missing = [c for c in EXPECTED_COLUMNS if c not in header]
    extra = [c for c in header if c not in EXPECTED_COLUMNS]
    if missing:
        errors.append(f"缺少列：{missing}（应为 {EXPECTED_COLUMNS}）")
    if extra:
        errors.append(f"多余列：{extra}（应为 {EXPECTED_COLUMNS}）")
    if errors:
        return [], errors
    return rows, []


def validate_rows(
    rows: list[dict[str, str]], hospital: str = DEFAULT_HOSPITAL
) -> list[str]:
    errors: list[str] = []
    routing = HOSPITALS[hospital]
    weeks = routing["weeks"]
    fix_tasks: tuple[str, ...] = routing["fix_tasks"]
    seen_ids: set[str] = set()
    if not rows:
        errors.append("复测清单为空（只有表头）")
    for i, row in enumerate(rows, start=2):  # 第 1 行是表头
        rid = (row.get("uat_id") or "").strip()
        where = f"第 {i} 行（{rid or '缺 uat_id'}）"
        if not rid:
            errors.append(f"{where}：uat_id 为空")
        elif not UAT_ID_PATTERN.match(rid):
            errors.append(f"{where}：uat_id 须形如 UAT-W34-001")
        elif int(rid.split("-")[1][1:]) not in weeks:
            errors.append(
                f"{where}：周次不在医院 {hospital.upper()} 的 UAT 窗口（W{min(weeks)}–W{max(weeks)}）"
            )
        elif rid in seen_ids:
            errors.append(f"{where}：uat_id 重复（同一发现的复测结论只留最终一条）")
        seen_ids.add(rid)
        sev = (row.get("severity") or "").strip()
        if sev not in SEVERITIES:
            errors.append(f"{where}：severity 须为 阻断/严重/一般/建议，实际「{sev}」")
        concl = (row.get("conclusion") or "").strip()
        if concl not in CONCLUSIONS:
            errors.append(f"{where}：conclusion 须为 通过/不通过/遗留，实际「{concl}」")
        fix_task = (row.get("fix_task") or "").strip()
        if fix_task not in fix_tasks:
            errors.append(
                f"{where}：fix_task 须为 {'/'.join(fix_tasks)}，实际「{fix_task}」"
            )
        date = (row.get("retest_date") or "").strip()
        if not RETEST_DATE_PATTERN.match(date):
            errors.append(f"{where}：retest_date 须为 YYYY-MM-DD，实际「{date}」")
        if not (row.get("retest_env") or "").strip():
            errors.append(f"{where}：retest_env 为空（复测环境标识，含修复版本口径）")
        version = (row.get("version") or "").strip()
        result = (row.get("reproduce_result") or "").strip()
        evidence = (row.get("evidence_ref") or "").strip()
        note = (row.get("comment") or "").strip()
        if concl == "通过":
            if not version:
                errors.append(f"{where}：结论为通过时 version 必填（关闭所依据的修复版本）")
            if "不可复现" not in result and "通过" not in result:
                errors.append(f"{where}：结论为通过时 reproduce_result 须写明按原步骤不可复现")
        elif concl == "不通过":
            if not result:
                errors.append(f"{where}：结论为不通过时 reproduce_result 必填（仍复现的描述）")
            if version and "不可复现" in result:
                errors.append(f"{where}：结论与复现描述矛盾（不通过 但 写不可复现）")
        elif concl == "遗留":
            if sev not in CLOSE_ALLOWED_SEVERITIES:
                errors.append(
                    f"{where}：仅一般 / 建议级可遗留（延期上线书面接受），{sev}必须复测通过关闭"
                )
            if not note:
                errors.append(f"{where}：结论为遗留时 comment 必填（处置结论与期限 + 医院书面接受留痕）")
        if not evidence:
            errors.append(f"{where}：evidence_ref 为空（复测记录行 / 截图编号，文件不入仓库）")
        joined = " ".join(
            (row.get(c) or "")
            for c in ("retest_env", "reproduce_result", "comment")
        )
        for hit in lint_sensitive_text(joined):
            errors.append(f"{where}：{hit}（AGENTS §5.5 只允许虚构示例数据）")
    return errors


def decide(row: dict[str, str]) -> tuple[str, str | None]:
    """复测结论 → Issue 动作：(动作, state_reason)。

    通过 → ("close", "completed")；遗留 → ("close", "not_planned")；
    不通过 → ("reopen", None)（保持打开；已关闭则重开并打回修复任务）。
    """
    concl = (row.get("conclusion") or "").strip()
    if concl == "通过":
        return ("close", "completed")
    if concl == "遗留":
        return ("close", "not_planned")
    return ("reopen", None)


def to_comment_body(
    row: dict[str, str], round_no: int, hospital: str = DEFAULT_HOSPITAL
) -> str:
    """复测结论评论（回写 Issue；含幂等标记与院区复测任务署名）。"""
    routing = HOSPITALS[hospital]
    rid = (row.get("uat_id") or "").strip()
    concl = (row.get("conclusion") or "").strip()
    sev = (row.get("severity") or "").strip()
    action, reason = decide(row)
    action_text = {
        ("close", "completed"): "复测通过，关闭",
        ("close", "not_planned"): "遗留（延期上线，医院书面接受），关闭",
        ("reopen", None): "复测不通过，打回修复（保持打开）",
    }[(action, reason)]
    lines = [
        f"## 复测结论（{routing['retest_id']}）",
        "",
        f"- **结论**：{concl}（{action_text}）",
        f"- **级别**：{sev}（{SEVERITIES.get(sev, '?')}）",
        f"- **原修复任务**：{(row.get('fix_task') or '').strip()}",
        f"- **复测日期**：{(row.get('retest_date') or '').strip()}",
        f"- **复测环境**：{(row.get('retest_env') or '').strip()}",
        f"- **修复版本**：{(row.get('version') or '').strip() or '—（不适用）'}",
        "",
        "### 按原复现步骤的复测结果",
        "",
        (row.get("reproduce_result") or "").strip(),
        "",
        "### 证据",
        "",
        f"{(row.get('evidence_ref') or '').strip()}（截图存 UAT 共享目录，不入仓库）",
    ]
    if (row.get("comment") or "").strip():
        lines += ["", "### 补充（处置结论 / 期限）", "", (row.get("comment") or "").strip()]
    lines += [
        "",
        "---",
        "",
        routing["source"],
        f"{RETEST_MARKER_PREFIX}{rid} #{round_no}",
        "",
    ]
    return "\n".join(lines)


# ---------- GitHub API（仅 --apply / --status 路径触达） ----------


def fetch_uat_issues(repo: str, token: str, max_pages: int = 20) -> list[dict]:
    """拉取 label UAT 的全部 Issue（含关闭，不含 PR）。"""
    issues: list[dict] = []
    for page in range(1, max_pages + 1):
        result = api(
            token,
            "GET",
            f"/repos/{repo}/issues?state=all&labels=UAT&per_page=100&page={page}",
        )
        if not result:
            break
        issues += [i for i in result if "pull_request" not in i]
        if len(result) < 100:
            break
    return issues


def map_uat_id_to_issue(issues: list[dict]) -> dict[str, dict]:
    """按 Issue 正文 UAT-ID 标记建 uat_id → issue 映射（uat_intake 入库口径）。"""
    mapping: dict[str, dict] = {}
    for issue in issues:
        for m in UAT_ID_MARKER_PATTERN.finditer(issue.get("body") or ""):
            mapping[m.group(1)] = issue
    return mapping


def existing_retest_rounds(repo: str, token: str, issue_number: int) -> set[str]:
    """该 Issue 既有复测评论的幂等标记（RETEST-ID: <id> #<n>）。"""
    markers: set[str] = set()
    page = 1
    while True:
        comments = api(
            token,
            "GET",
            f"/repos/{repo}/issues/{issue_number}/comments?per_page=100&page={page}",
        )
        if not comments:
            break
        for c in comments:
            for m in RETEST_MARKER_PATTERN.finditer(c.get("body") or ""):
                markers.add(f"{m.group(1)} #{m.group(2) or '1'}")
        if len(comments) < 100:
            break
        page += 1
    return markers


def apply_action(repo: str, token: str, number: int, action: str, reason: str | None) -> None:
    if action == "close":
        api(
            token,
            "PATCH",
            f"/repos/{repo}/issues/{number}",
            {"state": "closed", "state_reason": reason},
        )
    elif action == "reopen":
        api(token, "PATCH", f"/repos/{repo}/issues/{number}", {"state": "open"})


# ---------- 复测主流程 ----------


def run_retest(
    csv_path: Path, repo: str, apply: bool, round_no: int, hospital: str = DEFAULT_HOSPITAL
) -> int:
    rows, errors = load_retests(csv_path)
    if errors:
        for e in errors:
            print(f"✗ {e}", file=sys.stderr)
        return 1
    errors = validate_rows(rows, hospital)
    if errors:
        for e in errors:
            print(f"✗ {e}", file=sys.stderr)
        return 1
    routing = HOSPITALS[hospital]
    print(
        f"校验通过：{len(rows)} 条复测结论（{csv_path}；医院 {hospital.upper()}，"
        f"窗口 {routing['window_zh']}）"
    )

    token = ""
    mapping: dict[str, dict] = {}
    if apply:
        token = gh_token()
        mapping = map_uat_id_to_issue(fetch_uat_issues(repo, token))

    acted_n = skipped_n = missing_n = 0
    for row in rows:
        rid = row["uat_id"].strip()
        concl = row["conclusion"].strip()
        action, reason = decide(row)
        marker = f"{rid} #{round_no}"
        if not apply:
            print(f"◇ 将回写 {rid}：{concl} → {action}{f'（{reason}）' if reason else ''}")
            print(f"  评论含幂等标记 {RETEST_MARKER_PREFIX}{marker}")
            acted_n += 1
            continue
        issue = mapping.get(rid)
        if issue is None:
            print(f"✗ {rid}：缺陷库无此 UAT-ID 标记的 Issue（须先经 uat_intake 入库）", file=sys.stderr)
            missing_n += 1
            continue
        number = int(issue["number"])
        if marker in existing_retest_rounds(repo, token, number):
            print(f"↷ 跳过 {rid}（第 {round_no} 轮复测结论已回写过）")
            skipped_n += 1
            continue
        api(
            token,
            "POST",
            f"/repos/{repo}/issues/{number}/comments",
            {"body": to_comment_body(row, round_no, hospital)},
        )
        if action == "reopen" and issue.get("state") == "open":
            print(f"✓ 已回写 {rid}（#{number}）：不通过，保持打开并打回 {row['fix_task'].strip()}")
        else:
            apply_action(repo, token, number, action, reason)
            print(f"✓ 已回写 {rid}（#{number}）：{concl} → {action}{f'（{reason}）' if reason else ''}")
        acted_n += 1
    mode = "apply" if apply else "dry-run（未联网；重跑以 --apply 落库）"
    print(f"完成（{mode}）：回写 {acted_n}，跳过 {skipped_n}，未入库 {missing_n}")
    if missing_n:
        return 1
    return 0


def split_by_hospital(
    issues: list[dict], weeks: tuple[int, ...]
) -> tuple[list[dict], list[dict], list[dict]]:
    """按 UAT-ID 周次把 label UAT 的 Issue 分到本院 / 他院 / 无标记。

    OQ-153 ①：两院 UAT-ID 编号空间不冲突，判据看板按周次过滤；正文无
    UAT-ID 标记的 Issue（如手工建单）计入本院看板并提示 triage 补标记。
    """
    included: list[dict] = []
    excluded: list[dict] = []
    unmarked: list[dict] = []
    for issue in issues:
        ids = UAT_ID_MARKER_PATTERN.findall(issue.get("body") or "")
        if not ids:
            unmarked.append(issue)
            included.append(issue)
        elif any(int(i.split("-")[1][1:]) in weeks for i in ids):
            included.append(issue)
        else:
            excluded.append(issue)
    return included, excluded, unmarked


def run_status(repo: str, hospital: str = DEFAULT_HOSPITAL) -> int:
    """闭环判据看板：label UAT 的 Issue 按 severity 汇总开 / 关（判据 2 数据来源）。

    按所选院区的 UAT-ID 周次过滤（A=W33–W35、C=W44–W45，OQ-153 ①）：他院
    周次排除并单列计数；无 UAT-ID 标记的计入并提示（triage 补标记）。
    """
    routing = HOSPITALS[hospital]
    weeks = routing["weeks"]
    token = gh_token()
    all_issues = fetch_uat_issues(repo, token)
    issues, others, unmarked = split_by_hospital(all_issues, weeks)
    buckets: dict[str, dict[str, list[str]]] = {
        sev: {"open": [], "closed": []} for sev in SEVERITIES
    }
    unknown: list[str] = []
    for issue in issues:
        labels = [l.get("name", "") for l in issue.get("labels", [])]
        sev_labels = [l for l in labels if l in SEVERITY_LABELS]
        if len(sev_labels) != 1:
            unknown.append(f"#{issue['number']}")
            continue
        sev = SEVERITY_LABELS[sev_labels[0]]
        buckets[sev]["open" if issue.get("state") == "open" else "closed"].append(
            f"#{issue['number']}"
        )
    print(
        f"UAT 缺陷库看板（医院 {hospital.upper()}：label UAT 且 UAT-ID 周次 "
        f"W{min(weeks)}–W{max(weeks)}，共 {len(issues)} 条；"
        f"判据见 {routing['manual']} §7 判据 2）"
    )
    for sev, label in SEVERITIES.items():
        b = buckets[sev]
        print(
            f"  {sev}（{label}）：开 {len(b['open'])} / 关 {len(b['closed'])}"
            + (f"  开着：{' '.join(b['open'])}" if b["open"] else "")
        )
    if unknown:
        print(f"  ⚠ 缺 severity 标签或多重：{' '.join(unknown)}（triage 修正）")
    if unmarked:
        print(
            f"  ⚠ 无 UAT-ID 标记 {len(unmarked)} 条（已计入本院看板；triage 按 uat_intake 口径补标记）："
            + " ".join(f"#{i['number']}" for i in unmarked)
        )
    if others:
        print(
            f"  ↷ 排除他院周次 {len(others)} 条（UAT-ID 不在 W{min(weeks)}–W{max(weeks)}；"
            "两院编号空间不冲突，OQ-153 ①）"
        )
    high_open = buckets["阻断"]["open"]
    medium_open = buckets["严重"]["open"]
    verdict = (
        "✅ 判据 2（阻断 / 严重全部关闭）已达成"
        if not high_open and not medium_open
        else f"⛔ 判据 2 未达成：阻断开 {len(high_open)}、严重开 {len(medium_open)}"
    )
    print(verdict)
    low_open = buckets["一般"]["open"]
    if low_open:
        print(
            f"⚠ 一般级未关闭 {len(low_open)} 条：须有处置结论（关闭，或医院书面接受的延期上线处理）"
        )
    return 0


# ---------- 自检（CI 与本地恒跑，不联网） ----------


def _write_tmp_csv(text: str) -> Path:
    f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
    f.write(text)
    f.close()
    return Path(f.name)


GOOD_ROW = (
    "UAT-W34-001,严重,通过,E23-T09,2027-07-08,UAT 环境（版本冻结基准 + 修复版本）,"
    "uat-fix-2027-07-07b,按原复现步骤重走，缺陷不可复现（示例复测记录）。,"
    "UAT 复测记录 W34-11 / 截图 R-201（存 UAT 共享目录，不入仓库）,"
)
HEADER_LINE = ",".join(EXPECTED_COLUMNS)


def _base_csv(row_line: str = GOOD_ROW) -> str:
    return f"{HEADER_LINE}\n{row_line}\n"


def selftest() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    # 1. 仓库模板加载并校验通过
    root = Path(__file__).resolve().parent.parent
    rows, errs = load_retests(root / TEMPLATE_RELPATH)
    check("模板 CSV 表头与列定义一致", not errs)
    check("模板 CSV 全部行校验通过", not validate_rows(rows))
    check("模板含 2 条示例复测结论", len(rows) == 2)
    check(
        "模板示例行均带「示例」标记",
        all("示例" in (r["reproduce_result"] or "") for r in rows),
    )
    check("模板含通过与遗留两种结论示范", {r["conclusion"] for r in rows} == {"通过", "遗留"})

    # 2. BOM（Excel 另存的 utf-8-sig）可读
    p = _write_tmp_csv("\ufeff" + _base_csv())
    _, errs = load_retests(p)
    check("utf-8-sig（Excel BOM）可读", not errs)
    p.unlink()

    # 3. 表头错误：缺列 / 多列
    p = _write_tmp_csv(HEADER_LINE.replace(",comment", "") + "\n" + GOOD_ROW + "\n")
    _, errs = load_retests(p)
    check("缺列被拦截", any("缺少列" in e for e in errs))
    p.unlink()
    p = _write_tmp_csv(HEADER_LINE + ",extra\n" + GOOD_ROW + ",x\n")
    _, errs = load_retests(p)
    check("多列被拦截", any("多余列" in e for e in errs))
    p.unlink()

    # 4. 行级校验：逐项变异全部拦截
    def mutant_row(**kw: str) -> str:
        parts = GOOD_ROW.split(",")
        cols = EXPECTED_COLUMNS
        for k, v in kw.items():
            parts[cols.index(k)] = v
        return ",".join(parts)

    mutants = {
        "uat_id 非法": mutant_row(uat_id="W34-1"),
        "uat_id 重复": GOOD_ROW + "\n" + GOOD_ROW.replace("不可复现", "复测又通过"),
        "severity 非法": mutant_row(severity="致命"),
        "conclusion 非法": mutant_row(conclusion="关闭"),
        "fix_task 非法": mutant_row(fix_task="E19-T10"),
        "retest_date 非法": mutant_row(retest_date="2027/07/08"),
        "retest_env 为空": mutant_row(retest_env=""),
        "通过缺 version": mutant_row(version=""),
        "通过缺不可复现描述": mutant_row(reproduce_result="重走了一遍（示例）。"),
        "不通过缺复现描述": mutant_row(conclusion="不通过", reproduce_result=""),
        "结论与描述矛盾": mutant_row(conclusion="不通过", reproduce_result="按原步骤不可复现（示例）。"),
        "阻断级遗留被拦": mutant_row(severity="阻断", conclusion="遗留", comment="医院书面接受，期限 W36。"),
        "严重级遗留被拦": mutant_row(severity="严重", conclusion="遗留", comment="医院书面接受，期限 W36。"),
        "遗留缺 comment": mutant_row(severity="一般", conclusion="遗留"),
        "evidence_ref 为空": mutant_row(evidence_ref=""),
        "疑似手机号": mutant_row(retest_env="UAT 环境 13800138000（示例）"),
        "疑似身份证号": mutant_row(comment="回执人 11010119900307867X（示例）"),
    }
    for name, bad_row in mutants.items():
        p = _write_tmp_csv(_base_csv(bad_row))
        rows, errs = load_retests(p)
        verrs = validate_rows(rows) if rows else errs
        check(f"变异拦截：{name}", bool(verrs))
        p.unlink()

    # 5. 空清单
    p = _write_tmp_csv(HEADER_LINE + "\n")
    rows, errs = load_retests(p)
    check("只有表头的空清单被拦截", bool(validate_rows(rows)) and not errs)
    p.unlink()

    # 6. 结论 → 关闭动作映射（手册 §4 口径）
    row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW.split(",")))
    check("通过 → close(completed)", decide(row) == ("close", "completed"))
    row2 = dict(row, conclusion="不通过")
    check("不通过 → reopen（保持打开）", decide(row2) == ("reopen", None))
    row3 = dict(row, severity="一般", conclusion="遗留")
    check("遗留 → close(not_planned)", decide(row3) == ("close", "not_planned"))

    # 7. 评论正文：幂等标记 / 署名 / 路由 / 敏感
    body = to_comment_body(row, 1)
    check(
        "评论含幂等标记 RETEST-ID（带轮次）",
        RETEST_MARKER_PATTERN.search(body).group(0).strip() == "RETEST-ID: UAT-W34-001 #1",
    )
    check(
        "评论署名 E19-T10",
        "E19-T10" in body and HOSPITALS["a"]["retest_task"].split("（")[0] in body,
    )
    check("评论标注原修复任务（打回路由）", "E23-T09" in body)
    check("评论标注 severity label", "severity:medium" in body)
    check("评论含修复版本", "uat-fix-2027-07-07b" in body)
    check("证据不入仓库的口径写入评论", "不入仓库" in body)
    check("评论模板本身无敏感串", not lint_sensitive_text(body))
    row3 = dict(row3, comment="医院书面接受，期限 W36。")
    body3 = to_comment_body(row3, 2)
    check("遗留评论含处置结论段与轮次 #2", "处置结论" in body3 and "RETEST-ID: UAT-W34-001 #2" in body3)

    # 8. 幂等标记正则不误匹配正文行
    check(
        "RETEST 标记正则只匹配独立行",
        RETEST_MARKER_PATTERN.findall("xx RETEST-ID: UAT-W34-001 #1\nRETEST-ID: UAT-W34-002")
        == [("UAT-W34-002", "")],
    )

    # 9. 与 uat_intake 共用同一套分级 / 标记口径（不另立体系，OQ-122 ②）
    check(
        "severity 映射与 uat_intake 完全一致",
        SEVERITIES == {"阻断": "severity:high", "严重": "severity:medium", "一般": "severity:low", "建议": "severity:info"},
    )
    check("UAT-ID 定位正则与 uat_intake 同一对象", UAT_ID_MARKER_PATTERN is intake.ID_MARKER_PATTERN)

    # 10. 院区路由（医院 C：E21-T30 · W44–W45，OQ-153 ①）
    GOOD_ROW_C = (
        "UAT-W44-001,严重,通过,E23-T24,2027-09-16,UAT 环境（W44 冻结基准 + 修复版本）,"
        "cuat-fix-2027-09-15a,按原复现步骤重走：登记 DDD 患者后按器械类型自动入组且起算=植入日，缺陷不可复现（示例）。,"
        "UAT 复测记录 W44-21 / 截图 RC-101（存 UAT 共享目录，不入仓库）,"
    )
    c_row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW_C.split(",")))
    a_row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW.split(",")))
    check("院区路由枚举 = {a, c}", sorted(HOSPITALS) == ["a", "c"])
    check(
        "周次与 uat_intake 同源（不另立编号空间）",
        all(HOSPITALS[h]["weeks"] == intake.HOSPITALS[h]["weeks"] for h in HOSPITALS),
    )
    c_body = to_comment_body(c_row, 1, "c")
    a_body = to_comment_body(a_row, 1)
    check(
        "C 评论含幂等标记 RETEST-ID（带轮次）",
        "RETEST-ID: UAT-W44-001 #1" in c_body,
    )
    check("C 评论署名 E21-T30", "E21-T30" in c_body and "复测结论（E21-T30）" in c_body)
    check("C 评论标注原修复任务（打回路由）", "E23-T24" in c_body)
    check("C 评论来源署名 W44–W45", "W44–W45" in c_body and "--hospital c" in c_body)
    check("C 评论不含 A 侧任务（不串院）", "E23-T09" not in c_body and "E19-T10" not in c_body)
    check("A 评论不含 C 侧任务（不串院）", "E23-T24" not in a_body and "E21-T30" not in a_body)
    check("周次校验：W44 在 C 窗口通过", not validate_rows([c_row], "c"))
    check("周次校验：W44 不在 A 窗口被拦", any("窗口" in e for e in validate_rows([c_row], "a")))
    check("周次校验：W34 在 A 窗口通过", not validate_rows([a_row], "a"))
    check("周次校验：W34 不在 C 窗口被拦", any("窗口" in e for e in validate_rows([a_row], "c")))
    check(
        "fix_task 越院被拦（C 行填 A 侧任务）",
        any("fix_task" in e for e in validate_rows([dict(c_row, fix_task="E23-T09")], "c")),
    )
    check(
        "fix_task 越院被拦（A 行填 C 侧任务）",
        any("fix_task" in e for e in validate_rows([dict(a_row, fix_task="E23-T24")], "a")),
    )

    # 11. C 复测清单模板（docs/testing/uat-retest-hospital-c-template.csv）
    crows, cerrs = load_retests(root / TEMPLATE_RELPATH_C)
    check("C 模板 CSV 表头与列定义一致", not cerrs)
    check("C 模板全部行按 --hospital c 校验通过", not validate_rows(crows, "c"))
    check("C 模板含 2 条示例复测结论", len(crows) == 2)
    check(
        "C 模板示例行均带「示例」标记",
        all("示例" in (r["reproduce_result"] or "") for r in crows),
    )
    check(
        "C 模板含通过与遗留两种结论示范",
        {r["conclusion"] for r in crows} == {"通过", "遗留"},
    )
    check(
        "C 模板周次均在 W44 / W45 且修复任务为 E23-T24 / E23-T25",
        all(
            r["uat_id"].split("-")[1] in ("W44", "W45")
            and r["fix_task"] in ("E23-T24", "E23-T25")
            for r in crows
        ),
    )

    # 12. 看板按周次过滤（split_by_hospital，OQ-153 ①）
    mk = lambda body, n: {"number": n, "state": "open", "body": body, "labels": []}  # noqa: E731
    pool = [
        mk("xx\nUAT-ID: UAT-W44-001", 1),
        mk("UAT-ID: UAT-W45-012", 2),
        mk("UAT-ID: UAT-W34-001", 3),
        mk("手工建单，无标记", 4),
    ]
    inc_c, exc_c, unm_c = split_by_hospital(pool, HOSPITALS["c"]["weeks"])
    check(
        "C 看板收入 W44 / W45、排除 A 周次",
        [i["number"] for i in inc_c] == [1, 2, 4] and [i["number"] for i in exc_c] == [3],
    )
    inc_a, exc_a, unm_a = split_by_hospital(pool, HOSPITALS["a"]["weeks"])
    check(
        "A 看板收入 W33–W35、排除 C 周次",
        [i["number"] for i in inc_a] == [3, 4] and [i["number"] for i in exc_a] == [1, 2],
    )
    check("无 UAT-ID 标记计入并单列（两院同）", [i["number"] for i in unm_c] == [4] == [i["number"] for i in unm_a])

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("✓ " if ok else "✗ ") + n)
    print(f"selftest：{len(checks) - len(failed)}/{len(checks)} 通过")
    if failed:
        print("失败用例：" + "；".join(failed), file=sys.stderr)
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="UAT 复测清单校验、结论回写 GitHub Issues 并按结论关闭（默认 dry-run，医院 A/C 路由见 --hospital）"
    )
    sub = parser.add_mutually_exclusive_group()
    parser.add_argument(
        "csv",
        nargs="?",
        help="复测清单 CSV（缺省按 --hospital 用仓库模板：a→"
        f"{TEMPLATE_RELPATH}，c→{TEMPLATE_RELPATH_C}）",
    )
    parser.add_argument("--repo", default=REPO_DEFAULT, help=f"缺陷库所在仓库（默认 {REPO_DEFAULT}）")
    parser.add_argument(
        "--hospital",
        choices=sorted(HOSPITALS),
        default=DEFAULT_HOSPITAL,
        help="院区路由：a=医院 A（打回 E23-T09/T10、复测 E19-T10、周次 W33–W35）；"
        "c=医院 C（打回 E23-T24/T25、复测 E21-T30、周次 W44–W45）",
    )
    parser.add_argument("--apply", action="store_true", help="真正写缺陷库（默认 dry-run，不联网）")
    parser.add_argument("--round", type=int, default=1, help="复测轮次（幂等标记 #n；打回后复测用 2 起，默认 1）")
    parser.add_argument("--status", action="store_true", help="输出闭环判据看板（需联网拉取 label UAT 的 Issue，按院区周次过滤）")
    parser.add_argument("--selftest", action="store_true", help="运行内嵌自检（不联网，CI 恒跑）")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if args.status:
        return run_status(args.repo, args.hospital)
    if args.round < 1:
        print("✗ --round 须 ≥ 1", file=sys.stderr)
        return 1
    template = HOSPITALS[args.hospital]["template"]
    csv_path = Path(args.csv) if args.csv else Path(__file__).resolve().parent.parent / template
    return run_retest(csv_path, args.repo, args.apply, args.round, args.hospital)


if __name__ == "__main__":
    sys.exit(main())
