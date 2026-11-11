#!/usr/bin/env python3
"""医院 A 上线后运行保障（全栈 2 分担面）：SLA 台账判定、分担面巡检与另册防漂移校验（E23-T14 · W36–W37）。

任务口径（`docs/tasks/tasks.csv` E23-T14：全栈 2，依赖 E23-T11，验收「上线问题按时响应」）：
与全栈 1 手册（`docs/ops/hospital-a-hypercare.md`，E23-T13）同窗同构——值班 / 分级 / 台账 /
编号段 HYPC- 全部共用那一册，不另立口径；本工具交付全栈 2 分担面（**H 报告单与资料 /
I 统计与首页 / J 历史数据导入核对 / M 审计与平台视角**）的机器可判定部分：

1. 默认（不连库、不联网）：另册 runbook（`docs/ops/hospital-a-hypercare-fullstack2.md`）
   防漂移校验——验收原句与依赖、时限表（§3）与 `hypercare_verify.SLA_RESPONSE_MINUTES`
   逐字一致且**双册互锁**（全栈 1 册同数字）、台账列与共用模板一致、分担面巡检项
   （HC-H1~HC-M2）与阈值在册、互引文档存在、ci.yml 接线、docs/README.md 索引；
2. `--selftest`（不联网、不连库）：纯逻辑自检——与全栈 1 工具常量一致（SLA / 台账列 /
   编号形态）、六项分担面巡检判定边界、台账校验复用（`hypercare_verify` 同一套）、
   台账范围过滤（owner=全栈2 / --all）、只读护栏；
3. `--ledger <csv> [--asof "..."] [--all]`：共用台账的「按时响应」判定——默认只判
   owner=全栈 2 的分担面条目（退出评审加 `--all` 判全量，结果应与全栈 1 工具一致）；
4. `--drill`（docker compose 的 MySQL）：在克隆真实 Flyway schema 的两个隔离库上自验
   分担面巡检——租户库 `t_hypcd2`（LIKE t_demoa 建表，六类：附件断链 / 上传滞留 /
   统计停更 / 导入批次卡死 / 审计断写）+ 平台库 `crdp_hypcd2`（视角会话超时未收口）：
   基线六项全 PASS → 注入异常逐项检出 FAIL → 清场归零（不碰共享演示数据）；
5. 生产巡检（由持账号运维每日执行，只读）：`--host/--port/--db/--user [--platform-db]`——
   口令只经环境变量 `CRDP_HYPERCARE_MYSQL_PASSWORD`（或 `MYSQL_PWD`）进 mysql 客户端，
   不进 argv、不落盘；租户库执行 HC-H1~HC-M1、平台库执行 HC-M2（同一只读账号需两库授权）。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import csv
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

# 复用全栈 1 工具（同目录）：SLA 时限表 / 分级 label 映射 / 台账列与行校验 / SLA 判定 /
# 只读护栏 / Finding——两册共用同一套口径，复用即防漂移（--selftest 锁定两处常量相等）。
sys.path.insert(0, str(Path(__file__).resolve().parent))
import hypercare_verify as shared  # noqa: E402

Finding = shared.Finding
assert_readonly = shared.assert_readonly
parse_dt = shared.parse_dt
sla_outcome = shared.sla_outcome
load_ledger = shared.load_ledger
SLA_RESPONSE_MINUTES = shared.SLA_RESPONSE_MINUTES
SEVERITY_TO_LABEL = shared.SEVERITY_TO_LABEL
LEDGER_COLUMNS = shared.LEDGER_COLUMNS

ROOT = Path(__file__).resolve().parent.parent
RUNBOOK = ROOT / "docs/ops/hospital-a-hypercare-fullstack2.md"
PEER_RUNBOOK = ROOT / "docs/ops/hospital-a-hypercare.md"  # 全栈 1 册（共用口径的定义处）
TEMPLATE = ROOT / "docs/ops/hypercare-findings-template.csv"  # 共用台账模板（同一份）
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
DOCS_README = ROOT / "docs/README.md"

# 演练隔离库（租户侧克隆自 t_demoa，平台侧克隆自 crdp_platform；结束整库删除）
DRILL_SOURCE_DB = "t_demoa"
DRILL_DB = "t_hypcd2"
DRILL_PLATFORM_SOURCE_DB = "crdp_platform"
DRILL_PLATFORM_DB = "crdp_hypcd2"
DRILL_MARKER = "HYPCD2"

# 全栈 2 分担面（runbook §5；module 词表 = uat_intake.MODULES 的子集，M 面落 other）
SURFACE_MODULES = {"report": "H 报告单与资料", "stats": "I 统计与首页", "import": "J 历史数据导入核对"}
SURFACE_LETTERS = "H/I/J/M"
OWNER_FS2 = "全栈2"

# ---------------------------------------------------------------------------
# 分担面巡检阈值（runbook §6 与此处必须一致，防漂移由默认模式校验）
# ---------------------------------------------------------------------------

FILE_PENDING_STALE_HOURS = 24  # sys_file PENDING 超 24 小时未关联（HC-H2）
STATS_STALE_DAYS = 1  # st_node_occurrence MAX(refreshed_date) 落后当日超 1 天（HC-I1）
BATCH_STUCK_HOURS = 24  # imp_batch PENDING/RUNNING 超 24 小时未推进（HC-J1）
AUDIT_QUIET_HOURS = 24  # 操作审计与登录审计双零窗口（HC-M1）
VIEW_GRACE_HOURS = 1  # 视角会话 ends_at 后宽限（HC-M2；上限本身 = started_at + 2 小时）


# ---------------------------------------------------------------------------
# 分担面巡检（生产与演练同一套；全部只读 SELECT；口径 = runbook §6）
# ---------------------------------------------------------------------------


def surface_checks() -> list[dict[str, object]]:
    """租户库五项分担面巡检（HC-H1/H2、HC-I1、HC-J1、HC-M1）。"""

    def judge_link(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-H1", "报告单附件断链（挂接文件缺失或已删除）",
            count == 0,
            f"断链附件 {count} 个；>0 查 sys_file 状态与对象键（OBS 桶 iac/obs-archive），"
            f"预览将 404（runbook §7 H 面行）")

    def judge_pending(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-H2", f"上传文件滞留（PENDING > {FILE_PENDING_STALE_HOURS}h 未关联报告单）",
            count == 0,
            f"滞留文件 {count} 个；>0 查上传→保存链路是否中断（H-1/H-2 口径，runbook §7 H 面行）")

    def judge_stats(values: list[str]) -> Finding:
        total, lag = int(values[0] or 0), values[1]
        if total == 0:
            return Finding("HC-I1", f"统计事实表停更（MAX(refreshed_date) 落后 > {STATS_STALE_DAYS} 天）",
                           True, "统计表无行，跳过（新库预期，Q38 口径；观察期生产库导入后应有行）")
        lag_days = int(lag) if lag not in ("", "NULL", None) else 999
        return Finding(
            "HC-I1", f"统计事实表停更（MAX(refreshed_date) 落后 > {STATS_STALE_DAYS} 天）",
            lag_days <= STATS_STALE_DAYS,
            f"共 {total} 行，刷新日落后 {lag_days} 天（阈值 {STATS_STALE_DAYS}）；"
            f"超限查 st-node-refresh 作业（HC-D2 同源，每日 00:10 整表重建）")

    def judge_batch(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-J1", f"导入批次卡死（PENDING/RUNNING > {BATCH_STUCK_HOURS}h 未推进）",
            count == 0,
            f"卡死批次 {count} 个；>0 按 imp_batch.fail_reason 排查，配合 HC-D5 与"
            f" 导入回滚决策树（runbook §7 J 面行）")

    def judge_audit(values: list[str]) -> Finding:
        ops, logins = int(values[0] or 0), int(values[1] or 0)
        return Finding(
            "HC-M1", f"审计断写（{AUDIT_QUIET_HOURS}h 操作审计与登录审计双零）",
            ops > 0 or logins > 0,
            f"近 {AUDIT_QUIET_HOURS} 小时操作审计 {ops} 行、登录审计 {logins} 行；双零 = 审计管道"
            f"停摆或全站无活动（观察期两者都异常，按 P1 研判，runbook §7 M 面行）")

    return [
        {"id": "HC-H1", "sql": (
            "SELECT COUNT(*) FROM rpt_report_file rf "
            "LEFT JOIN sys_file f ON f.id = rf.file_id "
            "WHERE f.id IS NULL OR f.status <> 'ATTACHED'"),
         "judge": judge_link},
        {"id": "HC-H2", "sql": (
            f"SELECT COUNT(*) FROM sys_file "
            f"WHERE status = 'PENDING' AND uploaded_at < NOW() - INTERVAL {FILE_PENDING_STALE_HOURS} HOUR"),
         "judge": judge_pending},
        {"id": "HC-I1", "sql": "SELECT COUNT(*) FROM st_node_occurrence",
         "sql2": "SELECT DATEDIFF(CURDATE(), MAX(refreshed_date)) FROM st_node_occurrence",
         "judge": judge_stats},
        {"id": "HC-J1", "sql": (
            "SELECT COUNT(*) FROM imp_batch "
            f"WHERE status IN ('PENDING', 'RUNNING') AND updated_at < NOW() - INTERVAL {BATCH_STUCK_HOURS} HOUR"),
         "judge": judge_batch},
        {"id": "HC-M1", "sql": (
            f"SELECT COUNT(*) FROM aud_log WHERE created_at > NOW() - INTERVAL {AUDIT_QUIET_HOURS} HOUR"),
         "sql2": (
             f"SELECT COUNT(*) FROM aud_login_log WHERE created_at > NOW() - INTERVAL {AUDIT_QUIET_HOURS} HOUR"),
         "judge": judge_audit},
    ]


def platform_checks() -> list[dict[str, object]]:
    """平台库一项分担面巡检（HC-M2，平台视角；库 = crdp_platform 或 --platform-db）。"""

    def judge_view(values: list[str]) -> Finding:
        count = int(values[0] or 0)
        return Finding(
            "HC-M2", f"视角会话超时未收口（ACTIVE 过 ends_at + {VIEW_GRACE_HOURS}h）",
            count == 0,
            f"超时会话 {count} 个；>0 查视角超时清扫（PLT-VIEW-004 上限 2 小时），"
            f"会话级联与双侧审计按 M-2 口径复核（runbook §7 M 面行）")

    return [
        {"id": "HC-M2", "sql": (
            "SELECT COUNT(*) FROM plt_view_session "
            f"WHERE status = 'ACTIVE' AND ends_at < NOW() - INTERVAL {VIEW_GRACE_HOURS} HOUR"),
         "judge": judge_view},
    ]


def run_checks(runner, checks: list[dict[str, object]]) -> list[Finding]:
    findings: list[Finding] = []
    for check in checks:
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
    print(f"{prefix}分担面巡检{'全部通过' if ok else '存在异常项，按 runbook §4/§7 处置'}")
    return ok


# ---------------------------------------------------------------------------
# SQL 执行器：drill（compose MySQL，两个隔离库）与生产（mysql 客户端，口令经环境变量）
# ---------------------------------------------------------------------------


class DrillRunner:
    """compose MySQL。夹具经 exec_fixture（仅 --drill 路径，DDL/DML、全限定表名）；
    巡检只读、以 -D 选隔离库（租户 / 平台两个）。"""

    def __init__(self, db: str) -> None:
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
        self._exec(sql)

    def scalar(self, sql: str) -> str:
        assert_readonly(sql)
        out = self._exec(sql, db=self.db)
        lines = [line for line in out.splitlines() if line]
        return lines[0].split("\t")[0] if lines else ""


class RemoteRunner:
    """生产（只读巡检）。口令只经 CRDP_HYPERCARE_MYSQL_PASSWORD / MYSQL_PWD 环境变量。"""

    def __init__(self, host: str, port: str, db: str, user: str) -> None:
        self.host, self.port, self.db, self.user = host, port, db, user
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
# --drill：两个隔离库（LIKE 克隆真实 schema）基线全 PASS → 注入异常逐项检出 → 清场
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
    tenant_tables = ("rpt_report_file", "sys_file", "st_node_occurrence", "imp_batch",
                     "aud_log", "aud_login_log")
    likes = "\n".join(
        f"CREATE TABLE {DRILL_DB}.{t} LIKE {DRILL_SOURCE_DB}.{t};" for t in tenant_tables)
    return (
        f"DROP DATABASE IF EXISTS {DRILL_DB};\n"
        f"CREATE DATABASE {DRILL_DB} CHARACTER SET utf8mb4;\n{likes}\n"
        f"DROP DATABASE IF EXISTS {DRILL_PLATFORM_DB};\n"
        f"CREATE DATABASE {DRILL_PLATFORM_DB} CHARACTER SET utf8mb4;\n"
        f"CREATE TABLE {DRILL_PLATFORM_DB}.plt_view_session "
        f"LIKE {DRILL_PLATFORM_SOURCE_DB}.plt_view_session;")


def drill_baseline_seed_sql() -> str:
    """基线活动种子：HC-M1 需要近 24 小时审计行（观察期生产库天然有，空克隆库要播种；
    克隆表无触发器，注入阶段可直接 UPDATE 造「停写」）。"""
    return f"""
