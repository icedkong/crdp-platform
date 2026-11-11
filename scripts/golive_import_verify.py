#!/usr/bin/env python3
"""医院 A 正式数据导入核对工具与上线切换 runbook 防漂移校验（E23-T11 · W36）。

任务口径（`docs/tasks/tasks.csv` E23-T11：运维，依赖 E23-T07 + E22-T10，验收「生产导入核对
通过，A 上线」）：真实生产执行（正式文件交接、生产导入、DNS 切换、租户转正）由持华为云账号
的运维 / 实施在场完成（`AGENTS.md` 第 9 节：运维任务真实云操作不执行，只产出脚本 / 文档）。
本工具交付其中「机器可核对」的部分：

1. 默认（不连库、不联网）：runbook（`docs/ops/hospital-a-go-live.md`）防漂移校验——放行闸门、
   核对口径、回滚引用、转正动作（PLT-TEN-010 `POST /api/platform/tenants/{tenantId}/status`
   action=ENABLE）与 tasks.csv / 既有文档互引一致；ci.yml 已接线；docs/README.md 已索引；
   台账必填键清单与脚本 `LEDGER_REQUIRED_KEYS` 完全一致；
2. `--selftest`（不联网、不连库）：纯逻辑自检——台账校验（缺键 / 负数 / 注入式 marker /
   非数字年份）、只读护栏（生产核对语句必须 SELECT / SHOW / WITH 开头，写语句一律拒绝）、
   病区分布比对、演练夹具常量自洽；
3. `--drill`（docker compose 的 MySQL，演示租户库 t_demoa）：按真实 Flyway schema 造一个
   虚构导入终态（60 名患者，标记 `GLVDRL-`，AGENTS §5.5 全虚构），跑生产同款核对 →
   全部 PASS；随后注入四类差异（少一名患者 / 多一条年度记录 / 误建随访任务 / 缺提交审计）
   → 逐项检出 FAIL；结束清理（审计行经 `@crdp_audit_maintenance` 维护逃生口删除，
   与后端测试自清理同口径，V17 触发器注释）；
4. 生产核对（由持账号运维执行，只读）：`--host/--port/--db/--user --job <imp_job id>
   --ledger <台账.json>`——口令只经环境变量 `CRDP_GOLIVE_MYSQL_PASSWORD`（或 `MYSQL_PWD`）
   进 mysql 客户端，不进 argv、不落盘；对生产租户库执行 SELECT 输出核对表（作业状态 /
   批次统计 / 患者主档 / 年度记录分布 / 入组 / 联系方式 / 基线分节 / 任务=0 / 预检警告 /
   提交审计在册 / 批次写数清单 / 病区分布，口径 = `docs/ops/hospital-a-import-rehearsal.md`
   §3 汇总数核对）。抽样核对（病案号 / 姓名等明文字段逐字段比对）需明文权限 + 二次确认，
   属人工步骤，本工具不做（runbook §5）。

台账（ledger.json）= 源表基准（E23-T05 样本口径或正式文件行数统计），必填键见
`LEDGER_REQUIRED_KEYS`；marker 为队列标记（正式导入以原系统编号前缀圈定，演练用虚构前缀），
白名单 `^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$` 防注入。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNBOOK = ROOT / "docs/ops/hospital-a-go-live.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
DOCS_README = ROOT / "docs/README.md"

# 演练环境（与 check_restore_script.py / cross_region_drill 同一 compose MySQL）
DRILL_DB = "t_demoa"
DRILL_MARKER = "GLVDRL"
DRILL_JOB_ID = 990011

# 台账必填键（runbook §5 的清单与此处必须逐字一致，防漂移由默认模式校验）
LEDGER_REQUIRED_KEYS = [
    "marker",
    "jobId",
    "patients",
    "batches",
    "wardCounts",
    "histImportByYear",
    "histMissed",
    "phone1",
    "phone2",
    "sections",
    "warnBoolean01",
    "warnOptionCodeText",
]
LEDGER_OPTIONAL_KEYS = ["updatedPatients", "skippedPatients"]

MARKER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,31}$")
WRITE_PATTERN = re.compile(
    r"^\s*(insert|update|delete|replace|create|alter|drop|truncate|rename|grant"
    r"|revoke|lock|call|load|set|merge)\b",
    re.I,
)


# ---------------------------------------------------------------------------
# 只读护栏：生产核对路径上的每一条语句都必须是只读的
# ---------------------------------------------------------------------------


def assert_readonly(sql: str) -> None:
    """非 SELECT / SHOW / WITH 开头的语句一律拒绝（生产租户库只读红线）。"""
    stripped = sql.strip().rstrip(";").strip()
    if not stripped or WRITE_PATTERN.match(stripped):
        raise AssertionError(f"生产核对语句必须只读（SELECT / SHOW / WITH）：{stripped[:80]}")
    if ";" in stripped:
        raise AssertionError(f"生产核对语句不允许多语句：{stripped[:80]}")


# ---------------------------------------------------------------------------
# 台账（源表基准）
# ---------------------------------------------------------------------------


def validate_ledger(data: dict) -> tuple[list[str], dict]:
    """校验台账 JSON，返回（问题清单，规范化台账）。marker 白名单防 SQL 注入。"""
    problems: list[str] = []
    if not isinstance(data, dict):
        return ["台账必须是 JSON 对象"], {}
    for key in LEDGER_REQUIRED_KEYS:
        if key not in data:
            problems.append(f"台账缺必填键 {key}")
    if problems:
        return problems, {}

    marker = data["marker"]
    if not isinstance(marker, str) or not MARKER_PATTERN.match(marker):
        problems.append(f"marker 只允许字母数字开头、含 -_ 的 2~32 位串（防注入）：{marker!r}")
    job_id = data["jobId"]
    if not isinstance(job_id, int) or isinstance(job_id, bool) or job_id <= 0:
        problems.append("jobId 必须是正整数（imp_job.id）")
    for key in ("patients", "batches", "histMissed", "phone1", "phone2", "sections",
                "warnBoolean01", "warnOptionCodeText"):
        if not isinstance(data[key], int) or isinstance(data[key], bool) or data[key] < 0:
            problems.append(f"{key} 必须是非负整数")
    for key in LEDGER_OPTIONAL_KEYS:
        if key in data and (not isinstance(data[key], int) or isinstance(data[key], bool)
                            or data[key] < 0):
            problems.append(f"可选键 {key} 必须是非负整数")
    ward_counts = data["wardCounts"]
    if not isinstance(ward_counts, dict) or not ward_counts:
        problems.append("wardCounts 必须是 {病区id: 患者数} 的非空对象")
    else:
        for ward, count in ward_counts.items():
            if not re.fullmatch(r"\d+", str(ward)):
                problems.append(f"wardCounts 键必须是数字病区 id：{ward!r}")
            if not isinstance(count, int) or isinstance(count, bool) or count < 0:
                problems.append(f"wardCounts[{ward}] 必须是非负整数")
    years = data["histImportByYear"]
    if not isinstance(years, dict):
        problems.append("histImportByYear 必须是 {随访年度: 记录数} 对象")
    else:
        for year, count in years.items():
            if not re.fullmatch(r"\d+", str(year)):
                problems.append(f"histImportByYear 键必须是数字年度：{year!r}")
            if not isinstance(count, int) or isinstance(count, bool) or count < 0:
                problems.append(f"histImportByYear[{year}] 必须是非负整数")
    if problems:
        return problems, {}
    if sum(ward_counts.values()) != data["patients"]:
        problems.append(
            f"wardCounts 合计 {sum(ward_counts.values())} != patients {data['patients']}")
    return problems, {
        "marker": marker,
        "jobId": job_id,
        "patients": data["patients"],
        "batches": data["batches"],
        "wardCounts": {str(k): v for k, v in ward_counts.items()},
        "histImportByYear": {str(k): v for k, v in years.items()},
        "histMissed": data["histMissed"],
        "phone1": data["phone1"],
        "phone2": data["phone2"],
        "sections": data["sections"],
        "warnBoolean01": data["warnBoolean01"],
        "warnOptionCodeText": data["warnOptionCodeText"],
        "updatedPatients": data.get("updatedPatients", 0),
        "skippedPatients": data.get("skippedPatients", 0),
    }


# ---------------------------------------------------------------------------
# SQL 执行器：drill（docker compose 容器内 root）与生产（mysql 客户端，口令经环境变量）
# ---------------------------------------------------------------------------


class DrillRunner:
    """compose MySQL（本地 / CI 演练）。夹具写入经 exec_fixture（仅 --drill 路径）。"""

    def __init__(self, db: str = DRILL_DB) -> None:
        self.db = db

    def _exec(self, sql: str) -> str:
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
             f'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B {self.db}'],
            cwd=ROOT, input=sql, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        return proc.stdout

    def exec_fixture(self, sql: str) -> None:
        """夹具写入 / 清理（INSERT / DELETE，仅 --drill 允许）。"""
        self._exec(sql)

    def rows(self, sql: str) -> list[list[str]]:
        assert_readonly(sql)
        out = self._exec(sql)
        return [line.split("\t") for line in out.splitlines() if line]

    def scalar(self, sql: str) -> str:
        rows = self.rows(sql)
        return rows[0][0] if rows else ""


class RemoteRunner:
    """生产租户库（只读）。口令只经 CRDP_GOLIVE_MYSQL_PASSWORD / MYSQL_PWD 环境变量。"""

    def __init__(self, host: str, port: str, db: str, user: str) -> None:
        self.host = host
        self.port = port
        self.db = db
        self.user = user
        password = os.environ.get("CRDP_GOLIVE_MYSQL_PASSWORD") or os.environ.get("MYSQL_PWD")
        if not password:
            raise SystemExit(
                "缺少数据库口令：请设置环境变量 CRDP_GOLIVE_MYSQL_PASSWORD（只进 mysql 客户端"
                "进程环境，不进 argv、不落盘；runbook §5）")
        self.env = {**os.environ, "MYSQL_PWD": password}

    def rows(self, sql: str) -> list[list[str]]:
        assert_readonly(sql)
        proc = subprocess.run(
            ["mysql", f"-h{self.host}", f"-P{self.port}", f"-u{self.user}",
             "-N", "-B", "-e", sql, self.db],
            env=self.env, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        return [line.split("\t") for line in proc.stdout.splitlines() if line]

    def scalar(self, sql: str) -> str:
        rows = self.rows(sql)
        return rows[0][0] if rows else ""


# ---------------------------------------------------------------------------
# 核对检查（生产与演练同一套；全部只读 SELECT；口径 = E23-T06 §3 汇总数核对）
# ---------------------------------------------------------------------------


class Finding:
    def __init__(self, check_id: str, title: str, ok: bool, detail: str) -> None:
        self.check_id = check_id
        self.title = title
        self.ok = ok
        self.detail = detail


def run_verify(runner: DrillRunner | RemoteRunner, ledger: dict) -> list[Finding]:
    marker = ledger["marker"]
    job = ledger["jobId"]
    cohort = f"'{marker}-L%'"  # marker 已过白名单
    findings: list[Finding] = []

    def check(check_id: str, title: str, expected: str, actual_sql: str) -> None:
        actual = runner.scalar(actual_sql)
        findings.append(Finding(check_id, title, expected == actual,
                                f"台账 {expected} / 库内 {actual or '0'}"))

    def check_bool(check_id: str, title: str, ok: bool, detail: str) -> None:
        findings.append(Finding(check_id, title, ok, detail))

    # ① 作业终态：DONE 且暂存行数 = 源表行数（向导结果页的库内对应）
    status = runner.scalar(f"SELECT status FROM imp_job WHERE id = {job}")
    staged = runner.scalar(f"SELECT staged_rows FROM imp_job WHERE id = {job}")
    check_bool("C1", "作业终态 DONE 且暂存行数 = 台账",
               status == "DONE" and staged == str(ledger["patients"]),
               f"status={status or '无此作业'}、staged_rows={staged}")

    # ① 批次：总数与 DONE 数 = 台账批次数，无失败 / 未完成批次
    batch_total = runner.scalar(
        f"SELECT COUNT(*) FROM imp_batch WHERE job_id = {job}")
    batch_done = runner.scalar(
        f"SELECT COUNT(*) FROM imp_batch WHERE job_id = {job} AND status = 'DONE'")
    check_bool("C2", "批次全部 DONE、无失败批次",
               batch_total == batch_done == str(ledger["batches"]),
               f"批次总数 {batch_total}、DONE {batch_done}、台账 {ledger['batches']}")

    # ① 患者计数：新建 + 更新 + 跳过 = 源表行数（重导时更新数以台账可选键为准）
    created = runner.scalar(
        f"SELECT COALESCE(SUM(created_patients), 0) FROM imp_batch WHERE job_id = {job}")
    updated = runner.scalar(
        f"SELECT COALESCE(SUM(updated_patients), 0) FROM imp_batch WHERE job_id = {job}")
    skipped = runner.scalar(
        f"SELECT COALESCE(SUM(skipped_patients), 0) FROM imp_batch WHERE job_id = {job}")
    check_bool("C3", "批次统计 新建+更新+跳过 = 台账行数",
               int(created or 0) + int(updated or 0) + int(skipped or 0) == ledger["patients"]
               and int(updated or 0) == ledger["updatedPatients"]
               and int(skipped or 0) == ledger["skippedPatients"],
               f"新建 {created}、更新 {updated}（台账 {ledger['updatedPatients']}）、"
               f"跳过 {skipped}（台账 {ledger['skippedPatients']}）")

    # ② 库内患者主档：按队列标记圈定 = 台账（deleted=0，软删行不计）
    check("C4", "患者主档数 = 台账（队列标记圈定）", str(ledger["patients"]),
          f"SELECT COUNT(*) FROM pt_patient WHERE legacy_no LIKE {cohort} AND deleted = 0")

    # ② 年度记录分布：HIST_IMPORT 按年度逐个数 + HIST_MISSED 合计（Q06）
    for year, expected in ledger["histImportByYear"].items():
        check(f"C5.Y{year}", f"第 {year} 年 HIST_IMPORT 记录数 = 台账", str(expected),
              "SELECT COUNT(*) FROM fu_record fr JOIN pt_patient p ON fr.patient_id = p.id"
              f" WHERE p.legacy_no LIKE {cohort} AND fr.source = 'HIST_IMPORT'"
              f" AND fr.followup_year = {year}")
    check("C6", "历史未随访（HIST_MISSED）记录数 = 台账", str(ledger["histMissed"]),
          "SELECT COUNT(*) FROM fu_record fr JOIN pt_patient p ON fr.patient_id = p.id"
          f" WHERE p.legacy_no LIKE {cohort} AND fr.source = 'HIST_MISSED'")

    # ② 入组（auto_enroll 预置计划）
    check("C7", "入组数 = 台账", str(ledger["patients"]),
          "SELECT COUNT(*) FROM fu_enrollment e JOIN pt_patient p ON e.patient_id = p.id"
          f" WHERE p.legacy_no LIKE {cohort}")

    # ② 联系方式：电话1 / 电话2 槽位，来源 = 历史导入，每槽位恰一版
    for slot, key in (("PHONE_1", "phone1"), ("PHONE_2", "phone2")):
        check(f"C8.{slot}", f"{slot} 联系方式版本数 = 台账", str(ledger[key]),
              "SELECT COUNT(*) FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id"
              f" WHERE p.legacy_no LIKE {cohort} AND c.slot = '{slot}'"
              " AND c.source = 'IMPORT'")

    # ② 基线分节
    check("C9", "基线分节数 = 台账", str(ledger["sections"]),
          "SELECT COUNT(*) FROM pt_baseline_section bs JOIN pt_patient p ON"
          f" bs.patient_id = p.id WHERE p.legacy_no LIKE {cohort}")

    # ④ 任务 = 0（A-PAT-018 流程 6：历史导入只入组不建任务）
    check("C10", "随访任务数 = 0（导入不建任务）", "0",
          "SELECT COUNT(*) FROM fu_task t JOIN pt_patient p ON t.patient_id = p.id"
          f" WHERE p.legacy_no LIKE {cohort}")

    # ⑥ 预检警告清单：Q36 / Q37 脏格数一致、错误为 0（人工复核清单的输入）
    check("C11", "Q36 警告（BOOLEAN_01）= 台账脏格数", str(ledger["warnBoolean01"]),
          f"SELECT COUNT(*) FROM imp_issue WHERE job_id = {job} AND level = 'WARN'"
          " AND code = 'BOOLEAN_01'")
    check("C12", "Q37 警告（OPTION_CODE_TEXT）= 台账脏格数",
          str(ledger["warnOptionCodeText"]),
          f"SELECT COUNT(*) FROM imp_issue WHERE job_id = {job} AND level = 'WARN'"
          " AND code = 'OPTION_CODE_TEXT'")
    check("C13", "预检错误 = 0", "0",
          f"SELECT COUNT(*) FROM imp_issue WHERE job_id = {job} AND level = 'ERROR'")

    # 审计在册：上传 / 确认提交各恰 1 条（全程留痕，回滚取证的前提）
    for action in ("IMPORT_UPLOAD", "IMPORT_COMMIT"):
        check(f"C14.{action}", f"审计 {action} 恰 1 条", "1",
              "SELECT COUNT(*) FROM aud_log WHERE action = '" + action + "'"
              f" AND object_type = 'IMPORT_JOB' AND object_id = '{job}'")

    # 批次写数清单：新建患者逐条在册（按批回滚的反查依据，E18-T05）
    check("C15", "批次写数清单 PATIENT 行 = 新建患者数", created or "0",
          f"SELECT COUNT(*) FROM imp_batch_write WHERE job_id = {job}"
          " AND entity_type = 'PATIENT' AND created_flag = 1")

    # ⑤ 病区分布（患者列表页按病区筛选的库内对应）
    ward_rows = runner.rows(
        "SELECT p.ward_id, COUNT(*) FROM pt_patient p WHERE p.legacy_no LIKE " + cohort
        + " AND p.deleted = 0 GROUP BY p.ward_id ORDER BY p.ward_id")
    actual_wards = {row[0]: int(row[1]) for row in ward_rows if len(row) == 2}
    expected_wards = ledger["wardCounts"]
    check_bool("C16", "病区分布 = 台账", actual_wards == expected_wards,
               f"台账 {expected_wards} / 库内 {actual_wards}")

    return findings


def print_findings(findings: list[Finding]) -> bool:
    width = max(len(f.title) for f in findings)
    all_ok = True
    for f in findings:
        mark = "PASS" if f.ok else "FAIL"
        all_ok = all_ok and f.ok
        print(f"[{mark}] {f.check_id:<8} {f.title:<{width}}  {f.detail}")
    return all_ok


# ---------------------------------------------------------------------------
# 演练夹具（--drill）：真实 Flyway schema 上的虚构导入终态 + 差异注入检出
# ---------------------------------------------------------------------------

D = "2026-07-13"  # 演练出院日期（虚构；W36 当周）


def drill_ledger() -> dict:
    return {
        "marker": DRILL_MARKER,
        "jobId": DRILL_JOB_ID,
        "patients": 60,
        "batches": 1,
        "wardCounts": {"2": 30, "3": 30},
        "histImportByYear": {"1": 40, "2": 30, "3": 20},
        "histMissed": 25,
        "phone1": 55,
        "phone2": 20,
        "sections": 120,
        "warnBoolean01": 6,
        "warnOptionCodeText": 4,
    }


def drill_fixture_sql() -> str:
    """60 名虚构患者的「导入 DONE」终态（与台账 drill_ledger 逐项相等）。"""
    patients = []
    for i in range(1, 61):
        ward = 2 if i <= 30 else 3
        patients.append(
            f"('drill-fixture-mrn-{i}', '{DRILL_MARKER}-B{i:03d}',"
            f" 'drill-fixture-name-{i}', '{DRILL_MARKER}-N{i:03d}', 'M',"
            f" {ward}, '{D}', '{D}', '{DRILL_MARKER}-L{i:04d}')")
    patients_sql = ",\n".join(patients)
    issues = []
    for i in range(1, 7):  # Q36 脏格（示例）
        issues.append(f"({DRILL_JOB_ID}, '宽表', {i}, 12, 'L', 'WARN', 'BOOLEAN_01',"
                      " '是否类填 0/1，按空值导入待补录（演练示例）')")
    for i in range(1, 5):  # Q37 脏格（示例）
        issues.append(f"({DRILL_JOB_ID}, '宽表', {i}, 15, 'O', 'WARN', 'OPTION_CODE_TEXT',"
                      " '编码+文字，取编码入值、文字进备注（演练示例）')")
    issues_sql = ",\n".join(issues)
    return f"""
