#!/usr/bin/env python3
"""医院 B 用 A 配置模板初始化：runbook 防漂移校验 + 配置一致性核对工具（E23-T15 · W37）。

任务口径（`docs/tasks/tasks.csv` E23-T15：全栈，依赖 E12-T05，验收「B 租户开通，配置与 A 一致」）：
生产开通（向导操作、租户转正、人工抽查）由持账号运维 / 实施按 `docs/ops/hospital-b-onboarding.md`
在场完成（AGENTS §9）；本工具交付其中「机器可核对」的部分：

1. 默认（不连库、不联网）：runbook（`docs/ops/hospital-b-onboarding.md`）防漂移校验——放行闸门、
   向导 API（`POST /api/platform/tenants` 带 `templateId`）、config-template 步骤、两级核对口径、
   第 14 章 B 差异处置与承接任务、中止回滚（E05-T08）、由人执行清单、自动化对照；tasks.csv 行
   （类型 / 依赖 / 验收原句）；向导路径演练 IT `TenantBOnboardingMySqlIT` 存在且门控；
   ci.yml 三模式接线与 docs/README.md 索引；
2. `--selftest`（不联网、不连库）：纯逻辑自检——比对表清单与 `ConfigTableRegistry`（29 张登记表）
   完全一致（防「工具口径 ≠ 注册表」漂移）、只读护栏（核对语句必须 SELECT，写语句一律拒绝）、
   多重集相等 / 包含与差异注入检出的判定逻辑；
3. `--drill`（docker compose 的 MySQL，医院 A 化身 = t_demoa）：克隆 A 的全部表结构 +
   27 张可比配置表的行（模拟「B 已由 A 模板初始化」终态：术者取值剔除、病区只结构零行、
   用户引用置空、患者业务表空壳）到隔离库 t_e23t15chk → 生产同款核对全部 PASS；
   随后注入四类差异（字段字典改值 / 报告单类型多行 / 通知规则缺行 / 角色授权缺行）→
   逐项检出 FAIL；结束整库删除清场归零（与 hypercare_verify 演练同口径）；
4. 生产核对（由持账号运维执行，只读）：`--host/--port/--db-a/--db-b/--user`——口令只经环境变量
   `CRDP_TENANTB_MYSQL_PASSWORD`（或 `MYSQL_PWD`）进 mysql 客户端，不进 argv、不落盘；
   输出 23 张等价表逐表多重集比对 + 角色四表包含 + 只复制红线（患者业务数据为空、
   术者取值不复制、配置行用户引用为空），口径 = runbook §5.1。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNBOOK = ROOT / "docs/ops/hospital-b-onboarding.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
DOCS_README = ROOT / "docs/README.md"
IT = ROOT / "backend/crdp-server/src/test/java/com/crdp/server/TenantBOnboardingMySqlIT.java"
REGISTRY_JAVA = (
    ROOT / "backend/crdp-platform/src/main/java/com/crdp/platform/configtemplate/ConfigTableRegistry.java"
)

# 演练环境（与 golive_import_verify / hypercare_verify 同一 compose MySQL）
DRILL_DB_A = "t_demoa"  # 医院 A 的本地化身（V38 / V61 / V62 / V70 预置 + 种子）
DRILL_DB_B = "t_e23t15chk"  # 演练「B 初始化终态」隔离库，结束整库删除

# 注册表口径中「不参与两侧比对」的表与原因（selftest 与 ConfigTableRegistry 对账时计入）
NOT_COMPARED = {
    "sys_role_scope_dept": "导入按口径丢弃（OQ-91 ④：引用 sys_dept，组织架构不入模板）",
    "ward": "只复制结构（A-FLD-014：病区 / 中心保留定义不带取值，B 自维护，E23-T18）",
}

# ---------------------------------------------------------------------------
# 比对清单：与 ConfigTableRegistry（29 张登记表）同口径。{db} 占位符替换为比对侧库名。
# 数值引用（version_id / plan_id / tpl_id / mapping_id / role_id）一律 join 到本院行取
# 业务值，不比跨库自增 ID；不比 id / 用户引用 / 时间列（导入置空与 ON UPDATE 时间戳属
# 既有口径，OQ-90 ④ / OQ-91 ③）。查询与 TenantBOnboardingMySqlIT / E12-T06 IT 同源。
# ---------------------------------------------------------------------------

EQUALITY_QUERIES: list[tuple[str, str]] = [
    # ---- base：字典、参数、功能开关 ----
    ("cfg_dict", "SELECT code, name, description, status FROM `{db}`.cfg_dict"),
    (
        "cfg_dict_item",
        "SELECT dict_code, item_code, label, aliases, sort_no, source, status"
        " FROM `{db}`.cfg_dict_item WHERE dict_code <> 'surgeon'",
    ),
    ("cfg_param", "SELECT param_key, param_value, remark FROM `{db}`.cfg_param"),
    ("cfg_feature", "SELECT feature_code, enabled FROM `{db}`.cfg_feature"),
    # ---- followup：字段字典与表单（fe_* 七表）----
    ("fe_form", "SELECT code, name, usage_type, current_version, categories, status FROM `{db}`.fe_form"),
    (
        "fe_field",
        "SELECT form_code, field_key, field_code, label, category_key, type, unit, decimals,"
        " hard_range, soft_range, option_set_code, required, visible_when, required_when,"
        " derived_def, sensitive_flag, storage_type, help_text, sort_no, status"
        " FROM `{db}`.fe_field",
    ),
    ("fe_option_set", "SELECT code, name FROM `{db}`.fe_option_set"),
    (
        "fe_option",
        "SELECT set_code, option_code, label, ordinal, exclusive, needs_detail, aliases,"
        " sort_no, status FROM `{db}`.fe_option",
    ),
    ("fe_form_version", "SELECT form_code, version, categories, field_count FROM `{db}`.fe_form_version"),
    (
        "fe_form_version_field",
        "SELECT v.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key,"
        " f.type, f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code,"
        " f.required, f.visible_when, f.required_when, f.derived_def, f.sensitive_flag,"
        " f.storage_type, f.help_text, f.sort_no"
        " FROM `{db}`.fe_form_version_field f JOIN `{db}`.fe_form_version v ON v.id = f.version_id",
    ),
    (
        "fe_rule",
        "SELECT form_code, rule_id, category_key, target_field, severity, rule_type, params,"
        " message, sort_no, status FROM `{db}`.fe_rule",
    ),
    # ---- followup：计划模板 ----
    (
        "fu_plan",
        "SELECT code, name, version, status, start_date_field, cycle_type, cycle_years,"
        " auto_enroll, remark FROM `{db}`.fu_plan",
    ),
    (
        "fu_plan_node",
        "SELECT p.code, p.version, n.node_type, n.name, n.repeat_mode, n.repeat_count,"
        " n.cycle_value, n.cycle_unit, n.basis, n.offset_value, n.offset_unit, n.remind_value,"
        " n.remind_unit, n.window_before_value, n.window_before_unit, n.window_after_value,"
        " n.window_after_unit, n.task_type, n.owner_type, n.form_code, n.passed_window_action,"
        " n.enroll_mode, n.sort_no"
        " FROM `{db}`.fu_plan_node n JOIN `{db}`.fu_plan p ON p.id = n.plan_id",
    ),
    # 器械→计划映射（E21-T05 / C-FU-003；plan_code 为计划族编码引用，join fu_plan 取族名一并比对）
    (
        "fu_plan_mapping",
        "SELECT m.device_type, m.plan_code, p.name AS plan_name, m.enabled"
        " FROM `{db}`.fu_plan_mapping m"
        " LEFT JOIN `{db}`.fu_plan p ON p.code = m.plan_code AND p.version ="
        " (SELECT MAX(version) FROM `{db}`.fu_plan WHERE code = m.plan_code)",
    ),
    # ---- followup：通知设置 ----
    (
        "ntf_rule",
        "SELECT event_type, enabled, advance_days, send_time, channels, recipients,"
        " sms_template_code FROM `{db}`.ntf_rule",
    ),
    (
        "ntf_setting",
        "SELECT dnd_start, dnd_end, sms_daily_limit, overdue_sms_off_allowed"
        " FROM `{db}`.ntf_setting",
    ),
    # ---- followup：报告单设置（六表）----
    ("rpt_indicator_tpl", "SELECT name, status, remark FROM `{db}`.rpt_indicator_tpl"),
    (
        "rpt_indicator_item",
        "SELECT t.name AS tpl_name, i.item_name, i.item_code, i.unit, i.ref_range,"
        " i.ref_range_male, i.ref_range_female, i.sort_no"
        " FROM `{db}`.rpt_indicator_item i JOIN `{db}`.rpt_indicator_tpl t ON t.id = i.tpl_id",
    ),
    (
        "rpt_type",
        "SELECT code, name, form_code, name_required, default_digitize_status, sort_no,"
        " status, remark FROM `{db}`.rpt_type",
    ),
    (
        "rpt_name",
        "SELECT n.type_code, n.name, t.name AS tpl_name, n.digitize_status_override,"
        " n.sort_no, n.status FROM `{db}`.rpt_name n"
        " LEFT JOIN `{db}`.rpt_indicator_tpl t ON t.id = n.indicator_tpl_id",
    ),
    (
        "rpt_mapping",
        "SELECT m.code, m.name, m.type_code, m.target_type, m.target_form_code, m.phase,"
        " t.name AS tpl_name, m.extra_writes, m.sort_no, m.status, m.remark"
        " FROM `{db}`.rpt_mapping m"
        " LEFT JOIN `{db}`.rpt_indicator_tpl t ON t.id = m.indicator_tpl_id",
    ),
    (
        "rpt_mapping_item",
        "SELECT p.code AS mapping_code, i.item_code, i.target_field_key, i.extra_writes,"
        " i.sort_no FROM `{db}`.rpt_mapping_item i"
        " JOIN `{db}`.rpt_mapping p ON p.id = i.mapping_id",
    ),
    # ---- followup：对比标红规则（A-FU-027）----
    (
        "fu_compare_rule",
        "SELECT form_code, rule_code, name, category_key, target_field, rule_type, params,"
        " baseline_form_code, baseline_field, message, sort_no, status"
        " FROM `{db}`.fu_compare_rule",
    ),
    # ---- followup：首页卡片配置（A-DASH-013 随模板复制；role_id join 角色编码）----
    (
        "dash_role_layout",
        "SELECT r.code, d.card_code, d.visible, d.sort_order, d.allow_user_hide"
        " FROM `{db}`.dash_role_layout d JOIN `{db}`.sys_role r ON r.id = d.role_id",
    ),
]

CONTAINMENT_QUERIES: list[tuple[str, str]] = [
    (
        "sys_role",
        "SELECT code, name, built_in, status, role_type, module_code, description"
        " FROM `{db}`.sys_role",
    ),
    (
        "sys_role_scope",
        "SELECT r.code, s.scope_type FROM `{db}`.sys_role_scope s"
        " JOIN `{db}`.sys_role r ON r.id = s.role_id",
    ),
    (
        "sys_role_field_policy",
        "SELECT r.code, p.resource, p.field, p.policy FROM `{db}`.sys_role_field_policy p"
        " JOIN `{db}`.sys_role r ON r.id = p.role_id",
    ),
    (
        "sys_role_menu",
        "SELECT r.code, rm.menu_id FROM `{db}`.sys_role_menu rm"
        " JOIN `{db}`.sys_role r ON r.id = rm.role_id",
    ),
]

# 只复制红线（runbook §5.1）：B 侧为空 / 不随模板进入
REDLINE_EMPTY_TABLES = ["pt_patient", "fu_enrollment", "fu_task"]
REDLINE_QUERIES = [
    ("RL-surgeon", "B 的术者字典取值不随模板进入（只复制结构）",
     "SELECT COUNT(*) FROM `{db}`.cfg_dict_item WHERE dict_code = 'surgeon'"),
    ("RL-userref-fe_field", "B 的字段字典配置行不带源租户用户引用（导入置空）",
     "SELECT COUNT(*) FROM `{db}`.fe_field WHERE updated_by IS NOT NULL"),
    ("RL-userref-rpt_type", "B 的报告单类型配置行不带源租户用户引用",
     "SELECT COUNT(*) FROM `{db}`.rpt_type WHERE updated_by IS NOT NULL"),
    ("RL-userref-ntf_rule", "B 的通知规则配置行不带源租户用户引用",
     "SELECT COUNT(*) FROM `{db}`.ntf_rule WHERE updated_by IS NOT NULL"),
    ("RL-ward", "B 的病区取值不随模板进入（只复制结构，E23-T18 由 B 维护）",
     "SELECT COUNT(*) FROM `{db}`.ward"),
]

WRITE_PATTERN = re.compile(
    r"^\s*(insert|update|delete|replace|create|alter|drop|truncate|rename|grant"
    r"|revoke|lock|call|load|set|merge)\b",
    re.I,
)

# 库名护栏：只接受标识符（防注入——生产模式库名来自命令行参数）
DB_NAME_PATTERN = re.compile(r"^[A-Za-z0-9_]{1,64}$")


def qualify(db: str) -> str:
    if not DB_NAME_PATTERN.match(db):
        raise RuntimeError(f"库名必须是纯标识符（收到：{db!r}）")
    return db


def assert_readonly(sql: str) -> None:
    if WRITE_PATTERN.match(sql):
        raise RuntimeError(f"只读护栏：核对语句必须是 SELECT（收到：{sql[:80]}）")


# ---------------------------------------------------------------------------
# SQL 执行器：drill（docker compose 容器内 root）与生产（mysql 客户端，口令经环境变量）
# ---------------------------------------------------------------------------


class DrillRunner:
    """compose MySQL（本地 / CI 演练）。夹具写入经 exec_fixture（仅 --drill 路径）。"""

    def _exec(self, sql: str) -> str:
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
             'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B'
             " --default-character-set=utf8mb4"],
            cwd=ROOT, input=sql, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        return proc.stdout

    def exec_fixture(self, sql: str) -> None:
        """夹具写入 / 清理（CREATE / INSERT / UPDATE / DROP，仅 --drill 允许）。"""
        self._exec(sql)

    def rows(self, sql: str) -> list[list[str]]:
        assert_readonly(sql)
        out = self._exec(sql)
        return [line.split("\t") for line in out.splitlines() if line]

    def scalar(self, sql: str) -> str:
        rows = self.rows(sql)
        return rows[0][0] if rows else ""


class RemoteRunner:
    """生产租户库（只读）。口令只经 CRDP_TENANTB_MYSQL_PASSWORD / MYSQL_PWD 环境变量。"""

    def __init__(self, host: str, port: str, user: str) -> None:
        self.host = host
        self.port = port
        self.user = user
        password = os.environ.get("CRDP_TENANTB_MYSQL_PASSWORD") or os.environ.get("MYSQL_PWD")
        if not password:
            raise SystemExit(
                "缺少数据库口令：请设置环境变量 CRDP_TENANTB_MYSQL_PASSWORD（只进 mysql 客户端"
                "进程环境，不进 argv、不落盘；runbook §5.1）")
        self.env = {**os.environ, "MYSQL_PWD": password}

    def rows(self, sql: str) -> list[list[str]]:
        assert_readonly(sql)
        proc = subprocess.run(
            ["mysql", f"-h{self.host}", f"-P{self.port}", f"-u{self.user}",
             "-N", "-B", "--default-character-set=utf8mb4", "-e", sql],
            env=self.env, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        return [line.split("\t") for line in proc.stdout.splitlines() if line]

    def scalar(self, sql: str) -> str:
        rows = self.rows(sql)
        return rows[0][0] if rows else ""


# ---------------------------------------------------------------------------
# 核对逻辑（生产与演练同一套；全部只读 SELECT；口径 = runbook §5.1）
# ---------------------------------------------------------------------------


class Finding:
    def __init__(self, check_id: str, title: str, ok: bool, detail: str) -> None:
        self.check_id = check_id
        self.title = title
        self.ok = ok
        self.detail = detail


def multiset(rows: list[list[str]]) -> Counter:
    return Counter(tuple(row) for row in rows)


def diff_sample(a: Counter, b: Counter, side: str, limit: int = 3) -> str:
    """差异样例（runbook §5.1 的 FAIL 明细）：取一侧多出 / 少于另一侧的行。"""
    if side == "B":
        extra, missing = b - a, a - b
    else:
        extra, missing = a - b, b - a
    parts = []
    if extra:
        parts.append("B 多出：" + " ; ".join(repr(list(r)) for r in list(extra)[:limit]))
    if missing:
        parts.append("B 缺少：" + " ; ".join(repr(list(r)) for r in list(missing)[:limit]))
    return " ｜ ".join(parts) if parts else "无差异"


def run_compare(
    runner: DrillRunner | RemoteRunner, db_a: str, db_b: str,
    only: set[str] | None = None,
) -> list[Finding]:
    """全注册表比对（only 给定时只比对该表——演练注入轮的定向复核）。"""
    findings: list[Finding] = []

    for table, sql in EQUALITY_QUERIES:
        if only and table not in only:
            continue
        rows_a = runner.rows(sql.replace("{db}", qualify(db_a)))
        rows_b = runner.rows(sql.replace("{db}", qualify(db_b)))
        cnt_a, cnt_b = multiset(rows_a), multiset(rows_b)
        ok = cnt_a == cnt_b
        findings.append(Finding(
            f"EQ-{table}", f"等价表 {table} 业务列多重集与 A 一致", ok,
            f"A {sum(cnt_a.values())} 行 / B {sum(cnt_b.values())} 行"
            + ("" if ok else " ｜ " + diff_sample(cnt_a, cnt_b, "B"))))

    for table, sql in CONTAINMENT_QUERIES:
        if only and table not in only:
            continue
        rows_a = runner.rows(sql.replace("{db}", qualify(db_a)))
        rows_b = runner.rows(sql.replace("{db}", qualify(db_b)))
        cnt_a, cnt_b = multiset(rows_a), multiset(rows_b)
        # 多重集包含：A 的每行在 B 中出现次数不少于 A 侧（OQ-91 ① 并集，目标独有行不算漂移）
        missing = cnt_a - cnt_b
        ok = not missing
        findings.append(Finding(
            f"CT-{table}", f"并集表 {table} 包含 A 的全部配置行", ok,
            f"A {sum(cnt_a.values())} 行 / B {sum(cnt_b.values())} 行"
            + ("" if ok else " ｜ B 缺少：" + " ; ".join(repr(list(r)) for r in list(missing)[:3]))))

    if only:
        return findings

    for table in REDLINE_EMPTY_TABLES:
        cnt = runner.scalar(f"SELECT COUNT(*) FROM {{db}}.{table}".replace("{db}", qualify(db_b)))
        ok = cnt in ("0", "")
        findings.append(Finding(
            f"RL-{table}", f"红线：B 的 {table} 为空（A-FLD-014 不复制业务数据）", ok,
            f"B {table} 行数 = {cnt or 0}"))

    for check_id, title, sql in REDLINE_QUERIES:
        cnt = runner.scalar(sql.replace("{db}", qualify(db_b)))
        ok = cnt in ("0", "")
        findings.append(Finding(
            check_id, f"红线：{title}", ok, f"B 计数 = {cnt or 0}"))

    return findings


def print_findings(findings: list[Finding]) -> bool:
    width = max(len(f.check_id) for f in findings) + 2
    for f in findings:
        mark = "PASS" if f.ok else "FAIL"
        print(f"[{mark}] {f.check_id:<{width}} {f.title}")
        print(f"        {f.detail}")
    failed = [f for f in findings if not f.ok]
    print(f"\n核对完成：{len(findings) - len(failed)} PASS / {len(failed)} FAIL")
    return not failed


# ---------------------------------------------------------------------------
# 演练夹具（--drill）：克隆 A 配置为「B 初始化终态」 + 差异注入检出
# ---------------------------------------------------------------------------


def drill_fixture_sql() -> str:
    """「B 已由 A 模板初始化」终态：全部表结构 + 仅配置表行（模板语义）。

    * 结构：A 库所有表 CREATE TABLE ... LIKE（含患者业务表空壳——迁移建表、模板不带数据）；
    * 行：28 张可比配置表 INSERT ... SELECT；cfg_dict_item 剔除术者取值（只复制结构）；
    * 用户引用置空（tech-plan §6：配置中引用用户的字段一律置空，账号不随模板复制）；
    * ward 不带行（只复制结构）；flyway_schema_history 不入克隆（演练库非开通产物）。
    """
    compared = [t for t, _ in EQUALITY_QUERIES + CONTAINMENT_QUERIES]
    lines = [f"DROP DATABASE IF EXISTS {DRILL_DB_B};",
             f"CREATE DATABASE {DRILL_DB_B} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"]
    # 全部表结构（患者业务表建空壳；跳过视图与 flyway 历史表）
    tables = information_schema_tables()
    for t in tables:
        if t != "flyway_schema_history":
            lines.append(f"CREATE TABLE {DRILL_DB_B}.`{t}` LIKE {DRILL_DB_A}.`{t}`;")
    for t in compared:
        if t == "cfg_dict_item":
            lines.append(
                f"INSERT INTO {DRILL_DB_B}.cfg_dict_item"
                f" SELECT id, dict_code, item_code, label, aliases, sort_no, source, status,"
                f" NULL, created_at, updated_at FROM {DRILL_DB_A}.cfg_dict_item"
                f" WHERE dict_code <> 'surgeon';")
        elif t == "fe_field":
            lines.append(
                f"INSERT INTO {DRILL_DB_B}.fe_field"
                f" SELECT id, form_code, field_key, field_code, label, category_key, type, unit,"
                f" decimals, hard_range, soft_range, option_set_code, dict_code, carry_over,"
                f" required, visible_when,"
                f" required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no,"
                f" status, NULL, created_at, updated_at FROM {DRILL_DB_A}.fe_field;")
        elif t == "rpt_type":
            lines.append(
                f"INSERT INTO {DRILL_DB_B}.rpt_type"
                f" SELECT id, code, name, form_code, name_required, default_digitize_status,"
                f" sort_no, status, remark, NULL, created_at, updated_at FROM {DRILL_DB_A}.rpt_type;")
        elif t == "ntf_rule":
            lines.append(
                f"INSERT INTO {DRILL_DB_B}.ntf_rule"
                f" SELECT id, event_type, enabled, advance_days, send_time, channels, recipients,"
                f" sms_template_code, NULL, updated_at, created_at FROM {DRILL_DB_A}.ntf_rule;")
        elif t == "sys_role_menu":
            # 自增无主键表（role_id + menu_id 联合主键）：直接复制
            lines.append(
                f"INSERT INTO {DRILL_DB_B}.sys_role_menu"
                f" SELECT role_id, menu_id FROM {DRILL_DB_A}.sys_role_menu;")
        else:
            lines.append(
                f"INSERT INTO {DRILL_DB_B}.`{t}` SELECT * FROM {DRILL_DB_A}.`{t}`;")
    # 其余用户引用列统一置空（模板导入语义；上面四表列序不同已就地置空）
    user_ref_fixes = [
        f"UPDATE {DRILL_DB_B}.cfg_param SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.fe_option SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.fe_rule SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.fe_form_version SET published_by = NULL;",
        f"UPDATE {DRILL_DB_B}.fu_plan SET created_by = NULL;",
        f"UPDATE {DRILL_DB_B}.fu_plan_mapping SET created_by = NULL, updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.rpt_indicator_tpl SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.rpt_name SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.rpt_mapping SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.fu_compare_rule SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.dash_role_layout SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.ntf_setting SET updated_by = NULL;",
        f"UPDATE {DRILL_DB_B}.cfg_dict_item SET updated_by = NULL;",
    ]
    lines.extend(user_ref_fixes)
    return "\n".join(lines)


_INFORMATION_TABLES: list[str] | None = None


def information_schema_tables() -> list[str]:
    """A 库（t_demoa）的基表清单（演练夹具用；模块内缓存）。"""
    global _INFORMATION_TABLES
    if _INFORMATION_TABLES is None:
        runner = DrillRunner()
        out = runner._exec(
            "SELECT table_name FROM information_schema.tables"
            " WHERE table_schema = 't_demoa' AND table_type = 'BASE TABLE' ORDER BY table_name;")
        _INFORMATION_TABLES = [line for line in out.splitlines() if line]
    return _INFORMATION_TABLES


# 四类差异注入（每类一个check 期望 FAIL；口径 = runbook §5.1 差异处置）
DRIFT_INJECTIONS: list[tuple[str, str, str]] = [
    ("EQ-fe_field", "字段字典改值",
     f"UPDATE {DRILL_DB_B}.fe_field SET label = CONCAT(label, '（B 漂移）') LIMIT 1;"),
    ("EQ-rpt_type", "报告单类型多一行",
     f"INSERT INTO {DRILL_DB_B}.rpt_type"
     f" (code, name, form_code, name_required, default_digitize_status, sort_no, status, remark)"
     f" VALUES ('DRIFT_RPT', '漂移注入类型（示例）', 'A_ANNUAL_FU', 0, 'PAPER', 99, 'ENABLED',"
     f" 'drill 注入（示例）');"),
    ("EQ-ntf_rule", "通知规则缺一行",
     f"DELETE FROM {DRILL_DB_B}.ntf_rule LIMIT 1;"),
    ("CT-sys_role_menu", "角色授权缺行",
     f"DELETE FROM {DRILL_DB_B}.sys_role_menu LIMIT 1;"),
]


def expect(findings: list[Finding], check_id: str, ok_expected: bool, step: str) -> list[str]:
    problems: list[str] = []
    matched = [f for f in findings if f.check_id == check_id]
    if not matched:
        problems.append(f"{step}：找不到核对项 {check_id}")
    elif matched[0].ok != ok_expected:
        problems.append(
            f"{step}：期望 {check_id} {'PASS' if ok_expected else 'FAIL'}，实际相反（{matched[0].detail}）")
    return problems


def run_drill() -> int:
    print(f"== E23-T15 演练（{DRILL_DB_A} → {DRILL_DB_B}，compose MySQL）==\n")
    runner = DrillRunner()
    problems: list[str] = []

    print("-- 1. 建「B 初始化终态」克隆库（结构全量 + 配置表行 + 模板语义处置）")
    runner.exec_fixture(drill_fixture_sql())

    print("-- 2. 生产同款核对：应全部 PASS")
    findings = run_compare(runner, DRILL_DB_A, DRILL_DB_B)
    print_findings(findings)
    problems += [f"基线应 PASS：{f.check_id}（{f.detail}）" for f in findings if not f.ok]

    print("\n-- 3. 注入四类差异：逐项检出 FAIL（定向复核注入表）")
    for check_id, title, sql in DRIFT_INJECTIONS:
        runner.exec_fixture(sql)
        after = run_compare(runner, DRILL_DB_A, DRILL_DB_B, only={check_id.split("-", 1)[1]})
        target = [f for f in after if f.check_id == check_id]
        print(f"   注入「{title}」→ {check_id} = {'FAIL（检出）' if target and not target[0].ok else '未检出！'}")
        problems += expect(after, check_id, ok_expected=False, step=f"注入 {title}")

    print("\n-- 4. 清场（整库删除，归零）")
    runner.exec_fixture(f"DROP DATABASE IF EXISTS {DRILL_DB_B};")
    residue = runner.scalar(f"SELECT COUNT(*) FROM information_schema.schemata"
                            f" WHERE schema_name = '{DRILL_DB_B}'")
    if residue != "0":
        problems.append(f"清场失败：{DRILL_DB_B} 仍存在")

    if problems:
        print("\n演练未通过：")
        for p in problems:
            print(f"  - {p}")
        return 1
    print("\n演练通过：基线全 PASS、四类差异逐项检出、清场归零。")
    return 0


# ---------------------------------------------------------------------------
# selftest（不联网、不连库）
# ---------------------------------------------------------------------------


def parse_registry_tables() -> set[str]:
    """解析 ConfigTableRegistry.java 的登记表名（entry/structure 首个字符串参数）。"""
    src = REGISTRY_JAVA.read_text(encoding="utf-8")
    return set(re.findall(r"\b(?:entry|structure)\(\s*\n?\s*\"([a-z_]+)\"", src))


def run_selftest() -> int:
    print("== E23-T15 工具自检（不联网、不连库）==\n")
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    # 1. 比对清单 ↔ ConfigTableRegistry 完全一致（防工具口径漂移）
    registry = parse_registry_tables()
    tool_tables = {t for t, _ in EQUALITY_QUERIES} | {t for t, _ in CONTAINMENT_QUERIES} | set(NOT_COMPARED)
    check(f"注册表 {len(registry)} 张全部被工具覆盖（多出：{sorted(tool_tables - registry) or '无'}；"
          f"遗漏：{sorted(registry - tool_tables) or '无'}）", tool_tables == registry)
    eq_tables = {t for t, _ in EQUALITY_QUERIES}
    ct_tables = {t for t, _ in CONTAINMENT_QUERIES}
    check(f"等价 24 张 + 包含 4 张 + 口径不比对 {len(NOT_COMPARED)} 张（实际 {len(eq_tables)} / "
          f"{len(ct_tables)} / {len(NOT_COMPARED)}）",
          len(eq_tables) == 24 and len(ct_tables) == 4 and len(NOT_COMPARED) == 2)
    check("等价 / 包含 / 不比对三组互不重叠",
          not (eq_tables & ct_tables) and not (eq_tables & set(NOT_COMPARED))
          and not (ct_tables & set(NOT_COMPARED)))

    # 2. 只读护栏：所有核对语句 SELECT 开头；写语句被拒
    all_sql = [sql for _, sql in EQUALITY_QUERIES + CONTAINMENT_QUERIES] \
        + [sql for _, _, sql in REDLINE_QUERIES]
    check(f"核对语句全部只读（{len(all_sql)} 条 SELECT）",
          all(not WRITE_PATTERN.match(sql) for sql in all_sql))
    guarded = False
    try:
        assert_readonly("DROP DATABASE t_x")
    except RuntimeError:
        guarded = True
    check("写语句被只读护栏拦截", guarded)

    # 3. 占位符与库名替换：每条查询都带 {db} 且替换后不残留
    check("查询模板均含 {db} 占位符且可完整替换",
          all("{db}" in sql and "{db}" not in sql.replace("{db}", "`x`")
              for _, sql in EQUALITY_QUERIES + CONTAINMENT_QUERIES))

    # 4. 多重集相等 / 包含判定逻辑（差异注入检出的纯逻辑验证）
    a = multiset([["1", "x"], ["1", "x"], ["2", "y"]])
    b_same = multiset([["2", "y"], ["1", "x"], ["1", "x"]])
    b_dup = multiset([["1", "x"], ["1", "x"], ["1", "x"], ["2", "y"]])
    b_miss = multiset([["1", "x"], ["2", "y"]])
    check("多重集相等判定（重复行计数）", a == b_same and a != b_dup and a != b_miss)
    check("包含判定（A ⊆ B 且重复行数不亏）", not (a - b_dup) and not (a - b_same) and (a - b_miss))
    check("差异样例输出（多出 / 缺少两侧）",
          "B 多出" in diff_sample(a, b_dup, "B") and "B 缺少" in diff_sample(a, b_miss, "B"))

    # 5. 演练夹具与注入常量自洽
    check("演练注入覆盖四类且 check id 均在比对清单",
          len(DRIFT_INJECTIONS) == 4
          and all(cid.split("-", 1)[1] in (eq_tables | ct_tables) for cid, _, _ in DRIFT_INJECTIONS))
    fixture = drill_fixture_sql.__doc__ or ""
    check("夹具语义注释齐备（术者剔除 / 病区零行 / 用户引用置空 / 业务表空壳）",
          all(k in fixture for k in ("术者", "ward", "用户引用", "患者业务表")))

    # 6. 环境变量口令约定（与 golive 工具一致，不进 argv）
    check("生产模式口令环境变量名已约定（CRDP_TENANTB_MYSQL_PASSWORD）",
          "CRDP_TENANTB_MYSQL_PASSWORD" in Path(__file__).read_text(encoding="utf-8"))

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    print(f"\n自检完成：{len(checks) - len(failed)} PASS / {len(failed)} FAIL")
    return 0 if not failed else 1


# ---------------------------------------------------------------------------
# 默认模式：runbook / tasks.csv / IT / CI 防漂移校验
# ---------------------------------------------------------------------------


def e23_t15_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T15":
                return row
    return None


def run_default() -> int:
    print("== E23-T15 runbook 防漂移校验（不联网、不连库）==\n")
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    runbook = RUNBOOK.read_text(encoding="utf-8") if RUNBOOK.exists() else ""
    it = IT.read_text(encoding="utf-8") if IT.exists() else ""
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    readme = DOCS_README.read_text(encoding="utf-8") if DOCS_README.exists() else ""

    # ---- 1. tasks.csv 行 ----
    row = e23_t15_row()
    check("tasks.csv 有 E23-T15 行", row is not None)
    if row:
        check("任务标题与类型（用 A 配置模板初始化 B 租户 · 全栈）",
              "初始化 B 租户" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "全栈")
        check("依赖 E12-T05（开通模板路径，runbook §2 G3 一致）", "E12-T05" in (row.get("依赖") or ""))
        check("关联需求 A-FLD-014", "A-FLD-014" in (row.get("关联需求") or ""))
        check("验收原句（B 租户开通，配置与 A 一致）",
              (row.get("验收标准") or "").strip() == "B 租户开通，配置与 A 一致")

    # ---- 2. runbook 锚点 ----
    check("runbook 存在（docs/ops/hospital-b-onboarding.md）", bool(runbook))
    check("头部任务行（E23-T15 · 依赖 E12-T05 · 验收原句 · W37）",
          all(k in runbook for k in ("任务 E23-T15", "E12-T05", "**B 租户开通，配置与 A 一致**", "W37")))
    check("放行闸门表（G1 A 上线观察期 / G2 A 配置冻结 / G3 模板路径 IT / G4 生产基线 / G5 租户信息）",
          all(k in runbook for k in ("G1", "G2", "G3", "G4", "G5", "hospital-a-hypercare.md")))
    check("开通步骤（导出模板 API + 向导带 templateId + config-template SUCCESS + 转正）",
          all(k in runbook for k in (
              "POST /api/platform/config-templates", "POST /api/platform/tenants",
              "templateId", "config-template", "TRIAL", "init_template_id", "action=ENABLE")))
    check("两级核对（§5.1 机器侧 check_tenant_b_onboarding.py + §5.2 人工抽查每域 3–5 项）",
          "check_tenant_b_onboarding.py" in runbook and "人工抽查" in runbook)
    check("核对口径（24 张等价表 + 角色并集包含 + 只复制红线；不比自增 ID / 用户引用 / 时间列）",
          all(k in runbook for k in ("24 张等价表", "包含", "红线", "OQ-90", "OQ-91")))
    check("第 14 章 B 差异处置表与承接任务（E23-T16 / T17 / T18 / T28 / T19）",
          all(k in runbook for k in ("第 14 章", "E23-T16", "E23-T17", "E23-T18", "E23-T28", "E23-T19")))
    check("中止与回滚（config-template 重试 + 整体中止走 E05-T08 注销 + A 不受影响）",
          all(k in runbook for k in ("PROVISIONING", "E05-T08", "A 不受影响")))
    check("由人执行清单（AGENTS §9：向导操作 / 人工抽查 / 归档）", "由人执行" in runbook)
    check("自动化对照（TenantBOnboardingMySqlIT + 本工具三模式 + CI 步骤名）",
          all(k in runbook for k in ("TenantBOnboardingMySqlIT", "--selftest", "--drill", "医院 B 初始化校验（E23-T15）")))

    # ---- 3. 向导路径演练 IT ----
    check("TenantBOnboardingMySqlIT 存在", bool(it))
    if it:
        check("IT 门控 CRDP_IT_MYSQL_PORT 且 local 剖面", "CRDP_IT_MYSQL_PORT" in it and '"local"' in it)
        check("IT 断言全注册表（30 张 / 24 张等价表 + 角色包含）",
              "30" in it and "dash_role_layout" in it and "containsAll" in it)
        check("IT 自建自清（DROP DATABASE IF EXISTS）与虚构标记（示例）",
              "DROP DATABASE IF EXISTS" in it and "示例" in it)

    # ---- 4. 接线 ----
    check("ci.yml 三模式接线（selftest / 默认 / drill）",
          ci.count("check_tenant_b_onboarding.py") >= 3
          and "check_tenant_b_onboarding.py --selftest" in ci
          and "check_tenant_b_onboarding.py --drill" in ci)
    check("docs/README.md 索引 runbook", "hospital-b-onboarding.md" in readme)

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    print(f"\n校验完成：{len(checks) - len(failed)} PASS / {len(failed)} FAIL")
    return 0 if not failed else 1


# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true", help="纯逻辑自检（不联网、不连库）")
    parser.add_argument("--drill", action="store_true",
                        help="compose MySQL 演练：克隆 A 配置为 B 终态 → 全 PASS → 差异注入检出 → 清场")
    parser.add_argument("--host", help="生产核对：RDS 主机（只读）")
    parser.add_argument("--port", default="3306", help="生产核对：端口（默认 3306）")
    parser.add_argument("--db-a", help="生产核对：医院 A 租户库名")
    parser.add_argument("--db-b", help="生产核对：医院 B 租户库名")
    parser.add_argument("--user", help="生产核对：只读账号")
    args = parser.parse_args()

    if args.selftest:
        return run_selftest()
    if args.drill:
        return run_drill()
    if args.host or args.db_a or args.db_b or args.user:
        missing = [name for name, val in (
            ("--host", args.host), ("--db-a", args.db_a),
            ("--db-b", args.db_b), ("--user", args.user)) if not val]
        if missing:
            raise SystemExit(f"生产核对缺少参数：{' '.join(missing)}")
        runner = RemoteRunner(args.host, args.port, args.user)
        findings = run_compare(runner, args.db_a, args.db_b)
        return 0 if print_findings(findings) else 1
    return run_default()


if __name__ == "__main__":
    sys.exit(main())