INSERT INTO {DRILL_DB}.aud_log (event_type, actor_id, action, target, detail)
VALUES ('{DRILL_MARKER}_OPS', 1, 'INSPECT', '{DRILL_MARKER}（示例）', '演练基线审计行（示例）');
INSERT INTO {DRILL_DB}.aud_login_log (username, success, message)
VALUES ('{DRILL_MARKER}（示例）', 1, '演练基线登录成功（示例）');
"""


def drill_inject_sql() -> str:
    return f"""
INSERT INTO {DRILL_DB}.sys_file (object_key, original_name, format_code, mime_type, size_bytes,
                        sha256, status, uploader_id, uploaded_at)
VALUES ('{DRILL_MARKER}/deleted.pdf', '{DRILL_MARKER}-已删附件（示例）.pdf', 'PDF', 'application/pdf',
        1024, '{DRILL_MARKER}-SHA', 'DELETED', 1, NOW() - INTERVAL 2 HOUR);
INSERT INTO {DRILL_DB}.rpt_report_file (report_id, patient_id, file_id, sort_no)
VALUES (990001, 990001, LAST_INSERT_ID(), 0);
INSERT INTO {DRILL_DB}.sys_file (object_key, original_name, format_code, mime_type, size_bytes,
                        sha256, status, uploader_id, uploaded_at)