INSERT INTO imp_job (id, file_name, file_size, status, options_json, staged_rows,
                     operator_id, operator_name)
VALUES ({DRILL_JOB_ID}, 'drill-golive-fixture.xlsx', 1024, 'DONE',
        '{{"duplicateStrategy":"UPDATE"}}', 60, 1, 'drill（示例）');

INSERT INTO imp_batch (job_id, batch_no, first_patient_row_id, last_patient_row_id,
                       patient_count, status, created_patients, updated_patients,
                       skipped_patients, records_created)
VALUES ({DRILL_JOB_ID}, 1, 2, 61, 60, 'DONE', 60, 0, 0, 85);

INSERT INTO pt_patient (mrn_cipher, mrn_blind, name_cipher, name_blind, gender,
                        ward_id, surgery_date, discharge_date, legacy_no)
VALUES
{patients_sql};

INSERT INTO fu_enrollment (patient_id, plan_id, plan_code, start_date, mode, status)
SELECT id, 1, 'DRILL-GOLIVE', '{D}', 'AUTO', 'ACTIVE' FROM pt_patient
 WHERE legacy_no LIKE '{DRILL_MARKER}-L%';

INSERT INTO pt_contact (patient_id, slot, phone_cipher, phone_blind, effective_date,
                        source, version)
