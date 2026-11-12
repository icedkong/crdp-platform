#!/usr/bin/env python3
"""测评问题清单入缺陷库（E22-T14 · 配合测评机构现场测评，W39–W43）。

按 `AGENTS.md` 第 9 节，测评机构入场、访谈核查、报告与问题清单出具由人执行
（窗口 2027-08-09 ~ 2027-09-10，OQ-30 压缩 5 周）；本脚本交付「测评问题清单输出」的
机制（配合手册 `docs/ops/mlps/onsite-assessment.md` §7）：把测评机构现场发现的问题
清单（CSV，模板 `docs/ops/mlps/onsite-findings-template.csv`）校验后逐条建为
GitHub Issue（缺陷库 = icedkong/crdp-platform 的 Issues，OQ-112 ① / OQ-137 暂行），
作为 E22-T15 / T16 整改（W44）与 E22-T17 复测（W45）的输入。

安全护栏：

1. 默认 dry-run：只校验、只打印将创建的 Issue，不联网；`--apply` 才真正写缺陷库；
2. token 只经 `git credential fill`（host=github.com）取自凭据存储，仅进请求头，
   不回显、不落盘、不写入 Issue；
3. 幂等：Issue 正文含 `MLPS-ID: <finding_id>` 标记，入库前拉取仓库既有 Issue
   （含关闭）比对标记，已存在的跳过，重复运行不重复建单；
4. finding_id 窗口约束：`MLPS-W{39~43}-{流水 3 位}`——只接受现场测评周次（W39–W43），
   复测期（W45）新发现属 E22-T17 复测口径，不混入本清单；
5. 严禁真实数据入缺陷库（AGENTS §5.5）：校验文本中不得出现疑似真实手机号
   （1[3-9] 开头 11 位）与居民身份证号（18 位），证据只引用测评记录表 / 报告章节编号，
   报告原件与截图等证据文件不入仓库；
6. 风险映射：高 / 中 / 低 → label `severity:high|medium|low`（机构若用四级再加
   「提示」，改 RISK_LEVELS 一处，OQ-137 ②），另打 `bug`、`mlps`、`E22`；
   高 / 中风险按 asset_type 在正文标注整改任务（backend → E22-T15，frontend → E22-T16）。

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
TEMPLATE_RELPATH = Path("docs/ops/mlps/onsite-findings-template.csv")

# 测评问题清单列（模板 docs/ops/mlps/onsite-findings-template.csv 的唯一事实源）
EXPECTED_COLUMNS = [
    "finding_id",
    "title",
    "category",
    "control_point",
    "conclusion",
    "risk_level",
    "asset_type",
    "description",
    "evidence_ref",
    "impact",
    "assessor_suggestion",
    "fix_suggestion",
]
REQUIRED_TEXT_COLUMNS = [
    "title",
    "control_point",
    "description",
    "evidence_ref",
    "impact",
    "assessor_suggestion",
    "fix_suggestion",
]

# 等保 2.0（GB/T 22239-2019）三级安全要求分类（技术 5 + 管理 5，
# 与 docs/ops/mlps/self-assessment.md 的十分类一致）
CATEGORIES = (
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

# 测评结论口径：现场测评发现的问题项只有这两种（符合项不进问题清单）
CONCLUSIONS = {"不符合", "部分符合"}

# 风险等级：机构中文口径 → issue label（与 docs/ops/mlps/onsite-assessment.md §7 一致）
RISK_LEVELS = {
    "高": "severity:high",
    "中": "severity:medium",
    "低": "severity:low",
}

# 资产类型 → 高/中风险的整改责任任务（docs/tasks/tasks.csv E22-T15 / E22-T16）
ASSET_TYPES = {"backend", "frontend", "infra", "management", "other"}
ASSET_FIX_TASK = {
    "backend": "E22-T15（测评整改 · 后端 / 全栈 1）",
    "frontend": "E22-T16（测评整改 · 全栈 2）",
    "infra": "运维会诊（云上整改由持账号运维执行，见 docs/ops/cce-prod.md / docs/ops/rds-prod.md）",
    "management": "管理会诊（制度签署 / 记录补齐口径 M-14 / M-16，产品牵头）",
    "other": "三方会诊定责（产品 + 开发 + 运维）",
}

# finding_id 口径：MLPS-W{现场测评周次 39~43}-{流水 3 位}，如 MLPS-W41-003
ID_PATTERN = re.compile(r"^MLPS-W(39|4[0-3])-\d{3}$")
# AGENTS §5.5：疑似真实联系方式 / 证件号不得进仓库（含缺陷库）
MOBILE_CN_PATTERN = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
IDCARD_CN_PATTERN = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
# Issue 正文幂等标记
ID_MARKER_PREFIX = "MLPS-ID: "
ID_MARKER_PATTERN = re.compile(r"^MLPS-ID:\s*(MLPS-W(?:39|4[0-3])-\d{3})\s*$", re.M)

# 需要预置的 label（bug 为仓库已有；severity:* 已随渗透入库创建；mlps 由 --ensure-labels 补建）
LABEL_SPECS = [
    ("mlps", "5319e7", "等保测评问题（现场测评发现）"),
    ("E22", "1d76db", "E22 运维与合规"),
    ("severity:high", "b60200", "高危（测评 / 渗透分级）"),
    ("severity:medium", "d93f0b", "中危（测评 / 渗透分级）"),
    ("severity:low", "fbca04", "低危（测评 / 渗透分级）"),
]
BASE_LABELS = ["bug", "mlps", "E22"]


def load_findings(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    """读取测评问题清单 CSV（兼容 Excel 的 utf-8-sig），先校验表头。"""
    errors: list[str] = []
    try:
        raw = path.read_text(encoding="utf-8-sig")
    except FileNotFoundError:
        return [], [f"找不到测评问题清单：{path}"]
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
            errors.append(f"{where}：finding_id 须形如 MLPS-W41-003（周次限现场测评窗口 W39–W43）")
        elif rid in seen_ids:
            errors.append(f"{where}：finding_id 重复")
        seen_ids.add(rid)
        cat = (row.get("category") or "").strip()
        if cat not in CATEGORIES:
            errors.append(f"{where}：category 须为等保十分类之一（如 安全计算环境），实际「{cat}」")
        concl = (row.get("conclusion") or "").strip()
        if concl not in CONCLUSIONS:
            errors.append(f"{where}：conclusion 须为 不符合/部分符合，实际「{concl}」")
        risk = (row.get("risk_level") or "").strip()
        if risk not in RISK_LEVELS:
            errors.append(f"{where}：risk_level 须为 高/中/低，实际「{risk}」")
        atype = (row.get("asset_type") or "").strip()
        if atype not in ASSET_TYPES:
            errors.append(
                f"{where}：asset_type 须为 backend/frontend/infra/management/other，实际「{atype}」"
            )
        for col in REQUIRED_TEXT_COLUMNS:
            if not (row.get(col) or "").strip():
                errors.append(f"{where}：{col} 为空")
        joined = " ".join(
            (row.get(c) or "")
            for c in ("title", "control_point", "description", "impact", "assessor_suggestion")
        )
        for hit in lint_sensitive_text(joined):
            errors.append(f"{where}：{hit}（AGENTS §5.5 只允许虚构示例数据）")
        if not (row.get("evidence_ref") or "").strip():
            errors.append(f"{where}：evidence_ref 为空（证据引用测评记录表 / 报告章节编号，文件不入仓库）")
    if not rows:
        errors.append("测评问题清单为空（只有表头）")
    return errors


def to_labels(row: dict[str, str]) -> list[str]:
    return [*BASE_LABELS, RISK_LEVELS[(row.get("risk_level") or "").strip()]]


def to_issue_title(row: dict[str, str]) -> str:
    risk = (row.get("risk_level") or "").strip()
    return f"[等保{risk}风险] {row.get('title', '').strip()}（{row.get('finding_id', '').strip()}）"


def to_issue_body(row: dict[str, str]) -> str:
    atype = (row.get("asset_type") or "").strip()
    fix_task = ASSET_FIX_TASK.get(atype, ASSET_FIX_TASK["other"])
    lines = [
        "## 概要",
        "",
        (row.get("description") or "").strip(),
        "",
        f"- **安全要求分类**：{(row.get('category') or '').strip()}",
        f"- **测评指标 / 控制点**：{(row.get('control_point') or '').strip()}",
        f"- **测评结论**：{(row.get('conclusion') or '').strip()}",
        f"- **风险等级**：{(row.get('risk_level') or '').strip()}",
        f"- **资产类型**：{atype} → 整改责任：{fix_task}",
        "",
        "## 风险与影响",
        "",
        (row.get("impact") or "").strip(),
        "",
        "## 机构整改建议",
        "",
        (row.get("assessor_suggestion") or "").strip(),
        "",
        "## 我方整改方案",
        "",
        (row.get("fix_suggestion") or "").strip(),
        "",
        "## 证据",
        "",
        f"{(row.get('evidence_ref') or '').strip()}（报告原件与证据文件存项目文档库，不入仓库）",
        "",
        "---",
        "",
        "来源：E22-T14 现场测评（W39–W43）测评问题清单；由 `scripts/mlps_onsite_intake.py` 入库。",
        "整改：E22-T15 / T16（W44）→ 复测 E22-T17（W45）结论回写本 Issue。",
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
    """拉取仓库全部 Issue（含关闭）正文中的 MLPS-ID 标记，做入库幂等。"""
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
    print(f"校验通过：{len(rows)} 条测评问题（{csv_path}）")

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
    "MLPS-W41-003,口令策略与制度文件表述不一致（示例）,安全计算环境,身份鉴别,部分符合,中,backend,"
    "系统口令策略为 8 位 / 3 类，制度汇编写 10 位（示例发现，非真实问题）。,"
    "测评记录表 A-41-03 / 报告 §5.2,口径不一致可能在复评时被抽查。,"
    "统一制度表述与系统实际配置。,修订制度汇编表述并重新签署发布（M-14）。"
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
    check("模板含 2 条示例问题", len(rows) == 2)
    check("模板示例行标题均带「示例」标记", all("示例" in r["title"] for r in rows))

    # 2. BOM（Excel 另存的 utf-8-sig）可读
    p = _write_tmp_csv("\ufeff" + _base_csv())
    _, errs = load_findings(p)
    check("utf-8-sig（Excel BOM）可读", not errs)
    p.unlink()

    # 3. 表头错误：缺列 / 多列
    p = _write_tmp_csv(
        HEADER_LINE.replace(",fix_suggestion", "") + "\n"
        + GOOD_ROW.replace("修订制度汇编表述并重新签署发布（M-14）。", "")
        + "\n"
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
        "finding_id 窗口外（W35 复测周）": GOOD_ROW.replace("MLPS-W41-003", "MLPS-W35-003"),
        "finding_id 格式非法": GOOD_ROW.replace("MLPS-W41-003", "W41-3"),
        "finding_id 重复": GOOD_ROW + "\n" + GOOD_ROW.replace("口径不一致可能", "另一影响"),
        "category 非十分类": GOOD_ROW.replace(",安全计算环境,", ",数据安全,"),
        "conclusion 非法（符合项不进清单）": GOOD_ROW.replace(",部分符合,", ",符合,"),
        "risk_level 非法": GOOD_ROW.replace(",中,backend,", ",严重,backend,"),
        "asset_type 非法": GOOD_ROW.replace(",backend,", ",db,"),
        "control_point 为空": GOOD_ROW.replace("身份鉴别", ""),
        "assessor_suggestion 为空": GOOD_ROW.replace("统一制度表述与系统实际配置。", ""),
        "疑似手机号": GOOD_ROW.replace("制度汇编写", "制度汇编（联系 13800138000）写"),
        "疑似身份证号": GOOD_ROW.replace("系统口令策略为", "系统口令策略（经办 11010119900307867X）为"),
        "evidence_ref 为空": GOOD_ROW.replace("测评记录表 A-41-03 / 报告 §5.2", ""),
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

    # 6. 风险 → label 映射全三档（+ 基础 label）
    for risk, label in RISK_LEVELS.items():
        row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW.split(",")))
        row["risk_level"] = risk
        check(f"风险映射 {risk} → {label}", to_labels(row)[-1] == label and to_labels(row)[:3] == BASE_LABELS)

    # 7. Issue 标题 / 正文 / 幂等标记 / 整改任务路由
    row = dict(zip(EXPECTED_COLUMNS, GOOD_ROW.split(",")))
    body = to_issue_body(row)
    check(
        "标题带风险与编号",
        to_issue_title(row) == "[等保中风险] 口令策略与制度文件表述不一致（示例）（MLPS-W41-003）",
    )
    check("正文含幂等标记 MLPS-ID", ID_MARKER_PATTERN.search(body).group(1) == "MLPS-W41-003")
    check("正文含分类 / 控制点 / 结论", "安全计算环境" in body and "身份鉴别" in body and "部分符合" in body)
    check("中风险 backend 路由 E22-T15", "E22-T15" in body)
    row_fe = dict(row, asset_type="frontend")
    check("frontend 路由 E22-T16", "E22-T16" in to_issue_body(row_fe))
    row_mgmt = dict(row, asset_type="management")
    check("management 路由管理会诊（M-14 / M-16）", "管理会诊" in to_issue_body(row_mgmt) and "M-14" in to_issue_body(row_mgmt))
    check("证据不入仓库的口径写入正文", "不入仓库" in body)
    check("正文含整改与复测衔接（E22-T17）", "E22-T17" in body)
    check("敏感 lint 与正文互不干扰（正文模板本身无敏感串）", not lint_sensitive_text(body))

    # 8. 幂等标记正则不误匹配正文行；窗口约束正反例
    check(
        "标记正则只匹配独立行",
        ID_MARKER_PATTERN.findall("xx MLPS-ID: MLPS-W41-003\nMLPS-ID: MLPS-W42-004")
        == ["MLPS-W42-004"],
    )
    check("窗口约束：W39 / W43 合法", bool(ID_PATTERN.match("MLPS-W39-001")) and bool(ID_PATTERN.match("MLPS-W43-999")))
    check("窗口约束：W38 / W44 / W45 非法", not ID_PATTERN.match("MLPS-W38-001") and not ID_PATTERN.match("MLPS-W44-001") and not ID_PATTERN.match("MLPS-W45-001"))

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
        description="测评问题清单校验并写入 GitHub Issues 缺陷库（默认 dry-run）"
    )
    parser.add_argument("csv", nargs="?", help=f"测评问题清单 CSV（缺省用仓库模板 {TEMPLATE_RELPATH}）")
    parser.add_argument("--repo", default=REPO_DEFAULT, help=f"缺陷库所在仓库（默认 {REPO_DEFAULT}）")
    parser.add_argument("--apply", action="store_true", help="真正写缺陷库（默认 dry-run，不联网）")
    parser.add_argument("--ensure-labels", action="store_true", help="顺带核对 / 创建 severity 等 label（需 --apply）")
    parser.add_argument("--selftest", action="store_true", help="运行内嵌自检（不联网，CI 恒跑）")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    csv_path = Path(args.csv) if args.csv else Path(__file__).resolve().parent.parent / TEMPLATE_RELPATH
    return run_intake(csv_path, args.repo, args.apply, args.ensure_labels)


if __name__ == "__main__":
    sys.exit(main())