VALUES ('{DRILL_MARKER}/pending.pdf', '{DRILL_MARKER}-滞留附件（示例）.pdf', 'PDF', 'application/pdf',
        2048, '{DRILL_MARKER}-SHA2', 'PENDING', 1, NOW() - INTERVAL 25 HOUR);
INSERT INTO {DRILL_DB}.st_node_occurrence (enrollment_id, patient_id, plan_id, plan_code, plan_version,
                                  node_id, occurrence, plan_date, window_start, window_end,
                                  due_reason, refreshed_date)
VALUES (990002, 990002, 1, '{DRILL_MARKER}_PLAN', 1, 990002, 1, CURDATE() - INTERVAL 40 DAY,
        CURDATE() - INTERVAL 40 DAY, CURDATE() - INTERVAL 26 DAY, 'DUE', CURDATE() - INTERVAL 3 DAY);
INSERT INTO {DRILL_DB}.imp_batch (job_id, batch_no, first_patient_row_id, last_patient_row_id,
                         patient_count, status, updated_at)
VALUES (990003, 1, 1, 200, 200, 'PENDING', NOW() - INTERVAL 25 HOUR);
UPDATE {DRILL_DB}.aud_log SET created_at = NOW() - INTERVAL 48 HOUR WHERE event_type = '{DRILL_MARKER}_OPS';
UPDATE {DRILL_DB}.aud_login_log SET created_at = NOW() - INTERVAL 48 HOUR;
INSERT INTO {DRILL_PLATFORM_DB}.plt_view_session (session_no, account_id, account_name, display_name,
                                         role_codes, tenant_id, purpose, note, status, started_at, ends_at)