SELECT id, 'PHONE_1', 'drill-fixture-phone', CONCAT('{DRILL_MARKER}-P1-', id), '{D}',
       'IMPORT', 1
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%' LIMIT 55;

INSERT INTO pt_contact (patient_id, slot, phone_cipher, phone_blind, effective_date,
                        source, version)
SELECT id, 'PHONE_2', 'drill-fixture-phone', CONCAT('{DRILL_MARKER}-P2-', id), '{D}',
       'IMPORT', 1
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%' LIMIT 20;

INSERT INTO pt_baseline_section (patient_id, form_code, category_key,
                                 template_version)
SELECT id, 'DRILL_BASELINE', IF(id % 2 = 0, 'surgery', 'diagnosis'), 1
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%';
INSERT INTO pt_baseline_section (patient_id, form_code, category_key,
                                 template_version)
SELECT id, 'DRILL_BASELINE', IF(id % 2 = 0, 'diagnosis', 'surgery'), 1
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%';

INSERT INTO fu_record (patient_id, source, followup_year, visit_date, form_code,
                       template_version, status)
SELECT id, 'HIST_IMPORT', 1, '{D}', 'DRILL_ANNUAL', 1, 'SUBMITTED'
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%' LIMIT 40;
INSERT INTO fu_record (patient_id, source, followup_year, visit_date, form_code,
                       template_version, status)
