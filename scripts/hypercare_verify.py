#!/usr/bin/env python3
"""医院 A 上线后运行保障：SLA 台账判定、观察期巡检与 runbook 防漂移校验（E23-T13 · W36–W37）。

任务口径（`docs/tasks/tasks.csv` E23-T13：全栈 1，依赖 E23-T11，验收「上线问题按时响应」）：
观察期（W36–W37）的真实值班、接报与生产处置由人完成（`AGENTS.md` 第 9 节同 E23-T11 口径）。
本工具交付其中「机器可判定」的部分：

1. 默认（不连库、不联网）：runbook（`docs/ops/hospital-a-hypercare.md`）防漂移校验——验收原句
   与依赖、时限表（§3）与脚本 `SLA_RESPONSE_MINUTES` 逐字一致、台账列与 `LEDGER_COLUMNS`
   一致、巡检检查项（HC-D1~D6）与阈值在册、既有 runbook 互引、ci.yml 接线、
   docs/README.md 索引、模板表头一致；
2. `--selftest`（不联网、不连库）：纯逻辑自检——时限表单调、SLA 判定（按时 / 超时 / 边界 /
   未响应）、台账校验（缺列 / 非法分级 / 时间倒序 / 重复编号 / 疑似真实手机号 / 状态矛盾）、
   只读护栏（巡检语句必须 SELECT）、巡检判定函数与阈值常量自洽；
3. `--ledger <csv> [--asof "..."]`：观察期问题台账的「按时响应」判定——每条发现按分级时限
   核对 respondedAt − reportedAt ≤ 时限；未响应的以 --asof（默认当前时刻）判「超时未响应」；
   全部 PASS 才算验收句达成。台账 = GitHub Issues（label HYPERCARE，OQ-122① 梯子）导出 CSV，
   列口径见 `docs/ops/hypercare-findings-template.csv`；
4. `--drill`（docker compose 的 MySQL）：在克隆真实 Flyway schema 的隔离库 `t_hypcdr`（建表
   LIKE t_demoa、结束整库删除，不碰共享演示数据）上自验巡检：空库基线六项全 PASS → 注入
   六类异常（登录失败激增 / 调度失败 / outbox 积压 / 通知失败 / 导入卡死 / 任务逾期占比）
   → 逐项检出 FAIL → 清场归零；
5. 生产巡检（由持账号运维每日执行，只读）：`--host/--port/--db/--user`——口令只经环境变量
   `CRDP_HYPERCARE_MYSQL_PASSWORD`（或 `MYSQL_PWD`）进 mysql 客户端，不进 argv、不落盘；
   对生产租户库执行 §6 巡检同款 SELECT。登录冒烟 / 入口探测 / 平台端租户状态属人工步骤。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNBOOK = ROOT / "docs/ops/hospital-a-hypercare.md"
TEMPLATE = ROOT / "docs/ops/hypercare-findings-template.csv"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
DOCS_README = ROOT / "docs/README.md"

# 演练环境（与 golive_import_verify.py 同一 compose MySQL；隔离库克隆自 t_demoa 真实 schema）
DRILL_SOURCE_DB = "t_demoa"
DRILL_DB = "t_hypcdr"
DRILL_MARKER = "HYPCDR"

# ---------------------------------------------------------------------------
# 响应时限表（runbook §3 与此处必须逐字一致，防漂移由默认模式校验）
# ---------------------------------------------------------------------------

# 机器判定口径 = 自然时钟分钟数（从严）：P2「2 小时」、P3「1 个工作日」按 8 小时 = 480 分钟从严。
SLA_RESPONSE_MINUTES: dict[str, int] = {"P0": 15, "P1": 30, "P2": 120, "P3": 480}
# 分级 ↔ GitHub Issue label（OQ-122②：不另立体系，与渗透 / UAT 共用同一四级梯子）
SEVERITY_TO_LABEL: dict[str, str] = {
    "P0": "severity:high",
    "P1": "severity:medium",
    "P2": "severity:low",
    "P3": "severity:info",
}

DATETIME_FMT = "%Y-%m-%d %H:%M"
FINDING_ID_PATTERN = re.compile(r"^HYPC-[A-Z0-9][A-Z0-9-]{1,23}$")
PHONE_PATTERN = re.compile(r"1[3-9]\d{9}")
IDCARD_PATTERN = re.compile(r"\d{17}[\dXx]")

# 台账列（runbook §5 清单与模板表头与此处必须一致）
LEDGER_COLUMNS = [
    "finding_id", "reported_at", "channel", "severity", "module", "summary",
    "triaged_at", "owner", "responded_at", "response_action", "resolved_at",
    "status", "issue_url",
]
CHANNELS = ("值班电话", "医院服务群", "巡检", "监控告警", "邮件")
OWNERS = ("全栈1", "全栈2", "运维", "产品", "测试", "医院")
STATUSES = ("OPEN", "RESPONDED", "RESOLVED")

# ---------------------------------------------------------------------------
# 观察期巡检（runbook §6；生产只读，--drill 同款）
# ---------------------------------------------------------------------------

LOGIN_WINDOW_HOURS = 24
LOGIN_FAILURE_LIMIT = 10  # 24 小时登录失败 > 10 → 安全线索（HC-D1）
JOBFAIL_WINDOW_DAYS = 1  # 当日与昨日调度非 SUCCESS（HC-D2）
OUTBOX_STALE_HOURS = 1  # PENDING 超过 1 小时 → 分发积压（HC-D3）
NOTIFY_WINDOW_HOURS = 24  # 通知发送失败（HC-D4）
IMP_STUCK_HOURS = 24  # UPLOADED/PRECHECKING 超过 24 小时未推进（HC-D5）
OVERDUE_RATIO_LIMIT = 20  # OVERDUE 任务占比 > 20%（HC-D6）

WRITE_PATTERN = re.compile(
    r"^\s*(insert|update|delete|replace|create|alter|drop|truncate|rename|grant"
    r"|revoke|lock|call|load|set|merge)\b",
    re.I,
)


def assert_readonly(sql: str) -> None:
    """非 SELECT / SHOW / WITH 开头的语句一律拒绝（生产租户库只读红线）。"""
    stripped = sql.strip().rstrip(";").strip()
    if not stripped or WRITE_PATTERN.match(stripped):
        raise AssertionError(f"巡检语句必须只读（SELECT / SHOW / WITH）：{stripped[:80]}")
    if ";" in stripped:
        raise AssertionError(f"巡检语句不允许多语句：{stripped[:80]}")


@dataclass
class Finding:
    check_id: str
    name: str
    ok: bool
    detail: str


# ---------------------------------------------------------------------------
# 台账（问题清单 CSV）校验与 SLA 判定
# ---------------------------------------------------------------------------


def parse_dt(value: str) -> datetime:
    return datetime.strptime(value.strip(), DATETIME_FMT)


def validate_ledger_row(row: dict[str, str], row_no: int, seen_ids: set[str]) -> list[str]:
    """单行结构校验；返回问题清单（每条带行号前缀）。"""
    problems: list[str] = []
    prefix = f"第 {row_no} 行"

    def bad(msg: str) -> None:
        problems.append(f"{prefix}{msg}")

    finding_id = (row.get("finding_id") or "").strip()
    if not FINDING_ID_PATTERN.match(finding_id):
        bad(f" finding_id 非法（应为 HYPC-W{周}-{序} 形态、字母数字与 -，2~24 位）：{finding_id!r}")
    elif finding_id in seen_ids:
        bad(f" finding_id 重复：{finding_id}")
    seen_ids.add(finding_id)

    severity = (row.get("severity") or "").strip()
    if severity not in SLA_RESPONSE_MINUTES:
        bad(f" severity 必须是 {'/'.join(SLA_RESPONSE_MINUTES)}：{severity!r}")
    channel = (row.get("channel") or "").strip()
    if channel not in CHANNELS:
        bad(f" channel 必须是 {'/'.join(CHANNELS)}：{channel!r}")
    owner = (row.get("owner") or "").strip()
    if owner not in OWNERS:
        bad(f" owner 必须是 {'/'.join(OWNERS)}：{owner!r}")

    summary = (row.get("summary") or "").strip()
    if not summary:
        bad(" summary 不能为空")
    elif len(summary) > 200:
        bad(" summary 超过 200 字")
    if PHONE_PATTERN.search(summary) or IDCARD_PATTERN.search(summary):
        bad(" summary 疑似含真实手机号 / 身份证号（AGENTS §5.5：真实数据不进仓库）")
    module = (row.get("module") or "").strip()
    if not module or len(module) > 64:
        bad(f" module 必须是非空、64 字以内：{module!r}")

    times: dict[str, datetime | None] = {}
    for col in ("reported_at", "triaged_at", "responded_at", "resolved_at"):
        raw = (row.get(col) or "").strip()
        if not raw:
            times[col] = None
            if col == "reported_at":
                bad(" reported_at 必填（YYYY-MM-DD HH:MM）")
            continue
        try:
            times[col] = parse_dt(raw)
        except ValueError:
            bad(f" {col} 时间格式应为 YYYY-MM-DD HH:MM：{raw!r}")
            times[col] = None

    reported = times["reported_at"]
    for col in ("triaged_at", "responded_at", "resolved_at"):
        if reported and times[col] and times[col] < reported:
            bad(f" {col} 早于 reported_at（时间倒序）")
    if times["resolved_at"] and not times["responded_at"]:
        bad(" resolved_at 有值但 responded_at 为空（未响应不能先解决）")

    responded_raw = (row.get("responded_at") or "").strip()
    action = (row.get("response_action") or "").strip()
    if responded_raw and not action:
        bad(" responded_at 有值时 response_action 必填（响应 = 受理 + 首个实质动作）")
    if action and len(action) > 120:
        bad(" response_action 超过 120 字")

    status = (row.get("status") or "").strip()
    if status not in STATUSES:
        bad(f" status 必须是 {'/'.join(STATUSES)}：{status!r}")
    else:
        if status in ("RESPONDED", "RESOLVED") and not responded_raw:
            bad(f" status={status} 但 responded_at 为空")
        if status == "RESOLVED" and not (row.get("resolved_at") or "").strip():
            bad(" status=RESOLVED 但 resolved_at 为空")
        if (row.get("resolved_at") or "").strip() and status != "RESOLVED":
            bad(f" resolved_at 有值但 status={status}（应为 RESOLVED）")

    issue_url = (row.get("issue_url") or "").strip()
    if issue_url and not issue_url.startswith("https://github.com/icedkong/crdp-platform/issues/"):
        bad(f" issue_url 必须指向本仓库 Issues：{issue_url!r}")

    return problems


def sla_outcome(row: dict[str, str], asof: datetime) -> tuple[bool, str]:
    """按分级时限判定单条「按时响应」。返回（是否按时，说明）。"""
    severity = row["severity"].strip()
    limit = timedelta(minutes=SLA_RESPONSE_MINUTES[severity])
    reported = parse_dt(row["reported_at"])
    label = f"{row['finding_id'].strip()}（{severity}，时限 {SLA_RESPONSE_MINUTES[severity]} 分钟）"
    responded_raw = (row.get("responded_at") or "").strip()
    if responded_raw:
        delta = parse_dt(responded_raw) - reported
        if delta <= limit:
            return True, f"{label}：{delta.total_seconds() / 60:.0f} 分钟内响应"
        return False, (f"{label}：超时响应 {delta.total_seconds() / 60:.0f} 分钟"
                       f" > 时限 {SLA_RESPONSE_MINUTES[severity]} 分钟")
    delta = asof - reported
    if delta <= limit:
        return True, f"{label}：待响应（已 {delta.total_seconds() / 60:.0f} 分钟，未超时）"
    return False, (f"{label}：超时未响应 {delta.total_seconds() / 60:.0f} 分钟"
                   f" > 时限 {SLA_RESPONSE_MINUTES[severity]} 分钟")


def load_ledger(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    """读台账 CSV（Excel 导出兼容 BOM），校验表头与逐行结构；返回（问题，行）。"""
    with path.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        fieldnames = [name.strip() for name in (reader.fieldnames or [])]
        if fieldnames != LEDGER_COLUMNS:
            return [f"表头与台账列不一致：{fieldnames}"], []
        rows = [{k.strip(): (v or "").strip() for k, v in row.items()} for row in reader]
    problems: list[str] = []
    seen: set[str] = set()
    for idx, row in enumerate(rows, start=2):  # 第 1 行表头
        problems.extend(validate_ledger_row(row, idx, seen))
    return problems, rows


def run_ledger_check(path: Path, asof: datetime) -> int:
    problems, rows = load_ledger(path)
    if problems:
        print("台账结构不合法：")
        for p in problems:
            print("  - " + p)
        return 1
    outcomes = [sla_outcome(row, asof) for row in rows]
    for row, (ok, detail) in zip(rows, outcomes):
        print(f"[{'PASS' if ok else 'FAIL'}] {detail}")
    failed = [detail for _, (ok, detail) in zip(rows, outcomes) if not ok]
    if failed:
        print(f"[E23-T13] 台账 {len(rows)} 条中 {len(failed)} 条未按时响应——按 runbook §4 升级处置")
        return 1
    print(f"[E23-T13] 台账 {len(rows)} 条全部按时响应（验收句「上线问题按时响应」的机器判定面）")
    return 0


# ---------------------------------------------------------------------------
# 巡检检查（生产与演练同一套；全部只读 SELECT；口径 = runbook §6）
# ---------------------------------------------------------------------------


def inspection_checks() -> list[dict[str, object]]:
    """六项观察期巡检。sql / sql2 为只读语句，judge(values) 产出 Finding。"""
    def judge_login(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-D1", "登录失败激增（24h > 10，安全线索）",
            count <= LOGIN_FAILURE_LIMIT,
            f"24 小时失败 {count} 次（阈值 {LOGIN_FAILURE_LIMIT}）；超限按 runbook §7 安全事件路径")

    def judge_job(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-D2", "调度作业失败（当日与昨日非 SUCCESS）",
            count == 0,
            f"失败 {count} 条；>0 按 job_tenant_run.error_text 排查并补跑（runbook §7）")

    def judge_outbox(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-D3", f"outbox 分发积压（PENDING > {OUTBOX_STALE_HOURS} 小时）",
            count == 0,
            f"超时 PENDING {count} 条；>0 检查分发作业（E09）并核对事件不丢（PLT-ORG-013）")

    def judge_notify(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-D4", "通知发送失败（24h FAILED）",
            count == 0,
            f"24 小时 FAILED {count} 条；>0 按 ntf_send_log.detail 排查短信 / 站内通道")

    def judge_import(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-D5", f"导入作业卡死或部分失败（UPLOADED/PRECHECKING > {IMP_STUCK_HOURS}h 或 PARTIAL_FAILED）",
            count == 0,
            f"异常作业 {count} 个；>0 按 imp_job.fail_reason 与 go-live runbook §7 / 导入回滚决策树处置")

    def judge_overdue(values: list[str]) -> Finding:
        overdue, total = int(values[0] or 0), int(values[1] or 0)
        if total == 0:
            return Finding("HC-D6", f"任务逾期占比 > {OVERDUE_RATIO_LIMIT}%", True,
                           "无任务，跳过占比（上线初期导入不建任务，A-PAT-018 流程 6）")
        ratio = overdue * 100 // total
        return Finding(
            "HC-D6", f"任务逾期占比 > {OVERDUE_RATIO_LIMIT}%",
            ratio <= OVERDUE_RATIO_LIMIT,
            f"OVERDUE {overdue}/{total}（{ratio}%，阈值 {OVERDUE_RATIO_LIMIT}%）；"
            f"超限查每日调度链（FollowupTaskScheduler）与通知提醒是否到位")

    return [
        {"id": "HC-D1", "sql": (
            f"SELECT COUNT(*) FROM aud_login_log "
            f"WHERE success = 0 AND created_at > NOW() - INTERVAL {LOGIN_WINDOW_HOURS} HOUR"),
         "judge": judge_login},
        {"id": "HC-D2", "sql": (
            "SELECT COUNT(*) FROM job_tenant_run "
            f"WHERE status <> 'SUCCESS' AND run_date >= CURDATE() - INTERVAL {JOBFAIL_WINDOW_DAYS} DAY"),
         "judge": judge_job},
        {"id": "HC-D3", "sql": (
            f"SELECT COUNT(*) FROM sys_outbox "
            f"WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL {OUTBOX_STALE_HOURS} HOUR"),
         "judge": judge_outbox},
        {"id": "HC-D4", "sql": (
            f"SELECT COUNT(*) FROM ntf_send_log "
            f"WHERE send_status = 'FAILED' AND created_at > NOW() - INTERVAL {NOTIFY_WINDOW_HOURS} HOUR"),
         "judge": judge_notify},
        {"id": "HC-D5", "sql": (
            "SELECT COUNT(*) FROM imp_job WHERE status = 'PARTIAL_FAILED' "
            f"OR (status IN ('UPLOADED', 'PRECHECKING') "
            f"AND updated_at < NOW() - INTERVAL {IMP_STUCK_HOURS} HOUR)"),
         "judge": judge_import},
        {"id": "HC-D6", "sql": "SELECT COUNT(*) FROM fu_task WHERE status = 'OVERDUE'",
         "sql2": "SELECT COUNT(*) FROM fu_task", "judge": judge_overdue},
    ]


def run_inspection(runner: "DrillRunner | RemoteRunner") -> list[Finding]:
    findings: list[Finding] = []
    for check in inspection_checks():
        sql = str(check["sql"])
        assert_readonly(sql)
        values = [runner.scalar(sql)]
        if "sql2" in check:
            sql2 = str(check["sql2"])
            assert_readonly(sql2)
            values.append(runner.scalar(sql2))
        findings.append(check["judge"](values))  # type: ignore[operator]
    return findings


def print_findings(findings: list[Finding], prefix: str) -> bool:
    for f in findings:
        print(f"[{'PASS' if f.ok else 'FAIL'}] {f.check_id} {f.name}——{f.detail}")
    ok = all(f.ok for f in findings)
    print(f"{prefix}巡检{'全部通过' if ok else '存在异常项，按 runbook §4/§7 处置'}")
    return ok


# ---------------------------------------------------------------------------
# SQL 执行器：drill（compose MySQL，隔离库）与生产（mysql 客户端，口令经环境变量）
# ---------------------------------------------------------------------------


class DrillRunner:
    """compose MySQL。夹具经 exec_fixture（仅 --drill 路径，全限定表名、不预选库——
    隔离库首次创建前尚不存在，不能作为连接默认库）；巡检只读、以 -D 选隔离库。"""

    def __init__(self, db: str = DRILL_DB) -> None:
        self.db = db

    def _exec(self, sql: str, db: str | None = None) -> str:
        db_arg = f"-D {db}" if db else ""
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
             f'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B {db_arg}'],
            cwd=ROOT, input=sql, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        return proc.stdout

    def exec_fixture(self, sql: str) -> None:
        """建库 / 建表 / 注入 / 清理（DDL 与 DML，仅 --drill 允许；全限定表名）。"""
        self._exec(sql)

    def scalar(self, sql: str) -> str:
        assert_readonly(sql)
        out = self._exec(sql, db=self.db)
        lines = [line for line in out.splitlines() if line]
        return lines[0].split("\t")[0] if lines else ""


class RemoteRunner:
    """生产租户库（只读巡检）。口令只经 CRDP_HYPERCARE_MYSQL_PASSWORD / MYSQL_PWD 环境变量。"""

    def __init__(self, host: str, port: str, db: str, user: str) -> None:
        self.host = host
        self.port = port
        self.db = db
        self.user = user
        password = os.environ.get("CRDP_HYPERCARE_MYSQL_PASSWORD") or os.environ.get("MYSQL_PWD")
        if not password:
            raise SystemExit(
                "缺少数据库口令：请设置环境变量 CRDP_HYPERCARE_MYSQL_PASSWORD（只进 mysql 客户端"
                "进程环境，不进 argv、不落盘；runbook §6）")
        self.env = {**os.environ, "MYSQL_PWD": password}

    def scalar(self, sql: str) -> str:
        assert_readonly(sql)
        proc = subprocess.run(
            ["mysql", f"-h{self.host}", f"-P{self.port}", f"-u{self.user}",
             "-N", "-B", "-e", sql, self.db],
            env=self.env, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        lines = [line for line in proc.stdout.splitlines() if line]
        return lines[0].split("\t")[0] if lines else ""


# ---------------------------------------------------------------------------
# --drill：隔离库（LIKE 克隆真实 schema）基线全 PASS → 注入六类异常逐项检出 → 整库清场
# ---------------------------------------------------------------------------


def expect(findings: list[Finding], check_id: str, ok_expected: bool, step: str) -> list[str]:
    problems: list[str] = []
    hit = [f for f in findings if f.check_id == check_id]
    if not hit:
        return [f"{step}：未产出检查项 {check_id}"]
    for f in hit:
        if f.ok != ok_expected:
            problems.append(f"{step}：{check_id} 预期{'PASS' if ok_expected else 'FAIL'}"
                            f" 实际{'PASS' if f.ok else 'FAIL'}（{f.detail}）")
    return problems


def drill_clone_sql() -> str:
    tables = ("aud_login_log", "job_tenant_run", "sys_outbox", "ntf_send_log", "imp_job", "fu_task")
    likes = "\n".join(
        f"CREATE TABLE {DRILL_DB}.{t} LIKE {DRILL_SOURCE_DB}.{t};" for t in tables)
    return f"DROP DATABASE IF EXISTS {DRILL_DB};\nCREATE DATABASE {DRILL_DB} CHARACTER SET utf8mb4;\n{likes}"


def drill_inject_sql() -> str:
    return f"""