VALUES ('{DRILL_MARKER}SN01', 1, '{DRILL_MARKER}（示例）', '李运营（示例）', 'PLATFORM_OPS', 1,
        'TROUBLESHOOT', '演练注入：超时未收口视角会话（示例）', 'ACTIVE',
        NOW() - INTERVAL 4 HOUR, NOW() - INTERVAL 2 HOUR);
"""


def run_drill() -> int:
    tenant = DrillRunner(DRILL_DB)
    platform = DrillRunner(DRILL_PLATFORM_DB)
    problems: list[str] = []

    # 阶段 1：克隆真实 schema 的两个隔离库 + 基线活动种子 → 六项全 PASS
    tenant.exec_fixture(drill_clone_sql())
    tenant.exec_fixture(drill_baseline_seed_sql())
    findings = run_checks(tenant, surface_checks()) + run_checks(platform, platform_checks())
    for check_id in ("HC-H1", "HC-H2", "HC-I1", "HC-J1", "HC-M1", "HC-M2"):
        problems += expect(findings, check_id, True, "基线")
    if not print_findings(findings, "[演练·基线] "):
        problems.append("基线阶段不应有 FAIL")

    # 阶段 2：注入六类异常（{DRILL_MARKER} 标记、AGENTS §5.5 虚构）→ 逐项检出
    tenant.exec_fixture(drill_inject_sql())
    findings = run_checks(tenant, surface_checks()) + run_checks(platform, platform_checks())
    for check_id in ("HC-H1", "HC-H2", "HC-I1", "HC-J1", "HC-M1", "HC-M2"):
        problems += expect(findings, check_id, False, "注入")
    if print_findings(findings, "[演练·注入] "):
        problems.append("注入阶段不应全 PASS")

    # 阶段 3：两个隔离库整库清场并确认归零（不碰共享演示数据）
    tenant.exec_fixture(f"DROP DATABASE IF EXISTS {DRILL_DB};\n"
                        f"DROP DATABASE IF EXISTS {DRILL_PLATFORM_DB};")
    residue = tenant._exec(
        f"SELECT COUNT(*) FROM information_schema.schemata "
        f"WHERE schema_name IN ('{DRILL_DB}', '{DRILL_PLATFORM_DB}')")
    if residue.strip() != "0":
        problems.append(f"清场失败：隔离库仍存在（{residue.strip()}）")

    for p in problems:
        print("[FAIL] " + p)
    if problems:
        print(f"[E23-T14] 分担面巡检演练未通过（{len(problems)} 项）")
        return 1
    print("[E23-T14] 分担面巡检演练通过：基线全 PASS、六类异常逐项检出、清场归零")
    return 0


# ---------------------------------------------------------------------------
# --ledger：共用台账的按时响应判定（默认只判全栈 2 分担面，--all 判全量）
# ---------------------------------------------------------------------------


def in_fs2_scope(row: dict[str, str]) -> bool:
    """全栈 2 分担面条目 = owner 标注全栈2（runbook §5：定责只认 Issue 标注）。"""
    return (row.get("owner") or "").strip() == OWNER_FS2


def run_ledger_check(path: Path, asof: datetime, judge_all: bool) -> int:
    problems, rows = load_ledger(path)
    if problems:
        print("台账结构不合法：")
        for p in problems:
            print("  - " + p)
        return 1
    scoped = rows if judge_all else [r for r in rows if in_fs2_scope(r)]
    scope_name = "全量（退出评审口径）" if judge_all else f"owner={OWNER_FS2} 分担面"
    if not scoped:
        print(f"[E23-T14] 台账 {len(rows)} 条中无 {scope_name} 条目——无待判项")
        return 0
    outcomes = [sla_outcome(row, asof) for row in scoped]
    for row, (ok, detail) in zip(scoped, outcomes):
        print(f"[{'PASS' if ok else 'FAIL'}] {detail}")
    failed = [detail for _, (ok, detail) in zip(scoped, outcomes) if not ok]
    if failed:
        print(f"[E23-T14] {scope_name} {len(scoped)} 条中 {len(failed)} 条未按时响应"
              f"——按 runbook §4 升级处置")
        return 1
    print(f"[E23-T14] {scope_name} {len(scoped)} 条全部按时响应（台账共 {len(rows)} 条）")
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

    # 与全栈 1 工具口径一致（复用即同源，此处锁定防两脚本漂移）
    check("SLA 时限表与全栈 1 工具一致（双册同表）",
          SLA_RESPONSE_MINUTES == {"P0": 15, "P1": 30, "P2": 120, "P3": 480})
    check("分级 label 映射与全栈 1 工具一致（OQ-122②梯子）",
          SEVERITY_TO_LABEL == shared.SEVERITY_TO_LABEL)
    check("台账列与共用模板口径一致（LEDGER_COLUMNS 同源）",
          LEDGER_COLUMNS == shared.LEDGER_COLUMNS)

    # SLA 判定（复用函数仍自证边界）
    base = {"finding_id": "HYPC-W36-11", "reported_at": "2027-07-20 09:00", "severity": "P1"}
    check("P1 30 分钟边界 = 按时（复用全栈 1 判定）",
          sla_outcome({**base, "responded_at": "2027-07-20 09:30"}, datetime(2027, 7, 21))[0])
    check("P1 31 分钟 = 超时（复用全栈 1 判定）",
          not sla_outcome({**base, "responded_at": "2027-07-20 09:31"}, datetime(2027, 7, 21))[0])

    # 台账范围过滤
    fs2_row = {"finding_id": "HYPC-W36-11", "owner": OWNER_FS2}
    fs1_row = {"finding_id": "HYPC-W36-12", "owner": "全栈1"}
    check("范围过滤只认 owner=全栈2（runbook §5 定责口径）",
          in_fs2_scope(fs2_row) and not in_fs2_scope(fs1_row))

    # 分担面巡检判定边界（judge 直接喂值，不连库）
    judges = {c["id"]: c["judge"] for c in surface_checks() + platform_checks()}
    check("HC-H1 断链 0 通过 / 1 检出",
          judges["HC-H1"](["0"]).ok and not judges["HC-H1"](["1"]).ok)  # type: ignore[attr-defined]
    check("HC-H2 滞留 0 通过 / 1 检出",
          judges["HC-H2"](["0"]).ok and not judges["HC-H2"](["1"]).ok)  # type: ignore[attr-defined]
    check("HC-I1 空表跳过 / 落后 1 天通过 / 落后 2 天检出",
          judges["HC-I1"](["0", ""]).ok  # type: ignore[attr-defined]
          and judges["HC-I1"](["5000", "1"]).ok  # type: ignore[attr-defined]
          and not judges["HC-I1"](["5000", "2"]).ok)  # type: ignore[attr-defined]
    check("HC-I1 落后值缺失按异常检出",
          not judges["HC-I1"](["5000", "NULL"]).ok)  # type: ignore[attr-defined]
    check("HC-J1 卡批 0 通过 / 1 检出",
          judges["HC-J1"](["0"]).ok and not judges["HC-J1"](["1"]).ok)  # type: ignore[attr-defined]
    check("HC-M1 双零检出 / 登录审计有行通过",
          not judges["HC-M1"](["0", "0"]).ok and judges["HC-M1"](["0", "3"]).ok)  # type: ignore[attr-defined]
    check("HC-M2 超时会话 0 通过 / 1 检出",
          judges["HC-M2"](["0"]).ok and not judges["HC-M2"](["1"]).ok)  # type: ignore[attr-defined]

    # 只读护栏：分担面巡检语句全 SELECT；写语句样例被拒
    for item in surface_checks() + platform_checks():
        check(f"巡检 {item['id']} 语句只读", must_fail(assert_readonly, str(item["sql"])) is False)
        if "sql2" in item:
            check(f"巡检 {item['id']} 辅助语句只读",
                  must_fail(assert_readonly, str(item["sql2"])) is False)
    check("写语句被只读护栏拒绝", must_fail(assert_readonly, "DELETE FROM aud_log"))
    check("多语句被只读护栏拒绝",
          must_fail(assert_readonly, "SELECT 1; DELETE FROM plt_view_session"))

    # 台账行校验复用全栈 1 同一套（抽验拦截面）
    good = {
        "finding_id": "HYPC-W36-11", "reported_at": "2027-07-20 09:00", "channel": "巡检",
        "severity": "P2", "module": "report", "summary": "报告单预览打不开（示例）",
        "triaged_at": "", "owner": OWNER_FS2, "responded_at": "2027-07-20 10:30",
        "response_action": "分诊结论：附件断链，修复中", "resolved_at": "", "status": "RESPONDED",
        "issue_url": "https://github.com/icedkong/crdp-platform/issues/302",
    }
    check("合法行通过校验（复用全栈 1 校验器）",
          shared.validate_ledger_row(dict(good), 2, set()) == [])
    check("疑似真实身份证号拦截（AGENTS §5.5）",
          any("身份证号" in p for p in shared.validate_ledger_row(
              {**good, "summary": "患者 110101199001011234 反馈（示例）"}, 2, set())))
    check("owner 非法拦截（分担面枚举共用）",
          any("owner" in p for p in shared.validate_ledger_row(
              {**good, "owner": "全栈3"}, 2, set())))

    check("阈值常量为正", all(v > 0 for v in (
        FILE_PENDING_STALE_HOURS, STATS_STALE_DAYS, BATCH_STUCK_HOURS,
        AUDIT_QUIET_HOURS, VIEW_GRACE_HOURS)))

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    if failed:
        print(f"[E23-T14] 自检未通过（{len(failed)} 项）")
        return 1
    print(f"[E23-T14] 自检通过（{len(checks)} 项）")
    return 0


# ---------------------------------------------------------------------------
# 默认模式：另册 runbook 防漂移校验（含与全栈 1 册的双册互锁）
# ---------------------------------------------------------------------------


def e23_t14_row() -> dict[str, str] | None:
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T14":
                return row
    return None


def run_doc_checks() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    runbook = RUNBOOK.read_text(encoding="utf-8") if RUNBOOK.exists() else ""
    check("runbook 存在（docs/ops/hospital-a-hypercare-fullstack2.md）", bool(runbook))

    row = e23_t14_row()
    check("tasks.csv 有 E23-T14 行", row is not None)
    title = (row or {}).get("标题", "")
    accept = (row or {}).get("验收标准", "")
    deps = (row or {}).get("依赖", "")
    check("标题 W36–W37 与 tasks.csv 一致", "W36–W37" in title and "W36–W37" in runbook)
    check("验收原句在 runbook（上线问题按时响应）", accept and accept in runbook)
    check("依赖 E23-T11 在 runbook", deps == "E23-T11" and "E23-T11" in runbook)

    # 互引文档存在且被引用（全栈 1 册 + 承接的 go-live / 导入回滚 / 审计归档 / 全栈 2 UAT 册）
    for doc in (
        "hospital-a-hypercare.md",
        "hospital-a-go-live.md",
        "hospital-a-import-rollback.md",
        "audit-worm-archive.md",
        "uat-remediation-hospital-a.md",
    ):
        path = ROOT / "docs/ops" / doc
        if not path.exists():
            path = ROOT / "docs/testing" / doc
        check(f"引用的既有文档存在且被引用：{doc}", path.exists() and doc in runbook)

    # 时限表：数字与共用常量逐字一致，且双册互锁（全栈 1 册同数字）
    peer = PEER_RUNBOOK.read_text(encoding="utf-8") if PEER_RUNBOOK.exists() else ""
    for sev in ("P0", "P1", "P2", "P3"):
        check(f"时限表 {sev} = {SLA_RESPONSE_MINUTES[sev]} 分钟（与共用常量一致）",
              f"{SLA_RESPONSE_MINUTES[sev]} 分钟" in runbook)
    check("双册互锁：全栈 1 册时限数字一致（防两册漂移）",
          all(f"{SLA_RESPONSE_MINUTES[sev]} 分钟" in peer for sev in SLA_RESPONSE_MINUTES))
    check("时限表机器判定口径（自然时钟从严）", "从严" in runbook)
    check("分级映射 severity label（OQ-122②）",
          all(label in runbook for label in SEVERITY_TO_LABEL.values()) and "OQ-122" in runbook)

    # 台账：共用一份模板与列清单、编号段、Issue 载体
    check("台账列清单与共用 LEDGER_COLUMNS 一致",
          all(col in runbook for col in LEDGER_COLUMNS))
    check("共用模板存在且表头与 LEDGER_COLUMNS 一致",
          TEMPLATE.exists()
          and [c.strip() for c in TEMPLATE.read_text(encoding="utf-8-sig")
               .splitlines()[0].split(",")] == LEDGER_COLUMNS)
    check("编号段 HYPC- 与 GitHub Issues 载体（label HYPERCARE）",
          "HYPC-" in runbook and "HYPERCARE" in runbook and "GitHub Issues" in runbook)

    # 处置流程：三选一口径与升级
    check("处置三选一口径（修复 / 缓解 / 延期）",
          "修复" in runbook and "缓解" in runbook and "延期" in runbook)
    check("超时升级路径在册（共用全栈 1 册 §2）", "升级" in runbook)

    # 分担面巡检：六项检查与阈值
    for check_id in ("HC-H1", "HC-H2", "HC-I1", "HC-J1", "HC-M1", "HC-M2"):
        check(f"分担面巡检项在册：{check_id}", check_id in runbook)
    check("巡检阈值在册（滞留 24 小时 / 统计 1 天 / 卡批 24 小时 / 断写 24h / 宽限 1 小时）",
          f"{FILE_PENDING_STALE_HOURS} 小时" in runbook and f"{STATS_STALE_DAYS} 天" in runbook
          and f"{BATCH_STUCK_HOURS} 小时" in runbook and f"{AUDIT_QUIET_HOURS}h" in runbook
          and f"{VIEW_GRACE_HOURS} 小时" in runbook)

    # 分担面与并行任务、退出判据、红线
    check("全栈 2 分担面字母（H/I/J/M，承接 UAT §5 分界）", SURFACE_LETTERS in runbook)
    check("与 E23-T13（全栈 1 册）互引", "E23-T13" in runbook)
    peer_refs_fs2 = "E23-T14" in peer
    check("全栈 1 册反向互引本册（双册定责一致）", peer_refs_fs2)
    check("观察期退出判据在册（与全栈 1 册 §8 同一判据）", "退出" in runbook)
    check("真实数据红线（不进仓库 / CI）", "不进仓库" in runbook)

    # 工具用法与口令纪律
    check("工具用法（--selftest / --ledger / --drill / --host / --all）",
          "--selftest" in runbook and "--ledger" in runbook and "--drill" in runbook
          and "--host" in runbook and "--all" in runbook)
    check("口令只经环境变量（不进 argv / 不落盘）",
          "CRDP_HYPERCARE_MYSQL_PASSWORD" in runbook and "不落盘" in runbook)

    # CI 接线与索引
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接线（--selftest / 默认防漂移 / --drill 三步）",
          "hypercare_verify_fullstack2.py --selftest" in ci
          and "hypercare_verify_fullstack2.py --drill" in ci
          and ci.count("hypercare_verify_fullstack2.py") >= 3)
    readme = DOCS_README.read_text(encoding="utf-8") if DOCS_README.exists() else ""
    check("docs/README.md 已索引本册", "hospital-a-hypercare-fullstack2.md" in readme)

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    if failed:
        print(f"[E23-T14] runbook 防漂移校验未通过（{len(failed)} 项）")
        return 1
    print("[E23-T14] runbook 防漂移校验通过")
    return 0


# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true", help="纯逻辑自检（不联网、不连库）")
    parser.add_argument("--drill", action="store_true",
                        help="compose MySQL 分担面巡检演练（两个隔离库：基线全 PASS + 六类异常"
                             "逐项检出 + 清场）")
    parser.add_argument("--ledger", help="共用台账 CSV 路径（runbook §5，GitHub Issues 导出）")
    parser.add_argument("--asof", help="未响应条目的判定时刻（YYYY-MM-DD HH:MM，默认当前）")
    parser.add_argument("--all", action="store_true",
                        help="--ledger 判全量（退出评审口径，与全栈 1 工具同结果）；默认只判 owner=全栈2")
    parser.add_argument("--host", help="生产数据库地址（只读巡检，runbook §6）")
    parser.add_argument("--port", default="3306", help="端口（默认 3306）")
    parser.add_argument("--db", help="租户库名（t_租户编码）")
    parser.add_argument("--platform-db", default="crdp_platform",
                        help="平台库名（默认 crdp_platform，HC-M2 用）")
    parser.add_argument("--user", help="数据库账号（只读，需租户库与平台库两库授权）")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    if args.drill:
        return run_drill()
    if args.ledger:
        asof = parse_dt(args.asof) if args.asof else datetime.now()
        return run_ledger_check(Path(args.ledger), asof, args.all)
    if args.host or args.db or args.user:
        missing = [name for name, value in (
            ("--host", args.host), ("--db", args.db), ("--user", args.user))
            if not value]
        if missing:
            parser.error(f"生产巡检缺参数：{'、'.join(missing)}")
        tenant = RemoteRunner(args.host, args.port, args.db, args.user)
        platform = RemoteRunner(args.host, args.port, args.platform_db, args.user)
        findings = run_checks(tenant, surface_checks()) + run_checks(platform, platform_checks())
        ok = print_findings(findings, "[E23-T14] 生产观察期（全栈 2 分担面）")
        return 0 if ok else 1
    return run_doc_checks()


if __name__ == "__main__":
    sys.exit(main())