SELECT id, 'HIST_IMPORT', 2, '{D}', 'DRILL_ANNUAL', 1, 'SUBMITTED'
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%' LIMIT 30;
INSERT INTO fu_record (patient_id, source, followup_year, visit_date, form_code,
                       template_version, status)
SELECT id, 'HIST_IMPORT', 3, '{D}', 'DRILL_ANNUAL', 1, 'SUBMITTED'
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%' LIMIT 20;
INSERT INTO fu_record (patient_id, source, followup_year, form_code,
                       template_version, status)
SELECT id, 'HIST_MISSED', 2, 'DRILL_ANNUAL', 1, 'DRAFT'
  FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%' LIMIT 25;

INSERT INTO imp_batch_write (batch_id, job_id, entity_type, entity_id, created_flag)
SELECT b.id, b.job_id, 'PATIENT', p.id, 1
  FROM imp_batch b
  JOIN pt_patient p ON p.legacy_no LIKE '{DRILL_MARKER}-L%'
 WHERE b.job_id = {DRILL_JOB_ID} AND b.batch_no = 1;

INSERT INTO imp_issue (job_id, sheet_name, row_no, col_no, col_letters, level, code,
                      message)
VALUES
{issues_sql};

INSERT INTO aud_log (event_type, user_name, module, object_type, object_id, result,
                     action, detail)
