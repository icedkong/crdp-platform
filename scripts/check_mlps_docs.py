#!/usr/bin/env python3
"""等保测评资料（E22-T13）资料清单与四类资料草稿防漂移校验（CI 恒跑，不联网）。

校验对象（验收「资料清单齐全」的机器可验化身）：

1. `docs/tasks/tasks.csv` E22-T13 行：类型 = 产品、依赖 E22-T02、关联需求 A-NFR-007、
   验收原句「资料清单齐全」、排期 W39（2027-08-09）；
2. `docs/ops/mlps/README.md` 资料清单：四类资料（制度 / 拓扑 / 安全设计 / 自查记录）
   与前置材料（E22-T01 定级备案、E22-T02 测评合同）条目齐全（M-xx 连续无跳号）、
   状态（仓库就绪 / 由人补齐）计数与声明一致、指向的四份资料文件存在；
3. `security-design.md` 安全设计：GB/T 22239-2019 三级要求分类框架（技术五类 + 管理五类）、
   控制点对照（身份鉴别 / 访问控制 / 安全审计 / 入侵防范 / 恶意代码防范 / 数据保密性 /
   数据完整性 / 数据备份恢复 / 剩余信息保护 / 个人信息保护）、云侧责任共担、
   与拓扑 / 自查记录 / 制度互引；
4. `network-topology.md` 网络拓扑：入口链路与 cce-prod.md §1 同源（WAF / ELB /
   NodePort 30080）、跨可用区（cn-north-4a/b）、跨区域容灾（上海一）、端口矩阵、
   三套环境与生产数据不出生产环境、事实源（cce / rds / worm 三份基线 JSON 与校验脚本）、
   真实 ID 用占位符由人替换声明；
5. `management-policies.md` 制度：P-01~P-12 连续（总纲 / 人员 / 账号权限 / 密码密钥 /
   数据 / 运维 / 备份恢复 / 应急 / 漏洞 / 开发 / 云与供应链 / 审计日志）、
   关键管控锚点（堡垒机、禁直连、密钥轮换、导出审批、应急、半年演练、高危即报即修、
   Flyway、保密协议、离职回收）、由人签署清单；
6. `self-assessment.md` 自查记录：等保 2.0 十个安全要求分类齐全、自查基准日、
   「部分符合」行均带整改口径、已知偏差登记（恶意代码防范 tech-plan §10、OQ-30 /
   OQ-39 / OQ-42 / OQ-123 / OQ-127）、证据指向的仓库路径全部真实存在；
7. 索引与接线：`docs/README.md` 收录 mlps/、ci.yml 含本校验、open-questions.md 有
   OQ-133（资料载体与脱敏暂行口径）。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MLPS = ROOT / "docs/ops/mlps"
CHECKLIST = MLPS / "README.md"
DESIGN = MLPS / "security-design.md"
TOPOLOGY = MLPS / "network-topology.md"
POLICIES = MLPS / "management-policies.md"
ASSESSMENT = MLPS / "self-assessment.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"

# 清单表行：| M-01 | 资料 | 承载 | 责任 | 状态 |
CHECKLIST_ROW = re.compile(r"^\|\s*(M-\d{2})\s*\|[^|]+\|[^|]+\|[^|]+\|\s*(仓库就绪|由人补齐)\s*\|\s*$", re.M)
# 条数声明：清单共 N 条：仓库就绪 A 条、由人补齐 B 条
COUNT_CLAIM = re.compile(r"清单共\s*(\d+)\s*条：仓库就绪\s*(\d+)\s*条、由人补齐\s*(\d+)\s*条")
# 制度条目行：| P-01 | 名称 | … |
POLICY_ROW = re.compile(r"^\|\s*(P-\d{2})\s*\|", re.M)
# 自查表中的仓库路径引用（docs/ 下 .md、iac/ 下 .json）——必须真实存在
DOC_PATH = re.compile(r"(?:docs/(?:ops|requirements)/[A-Za-z0-9_\-./]+\.(?:md|json)|iac/[A-Za-z0-9_\-./]+\.json)")

# 等保 2.0（GB/T 22239-2019）三级安全要求分类（技术 5 + 管理 5）
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

# 自查记录证据指向必须存在的仓库文件（防引用失效）
REQUIRED_EVIDENCE = (
    "docs/ops/cce-prod.md",
    "docs/ops/rds-prod.md",
    "docs/ops/audit-worm-archive.md",
    "docs/ops/tenant-restore.md",
    "docs/ops/cross-region-drill.md",
    "docs/ops/pentest.md",
    "docs/ops/pentest-remediation.md",
    "docs/ops/pentest-remediation-frontend.md",
    "docs/requirements/tech-plan.md",
    "docs/requirements/platform-spec.md",
    "iac/cce-prod/cce-baseline.json",
    "iac/rds-prod/params-baseline.json",
    "iac/obs-archive/worm-baseline.json",
)


def e22_t13_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E22-T13":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    def read(p: Path) -> str:
        return p.read_text(encoding="utf-8") if p.exists() else ""

    checklist = read(CHECKLIST)
    design = read(DESIGN)
    topology = read(TOPOLOGY)
    policies = read(POLICIES)
    assessment = read(ASSESSMENT)

    # ---- 1. tasks.csv 行 ----
    row = e22_t13_row()
    check("tasks.csv 有 E22-T13 行", row is not None)
    if row:
        check(
            "任务标题与类型（等保测评资料 · 产品）",
            "等保测评资料" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "产品",
        )
        check("依赖 E22-T02（测评机构签约）", (row.get("依赖") or "").strip() == "E22-T02")
        check("关联需求 A-NFR-007", "A-NFR-007" in (row.get("关联需求") or ""))
        check("验收原句（资料清单齐全）", (row.get("验收标准") or "").strip() == "资料清单齐全")
        check("排期 W39 / 2027-08-09", "W39" in (row.get("开始周") or "") and (row.get("开始日期") or "") == "2027-08-09")

    # ---- 2. 资料清单（验收锚点） ----
    check("资料清单存在（docs/ops/mlps/README.md）", bool(checklist))
    check(
        "清单头部任务行（E22-T13 · 依赖 E22-T02 · 验收「资料清单齐全」 · W39）",
        "E22-T13" in checklist and "E22-T02" in checklist and "资料清单齐全" in checklist and "W39" in checklist,
    )
    check("清单引用关联需求 A-NFR-007", "A-NFR-007" in checklist)
    for category in ("制度", "拓扑", "安全设计", "自查记录"):
        check(f"清单含四类资料标题「{category}」", f"## {category}" in checklist)
    for f in ("management-policies.md", "network-topology.md", "security-design.md", "self-assessment.md"):
        check(f"清单指向资料文件「{f}」", f in checklist and (MLPS / f).exists())
    rows = CHECKLIST_ROW.findall(checklist)
    ids = [rid for rid, _ in rows]
    expected = [f"M-{i:02d}" for i in range(1, len(rows) + 1)]
    check("清单条目编号连续（M-01 起无跳号）且 ≥ 17 条", ids == expected and len(rows) >= 17)
    claims = COUNT_CLAIM.findall(checklist)
    repo_ready = sum(1 for _, s in rows if s == "仓库就绪")
    manual = sum(1 for _, s in rows if s == "由人补齐")
    check(
        "清单条数声明与状态计数一致",
        len(claims) == 1
        and int(claims[0][0]) == len(rows)
        and int(claims[0][1]) == repo_ready
        and int(claims[0][2]) == manual,
    )
    check(
        "前置材料列入（E22-T01 定级备案 · E22-T02 测评合同 · 备案受理）",
        "E22-T01" in checklist and "定级" in checklist and "E22-T02" in checklist and "备案受理" in checklist,
    )
    check("清单含由人补齐事项（盖章 / 真实 ID 替换 / 复核更新）", "盖章" in checklist and "占位符" in checklist)
    check("清单含与 E22-T14 现场测评的衔接", "E22-T14" in checklist and "现场测评" in checklist)
    check("清单声明自动化对照（check_mlps_docs.py）", "check_mlps_docs.py" in checklist)
    check("清单声明 OQ-133（载体与脱敏暂行口径）", "OQ-133" in checklist)

    # ---- 3. 安全设计 ----
    check("安全设计存在", bool(design))
    check("安全设计基于 GB/T 22239-2019 三级", "GB/T 22239-2019" in design and "三级" in design)
    check("安全设计引用需求依据（NFR-CMP-001 · A-NFR-007 · tech-plan §4）",
          "NFR-CMP-001" in design and "A-NFR-007" in design and "tech-plan.md" in design and "§4" in design)
    for cat in DENGBAO_CATEGORIES[:5]:
        check(f"安全设计含技术类要求「{cat}」", cat in design)
    check("安全设计含管理类要求指向制度汇编", "management-policies.md" in design)
    for point in (
        "身份鉴别",
        "访问控制",
        "安全审计",
        "入侵防范",
        "恶意代码防范",
        "数据保密性",
        "数据完整性",
        "数据备份恢复",
        "剩余信息保护",
        "个人信息保护",
    ):
        check(f"安全设计控制点对照含「{point}」", point in design)
    check("安全设计声明云侧责任共担（安全物理环境）", "责任共担" in design and "安全物理环境" in design)
    check(
        "安全设计含密码应用与密评预留（AES-256-GCM · 国密切换）",
        "AES-256-GCM" in design and "国密" in design,
    )
    check("安全设计互引拓扑与自查记录", "network-topology.md" in design and "self-assessment.md" in design)
    check("安全设计声明自查判定口径（符合 / 部分符合）", "部分符合" in design)

    # ---- 4. 网络拓扑 ----
    check("网络拓扑存在", bool(topology))
    check(
        "入口链路与 cce-prod.md §1 同源（WAF · ELB · NodePort 30080 · 2 副本）",
        all(k in topology for k in ("WAF", "ELB", "NodePort 30080", "2 副本")) and "cce-prod.md" in topology,
    )
    check("拓扑含跨可用区（cn-north-4a / cn-north-4b）", "cn-north-4a" in topology and "cn-north-4b" in topology)
    check("拓扑含跨区域容灾（上海一 cn-east-3）", "上海一" in topology and "cn-east-3" in topology)
    check(
        "拓扑含信任域与最小开放（公网仅 80/443 · 回源仅 ELB→30080 · RDS 白名单只放行节点）",
        "80/443" in topology and "30080" in topology and "3306" in topology and "白名单" in topology,
    )
    check(
        "拓扑含运维与安全组件（CBH 堡垒机 · HSS · LTS · CES · NAT · DEW）",
        all(k in topology for k in ("CBH", "HSS", "LTS", "CES", "NAT", "DEW")),
    )
    check("拓扑含审计归档 WORM 桶（单可用区 · 15 年）", "WORM" in topology and "15 年" in topology)
    check(
        "拓扑声明三套环境与生产数据不出生产环境",
        "开发" in topology and "测试" in topology and "生产数据不出生产环境" in topology,
    )
    check(
        "拓扑事实源与防漂移（cce-baseline.json · params-baseline.json · worm-baseline.json · 校验脚本）",
        all(
            k in topology
            for k in (
                "cce-baseline.json",
                "params-baseline.json",
                "worm-baseline.json",
                "check_cce_baseline.py",
                "check_rds_baseline.py",
                "check_worm_baseline.py",
            )
        ),
    )
    check("拓扑真实信息占位符声明（VPC / 实例 ID / 域名由人替换）", "占位符" in topology and "VPC" in topology)
    check("拓扑互引安全设计", "security-design.md" in topology)

    # ---- 5. 管理制度 ----
    check("制度汇编存在", bool(policies))
    policy_ids = POLICY_ROW.findall(policies)
    expected_p = [f"P-{i:02d}" for i in range(1, len(policy_ids) + 1)]
    check("制度条目编号连续（P-01 起无跳号）且共 12 项", policy_ids == expected_p and len(policy_ids) == 12)
    for anchor in ("总纲", "人员", "账号", "密码", "密钥", "数据", "运维", "备份", "恢复", "应急", "开发", "供应链"):
        check(f"制度汇编含主题「{anchor}」", anchor in policies)
    for guard, why in [
        ("CBH 云堡垒机", "运维通道统一堡垒机"),
        ("禁止直连", "生产数据库禁止直连"),
        ("密钥轮换", "数据密钥年度轮换"),
        ("导出", "敏感导出审批"),
        ("保密协议", "人员保密要求"),
        ("离职", "离职权限回收"),
        ("双因素", "管理类账号双因素"),
        ("高危即报即修", "漏洞处置时限"),
        ("Flyway", "数据库变更纪律"),
        ("演练", "恢复演练节奏"),
    ]:
        check(f"制度管控锚点「{guard}」（{why}）", guard in policies)
    check("制度汇编含由人签署清单", "由人签署" in policies or "由人签署" in checklist)
    check("制度汇编互引自查记录", "self-assessment.md" in policies)

    # ---- 6. 自查记录 ----
    check("自查记录存在", bool(assessment))
    for cat in DENGBAO_CATEGORIES:
        check(f"自查记录覆盖「{cat}」", cat in assessment)
    check("自查记录含基准日与复核口径（W39 前复核更新）", "自查基准日" in assessment and "复核" in assessment)
    # 「部分符合」的表行必须同时给出整改口径（同一行内含「整改」）
    partial_rows = [
        line for line in assessment.splitlines() if "部分符合" in line and line.lstrip().startswith("|")
    ]
    check("自查判定含部分符合项（如实登记，非全绿粉饰）", len(partial_rows) >= 1)
    check(
        "部分符合行均带整改口径",
        len(partial_rows) >= 1 and all("整改" in line for line in partial_rows),
    )
    check(
        "已知偏差登记（恶意代码防范 tech-plan §10 残余风险）",
        "恶意代码防范" in assessment and "§10" in assessment,
    )
    for oq in ("OQ-30", "OQ-39", "OQ-42", "OQ-123", "OQ-127"):
        check(f"自查记录登记偏差口径「{oq}」", oq in assessment)
    check("自查记录互引制度与安全设计", "management-policies.md" in assessment and "security-design.md" in assessment)
    # 证据指向真实存在（正文引用的 docs/ .md 与 iac/ .json 路径逐个核对）
    cited = set(DOC_PATH.findall(assessment)) | set(DOC_PATH.findall(checklist))
    missing = [p for p in sorted(cited) if not (ROOT / p).exists()]
    check("清单与自查引用的仓库路径全部存在", not missing)
    if missing:
        print("  引用失效路径：", "；".join(missing), file=sys.stderr)
    for ev in REQUIRED_EVIDENCE:
        check(f"自查证据在位「{ev}」", (ROOT / ev).exists())
    joined = assessment + checklist
    for ev in REQUIRED_EVIDENCE:
        check(f"证据被清单或自查引用「{ev}」", ev in joined)

    # ---- 7. 索引与接线 ----
    ci = read(CI_YML)
    check("ci.yml 接入本校验（check_mlps_docs.py）", "check_mlps_docs.py" in ci)
    readme = read(README)
    check("docs/README.md 收录 mlps/ 资料目录", "mlps" in readme)
    oq = read(OPEN_QUESTIONS)
    check("open-questions.md 有 OQ-133（载体与脱敏暂行口径）", "OQ-133" in oq)

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