INSERT INTO {DRILL_DB}.aud_login_log (username, success, message)
SELECT '{DRILL_MARKER}（示例）', 0, '演练注入失败（示例）' FROM {DRILL_SOURCE_DB}.sys_user
 LIMIT {LOGIN_FAILURE_LIMIT + 1};
INSERT INTO {DRILL_DB}.job_tenant_run (job_code, run_date, status, started_at, finished_at, error_text)
VALUES ('{DRILL_MARKER}_JOB', CURDATE(), 'FAILED', NOW(), NOW(), '{DRILL_MARKER}（示例）调度失败注入');
INSERT INTO {DRILL_DB}.sys_outbox (event_type, payload, status, created_at)
VALUES ('{DRILL_MARKER}_OUTBOX_STUCK', '{{}}', 'PENDING', NOW() - INTERVAL 2 HOUR);
INSERT INTO {DRILL_DB}.ntf_send_log (event_type, recipient_id, channel, send_status, title, detail, dedup_key)
VALUES ('{DRILL_MARKER}_NT', 1, 'INBOX', 'FAILED', '{DRILL_MARKER}（示例）通知', '演练注入失败（示例）',
        '{DRILL_MARKER}-DEDUP-1');
INSERT INTO {DRILL_DB}.imp_job (file_name, file_size, status, operator_id, operator_name, created_at, updated_at)
VALUES ('{DRILL_MARKER}-卡死作业（示例）.xlsx', 1024, 'PRECHECKING', 1, '{DRILL_MARKER}（示例）',
        NOW() - INTERVAL 25 HOUR, NOW() - INTERVAL 25 HOUR);
