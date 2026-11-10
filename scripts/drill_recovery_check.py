#!/usr/bin/env python3
"""恢复演练测试核对（E22-T12 · 依赖 E22-T07，验收：按清单核对恢复数据）。

依据 `docs/ops/cross-region-drill.md` §7：E22-T12 按 §4 验证清单与最新存档报告
（`docs/ops/drill-records/`）核对恢复数据；§8：存档登记与结论确认是本核对的输入。
按 `AGENTS.md` 第 9 节，生产演练（健康检查、医院端冒烟、DEW 密钥、ShedLock 抢锁）
由持云账号的运维 / 测试在场执行，本工具交付机器可核对的全部清单项与防漂移校验：

1. 默认（不连库）：存档核对——演练登记表每行与存档报告交叉核对（演练编号、两个
   RPO 与 RTO 实测值一致、报告内时间线重算一致、验收门全 PASS、「达标」结论满足
   区域内 RPO ≤ 300 秒且 RTO ≤ 14400 秒）；核对登记表引用有效；核对清单文档
   （`docs/ops/drill-recovery-checklist.md`）与 runbook §4 逐项对齐；审计链镜像
   列序与后端 `AuditChainTable.java` 不漂移；CI 已接入；
2. `--drill`（docker compose 的 MySQL，与 E22-T07 演练同环境）：重演化身演练后
   **按 §4 清单逐项核对恢复数据**——

   - 夹具复用 `check_cross_region_drill.py` 的 Drill（唯一事实源），并在「北京四
     生产」租户库化身写入真实可验算的审计链行（Python 复刻 `AuditChainTable` v1
     规范化串 + SHA-256，衔接已有 Java 封存链尾）：C0 = 跨区域同步前（必须恢复）、
     C1 = 同步后（不得恢复）——链值连续性随恢复点截断的直接证据；
   - 跑 `cross_region_drill.sh` local-drill 全流程（七阶段、报告存档），随后逐项：
     ① 恢复点三态标记（M0 在 / M1、M2 不在）② 迁移历史随库恢复且版本集与仓库
     脚本（base + followup + seed）一致 ③ 注册表改写完整（无故障区域残留、演练
     租户行 db_name / jdbc / password_cipher 齐备）④ 健康检查（化身 = 报告判定，
     生产由人）⑤ 医院端冒烟（生产由人；机器侧恢复库逐表行数 = 副本基线）
     ⑥ DEW 密钥（生产由人；机器侧 password_cipher 随库恢复非空）⑦ 对象对齐
     （清单对象全部在备用副本且**内容逐字节一致**、恢复点后对象不入清单）
     ⑧ 审计链完整（恢复库全部封存行按内容 SHA-256 重算通过——含源库由 Java
     AuditChainWriter 封存的存量行，即镜像格式的真实佐证；链行指纹与跨区域快照
     逐行一致 = 恢复不引入新空洞；演练链段衔接；恢复点后链行缺失属预期）
     ⑨ 定时作业（平台库 shedlock 表随库恢复；抢锁生产由人）；
   - 存量 Java 封存链行随夹具一并重算：镜像格式若与后端不一致立即 FAIL（防漂移）。

3. `--selftest`（不联网、不连库）：审计链镜像与登记表交叉核对的单元自检——
   URL 编码对齐 Java URLEncoder（含 `~ * 空格 + % 换行 中文` 等边界）、链验证
   通过 / 篡改检出 BAD_HASH / 断链检出 BROKEN_LINK / 漏封存检出 MISSING_HASH、
   登记表 ↔ 报告交叉核对的一致样例通过与不一致样例报错。

演练数据全部虚构（医院名称带「示例」，AGENTS.md 第 5 节第 5 条），化身库演练后
清理干净；审计链行只 INSERT（表触发器禁止 UPDATE / DELETE，PLT-AUD-011）。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import importlib.util
import json
import re
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHECKLIST = ROOT / "docs" / "ops" / "drill-recovery-checklist.md"
RUNBOOK = ROOT / "docs" / "ops" / "cross-region-drill.md"
RECORDS_README = ROOT / "docs" / "ops" / "drill-records" / "README.md"
AUDIT_CHAIN_TABLE_JAVA = (
    ROOT / "backend" / "crdp-framework" / "src" / "main" / "java"
    / "com" / "crdp" / "framework" / "audit" / "AuditChainTable.java"
)
TENANT_MIGRATIONS = ROOT / "backend" / "crdp-server" / "src" / "main" / "resources" / "db" / "tenant"
CI = ROOT / ".github" / "workflows" / "ci.yml"
CROSS_REGION_CHECK = ROOT / "scripts" / "check_cross_region_drill.py"

RPO_TARGET_SECONDS = 300     # 区域内 RPO ≤ 5 分钟（NFR-AVL-002 验收门）
RTO_TARGET_SECONDS = 14400   # RTO ≤ 4 小时（NFR-AVL-002）

# 以 check_cross_region_drill.py 为唯一事实源（夹具、演练租户、化身库、注册表改写口径）
_spec = importlib.util.spec_from_file_location("check_cross_region_drill", CROSS_REGION_CHECK)
xrg = importlib.util.module_from_spec(_spec)
assert _spec.loader is not None
_spec.loader.exec_module(xrg)

# ---------------------------------------------------------------------------
# 审计哈希链镜像（AuditChainTable v1 规范化串 + AuditChainVerifier 重算口径）
# ---------------------------------------------------------------------------

GENESIS = "0" * 64
# aud_log 参与哈希的列（顺序固定）：静态核对与 AuditChainTable.java 对齐，防漂移
AUDIT_LOG_HASH_COLUMNS = [
    "event_type", "actor_id", "action", "target", "detail", "trace_id",
    "user_name", "display_name", "user_type", "role_codes", "module",
    "object_type", "object_id", "result", "error_code", "reason",
    "before_json", "after_json", "ip", "user_agent", "view_session_id",
]
# 演练核对写入的链行标记（虚构数据；actor_id 避开演示库用户段）
C0_ACTOR, C1_ACTOR = 999995, 999994


def url_encode_java(text: str) -> str:
    """对齐 java.net.URLEncoder.encode(s, UTF-8)：字母数字与 . - * _ 保留、空格 → +、其余大写百分号编码。"""
    return (
        urllib.parse.quote(text, safe="")
        .replace("%2A", "*")   # Java 保留 *，Python 编码为 %2A
        .replace("%20", "+")   # Java 空格 → +，Python 编码为 %20
        .replace("~", "%7E")   # Java 编码 ~，Python 始终保留
    )


def canonical_value(value: object) -> str:
    """取值归一（AuditChainTable.canonicalValue）：None → 空串，数值 → 长整型十进制。"""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    return str(value)


def canonical_form(values: dict[str, object]) -> str:
    parts = ["v1", "aud_log"]
    for column in AUDIT_LOG_HASH_COLUMNS:
        parts.append(f"{column}={url_encode_java(canonical_value(values.get(column)))}")
    return "\n".join(parts)


def chain_hash(prev_hash: str, values: dict[str, object]) -> str:
    return hashlib.sha256(
        (prev_hash + "\n" + canonical_form(values)).encode("utf-8")
    ).hexdigest()


def verify_chain(rows: list[dict]) -> list[str]:
    """按 AuditChainVerifier 口径验链（无归档桥接——化身库 aud_archive 无登记段，生产以验链作业为准）。

    rows：主键升序；每行 {id, values, prev_hash, hash}，hash 为 None 表示未封存。
    返回断点描述列表（空 = 链完整）。演示库存量链的空洞来自 IT 自清理
    （AuditTableMaintenance 预期行为），化身核对以「链行指纹与快照逐行一致」判恢复保真。
    """
    breaks: list[str] = []
    expected_prev = GENESIS
    started = False
    for row in rows:
        if row["hash"] is None:
            if started:
                breaks.append(f"id={row['id']} MISSING_HASH（链已开始后出现未封存行）")
            continue
        started = True
        if chain_hash(row["prev_hash"], row["values"]) != row["hash"]:
            breaks.append(f"id={row['id']} BAD_HASH（存量 hash 与按内容重算值不符）")
        if row["prev_hash"] != expected_prev:
            breaks.append(f"id={row['id']} BROKEN_LINK（prev_hash 与实际前序不衔接）")
        expected_prev = row["hash"]
    return breaks


# ---------------------------------------------------------------------------
# 存档核对：演练登记表 / 核对登记表 / 存档报告交叉核对（默认模式，不连库）
# ---------------------------------------------------------------------------

SEC_PATTERN = re.compile(r"^## +(.+?) *$", re.M)
REGISTRY_ROW = re.compile(r"^\|\s*(\d{4}-\d{2}-\d{2})\s*\|")


def split_table_cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def parse_registry_tables(text: str) -> tuple[list[list[str]], list[list[str]]]:
    """解析演练登记表与核对登记表（E22-T12）的数据行。"""
    drills: list[list[str]] = []
    checks: list[list[str]] = []
    current: str | None = None
    for line in text.splitlines():
        header = SEC_PATTERN.match(line)
        if header:
            current = header.group(1)
            continue
        if not REGISTRY_ROW.match(line):
            continue
        if current and "演练登记表" in current:
            drills.append(split_table_cells(line))
        elif current and "核对登记表" in current:
            checks.append(split_table_cells(line))
    return drills, checks


def parse_seconds(cell: str) -> int | None:
    matched = re.search(r"(\d+)\s*秒", cell)
    return int(matched.group(1)) if matched else None


def parse_report_md(text: str, path: Path) -> tuple[dict, list[str]]:
    """解析存档演练报告，并做内部勾稽（时间线重算 = 实测值）。"""
    errors: list[str] = []
    report: dict = {}

    def grab(pattern: str, name: str) -> str | None:
        found = re.search(pattern, text, re.M)
        if not found:
            errors.append(f"{path.name} 缺少字段：{name}")
            return None
        return found.group(1)

    report["drill_id"] = grab(r"- 演练编号：(\S+?)（模式", "演练编号")
    report["declared_at"] = grab(r"- 宣告时刻（T0）：(\S+)", "宣告时刻")
    point_in = grab(r"- 恢复点：区域内\s*(\S+?)；", "区域内恢复点")
    point_x = grab(r"- 恢复点：区域内\s*\S+；跨区域\s*(\S+?)$", "跨区域恢复点")
    verified = grab(r"\|\s*验证完成（服务恢复）\s*\|\s*(\S+?)\s*\|", "验证完成时刻")
    metrics: dict[str, tuple[int, str]] = {}
    for key, pattern in [
        ("rpo_in", r"^\|\s*实测 RPO（区域内）\s*\|[^|]+\|\s*(\d+) 秒（[\d.]+ 分钟）\s*\|\s*(\w+)"),
        ("rpo_x", r"^\|\s*实测 RPO（跨区域）\s*\|[^|]+\|\s*(\d+) 秒（[\d.]+ 分钟）\s*\|\s*(\w+)"),
        ("rto", r"^\|\s*实测 RTO\s*\|[^|]+\|\s*(\d+) 秒（[\d.]+ 分钟）\s*\|\s*(\w+)"),
    ]:
        found = re.search(pattern, text, re.M)
        if not found:
            errors.append(f"{path.name} 缺少指标行：{key}")
            continue
        metrics[key] = (int(found.group(1)), found.group(2))
    if errors:
        return report, errors
    assert point_in and point_x and verified and report["declared_at"]
    report.update({"rpo_in": metrics["rpo_in"][0], "rpo_x": metrics["rpo_x"][0],
                   "rto": metrics["rto"][0],
                   "verdicts": {k: v[1] for k, v in metrics.items()}})
    try:
        declared = datetime.datetime.fromisoformat(report["declared_at"])
        in_ts = datetime.datetime.fromisoformat(point_in)
        x_ts = datetime.datetime.fromisoformat(point_x)
        done = datetime.datetime.fromisoformat(verified)
    except ValueError as exc:
        return report, [f"{path.name} 时间戳不是 ISO-8601 带时区：{exc}"]
    if (declared - in_ts).total_seconds() != report["rpo_in"]:
        errors.append(f"{path.name} 区域内 RPO 与「宣告 − 区域内恢复点」重算不一致")
    if (declared - x_ts).total_seconds() != report["rpo_x"]:
        errors.append(f"{path.name} 跨区域 RPO 与「宣告 − 跨区域恢复点」重算不一致")
    if (done - declared).total_seconds() != report["rto"]:
        errors.append(f"{path.name} RTO 与「验证完成 − 宣告」重算不一致")
    if set(report["verdicts"].values()) != {"PASS"}:
        errors.append(f"{path.name} 验收门判定应全 PASS，实际 {report['verdicts']}")
    return report, errors


def crosscheck_registry_and_reports(problems: list[str]) -> None:
    if not RECORDS_README.exists():
        problems.append(f"缺少 {RECORDS_README.relative_to(ROOT)}（演练报告存档规则与登记表）")
        return
    text = RECORDS_README.read_text(encoding="utf-8")
    drills, checks = parse_registry_tables(text)
    if not drills:
        problems.append("演练登记表为空（E22-T07 验收：演练报告存档）")
    drill_ids: set[str] = set()
    for row in drills:
        if len(row) != 8:
            problems.append(f"演练登记行列数应为 8（日期/编号/环境/RPO×2/RTO/结论/报告）：{row}")
            continue
        _date, drill_id, _env, rpo_in, rpo_x, rto, conclusion, report_cell = row
        drill_ids.add(drill_id)
        rpo_in_s, rpo_x_s, rto_s = parse_seconds(rpo_in), parse_seconds(rpo_x), parse_seconds(rto)
        if rpo_in_s is None or rpo_x_s is None or rto_s is None:
            problems.append(f"演练登记行 {drill_id} 的 RPO/RTO 不是「N 秒」格式：{row}")
            continue
        if "达标" in conclusion and not (rpo_in_s <= RPO_TARGET_SECONDS and rto_s <= RTO_TARGET_SECONDS):
            problems.append(f"演练登记行 {drill_id} 结论含「达标」但实测值不满足达标判据（区域内 RPO ≤ {RPO_TARGET_SECONDS}s 且 RTO ≤ {RTO_TARGET_SECONDS}s）")
        report_name = report_cell.strip("`")
        if report_name.endswith(".md"):
            report_path = RECORDS_README.parent / report_name
            if not report_path.exists():
                problems.append(f"演练登记行 {drill_id} 引用的存档报告不存在：{report_name}")
                continue
            report, errors = parse_report_md(
                report_path.read_text(encoding="utf-8"), report_path)
            problems.extend(errors)
            if report.get("drill_id") and report["drill_id"] != drill_id:
                problems.append(f"存档报告 {report_name} 演练编号 {report['drill_id']} 与登记行 {drill_id} 不一致")
            for key, registered in [("rpo_in", rpo_in_s), ("rpo_x", rpo_x_s), ("rto", rto_s)]:
                if key in report and report[key] != registered:
                    problems.append(f"存档报告 {report_name} 的 {key}={report[key]}s 与登记行 {registered}s 不一致")
    if not checks:
        problems.append("核对登记表（E22-T12）为空：至少一条对本仓库已存档演练报告的核对记录")
    for row in checks:
        if len(row) != 5:
            problems.append(f"核对登记行列数应为 5（日期/演练编号/核对方式/清单结果/结论）：{row}")
            continue
        date, drill_id, method, result, conclusion = row
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
            problems.append(f"核对登记行日期不合法：{date}")
        if drill_id not in drill_ids:
            problems.append(f"核对登记行引用的演练编号 {drill_id} 不在演练登记表中")
        if not method or not result or not conclusion:
            problems.append(f"核对登记行有空单元格：{row}")


def check_docs_and_wiring(problems: list[str]) -> None:
    """核对清单文档与 runbook §4 逐项对齐、审计链镜像列序与后端一致、CI 已接入。"""
    if not RUNBOOK.exists():
        problems.append(f"缺少 {RUNBOOK.relative_to(ROOT)}（跨区域恢复演练 runbook）")
        return
    runbook = RUNBOOK.read_text(encoding="utf-8")
    section4 = runbook.split("## 4. 验证清单", 1)[-1].split("## 5.", 1)[0]
    heads = []
    for item in re.finditer(r"^- \[ \] (.+)$", section4, re.M):
        heads.append(item.group(1).replace("**", "").split("：", 1)[0].strip())
    if len(heads) != 9:
        problems.append(f"runbook §4 应有 9 个清单项（逐项核对），实际 {len(heads)} 个：{heads}")

    if not CHECKLIST.exists():
        problems.append(f"缺少 {CHECKLIST.relative_to(ROOT)}（恢复演练核对清单，E22-T12）")
        return
    doc = CHECKLIST.read_text(encoding="utf-8")
    for token in ["E22-T12", "E22-T07", "cross-region-drill.md", "drill_recovery_check.py",
                  "drill-records", "核对登记表", "生产由人"]:
        if token not in doc:
            problems.append(f"核对清单文档缺少内容：{token}")
    for head in heads:
        if head not in doc:
            problems.append(f"核对清单文档缺少 runbook §4 清单项：{head}")

    java = AUDIT_CHAIN_TABLE_JAVA.read_text(encoding="utf-8")
    found = re.search(
        r"TENANT_OPERATION\s*=\s*new AuditChainTable\(\s*\"aud_log\",\s*List\.of\((.*?)\)\);",
        java, re.S)
    if not found:
        problems.append("AuditChainTable.java 中找不到 aud_log 列登记（TENANT_OPERATION），镜像无法对齐")
    else:
        java_columns = re.findall(r'"([a-z_]+)"', found.group(1))
        if java_columns != AUDIT_LOG_HASH_COLUMNS:
            problems.append(
                "审计链镜像列序与 AuditChainTable.java 不一致（后端调整列时必须同步镜像）："
                f"java={java_columns}")
    if '"0".repeat(64)' not in java:
        problems.append("AuditChainTable.java 的 GENESIS 锚（64 个 0）变了，镜像需同步")

    ci = CI.read_text(encoding="utf-8") if CI.exists() else ""
    if "drill_recovery_check.py" not in ci:
        problems.append("ci.yml 未接入 drill_recovery_check.py（核对需进 CI）")


# ---------------------------------------------------------------------------
# 本地化身演练核对（--drill）：重演 E22-T07 演练，按 §4 清单逐项核对恢复数据
# ---------------------------------------------------------------------------


class RecoveryCheck:
    """按 runbook §4 清单核对恢复数据；夹具与演练调用复用 E22-T07 的 Drill。"""

    def __init__(self) -> None:
        self.problems: list[str] = []
        self.results: list[str] = []
        self.drill = xrg.Drill()
        self.c0_hash = ""
        self.c0_prev = ""
        self.sealed_before = 0
        self.snapshot_chain: list[tuple] = []

    def ok(self, cond: bool, item: str, message: str) -> None:
        self.results.append(f"{'PASS' if cond else 'FAIL'} · 清单{item} · {message}")
        if not cond:
            self.problems.append(f"清单{item} {message}")

    def write_chain_row(self, actor_id: int, label: str, detail: str) -> tuple[str, str]:
        """向「北京四生产」租户库化身追加一条真实可验算的封存审计链行，返回（前序 hash, 本条 hash）。"""
        prev = self.drill.scalar(
            "SELECT hash FROM aud_log WHERE hash IS NOT NULL ORDER BY id DESC LIMIT 1",
            db=xrg.REGION_A_TENANT) or GENESIS
        values = {
            "event_type": "DRILL_RECOVERY_CHECK", "actor_id": actor_id, "action": "VERIFY",
            "target": f"t_{xrg.TENANT}", "detail": detail, "trace_id": "xrg-local-check",
            "user_name": "xrgcheck-op", "display_name": label, "user_type": "HOSPITAL",
            "role_codes": "drill", "module": "ops", "object_type": "DRILL",
            "object_id": f"xrg-{actor_id}", "result": "SUCCESS", "error_code": None,
            "reason": None, "before_json": None, "after_json": None, "ip": None,
            "user_agent": None, "view_session_id": None,
        }
        seal = chain_hash(prev, values)
        columns = ", ".join(AUDIT_LOG_HASH_COLUMNS)
        literals = []
        for column in AUDIT_LOG_HASH_COLUMNS:
            value = values[column]
            if value is None:
                literals.append("NULL")
            elif isinstance(value, int):
                literals.append(str(value))
            else:
                literals.append("'" + str(value).replace("'", "''") + "'")
        self.drill.exec_mysql(
            f"INSERT INTO aud_log ({columns}, prev_hash, hash)"
            f" VALUES ({', '.join(literals)}, '{prev}', '{seal}')",
            db=xrg.REGION_A_TENANT)
        return prev, seal

    def read_chain(self, db: str) -> list[dict]:
        """读回审计链行（值经 JSON 往返避免制表符破坏行结构；NULL 以 '' 化对齐 canonicalValue）。"""
        columns = ", ".join(
            f"'{c}', IFNULL({c}, '')" for c in AUDIT_LOG_HASH_COLUMNS)
        out = self.drill.exec_mysql(
            "SELECT JSON_OBJECT('id', id, " + columns
            + ", 'prev_hash', IFNULL(prev_hash, ''), 'hash', IFNULL(hash, ''))"
            " FROM aud_log ORDER BY id", db=db)
        rows = []
        for line in out.splitlines():
            if not line:
                continue
            raw = json.loads(line)
            rows.append({
                "id": int(raw["id"]),
                "values": {c: raw[c] for c in AUDIT_LOG_HASH_COLUMNS},
                "prev_hash": raw["prev_hash"],
                "hash": raw["hash"] or None,
            })
        return rows

    @staticmethod
    def chain_tuples(rows: list[dict]) -> list[tuple]:
        """链行指纹：(id, prev_hash, hash)——恢复保真核对与空洞计数都用它。"""
        return [(r["id"], r["prev_hash"], r["hash"]) for r in rows]

    def repo_tenant_versions(self) -> set[str]:
        versions: set[str] = set()
        for sub in ("base", "followup", "seed"):
            for script in (TENANT_MIGRATIONS / sub).glob("V*.sql"):
                matched = re.match(r"V(\d+)__", script.name)
                if matched:
                    versions.add(matched.group(1))
        return versions

    def run(self) -> list[str]:
        drill = self.drill
        drill.setup_fixture()  # 复制演示库、登记演练租户、标记 M0、对象 rpt-m0.bin

        # 源库（演示库拷贝）中由 Java AuditChainWriter 封存的存量链行数——恢复后必须原样验算通过
        self.sealed_before = int(drill.scalar(
            "SELECT COUNT(*) FROM aud_log WHERE hash IS NOT NULL", db=xrg.REGION_A_TENANT))
        # C0 链行 + 对象 rpt-c0：跨区域同步前写入（必须恢复）
        self.c0_prev, self.c0_hash = self.write_chain_row(
            C0_ACTOR, "恢复核对链行 C0（示例）", "恢复演练核对链行 C0（示例）")
        (drill.objects_region_a / "rpt-c0.bin").write_bytes(b"object-chain-c0")
        # 跨区域快照时刻的链行指纹（含 C0）：恢复库必须与之逐行一致（保真核对基线）
        self.snapshot_chain = self.chain_tuples(self.read_chain(xrg.REGION_A_TENANT))
        # 跨区域副本（先同步）：此后写 M1、链行 C1、对象 rpt-m1
        _, t_sync_x = drill.snapshot(drill.backup_dir, ["rpt-m0.bin", "rpt-c0.bin"])
        drill.write_marker(xrg.M1, "跨区域演练标记 M1（示例）")
        self.write_chain_row(C1_ACTOR, "恢复核对链行 C1（示例）", "恢复演练核对链行 C1（示例）")
        (drill.objects_region_a / "rpt-m1.bin").write_bytes(b"object-after-cross-sync")
        # 区域内副本（后同步）：此后写 M2、对象 rpt-m2（均不进任何副本）
        _, t_sync_in = drill.snapshot(
            drill.inregion_dir, ["rpt-m0.bin", "rpt-c0.bin", "rpt-m1.bin"])
        drill.write_marker(xrg.M2, "跨区域演练标记 M2（示例）")
        (drill.objects_region_a / "rpt-m2.bin").write_bytes(b"object-after-inregion-sync")

        proof_file = drill.work / "proof.tsv"
        proof_file.write_text(
            f"{xrg.MARKER_TABLE}\tuser_id = {xrg.M0}\t1\n"
            f"{xrg.MARKER_TABLE}\tuser_id = {xrg.M1}\t0\n"
            f"{xrg.MARKER_TABLE}\tuser_id = {xrg.M2}\t0\n"
            f"aud_log\tactor_id = {C0_ACTOR}\t1\n"
            f"aud_log\tactor_id = {C1_ACTOR}\t0\n",
            encoding="utf-8")
        archive_dir = drill.work / "archive"
        password = drill.root_password()
        full = drill.run_drill(
            ["--last-restorable-in-region", t_sync_in,
             "--last-restorable-cross-region", t_sync_x,
             "--backup-dir", str(drill.backup_dir),
             "--restored-platform-db", xrg.RESTORED_PLATFORM,
             "--objects-standby-dir", str(drill.objects_standby),
             "--proof-file", str(proof_file),
             "--report-archive-dir", str(archive_dir),
             "--work-dir", str(drill.work / "run"),
             "--yes"], password)
        if full.returncode != 0:
            self.problems.append(f"演练脚本执行失败（{full.returncode}）：{full.stderr[-600:]}")
            return self.problems

        report = json.loads(
            (drill.work / "run" / "report.json").read_text(encoding="utf-8"))
        self.check_list_items(report, t_sync_in, t_sync_x)
        return self.problems

    # ---- runbook §4 逐项核对 ------------------------------------------------

    def check_list_items(self, report: dict, t_sync_in: str, t_sync_x: str) -> None:
        drill = self.drill
        tenant_db = f"t_{xrg.TENANT}"

        # 清单 1 · 恢复点三态标记：M0 在（跨区域副本里有），M1 / M2 不在
        m0 = drill.row_count(tenant_db, xrg.MARKER_TABLE, f"WHERE user_id = {xrg.M0}")
        m1 = drill.row_count(tenant_db, xrg.MARKER_TABLE, f"WHERE user_id = {xrg.M1}")
        m2 = drill.row_count(tenant_db, xrg.MARKER_TABLE, f"WHERE user_id = {xrg.M2}")
        self.ok(m0 == 1 and m1 == 0 and m2 == 0, "1 恢复点三态标记",
                f"三态标记 M0={m0} / M1={m1} / M2={m2}（期望 1/0/0）")
        self.ok(report["verdicts"].get("proofs") == "PASS", "1 恢复点三态标记",
                "演练报告验证清单判定 PASS")

        # 清单 2 · 迁移历史随库恢复：非空且版本集与仓库脚本、源库完全一致
        history = int(drill.scalar(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", db=tenant_db))
        recovered_versions = {
            row[0] for row in drill.rows(
                "SELECT version FROM flyway_schema_history WHERE success = 1", db=tenant_db)}
        source_versions = {
            row[0] for row in drill.rows(
                "SELECT version FROM flyway_schema_history WHERE success = 1",
                db=xrg.REGION_A_TENANT)}
        repo_versions = self.repo_tenant_versions()
        self.ok(history > 0, "2 迁移历史随库恢复", f"flyway_schema_history 成功版本 {history} 行（非空）")
        self.ok(recovered_versions == repo_versions,
                "2 迁移历史随库恢复",
                f"恢复库版本集与仓库脚本（base+followup+seed）一致（{len(recovered_versions)} 个）"
                if recovered_versions == repo_versions else
                f"恢复库版本集与仓库脚本不一致：缺 {sorted(repo_versions - recovered_versions)[:5]}"
                f" 多 {sorted(recovered_versions - repo_versions)[:5]}")
        self.ok(recovered_versions == source_versions, "2 迁移历史随库恢复",
                "恢复库版本集与源库（快照时刻）一致")

        # 清单 3 · 注册表：改写完整、演练租户行指向恢复库、无故障区域地址残留
        registry = drill.registry_rows(xrg.RESTORED_PLATFORM)
        tenant_rows = [r for r in registry if r[0] == xrg.TENANT]
        self.ok(all(f"//{xrg.JDBC_HOST_STANDBY}/" in r[2] for r in registry), "3 注册表切换",
                f"全部 {len(registry)} 行 jdbc_url 指向备用区域实例")
        self.ok(not any(xrg.JDBC_HOST_REGION_A in r[2] for r in registry), "3 注册表切换",
                "无任何行残留故障区域（cn-north-4）地址")
        self.ok(len(tenant_rows) == 1 and tenant_rows[0][1] == f"t_{xrg.TENANT}", "3 注册表切换",
                f"演练租户行 db_name = {tenant_rows[0][1] if tenant_rows else '无'}")
        cipher = drill.scalar(
            "SELECT IFNULL(password_cipher, '') FROM plt_tenant_resource r"
            f" JOIN plt_tenant t ON t.id = r.tenant_id WHERE t.code = '{xrg.TENANT}'",
            db=xrg.RESTORED_PLATFORM)
        self.ok(bool(cipher), "6 DEW 密钥可用",
                "注册表 password_cipher 随库恢复非空（解密与连接由生产演练核对）")

        # 清单 4 · 健康检查（化身 = 报告 verify 记录与验收门判定；生产 /actuator/health UP 由人核对）
        verified_at = report.get("milestones", {}).get("verified_at")
        self.ok(bool(verified_at), "4 健康检查",
                f"演练报告记录验证完成时刻 {verified_at}（化身口径）；生产健康检查由人核对")
        self.ok(set(report["verdicts"].values()) == {"PASS"}, "4 健康检查",
                f"演练报告验收门全 PASS：{report['verdicts']}")

        # 清单 5 · 医院端登录与冒烟（生产由人）：机器侧核对恢复库逐表行数 = 副本基线（导入完整性）
        baseline = (drill.backup_dir / f"counts_{xrg.TENANT}.tsv").read_text(
            encoding="utf-8").splitlines()
        mismatch = [
            line.split("\t")[0] for line in baseline
            if drill.row_count(tenant_db, line.split("\t")[0]) != int(line.split("\t")[1])]
        self.ok(bool(baseline) and not mismatch, "5 医院端冒烟（数据前提）",
                f"恢复库逐表行数与跨区域副本基线一致（{len(baseline)} 表）"
                if not mismatch else f"行数不一致：{mismatch[:5]}")

        # 清单 7 · 对象对齐：清单对象全部在备用副本且内容逐字节一致；恢复点之后对象不入副本
        object_checks = []
        for name, content in [("rpt-m0.bin", b"object-before-cross-sync"),
                              ("rpt-c0.bin", b"object-chain-c0")]:
            target = drill.objects_standby / name
            object_checks.append(target.exists() and target.read_bytes() == content)
        self.ok(all(object_checks), "7 对象对齐",
                "恢复点之前的对象（rpt-m0、rpt-c0）在备用副本且内容逐字节一致（报告单预览取回化身）")
        self.ok(not (drill.objects_standby / "rpt-m2.bin").exists(), "7 对象对齐",
                "恢复点之后上传的对象（rpt-m2）不出现在备用区域副本")
        self.ok(report["verdicts"].get("objects") == "PASS", "7 对象对齐", "演练报告对象对齐判定 PASS")

        # 清单 8 · 审计链完整：化身口径 = ① 恢复库全部封存行按内容 SHA-256 重算通过（无 BAD_HASH）
        # ② 链行指纹与跨区域快照逐行一致（恢复不引入新空洞、不改不漏）③ 演练写入的链段
        # （源库尾行 → C0）衔接且重算通过 ④ 恢复点之后的链行（C1）不在。
        # 演示库存量链的空洞来自 IT 自清理（AuditTableMaintenance 预期行为），完整链连续性
        # 由验链作业对真实环境校验（PLT-AUD-009）；生产核对项见清单文档。
        rows = self.read_chain(tenant_db)
        breaks = verify_chain(rows)
        sealed = [r for r in rows if r["hash"] is not None]
        bad_hash = [b for b in breaks if "BAD_HASH" in b]
        self.ok(not bad_hash, "8 审计链完整",
                f"恢复库全部 {len(sealed)} 条封存链行按内容 SHA-256 重算通过（0 处 BAD_HASH，"
                "含源库由 Java AuditChainWriter 封存的存量行）"
                if not bad_hash else f"内容与 hash 不符 {len(bad_hash)} 处：{bad_hash[:3]}")
        recovered_tuples = self.chain_tuples(rows)
        self.ok(recovered_tuples == self.snapshot_chain, "8 审计链完整",
                f"链行指纹 (id, prev_hash, hash) 与跨区域快照逐行一致（{len(self.snapshot_chain)} 行，"
                "恢复未引入新空洞、无改动无缺失）"
                if recovered_tuples == self.snapshot_chain else
                f"链行指纹与快照不一致：恢复多出 {len(set(recovered_tuples) - set(self.snapshot_chain))} 行、"
                f"缺失 {len(set(self.snapshot_chain) - set(recovered_tuples))} 行")
        c0 = [r for r in sealed if str(r["values"]["actor_id"]) == str(C0_ACTOR)]
        c1 = [r for r in rows if str(r["values"]["actor_id"]) == str(C1_ACTOR)]
        self.ok(len(c0) == 1 and c0[0]["hash"] == self.c0_hash
                and c0[0]["prev_hash"] == self.c0_prev, "8 审计链完整",
                f"C0 链行在、衔接源库尾行（prev_hash 一致）且 hash 与写入时一致（{self.c0_hash[:12]}…）"
                if c0 else "C0 链行缺失")
        self.ok(len(c1) == 0, "8 审计链完整", "跨区域同步后的 C1 链行不在恢复库（链值缺失属预期——该段数据本就丢了）")
        self.ok(len(sealed) == self.sealed_before + 1, "8 审计链完整",
                f"封存行数 {len(sealed)} = 源库存量 {self.sealed_before} + 跨区域同步前 C0（恢复点截断生效）")

        # 清单 9 · 定时作业恢复执行（ShedLock 抢锁生产由人）：平台库化身 shedlock 表随库恢复
        shedlock_tables = drill.rows(
            "SELECT COUNT(*) FROM information_schema.tables"
            f" WHERE table_schema = '{xrg.RESTORED_PLATFORM}' AND table_name = 'shedlock'")
        self.ok(shedlock_tables and shedlock_tables[0][0] == "1", "9 定时作业恢复执行",
                "平台库 shedlock 表随库恢复（结构在，抢锁行为由生产演练核对）")

        # 报告勾稽：实测 RPO / RTO 与里程碑重算一致（E22-T07 报告口径）
        declared = datetime.datetime.fromisoformat(report["declared_at"])
        sync_in = datetime.datetime.fromisoformat(t_sync_in)
        sync_x = datetime.datetime.fromisoformat(t_sync_x)
        done = datetime.datetime.fromisoformat(report["milestones"]["verified_at"])
        measured = report["measured"]
        self.ok(measured["in_region_rpo_seconds"] == (declared - sync_in).total_seconds()
                and measured["cross_region_rpo_seconds"] == (declared - sync_x).total_seconds()
                and measured["rto_seconds"] == (done - declared).total_seconds(),
                "4 健康检查", "报告实测 RPO/RTO 与两口径恢复点、验证完成时刻重算一致")


def run_drill_mode() -> list[str]:
    check = RecoveryCheck()
    try:
        problems = check.run()
    except (RuntimeError, OSError, json.JSONDecodeError) as exc:
        problems = [f"化身演练核对执行异常：{exc}"]
    for line in check.results:
        print(f"  {line}")
    # 演练核对后，再做存档核对（登记表 ↔ 存档报告 ↔ 清单文档 ↔ CI）
    crosscheck_registry_and_reports(problems)
    check_docs_and_wiring(problems)
    try:
        check.drill.cleanup_fixture()
    except RuntimeError as exc:
        problems.append(f"演练清理失败：{exc}")
    return problems


# ---------------------------------------------------------------------------
# 自检（--selftest，不联网不连库）
# ---------------------------------------------------------------------------


def selftest() -> int:
    failures: list[str] = []

    def case(name: str, ok: bool) -> None:
        print(f"  {'PASS' if ok else 'FAIL'} · {name}")
        if not ok:
            failures.append(name)

    # 1) URL 编码镜像对齐 java.net.URLEncoder（期望值取自 JDK 21 URLEncoder 实测输出）
    for sample, expected in [
        ("恢复演练核对（示例）",
         "%E6%81%A2%E5%A4%8D%E6%BC%94%E7%BB%83%E6%A0%B8%E5%AF%B9%EF%BC%88%E7%A4%BA%E4%BE%8B%EF%BC%89"),
        ("abc XYZ 123", "abc+XYZ+123"),
        ("a~b*c.d-e_f/g h", "a%7Eb*c.d-e_f%2Fg+h"),
        ("角色A,B;C=D?E#F&G+H%I\"J'K<L>M",
         "%E8%A7%92%E8%89%B2A%2CB%3BC%3DD%3FE%23F%26G%2BH%25I%22J%27K%3CL%3EM"),
        ("", ""),
        ("line1\nline2", "line1%0Aline2"),
        ("0.1-2_3", "0.1-2_3"),
        ("https://x/y", "https%3A%2F%2Fx%2Fy"),
    ]:
        case(f"URLEncoder 镜像 {sample[:18]!r}", url_encode_java(sample) == expected)

    # 2) 链验证：通过 / 篡改 BAD_HASH / 删行 BROKEN_LINK / 漏封存 MISSING_HASH / 空值归一
    base = {"event_type": "DRILL", "actor_id": 1, "action": "VERIFY", "detail": "链行（示例）",
            "error_code": None, "role_codes": None}
    rows = []
    prev = GENESIS
    for i in range(1, 4):
        values = dict(base, actor_id=i)
        seal = chain_hash(prev, values)
        rows.append({"id": i, "values": values, "prev_hash": prev, "hash": seal})
        prev = seal
    case("链验证通过（三行封存链）", verify_chain(rows) == [])
    tampered = [dict(r) for r in rows]
    tampered[1] = {**tampered[1], "values": {**tampered[1]["values"], "detail": "被篡改（示例）"}}
    case("篡改检出 BAD_HASH", any("BAD_HASH" in b for b in verify_chain(tampered)))
    deleted = [rows[0], rows[2]]
    case("删行检出 BROKEN_LINK", any("BROKEN_LINK" in b for b in verify_chain(deleted)))
    unsealed = rows + [{"id": 4, "values": dict(base), "prev_hash": "", "hash": None}]
    case("漏封存检出 MISSING_HASH", any("MISSING_HASH" in b for b in verify_chain(unsealed)))
    case("链首未封存前缀跳过（E08-T01 遗留口径）",
          verify_chain([{"id": 9, "values": dict(base), "prev_hash": "", "hash": None}] + rows) == [])
    empty_values = {c: None for c in AUDIT_LOG_HASH_COLUMNS}
    case("全空值行可封存与重算", chain_hash(GENESIS, empty_values) == chain_hash(
        GENESIS, {c: "" for c in AUDIT_LOG_HASH_COLUMNS}))

    # 3) 登记表 ↔ 存档报告交叉核对：一致样例通过、不一致样例报错
    sample_readme = """# 恢复演练报告存档