VALUES ('IMPORT', 'drill（示例）', 'followup', 'IMPORT_JOB', '{DRILL_JOB_ID}',
        'SUCCESS', 'IMPORT_UPLOAD', '上线核对演练夹具（虚构）'),
       ('IMPORT', 'drill（示例）', 'followup', 'IMPORT_JOB', '{DRILL_JOB_ID}',
        'SUCCESS', 'IMPORT_COMMIT', '上线核对演练夹具（虚构）');
"""


def drill_cleanup_sql() -> str:
    """清场：按标记 / 作业 id 删净；审计行经 @crdp_audit_maintenance 维护逃生口（同一会话）。"""
    return f"""
SET @crdp_audit_maintenance = 1;
DELETE FROM aud_log WHERE object_type = 'IMPORT_JOB' AND object_id = '{DRILL_JOB_ID}';
SET @crdp_audit_maintenance = NULL;
DELETE t FROM fu_task t JOIN pt_patient p ON t.patient_id = p.id
 WHERE p.legacy_no LIKE '{DRILL_MARKER}-L%';
DELETE fr FROM fu_record fr JOIN pt_patient p ON fr.patient_id = p.id
 WHERE p.legacy_no LIKE '{DRILL_MARKER}-L%';
DELETE c FROM pt_contact c JOIN pt_patient p ON c.patient_id = p.id
 WHERE p.legacy_no LIKE '{DRILL_MARKER}-L%';