INSERT INTO {DRILL_DB}.fu_task (enrollment_id, patient_id, plan_id, node_id, occurrence, task_type,
                     owner_type, plan_date, remind_start, window_start, window_end, open_date,
                     create_type, status, created_logical_date, lock_version)
VALUES (990001, 990001, 1, 990001, 1, 'FORM', 'WARD_DEFAULT', CURDATE() - INTERVAL 30 DAY,
        CURDATE() - INTERVAL 37 DAY, CURDATE() - INTERVAL 37 DAY, CURDATE() - INTERVAL 23 DAY,
        CURDATE() - INTERVAL 37 DAY, 'NORMAL', 'OVERDUE', CURDATE() - INTERVAL 37 DAY, 0);
"""


def run_drill() -> int:
    runner = DrillRunner()
    problems: list[str] = []

    # 阶段 1：克隆真实 schema 的隔离空库 → 基线六项全 PASS
    runner.exec_fixture(drill_clone_sql())
    findings = run_inspection(runner)
    problems += expect(findings, "HC-D1", True, "基线")
    problems += expect(findings, "HC-D2", True, "基线")
    problems += expect(findings, "HC-D3", True, "基线")
    problems += expect(findings, "HC-D4", True, "基线")
    problems += expect(findings, "HC-D5", True, "基线")
    problems += expect(findings, "HC-D6", True, "基线")
    if not print_findings(findings, "[演练·基线] "):
        problems.append("基线阶段不应有 FAIL")

    # 阶段 2：注入六类异常（全部 {DRILL_MARKER} 标记、AGENTS §5.5 虚构）→ 逐项检出
    runner.exec_fixture(drill_inject_sql())
    findings = run_inspection(runner)
    problems += expect(findings, "HC-D1", False, "注入")
    problems += expect(findings, "HC-D2", False, "注入")
    problems += expect(findings, "HC-D3", False, "注入")
    problems += expect(findings, "HC-D4", False, "注入")
    problems += expect(findings, "HC-D5", False, "注入")
    problems += expect(findings, "HC-D6", False, "注入")
    if print_findings(findings, "[演练·注入] "):
        problems.append("注入阶段不应全 PASS")

    # 阶段 3：整库清场并确认归零（隔离库不碰共享演示数据）
    runner.exec_fixture(f"DROP DATABASE IF EXISTS {DRILL_DB};")
    residue = runner._exec(
        f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '{DRILL_DB}'")
    if residue.strip() != "0":
        problems.append(f"清场失败：{DRILL_DB} 仍存在")

    for p in problems:
        print("[FAIL] " + p)
    if problems:
        print(f"[E23-T13] 巡检演练未通过（{len(problems)} 项）")
        return 1
    print("[E23-T13] 巡检演练通过：基线全 PASS、六类异常逐项检出、清场归零")
    return 0


# ---------------------------------------------------------------------------
# --selftest：纯逻辑自检（不联网、不连库）
# ---------------------------------------------------------------------------


def run_selftest() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    def must_fail(fn, *args, **kwargs) -> bool:
        try:
            fn(*args, **kwargs)
            return False
        except Exception:
            return True

    # 时限表：分级齐、单调不降、label 映射齐
    minutes = [SLA_RESPONSE_MINUTES[s] for s in ("P0", "P1", "P2", "P3")]
    check("时限表覆盖 P0~P3 且逐级放宽", minutes == sorted(minutes) and len(set(minutes)) == 4)
    check("分级 ↔ severity label 映射齐（OQ-122②梯子）",
          set(SEVERITY_TO_LABEL) == set(SLA_RESPONSE_MINUTES)
          and set(SEVERITY_TO_LABEL.values()) == {"severity:high", "severity:medium",
                                                  "severity:low", "severity:info"})

    # SLA 判定：按时 / 边界 / 超时 / 未响应未超时 / 未响应超时
    base = {"finding_id": "HYPC-W36-01", "reported_at": "2027-07-20 09:00",
            "severity": "P0"}
    check("P0 14 分钟内响应 = 按时",
          sla_outcome({**base, "responded_at": "2027-07-20 09:14"}, datetime(2027, 7, 21))[0])
    check("P0 恰 15 分钟（边界）= 按时",
          sla_outcome({**base, "responded_at": "2027-07-20 09:15"}, datetime(2027, 7, 21))[0])
    check("P0 16 分钟 = 超时响应",
          not sla_outcome({**base, "responded_at": "2027-07-20 09:16"}, datetime(2027, 7, 21))[0])
    check("未响应 10 分钟 = 待响应（未超时）",
          sla_outcome(base, datetime(2027, 7, 20, 9, 10))[0])
    check("未响应 16 分钟 = 超时未响应",
          not sla_outcome(base, datetime(2027, 7, 20, 9, 16))[0])
    check("P3 480 分钟口径（1 个工作日从严）",
          sla_outcome({**base, "severity": "P3", "responded_at": "2027-07-20 17:00"},
                      datetime(2027, 7, 21))[0]
          and not sla_outcome({**base, "severity": "P3", "responded_at": "2027-07-20 17:01"},
                              datetime(2027, 7, 21))[0])

    # 台账行校验：各拦截面
    good = {
        "finding_id": "HYPC-W36-01", "reported_at": "2027-07-20 09:00", "channel": "医院服务群",
        "severity": "P1", "module": "task", "summary": "任务列表打不开（示例）",
        "triaged_at": "", "owner": "全栈1", "responded_at": "2027-07-20 09:20",
        "response_action": "分诊结论：定责全栈1，修复中", "resolved_at": "2027-07-21 10:00",
        "status": "RESOLVED",
        "issue_url": "https://github.com/icedkong/crdp-platform/issues/301",
    }
    check("合法行通过校验", validate_ledger_row(dict(good), 2, set()) == [])
    check("非法 severity 拦截",
          any("severity" in p for p in validate_ledger_row(
              {**good, "severity": "P9"}, 2, set())))
    check("疑似真实手机号拦截（AGENTS §5.5）",
          any("手机号" in p for p in validate_ledger_row(
              {**good, "summary": "患者 13812345678 反馈打不开（示例）"}, 2, set())))
    check("responded_at 早于 reported_at 拦截",
          any("时间倒序" in p for p in validate_ledger_row(
              {**good, "responded_at": "2027-07-20 08:00"}, 2, set())))
    check("重复 finding_id 拦截",
          any("重复" in p for p in validate_ledger_row(
              dict(good), 2, {"HYPC-W36-01"})))
    check("响应缺实质动作拦截", any("response_action" in p for p in validate_ledger_row(
        {**good, "response_action": ""}, 2, set())))
    check("status 与时间列矛盾拦截（RESOLVED 缺 resolved_at）",
          any("resolved_at" in p for p in validate_ledger_row(
              {**good, "resolved_at": ""}, 2, set())))
    check("issue_url 非本仓库拦截",
          any("issue_url" in p for p in validate_ledger_row(
              {**good, "issue_url": "https://example.com/1"}, 2, set())))
    check("时间格式非法拦截", any("reported_at" in p for p in validate_ledger_row(
        {**good, "reported_at": "2027/07/20"}, 2, set())))

    # 只读护栏：巡检语句全 SELECT；写语句样例被拒
    for item in inspection_checks():
        check(f"巡检 {item['id']} 语句只读", must_fail(assert_readonly, str(item["sql"])) is False)
        if "sql2" in item:
            check(f"巡检 {item['id']} 辅助语句只读",
                  must_fail(assert_readonly, str(item["sql2"])) is False)
    check("写语句被只读护栏拒绝", must_fail(assert_readonly, "DELETE FROM fu_task"))
    check("多语句被只读护栏拒绝",
          must_fail(assert_readonly, "SELECT 1; DELETE FROM fu_task"))

    # 巡检判定函数：阈值边界
    d1 = next(c for c in inspection_checks() if c["id"] == "HC-D1")["judge"](["10"])
    d1b = next(c for c in inspection_checks() if c["id"] == "HC-D1")["judge"](["11"])
    check("HC-D1 阈值边界（10 通过 / 11 检出）",
          d1.ok and not d1b.ok)  # type: ignore[attr-defined]
    d6 = next(c for c in inspection_checks() if c["id"] == "HC-D6")["judge"](["0", "0"])
    d6b = next(c for c in inspection_checks() if c["id"] == "HC-D6")["judge"](["1", "4"])
    check("HC-D6 空库跳过 / 25% 检出", d6.ok and not d6b.ok)  # type: ignore[attr-defined]
    check("阈值常量为正", all(v > 0 for v in (
        LOGIN_FAILURE_LIMIT, OUTBOX_STALE_HOURS, NOTIFY_WINDOW_HOURS,
        IMP_STUCK_HOURS, OVERDUE_RATIO_LIMIT)))

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    if failed:
        print(f"[E23-T13] 自检未通过（{len(failed)} 项）")
        return 1
    print(f"[E23-T13] 自检通过（{len(checks)} 项）")
    return 0


# ---------------------------------------------------------------------------
# 默认模式：runbook 防漂移校验
# ---------------------------------------------------------------------------


def e23_t13_row() -> dict[str, str] | None:
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T13":
                return row
    return None


def run_doc_checks() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    runbook = RUNBOOK.read_text(encoding="utf-8") if RUNBOOK.exists() else ""
    check("runbook 存在（docs/ops/hospital-a-hypercare.md）", bool(runbook))

    row = e23_t13_row()
    check("tasks.csv 有 E23-T13 行", row is not None)
    title = (row or {}).get("标题", "")
    accept = (row or {}).get("验收标准", "")
    deps = (row or {}).get("依赖", "")
    check("标题 W36–W37 与 tasks.csv 一致",
          "W36–W37" in title and "W36–W37" in runbook)
    check("验收原句在 runbook（上线问题按时响应）", accept and accept in runbook)
    check("依赖 E23-T11 在 runbook（承接 go-live §8 衔接）",
          deps == "E23-T11" and "E23-T11" in runbook)

    # 互引文档存在且被引用
    for doc in (
        "hospital-a-go-live.md",
        "hospital-a-import-rollback.md",
        "tenant-restore.md",
        "cross-region-drill.md",
        "cce-prod.md",
        "uat-hospital-a.md",
        "uat-remediation-hospital-a-fullstack1.md",
    ):
        path = ROOT / "docs/ops" / doc
        if not path.exists():
            path = ROOT / "docs/testing" / doc
        check(f"引用的既有文档存在且被引用：{doc}", path.exists() and doc in runbook)

    # 承接 go-live runbook 的观察期与中止预案（§8 对本任务的输入）
    check("承接 go-live §6 观察期与 §7 中止预案",
          "观察期" in runbook and "中止" in runbook)

    # 时限表：数字与脚本常量逐字一致
    for sev in ("P0", "P1", "P2", "P3"):
        check(f"时限表 {sev} = {SLA_RESPONSE_MINUTES[sev]} 分钟（与脚本一致）",
              f"{SLA_RESPONSE_MINUTES[sev]} 分钟" in runbook)
    check("时限表机器判定口径（自然时钟从严）", "从严" in runbook)
    check("分级映射 severity label（OQ-122②）",
          all(label in runbook for label in SEVERITY_TO_LABEL.values())
          and "OQ-122" in runbook)

    # 台账：列清单、模板、编号段、Issue 载体
    check("台账列清单与脚本 LEDGER_COLUMNS 一致",
          all(col in runbook for col in LEDGER_COLUMNS))
    check("模板存在且表头与 LEDGER_COLUMNS 一致",
          TEMPLATE.exists()
          and [c.strip() for c in TEMPLATE.read_text(encoding="utf-8-sig")
               .splitlines()[0].split(",")] == LEDGER_COLUMNS)
    check("编号段 HYPC- 与 GitHub Issues 载体（label HYPERCARE）",
          "HYPC-" in runbook and "HYPERCARE" in runbook and "GitHub Issues" in runbook)

    # 处置流程：三选一口径与升级
    check("处置三选一口径（修复 / 缓解 / 延期）",
          "修复" in runbook and "缓解" in runbook and "延期" in runbook)
    check("超时升级路径在册", "升级" in runbook)

    # 巡检：六项检查与阈值
    for check_id in ("HC-D1", "HC-D2", "HC-D3", "HC-D4", "HC-D5", "HC-D6"):
        check(f"巡检检查项在册：{check_id}", check_id in runbook)
    check("巡检阈值在册（登录失败 10 / outbox 1 小时 / 导入卡死 24 小时 / 逾期 20%）",
          f"> {LOGIN_FAILURE_LIMIT}" in runbook and f"> {OUTBOX_STALE_HOURS} 小时" in runbook
          and f"> {IMP_STUCK_HOURS} 小时" in runbook and f"> {OVERDUE_RATIO_LIMIT}%" in runbook)
    check("工具用法（--selftest / --ledger / --drill）",
          "--selftest" in runbook and "--ledger" in runbook and "--drill" in runbook)
    check("口令只经环境变量（不进 argv / 不落盘）",
          "CRDP_HYPERCARE_MYSQL_PASSWORD" in runbook and "不落盘" in runbook)

    # 分担面与并行任务、退出判据、红线
    check("全栈 1 分担面（承接 UAT A/B/C/D/E/F/G/K/L 分界）",
          "A/B/C/D/E/F/G/K/L" in runbook)
    check("与 E23-T14（全栈 2）分界互引", "E23-T14" in runbook)
    check("观察期退出判据在册", "退出" in runbook)
    check("真实数据红线（不进仓库 / CI）", "不进仓库" in runbook)

    # CI 接线与索引
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接线（--selftest / 默认防漂移 / --drill 三步）",
          "hypercare_verify.py --selftest" in ci
          and "hypercare_verify.py --drill" in ci
          and ci.count("hypercare_verify.py") >= 3)
    readme = DOCS_README.read_text(encoding="utf-8") if DOCS_README.exists() else ""
    check("docs/README.md 已索引 runbook", "hospital-a-hypercare.md" in readme)

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    if failed:
        print(f"[E23-T13] runbook 防漂移校验未通过（{len(failed)} 项）")
        return 1
    print("[E23-T13] runbook 防漂移校验通过")
    return 0


# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true", help="纯逻辑自检（不联网、不连库）")
    parser.add_argument("--drill", action="store_true",
                        help="compose MySQL 巡检演练（隔离库基线全 PASS + 六类异常逐项检出 + 清场）")
    parser.add_argument("--ledger", help="问题台账 CSV 路径（runbook §5，GitHub Issues 导出）")
    parser.add_argument("--asof", help="未响应条目的判定时刻（YYYY-MM-DD HH:MM，默认当前）")
    parser.add_argument("--host", help="生产租户库地址（只读巡检，runbook §6）")
    parser.add_argument("--port", default="3306", help="端口（默认 3306）")
    parser.add_argument("--db", help="租户库名（t_租户编码）")
    parser.add_argument("--user", help="数据库账号（只读）")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    if args.drill:
        return run_drill()
    if args.ledger:
        asof = parse_dt(args.asof) if args.asof else datetime.now()
        return run_ledger_check(Path(args.ledger), asof)
    if args.host or args.db or args.user:
        missing = [name for name, value in (
            ("--host", args.host), ("--db", args.db), ("--user", args.user))
            if not value]
        if missing:
            parser.error(f"生产巡检缺参数：{'、'.join(missing)}")
        runner = RemoteRunner(args.host, args.port, args.db, args.user)
        findings = run_inspection(runner)
        ok = print_findings(findings, "[E23-T13] 生产观察期")
        return 0 if ok else 1
    return run_doc_checks()


if __name__ == "__main__":
    sys.exit(main())