## 演练登记表

| 日期 | 演练编号 | 环境 | 区域内 RPO（实测） | 跨区域 RPO（实测） | RTO（实测） | 结论 | 报告 |
|---|---|---|---|---|---|---|---|
| 2026-11-10 | xrg-selftest | 本地 CI 化身 | 1 秒 | 7 秒 | 9 秒 | 机制达标 | `selftest-report.md` |

## 核对登记表（E22-T12）

| 日期 | 演练编号 | 核对方式 | 清单结果 | 结论 |
|---|---|---|---|---|
| 2026-11-10 | xrg-selftest | scripts/drill_recovery_check.py --drill | 机器核对项全 PASS | 通过 |
"""
    sample_report = """# 跨区域恢复演练报告（E22-T07）

- 演练编号：xrg-selftest（模式 local-drill）
- 宣告时刻（T0）：2026-11-10T13:44:26+08:00
- 恢复点：区域内 2026-11-10T13:44:25+08:00；跨区域 2026-11-10T13:44:19+08:00

| 里程碑 | 时刻 |
|---|---|
| 宣告区域灾难（T0） | 2026-11-10T13:44:26+08:00 |
| 验证完成（服务恢复） | 2026-11-10T13:44:35+08:00 |

| 指标 | 目标 | 实测值 | 判定 |
|---|---|---|---|
| 实测 RPO（区域内） | ≤ 300 秒 | 1 秒（0.0 分钟） | PASS |
| 实测 RPO（跨区域） | 无 SLA | 7 秒（0.1 分钟） | PASS |
| 实测 RTO | ≤ 4 小时 | 9 秒（0.1 分钟） | PASS |
"""
    drills, checks = parse_registry_tables(sample_readme)
    case("登记表解析（演练 1 行 / 核对 1 行）", len(drills) == 1 and len(checks) == 1)
    report, errors = parse_report_md(sample_report, Path("selftest-report.md"))
    case("报告解析与时间线重算一致", not errors and report.get("rpo_in") == 1
         and report.get("rpo_x") == 7 and report.get("rto") == 9)
    bad_report = sample_report.replace("| 1 秒（0.0 分钟） | PASS |", "| 2 秒（0.0 分钟） | PASS |")
    _, bad_errors = parse_report_md(bad_report, Path("selftest-report.md"))
    case("RPO 与时间线重算不一致必须报错", any("区域内 RPO" in e for e in bad_errors))
    failed_report = sample_report.replace("| 9 秒（0.1 分钟） | PASS |", "| 9 秒（0.1 分钟） | FAIL |")
    _, failed_errors = parse_report_md(failed_report, Path("selftest-report.md"))
    case("验收门 FAIL 必须报错", any("全 PASS" in e for e in failed_errors))

    if failures:
        print(f"自检未通过，共 {len(failures)} 处：{failures}")
        return 1
    print("自检通过：URLEncoder 镜像、链验算（含篡改 / 删行 / 漏封存检出）、登记表与报告交叉核对。")
    return 0


# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--drill", action="store_true",
                       help="在 docker compose 的 MySQL 上重演化身演练并按 runbook §4 清单核对恢复数据")
    modes.add_argument("--selftest", action="store_true",
                       help="单元自检（不联网不连库）：审计链镜像与交叉核对解析")
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    problems: list[str] = []
    if args.drill:
        print("E22-T12 恢复演练核对：重演化身演练（E22-T07 夹具）……")
        problems = run_drill_mode()
    else:
        crosscheck_registry_and_reports(problems)
        check_docs_and_wiring(problems)
    if problems:
        print(f"恢复演练核对未通过，共 {len(problems)} 处：")
        for message in problems:
            print(f"  - {message}")
        return 1
    if args.drill:
        print("恢复演练核对通过：runbook §4 清单机器可核对项全部 PASS"
              "（健康检查、医院端冒烟、DEW 密钥解密、ShedLock 抢锁为生产核对项，由人按清单执行），"
              "存档登记与报告交叉核对一致。")
    else:
        print("恢复演练存档核对通过：登记表 ↔ 存档报告交叉一致、清单文档与 runbook §4 对齐、"
              "审计链镜像与后端不漂移、CI 已接入（加 --drill 可核对化身恢复数据）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