DELETE e FROM fu_enrollment e JOIN pt_patient p ON e.patient_id = p.id
 WHERE p.legacy_no LIKE '{DRILL_MARKER}-L%';
DELETE bs FROM pt_baseline_section bs JOIN pt_patient p ON bs.patient_id = p.id
 WHERE p.legacy_no LIKE '{DRILL_MARKER}-L%';
DELETE FROM pt_patient WHERE legacy_no LIKE '{DRILL_MARKER}-L%';
DELETE FROM imp_batch_write WHERE job_id = {DRILL_JOB_ID};
DELETE FROM imp_batch WHERE job_id = {DRILL_JOB_ID};
DELETE FROM imp_issue WHERE job_id = {DRILL_JOB_ID};
DELETE FROM imp_job WHERE id = {DRILL_JOB_ID};
"""


def expect(findings: list[Finding], check_id: str, ok_expected: bool, step: str) -> list[str]:
    """演练断言：指定检查项的 PASS/FAIL 必须与预期一致。返回问题清单。"""
    problems: list[str] = []
    hit = [f for f in findings if f.check_id == check_id]
    if not hit:
        return [f"{step}：未产出检查项 {check_id}"]
    for f in hit:
        if f.ok != ok_expected:
            problems.append(f"{step}：{check_id} 预期{'PASS' if ok_expected else 'FAIL'}"
                            f" 实际{'PASS' if f.ok else 'FAIL'}（{f.detail}）")
    return problems


def run_drill() -> int:
    runner = DrillRunner()
    problems, ledger = validate_ledger(drill_ledger())
    if problems or not ledger:
        print("[E23-T11] 演练台账不合法：")
        for p in problems:
            print("  - " + p)
        return 1
    runner.exec_fixture(drill_cleanup_sql())  # 幂等：先清历史残留
    runner.exec_fixture(drill_fixture_sql())

    # ① 一致终态 → 全部 PASS（生产核对同款路径）
    findings = run_verify(runner, ledger)
    print("[E23-T11] 演练 · 一致终态核对：")
    if not print_findings(findings):
        problems.append("一致终态存在未通过项（见上）")

    # ② 差异注入 → 逐项检出（生产核对不一致时按 runbook §7 处置）
    cohort = f"'{DRILL_MARKER}-L%'"

    runner.exec_fixture(
        "SET @v = (SELECT id FROM pt_patient WHERE legacy_no LIKE "
        f"{cohort} ORDER BY id LIMIT 1);\n"
        "DELETE FROM fu_record WHERE patient_id = @v;\n"
        "DELETE FROM pt_contact WHERE patient_id = @v;\n"
        "DELETE FROM fu_enrollment WHERE patient_id = @v;\n"
        "DELETE FROM pt_baseline_section WHERE patient_id = @v;\n"
        "DELETE FROM pt_patient WHERE id = @v;")
    tampered = run_verify(runner, ledger)
    print("[E23-T11] 演练 · 差异注入①（少一名患者）：")
    print_findings(tampered)
    problems += expect(tampered, "C4", False, "差异注入①")
    problems += expect(tampered, "C7", False, "差异注入①")

    runner.exec_fixture(
        # 注入 2 条：差异注入①已使 Y1 少 1（40→39），再 +2 = 41 ≠ 40，检出「多行」
        "INSERT INTO fu_record (patient_id, source, followup_year, form_code,"
        " template_version, status) SELECT id, 'HIST_IMPORT', 1, 'DRILL_ANNUAL', 1,"
        f" 'SUBMITTED' FROM pt_patient WHERE legacy_no LIKE {cohort} LIMIT 2")
    tampered = run_verify(runner, ledger)
    print("[E23-T11] 演练 · 差异注入②（年度记录多 2 条）：")
    print_findings(tampered)
    problems += expect(tampered, "C5.Y1", False, "差异注入②")

    runner.exec_fixture(
        "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id, occurrence,"
        " task_type, plan_date, remind_start, window_start, window_end, open_date,"
        " created_logical_date) SELECT e.id, e.patient_id, 1, 1, 1, 'FORM',"
        f" '{D}', '{D}', '{D}', '{D}', '{D}', '{D}' FROM fu_enrollment e"
        f" JOIN pt_patient p ON e.patient_id = p.id WHERE p.legacy_no LIKE {cohort}"
        " LIMIT 1")
    tampered = run_verify(runner, ledger)
    print("[E23-T11] 演练 · 差异注入③（误建随访任务）：")
    print_findings(tampered)
    problems += expect(tampered, "C10", False, "差异注入③")

    runner.exec_fixture(
        "SET @crdp_audit_maintenance = 1;\n"
        "DELETE FROM aud_log WHERE object_type = 'IMPORT_JOB'"
        f" AND object_id = '{DRILL_JOB_ID}' AND action = 'IMPORT_COMMIT';\n"
        "SET @crdp_audit_maintenance = NULL;")
    tampered = run_verify(runner, ledger)
    print("[E23-T11] 演练 · 差异注入④（缺提交审计）：")
    print_findings(tampered)
    problems += expect(tampered, "C14.IMPORT_COMMIT", False, "差异注入④")

    # ③ 清场：演练队列归零（「等于没导过」口径复核，E23-T07 §3）
    runner.exec_fixture(drill_cleanup_sql())
    leftovers = runner.scalar(
        f"SELECT COUNT(*) FROM pt_patient WHERE legacy_no LIKE {cohort}")
    job_left = runner.scalar(f"SELECT COUNT(*) FROM imp_job WHERE id = {DRILL_JOB_ID}")
    if leftovers != "0" or job_left != "0":
        problems.append(f"清场未归零：患者 {leftovers}、作业 {job_left}")

    if problems:
        print("[E23-T11] 演练失败：")
        for p in problems:
            print("  - " + p)
        return 1
    print("[E23-T11] 上线核对演练通过：一致终态全 PASS，四类差异注入逐项检出，清场归零")
    return 0


# ---------------------------------------------------------------------------
# --selftest：纯逻辑自检（不联网、不连库）
# ---------------------------------------------------------------------------


def run_selftest() -> int:
    problems: list[str] = []

    def case(name: str, ok: bool) -> None:
        if not ok:
            problems.append(name)

    # 台账校验
    problems_ledger, normalized = validate_ledger(drill_ledger())
    case("演练台账应校验通过", not problems_ledger and normalized["patients"] == 60)
    bad = dict(drill_ledger())
    del bad["phone1"]
    case("缺必填键应报错", bool(validate_ledger(bad)[0]))
    bad = dict(drill_ledger())
    bad["marker"] = "GLV'; DROP TABLE pt_patient; --"
    case("注入式 marker 应拒绝", bool(validate_ledger(bad)[0]))
    for bad_marker in ("AB", "GLV L", "GLV%_", "？？？", "-GLV"):
        bad["marker"] = bad_marker
        case(f"非法 marker {bad_marker!r} 应拒绝", bool(validate_ledger(bad)[0]))
    bad = dict(drill_ledger())
    bad["marker"] = "GLVDRL"
    bad["histMissed"] = -1
    case("负数应拒绝", bool(validate_ledger(bad)[0]))
    bad["histMissed"] = 25
    bad["wardCounts"] = {"2; DROP": 30, "3": 30}
    case("wardCounts 非数字键应拒绝", bool(validate_ledger(bad)[0]))
    bad["wardCounts"] = {"2": 30, "3": 29}
    case("wardCounts 合计不等 patients 应拒绝", bool(validate_ledger(bad)[0]))
    bad["wardCounts"] = {"2": 30, "3": 30}
    bad["histImportByYear"] = {"第1年": 40}
    case("非数字年度应拒绝", bool(validate_ledger(bad)[0]))
    bad["histImportByYear"] = {"1": 40, "2": 30, "3": 20}
    bad["jobId"] = "990011"
    case("jobId 非整数应拒绝", bool(validate_ledger(bad)[0]))

    # 只读护栏
    for sql in ("SELECT 1", "  SHOW WARNINGS", "WITH x AS (SELECT 1) SELECT * FROM x"):
        try:
            assert_readonly(sql)
        except AssertionError:
            problems.append(f"只读语句被误拒：{sql}")
    for sql in ("DELETE FROM pt_patient", "insert INTO t VALUES (1)",
                "UPDATE t SET a = 1", "TRUNCATE TABLE t", "CREATE TABLE t (id INT)",
                "SELECT 1; DELETE FROM t", "SET @a = 1", "GRANT ALL ON *.* TO x",
                ""):
        try:
            assert_readonly(sql)
            problems.append(f"写语句未拦截：{sql!r}")
        except AssertionError:
            pass

    # 夹具常量自洽：分节 = 2×患者、联系方式与年度记录 = 台账、病区分布合计 = 患者
    ledger = drill_ledger()
    case("夹具分节数 = 2 × 患者", ledger["sections"] == 2 * ledger["patients"])
    case("病区分布合计 = 患者",
         sum(ledger["wardCounts"].values()) == ledger["patients"])
    case("演练 marker 白名单通过", bool(MARKER_PATTERN.match(ledger["marker"])))

    if problems:
        print("[E23-T11] selftest 失败：")
        for p in problems:
            print("  - " + p)
        return 1
    print("[E23-T11] selftest 通过：台账校验 / 只读护栏 / 夹具自洽")
    return 0


# ---------------------------------------------------------------------------
# 默认模式：runbook 防漂移校验（CI 恒跑，不连库）
# ---------------------------------------------------------------------------


def e23_t11_row() -> dict[str, str] | None:
    import csv

    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T11":
                return row
    return None


def run_doc_checks() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    runbook = RUNBOOK.read_text(encoding="utf-8") if RUNBOOK.exists() else ""
    check("runbook 存在（docs/ops/hospital-a-go-live.md）", bool(runbook))

    row = e23_t11_row()
    check("tasks.csv 有 E23-T11 行", row is not None)
    title = (row or {}).get("标题", "")
    accept = (row or {}).get("验收标准", "")
    deps = (row or {}).get("依赖", "")
    check("任务标题含 W36（与 tasks.csv 一致）", "W36" in title and "W36" in runbook)
    check("验收原句在 runbook 头部（生产导入核对通过，A 上线）",
          accept and accept in runbook)
    check("依赖 E23-T07 与 E22-T10 在 runbook 头部",
          "E23-T07" in deps and "E22-T10" in deps
          and "E23-T07" in runbook and "E22-T10" in runbook)

    # 互引文档存在且被 runbook 引用
    for doc in (
        "hospital-a-import-rehearsal.md",
        "hospital-a-import-rollback.md",
        "hospital-a-sample-intake.md",
        "pentest-remediation.md",
        "cce-prod.md",
        "cross-region-drill.md",
        "tenant-restore.md",
    ):
        check(f"引用的既有文档存在且被引用：{doc}",
              (ROOT / "docs/ops" / doc).exists() and doc in runbook)
    check("引用 UAT 手册（docs/testing/uat-hospital-a.md）与签字判据",
          (ROOT / "docs/testing/uat-hospital-a.md").exists()
          and "uat-hospital-a.md" in runbook and "签字判据" in runbook)

    # 放行闸门关键口径
    check("闸门：渗透高危清零（E22-T10）", "高危清零" in runbook)
    check("闸门：恢复演练达标（M3 出口）", "恢复演练达标" in runbook)
    check("闸门：演练放行结论（E23-T07 §6）", "放行" in runbook)

    # 上线切换动作
    check("转正动作 = PLT-TEN-010 status 接口 action=ENABLE",
          "PLT-TEN-010" in runbook and "tenants/{tenantId}/status" in runbook
          and "ENABLE" in runbook)
    check("入口切换 = DNS 切到 WAF CNAME（cce-prod §7）", "WAF" in runbook and "CNAME" in runbook)

    # 核对口径与工具
    check("汇总数核对口径引用 E23-T06 §3", "hospital-a-import-rehearsal.md" in runbook
          and "汇总数核对" in runbook and "抽样核对" in runbook)
    check("抽样核对为人工步骤（明文权限 + 二次确认）",
          "明文" in runbook and "二次确认" in runbook)
    check("工具用法（golive_import_verify.py --ledger --job）",
          "golive_import_verify.py" in runbook and "--ledger" in runbook
          and "--job" in runbook)
    check("台账必填键清单与脚本一致（防漂移）",
          all(key in runbook for key in LEDGER_REQUIRED_KEYS))
    check("口令只经环境变量（不进 argv / 不落盘）",
          "CRDP_GOLIVE_MYSQL_PASSWORD" in runbook and "不落盘" in runbook)

    # 回滚与红线
    check("回滚引用 E23-T07 决策树（hospital-a-import-rollback.md §1）",
          "hospital-a-import-rollback.md" in runbook and "决策树" in runbook)
    check("真实数据红线（不进仓库 / CI）", "不进仓库" in runbook)
    check("演练耗时预算引用 E23-T07 实测", "实测" in runbook)

    # 上线后衔接
    check("上线后衔接 E23-T12 冒烟 / E23-T13 保障",
          "E23-T12" in runbook and "E23-T13" in runbook)

    # CI 接线与索引
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 接线（--selftest / 默认防漂移 / --drill 三步）",
          "golive_import_verify.py --selftest" in ci
          and "golive_import_verify.py --drill" in ci
          and ci.count("golive_import_verify.py") >= 3)
    readme = DOCS_README.read_text(encoding="utf-8") if DOCS_README.exists() else ""
    check("docs/README.md 已索引 runbook", "hospital-a-go-live.md" in readme)

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    if failed:
        print(f"[E23-T11] runbook 防漂移校验未通过（{len(failed)} 项）")
        return 1
    print("[E23-T11] runbook 防漂移校验通过")
    return 0


# ---------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--selftest", action="store_true", help="纯逻辑自检（不联网、不连库）")
    parser.add_argument("--drill", action="store_true",
                        help="compose MySQL 演练（虚构终态全 PASS + 差异注入检出 + 清场）")
    parser.add_argument("--host", help="生产租户库地址（只读核对）")
    parser.add_argument("--port", default="3306", help="端口（默认 3306）")
    parser.add_argument("--db", help="租户库名（t_租户编码）")
    parser.add_argument("--user", help="数据库账号（只读）")
    parser.add_argument("--job", type=int, help="导入作业 imp_job.id")
    parser.add_argument("--ledger", help="台账 JSON 路径（源表基准，runbook §5）")
    args = parser.parse_args(argv)

    if args.selftest:
        return run_selftest()
    if args.drill:
        return run_drill()
    if args.host or args.db or args.user or args.job or args.ledger:
        missing = [name for name, value in (
            ("--host", args.host), ("--db", args.db), ("--user", args.user),
            ("--job", args.job), ("--ledger", args.ledger)) if not value]
        if missing:
            parser.error(f"生产核对缺参数：{'、'.join(missing)}")
        ledger_data = json.loads(Path(args.ledger).read_text(encoding="utf-8"))
        problems, ledger = validate_ledger(ledger_data)
        if problems:
            print("台账不合法：")
            for p in problems:
                print("  - " + p)
            return 1
        runner = RemoteRunner(args.host, args.port, args.db, args.user)
        findings = run_verify(runner, ledger)
        ok = print_findings(findings)
        print("[E23-T11] 生产导入核对" + ("通过（汇总数一致）" if ok else "不一致：按 runbook §7 处置"))
        return 0 if ok else 1
    return run_doc_checks()


if __name__ == "__main__":
    sys.exit(main())
