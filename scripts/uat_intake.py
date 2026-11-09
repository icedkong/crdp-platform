#!/usr/bin/env python3
"""医院 A UAT 发现入缺陷库（E23-T08 · UAT 组织、脚本与问题跟踪，W33–W35）。

按 `AGENTS.md` 第 9 节，与医院的窗口确认、人员组织、现场执行与签字由产品 / 实施在场执行
（窗口 2027-06-28 ~ 2027-07-16，见 docs/testing/uat-hospital-a.md §9）；本脚本交付
「问题入缺陷库」的机制：把 UAT 现场记录整理出的发现清单（CSV，模板
`docs/testing/uat-findings-template.csv`）校验后逐条建为 GitHub Issue（缺陷库 =
icedkong/crdp-platform 的 Issues，沿用 OQ-112 ① 的暂行载体，口径见 OQ-122）。

安全护栏：

1. 默认 dry-run：只校验、只打印将创建的 Issue，不联网；`--apply` 才真正写缺陷库；
2. token 只经 `git credential fill`（host=github.com）取自凭据存储，仅进请求头，
   不回显、不落盘、不写入 Issue；
3. 幂等：Issue 正文含 `UAT-ID: <finding_id>` 标记，入库前拉取仓库既有 Issue
   （含关闭）比对标记，已存在的跳过，重复运行不重复建单；
4. 严禁真实数据入缺陷库（AGENTS §5.5）：校验发现文本中不得出现疑似真实手机号
   （1[3-9] 开头 11 位）与居民身份证号（18 位）；证据只引用 UAT 执行记录表行号 /
   截图编号（截图存 UAT 共享目录，不入仓库）；
5. 分级映射（OQ-122 ②，沿用 OQ-112 的四级梯子）：阻断 / 严重 / 一般 / 建议 →
   label `severity:high|medium|low|info`，另打 `bug`、`UAT`、`E23`；正文标注修复
   任务 E23-T09 / E23-T10（每日 triage 定责）与复测任务 E19-T10。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO_DEFAULT = "icedkong/crdp-platform"
API_BASE = "https://api.github.com"
TEMPLATE_RELPATH = Path("docs/testing/uat-findings-template.csv")

# 发现清单列（模板 docs/testing/uat-findings-template.csv 的唯一事实源）
EXPECTED_COLUMNS = [
    "finding_id",
    "title",
    "severity",
    "module",
    "page_or_api",
    "reporter_role",
    "description",
    "reproduce_steps",
    "expected_behavior",
    "actual_behavior",
    "evidence_ref",
    "fix_suggestion",
]
REQUIRED_TEXT_COLUMNS = [
    "title",
    "page_or_api",
    "reporter_role",
    "description",
    "reproduce_steps",
    "expected_behavior",
    "actual_behavior",
    "fix_suggestion",
]

# 分级：UAT 中文口径 → issue label（与 docs/testing/uat-hospital-a.md §6 一致；
# 梯子沿用 OQ-112，UAT 不另立 severity 体系）
SEVERITIES = {
    "阻断": "severity:high",
    "严重": "severity:medium",
    "一般": "severity:low",
    "建议": "severity:info",
}

# 模块（与 docs/testing/uat-hospital-a.md §5 用例分组一致，供 triage 归类）
MODULES = {
    "account",  # 登录与账号
    "patient",  # 患者档案与检索
    "baseline",  # 基线录入
    "plan",  # 计划与入组
    "task",  # 任务与状态流转
    "form",  # 随访表单录入
    "contact",  # 沟通记录与通知
    "report",  # 报告单与资料
    "stats",  # 统计与首页
    "import",  # 历史数据导入
    "scope",  # 权限与数据范围
    "mobile",  # 平板与手机
    "other",  # 其他
}
MODULE_LABELS_ZH = {
    "account": "登录与账号",
    "patient": "患者档案与检索",
    "baseline": "基线录入",
    "plan": "计划与入组",
    "task": "任务与状态流转",
    "form": "随访表单录入",
    "contact": "沟通记录与通知",
    "report": "报告单与资料",
    "stats": "统计与首页",
    "import": "历史数据导入",
    "scope": "权限与数据范围",
    "mobile": "平板与手机",
    "other": "其他",
}

# 修复 / 复测责任任务（docs/tasks/tasks.csv E23-T09 / E23-T10 / E19-T10）
FIX_TASKS = "E23-T09 / E23-T10（每日 triage 定责，手册 §6）"
RETEST_TASK = "E19-T10（A UAT 支持与缺陷复测）"

# finding_id 口径：UAT-W{周次 1~2 位}-{流水 3 位}，如 UAT-W34-001
ID_PATTERN = re.compile(r"^UAT-W\d{1,2}-\d{3}$")
# AGENTS §5.5：疑似真实联系方式 / 证件号不得进仓库（含缺陷库）
MOBILE_CN_PATTERN = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
IDCARD_CN_PATTERN = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
# Issue 正文幂等标记
ID_MARKER_PREFIX = "UAT-ID: "
ID_MARKER_PATTERN = re.compile(r"^UAT-ID:\s*(UAT-W\d{1,2}-\d{3})\s*$", re.M)

# 需要预置的 label（bug 为仓库已有；其余由 --ensure-labels 创建，severity 梯子与渗透共用）
LABEL_SPECS = [
    ("UAT", "c2e0c6", "UAT 验收测试发现（医院用户执行 UAT 脚本提出）"),
    ("E23", "1d76db", "E23 租户上线"),
    ("severity:high", "b60200", "高危（阻断主流程 / 主功能不可用）"),
    ("severity:medium", "d93f0b", "中危（主要功能错误但有绕行）"),
    ("severity:low", "fbca04", "低危（次要功能 / 体验问题）"),
    ("severity:info", "c5def5", "信息（优化建议，不阻塞验收）"),
]
BASE_LABELS = ["bug", "UAT", "E23"]


def load_findings(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    """读取发现清单 CSV（兼容 Excel 的 utf-8-sig），先校验表头。"""
    errors: list[str] = []
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        return [], [f"找不到发现清单：{path}"]
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


def lint_sensitive_text(text: str) -> list[str]:
    """AGENTS §5.5：清单文本不得含疑似真实手机号 / 身份证号（示例数据例外）。"""
    found: list[str] = []
    for m in MOBILE_CN_PATTERN.finditer(text):
        found.append(f"疑似真实手机号 {m.group(0)[:3]}****{m.group(0)[-4:]}")
    for m in IDCARD_CN_PATTERN.finditer(text):
        found.append(f"疑似身份证号 {m.group(0)[:6]}********")
    return found


def validate_rows(rows: list[dict[str, str]]) -> list[str]:
    errors: list[str] = []
    seen_ids: set[str] = set()
    for i, row in enumerate(rows, start=2):  # 第 1 行是表头
        rid = (row.get("finding_id") or "").strip()
        where = f"第 {i} 行（{rid or '缺 finding_id'}）"
        if not rid:
            errors.append(f"{where}：finding_id 为空")
        elif not ID_PATTERN.match(rid):
            errors.append(f"{where}：finding_id 须形如 UAT-W34-001")
        elif rid in seen_ids:
            errors.append(f"{where}：finding_id 重复")
        seen_ids.add(rid)
        sev = (row.get("severity") or "").strip()
        if sev not in SEVERITIES:
            errors.append(f"{where}：severity 须为 阻断/严重/一般/建议，实际「{sev}」")
        module = (row.get("module") or "").strip()
        if module not in MODULES:
            errors.append(
                f"{where}：module 须为 {'/'.join(sorted(MODULES))}，实际「{module}」"
            )
        for col in REQUIRED_TEXT_COLUMNS:
            if not (row.get(col) or "").strip():
                errors.append(f"{where}：{col} 为空")
        joined = " ".join(
            (row.get(c) or "")
            for c in (
                "title",
                "page_or_api",
                "reporter_role",
                "description",
                "reproduce_steps",
                "expected_behavior",
                "actual_behavior",
            )
        )
        for hit in lint_sensitive_text(joined):
            errors.append(f"{where}：{hit}（AGENTS §5.5 只允许虚构示例数据）")
        if not (row.get("evidence_ref") or "").strip():
            errors.append(
                f"{where}：evidence_ref 为空（证据引用 UAT 执行记录表行 / 截图编号，文件不入仓库）"
            )
    if not rows:
        errors.append("发现清单为空（只有表头）")
    return errors


def to_labels(row: dict[str, str]) -> list[str]:
    return [*BASE_LABELS, SEVERITIES[(row.get("severity") or "").strip()]]


def to_issue_title(row: dict[str, str]) -> str:
    sev = (row.get("severity") or "").strip()
    return f"[UAT{sev}] {row.get('title', '').strip()}（{row.get('finding_id', '').strip()}）"


def to_issue_body(row: dict[str, str]) -> str:
    module = (row.get("module") or "").strip()
    module_zh = MODULE_LABELS_ZH.get(module, module)
    lines = [
        "## 概要",
        "",
        (row.get("description") or "").strip(),
        "",
        f"- **级别**：{(row.get('severity') or '').strip()}",
        f"- **模块**：{module_zh}（{module}）",
        f"- **位置**：{(row.get('page_or_api') or '').strip()}",
        f"- **提出人角色**：{(row.get('reporter_role') or '').strip()}",
        f"- **修复任务**：{FIX_TASKS}",
        f"- **复测任务**：{RETEST_TASK}",
        "",
        "## 复现步骤",
        "",
        (row.get("reproduce_steps") or "").strip(),
        "",
        "## 预期（按需求 / UAT 脚本）",
        "",
        (row.get("expected_behavior") or "").strip(),
        "",
        "## 实际",
        "",
        (row.get("actual_behavior") or "").strip(),
        "",
        "## 修复建议",
        "",
        (row.get("fix_suggestion") or "").strip(),
        "",
        "## 证据",
        "",
        f"{(row.get('evidence_ref') or '').strip()}（截图存 UAT 共享目录，不入仓库）",
        "",
        "---",
        "",
        "来源：E23-T08 医院 A UAT（W33–W35）发现清单；由 `scripts/uat_intake.py` 入库。",
        f"{ID_MARKER_PREFIX}{(row.get('finding_id') or '').strip()}",
        "",
    ]
    return "\n".join(lines)


# ---------- GitHub API（仅 --apply 路径触达） ----------


def gh_token() -> str:
    """token 只从 git 凭据存储取，仅用于请求头，绝不打印。"""
    proc = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n",
        capture_output=True,
        text=True,
        check=True,
    )
    for line in proc.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    raise SystemExit("git credential fill 未返回 password（先配置 GitHub 凭据）")


def api(
    token: str,
    method: str,
    path: str,
    payload: dict | None = None,
) -> dict:
    url = f"{API_BASE}{path}"
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req) as resp:
            body = resp.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        raise SystemExit(f"GitHub API {method} {path} 失败：HTTP {e.code} {detail[:300]}") from e


def ensure_labels(repo: str, token: str) -> list[str]:
    """缺的 label 建掉（已存在跳过）；返回本次新建的 label。"""
    created: list[str] = []
    for name, color, desc in LABEL_SPECS:
        try:
            api(token, "GET", f"/repos/{repo}/labels/{urllib.parse.quote(name)}")
        except SystemExit:
            api(
                token,
                "POST",
                f"/repos/{repo}/labels",
                {"name": name, "color": color, "description": desc},
            )
            created.append(name)
    return created


def existing_marker_ids(repo: str, token: str, max_pages: int = 20) -> set[str]:
    """拉取仓库全部 Issue（含关闭）正文中的 UAT-ID 标记，做入库幂等。"""
    ids: set[str] = set()
    for page in range(1, max_pages + 1):
        result = api(
            token,
            "GET",
            f"/repos/{repo}/issues?state=all&per_page=100&page={page}",
        )
        if not result:
            break
        for issue in result:
            if "pull_request" in issue:  # PR 不算缺陷
                continue
            for m in ID_MARKER_PATTERN.finditer(issue.get("body") or ""):
                ids.add(m.group(1))
        if len(result) < 100:
            break
    return ids


def create_issue(repo: str, token: str, row: dict[str, str]) -> str:
    result = api(
        token,
        "POST",
        f"/repos/{repo}/issues",
        {"title": to_issue_title(row), "body": to_issue_body(row), "labels": to_labels(row)},
    )
    return str(result.get("html_url", result.get("number", "?")))


# ---------- 入库主流程 ----------


def run_intake(csv_path: Path, repo: str, apply: bool, ensure: bool) -> int:
    rows, errors = load_findings(csv_path)
    if errors:
        for e in errors:
            print(f"✗ {e}", file=sys.stderr)
        return 1
    errors = validate_rows(rows)
    if errors:
        for e in errors:
            print(f"✗ {e}", file=sys.stderr)
        return 1
    print(f"校验通过：{len(rows)} 条发现（{csv_path}）")

    token = ""
    existing: set[str] = set()
    if apply:
        token = gh_token()
        if ensure:
            created = ensure_labels(repo, token)
            print(f"label 预置完成：新建 {created if created else '无（全部已存在）'}")
        existing = existing_marker_ids(repo, token)
    elif ensure:
        print("dry-run：将创建 / 核对 label：" + "、".join(n for n, _, _ in LABEL_SPECS))

    created_n = skipped_n = 0
    for row in rows:
        rid = row["finding_id"].strip()
        title = to_issue_title(row)
        labels = ", ".join(to_labels(row))
        if rid in existing:
            print(f"↷ 跳过 {rid}（缺陷库已有同标记 Issue）")
            skipped_n += 1
            continue
        if not apply:
            print(f"◇ 将创建 {rid}：{title}")
            print(f"  labels: {labels}")
            print(f"  正文含幂等标记 {ID_MARKER_PREFIX}{rid}")
            created_n += 1
            continue
        url = create_issue(repo, token, row)
        print(f"✓ 已创建 {rid} → {url}")
        created_n += 1
    mode = "apply" if apply else "dry-run（未联网；重跑以 --apply 落库）"
    print(f"完成（{mode}）：创建 {created_n}，跳过 {skipped_n}")
    return 0


# ---------- 自检（CI 与本地恒跑，不联网） ----------


def _write_tmp_csv(text: str) -> Path:
    f = tempfile.NamedTemporaryFile("w", suffix=".csv", delete=False, encoding="utf-8")
    f.write(text)
    f.close()
    return Path(f.name)


GOOD_ROW = (
    "UAT-W34-003,首页随访率卡片点击无响应（示例）,阻断,stats,/dashboard,科主任,"
    "首页随访率卡片点击后无任何反应（示例发现，非真实缺陷）。,"
    "1. 以科主任 UAT 账号（示例）登录医院端；2. 点击首页随访率卡片。,"
    "点击跳转对应统计列表。,无响应。,UAT 执行记录表 W34-07 / 截图 S-121,卡片点击接路由跳转。"
)
HEADER_LINE = ",".join(EXPECTED_COLUMNS)


def _base_csv(row_line: str = GOOD_ROW) -> str:
    return f"{HEADER_LINE}\n{row_line}\n"


def selftest() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    # 1. 仓库模板加载并校验通过（含 utf-8-sig / 多行单元格）
    root = Path(__file__).resolve().parent.parent
    rows, errs = load_findings(root / TEMPLATE_RELPATH)
    check("模板 CSV 表头与列定义一致", not errs)
    check("模板 CSV 全部行校验通过", not validate_rows(rows))
    check("模板含 2 条示例发现", len(rows) == 2)
    check("模板示例行标题均带「示例」标记", all("示例" in r["title"] for r in rows))

    # 2. BOM（Excel 另存的 utf-8-sig）可读
    p = _write_tmp_csv("\ufeff" + _base_csv())
    _, errs = load_findings(p)
    check("utf-8-sig（Excel BOM）可读", not errs)
    p.unlink()

    # 3. 表头错误：缺列 / 多列
    p = _write_tmp_csv(
        HEADER_LINE.replace(",fix_suggestion", "") + "\n" + GOOD_ROW.replace("卡片点击接路由跳转。", "") + "\n"
    )
    _, errs = load_findings(p)
    check("缺列被拦截", any("缺少列" in e for e in errs))
    p.unlink()
    p = _write_tmp_csv(HEADER_LINE + ",extra\n" + GOOD_ROW + ",x\n")
    _, errs = load_findings(p)
    check("多列被拦截", any("多余列" in e for e in errs))
    p.unlink()

    # 4. 行级校验：逐项变异全部拦截
    mutants = {
        "finding_id 非法": GOOD_ROW.replace("UAT-W34-003", "W34-3"),
        "finding_id 重复": GOOD_ROW + "\n" + GOOD_ROW.replace("无响应。", "另一种实际。"),
        "severity 非法": GOOD_ROW.replace(",阻断,", ",致命,"),
        "module 非法": GOOD_ROW.replace(",stats,", ",dashboard,"),
        "fix_suggestion 为空": GOOD_ROW.replace("卡片点击接路由跳转。", ""),
        "疑似手机号": GOOD_ROW.replace("科主任", "科主任 13800138000"),
        "疑似身份证号": GOOD_ROW.replace("以科主任 UAT 账号（示例）登录", "以 11010119900307867X（示例）登录"),
        "evidence_ref 为空": GOOD_ROW.replace("UAT 执行记录表 W34-07 / 截图 S-121", ""),
        "reporter_role 为空": GOOD_ROW.replace(",科主任,", ",,"),
    }
    for name, bad_row in mutants.items():
        p = _write_tmp_csv(_base_csv(bad_row))
        rows, errs = load_findings(p)
        verrs = validate_rows(rows) if rows else errs
        check(f"变异拦截：{name}", bool(verrs))
        p.unlink()

    # 5. 空清单
    p = _write_tmp_csv(HEADER_LINE + "\n")
    rows, errs = load_findings(p)
    check("只有表头的空清单被拦截", bool(validate_rows(rows)) and not errs)
    p.unlink()

    # 6. 分级 → label 映射全四档
    for sev, label in SEVERITIES.items():
        row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW.split(",")))
        row["severity"] = sev
        check(f"分级映射 {sev} → {label}", to_labels(row)[-1] == label and to_labels(row)[:3] == BASE_LABELS)

    # 7. Issue 标题 / 正文 / 幂等标记 / 修复与复测任务
    row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW.split(",")))
    body = to_issue_body(row)
    check(
        "标题带级别与编号",
        to_issue_title(row) == "[UAT阻断] 首页随访率卡片点击无响应（示例）（UAT-W34-003）",
    )
    check("正文含幂等标记 UAT-ID", ID_MARKER_PATTERN.search(body).group(1) == "UAT-W34-003")
    check("正文标注修复任务 E23-T09 / E23-T10", "E23-T09" in body and "E23-T10" in body)
    check("正文标注复测任务 E19-T10", "E19-T10" in body)
    check("正文带模块中文名", "统计与首页" in body)
    check("证据不入仓库的口径写入正文", "不入仓库" in body)
    check("敏感 lint 与正文互不干扰（正文模板本身无敏感串）", not lint_sensitive_text(body))

    # 8. 幂等标记正则不误匹配正文行
    check(
        "标记正则只匹配独立行",
        ID_MARKER_PATTERN.findall("xx UAT-ID: UAT-W34-003\nUAT-ID: UAT-W34-004") == ["UAT-W34-004"],
    )

    # 9. 模块清单与手册 §5 分组一致（13 组）
    check("模块枚举与手册 §5 分组数一致（13）", len(MODULES) == 13 and len(MODULE_LABELS_ZH) == 13)

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
        description="UAT 发现清单校验并写入 GitHub Issues 缺陷库（默认 dry-run）"
    )
    parser.add_argument("csv", nargs="?", help=f"发现清单 CSV（缺省用仓库模板 {TEMPLATE_RELPATH}）")
    parser.add_argument("--repo", default=REPO_DEFAULT, help=f"缺陷库所在仓库（默认 {REPO_DEFAULT}）")
    parser.add_argument("--apply", action="store_true", help="真正写缺陷库（默认 dry-run，不联网）")
    parser.add_argument("--ensure-labels", action="store_true", help="顺带核对 / 创建 UAT 等 label（需 --apply）")
    parser.add_argument("--selftest", action="store_true", help="运行内嵌自检（不联网，CI 恒跑）")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    csv_path = Path(args.csv) if args.csv else Path(__file__).resolve().parent.parent / TEMPLATE_RELPATH
    return run_intake(csv_path, args.repo, args.apply, args.ensure_labels)


if __name__ == "__main__":
    sys.exit(main())
