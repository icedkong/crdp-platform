#!/usr/bin/env python3
"""校验单院恢复脚本（E22-T06 · scripts/restore_tenant.sh）不漂移，并可本地演练。

按 `AGENTS.md` 第 9 节，生产恢复涉及华为云真实资源（RDS 时间点恢复到新实例），不在本地执行；
「本地全绿」因此落在两层：

1. 静态校验（CI 恒跑）：脚本语法、安全护栏（租户编码白名单、ISO-8601 恢复点、--yes 确认门、
   绝不 DROP 任何库 / 账号、不硬编码本地口令）、五阶段（preflight → export → import → switch →
   verify）与注册表切换（plt_tenant_resource + jdbc_url 重写 + 授权）齐备；
2. `--drill` 本地演练（CI 在 MySQL 起来后跑）：在 docker compose 的 MySQL 上，用已迁移的演示库
   t_demoa 复制出演练租户 t_rstcheck 与「PITR 临时实例快照」t_rstcheck_pitr，再在恢复点之后
   向生产库补一行标记数据，走脚本全流程：

   - 拒绝非法租户编码 / 非法恢复点；未加 --yes 只出计划、零改动；
   - 从 t_rstcheck_pitr 恢复 → 新库 t_rstcheck_rst* 建成、表数与行数一致、flyway_schema_history 在；
   - 注册表切换到新库（db_name / jdbc_url），用户名不变，旧库原样保留（标记行还在，新库没有——
     「恢复到指定时间点」生效的直接证据）；
   - --stop-after import 不切换；--rollback 按 manifest 恢复注册表行。

演练数据全部虚构（医院名称带「示例」），结束后清理干净，不留任何残留。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import datetime
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "restore_tenant.sh"
RUNBOOK = ROOT / "docs" / "ops" / "tenant-restore.md"
CI = ROOT / ".github" / "workflows" / "ci.yml"

# 演练租户（全部为虚构数据，AGENTS.md 第 5 节第 5 条）
TENANT = "rstcheck"
OLD_DB = f"t_{TENANT}"
PITR_DB = f"{OLD_DB}_pitr"
SOURCE_TENANT_DB = "t_demoa"  # 复制来源：本地已迁移的演示租户库
JDBC_TPL = (
    "jdbc:mysql://127.0.0.1:13306/{db}?useUnicode=true&characterEncoding=utf-8"
    "&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
)
# 恢复点之后才写入的标记行：证明新库停在恢复点、旧库未被触碰
MARKER_TABLE = "fu_saved_filter"
MARKER_USER_ID = 999999
MARKER_SQL = (
    f"INSERT INTO {MARKER_TABLE} (user_id, name, payload) VALUES "
    f"({MARKER_USER_ID}, '恢复演练标记（示例）', '{{\"filters\": []}}')"
)

# ---------------------------------------------------------------------------
# 静态校验：安全护栏与五阶段齐备，且与 runbook / CI 相互引用
# ---------------------------------------------------------------------------

# 必须存在的片段（安全护栏、参数、恢复流程关键 SQL；出现位置不约束）
REQUIRED_PRESENCE = [
    "set -euo pipefail",
    "^[a-z][a-z0-9]{1,31}$",  # 租户编码白名单（与开通编排 TenantOnboardingService 一致）
    "--restore-point",
    "fromisoformat",  # ISO-8601 带时区校验（python3）
    "--mode",
    "local-drill",
    "--source-db",
    "--docker-service",
    "--stop-after",
    "--rollback",
    "--allow-enabled",
    "mysqldump",
    "--single-transaction",
    "s/DEFINER=",  # 导入前剥离 DEFINER，避免迁移账号无 SUPER 建触发器失败
    "flyway_schema_history",  # verify 阶段核对迁移历史
    "plt_tenant_resource",  # 数据源注册表切换
    "REPLACE(jdbc_url",  # jdbc_url 指向新库
    "GRANT ALL PRIVILEGES",  # 租户账号授权新库
]

# 五阶段：函数定义按 1→5 出现，主流程调用也在全部定义之后按 1→5 出现
PHASES = ["phase_preflight", "phase_export", "phase_import", "phase_switch", "phase_verify"]

# 禁止出现：脚本绝不删除任何库 / 账号（旧库保留，清理走 runbook 人工步骤），不携带本地口令
FORBIDDEN = {
    "DROP DATABASE": "恢复脚本不得删除数据库（旧库保留供回滚，清理见 runbook 人工步骤）",
    "DROP USER": "恢复脚本不得删除账号（清理见 runbook 人工步骤）",
    "DevOnly#": "本地 / 演示口令不得复制进脚本（AGENTS.md 第 4 节）",
}

# runbook 必须覆盖的小节与关键内容（流程与脚本五阶段同口径）
REQUIRED_DOC = [
    "E22-T06",
    "tech-plan",  # §14 单院恢复：实例级 PITR → 导出 → 导入 → 切换注册表
    "生成列",  # 不用库表级时间点恢复的原因（热门 JSON 字段 / pt_implant 唯一约束）
    "iac/rds-prod",  # 依赖 E22-T04 的实例与备份
    "恢复到新实例",
    "restore_tenant.sh",
    "plt_tenant_resource",
    "滚动重启",  # 生效步骤：池在启动时按注册表重建
    "## 4. 验证清单",
    "## 5. 回滚",
    "## 6. 清理",
    "留痕",
]


def check_static(problems: list[str]) -> None:
    if not SCRIPT.exists():
        problems.append(f"缺少 {SCRIPT.relative_to(ROOT)}（E22-T06 单院恢复脚本）")
        return
    if not SCRIPT.stat().st_mode & 0o111:
        problems.append(f"{SCRIPT.relative_to(ROOT)} 必须可执行（chmod +x）")
    syntax = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
    if syntax.returncode != 0:
        problems.append(f"bash -n 语法错误：{syntax.stderr.strip()}")
    text = SCRIPT.read_text(encoding="utf-8")
    for token in REQUIRED_PRESENCE:
        if token not in text:
            problems.append(f"restore_tenant.sh 缺少片段：{token}")
    pos = -1
    for phase in PHASES:  # 函数定义序
        found = text.find(f"{phase}()", pos + 1)
        if found < 0:
            problems.append(f"restore_tenant.sh 缺少阶段函数定义（或顺序不对）：{phase}()")
        else:
            pos = found
    defs_end = max(text.find(f"{phase}()") for phase in PHASES)
    pos = defs_end
    for phase in PHASES:  # 主流程调用序（在全部定义之后）
        found = text.find(f"\n{phase}\n", pos + 1)
        if found < 0:
            problems.append(f"restore_tenant.sh 主流程缺少对 {phase} 的顺序调用")
        else:
            pos = found
    for token, why in FORBIDDEN.items():
        if token in text:
            problems.append(f"restore_tenant.sh 出现禁止内容 {token!r}：{why}")
    if not RUNBOOK.exists():
        problems.append(f"缺少 {RUNBOOK.relative_to(ROOT)}（单院恢复 runbook）")
    else:
        doc = RUNBOOK.read_text(encoding="utf-8")
        for token in REQUIRED_DOC:
            if token not in doc:
                problems.append(f"runbook 缺少内容：{token}")
    ci = CI.read_text(encoding="utf-8") if CI.exists() else ""
    if "check_restore_script.py" not in ci:
        problems.append("ci.yml 未接入 check_restore_script.py（防漂移需进 CI）")


# ---------------------------------------------------------------------------
# 本地演练：在 docker compose 的 MySQL 上全流程验证（--drill）
# ---------------------------------------------------------------------------


class Drill:
    def __init__(self) -> None:
        self.problems: list[str] = []
        self.work_dir = Path(tempfile.mkdtemp(prefix="crdp-restore-drill-"))

    # ---- 基础设施：全部经容器内 root 执行，口令不落主机 --------------------

    def exec_mysql(self, sql: str, db: str | None = None) -> str:
        tail = f" {db}" if db else ""
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
             f'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B{tail}'],
            cwd=ROOT, input=sql, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"mysql 执行失败：{proc.stderr.strip()[:400]}")
        return proc.stdout

    def rows(self, sql: str, db: str | None = None) -> list[list[str]]:
        out = self.exec_mysql(sql, db)
        return [line.split("\t") for line in out.splitlines() if line]

    def scalar(self, sql: str, db: str | None = None) -> str:
        rows = self.rows(sql, db)
        return rows[0][0] if rows else ""

    def root_password(self) -> str:
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "printenv", "MYSQL_ROOT_PASSWORD"],
            cwd=ROOT, capture_output=True, text=True,
        )
        if proc.returncode != 0 or not proc.stdout.strip():
            raise RuntimeError("取不到 MySQL root 口令：请先 docker compose up -d")
        return proc.stdout.strip()

    # ---- 恢复脚本调用 ------------------------------------------------------

    def run_restore(self, args: list[str], password: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [str(SCRIPT), "--docker-service", "mysql",
             "--target-host", "127.0.0.1", "--target-port", "3306",
             "--target-user", "root", *args],
            cwd=ROOT,
            env={"PATH": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin",
                 "CRDP_RESTORE_TARGET_PASSWORD": password},
            capture_output=True, text=True, timeout=300,
        )

    def restore_point(self) -> str:
        return (
            datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8)))
            .replace(microsecond=0)
            .isoformat()
        )

    # ---- 断言 --------------------------------------------------------------

    def ok(self, cond: bool, message: str) -> None:
        if not cond:
            self.problems.append(message)

    def db_exists(self, db: str) -> bool:
        return self.scalar(
            f"SELECT COUNT(*) FROM information_schema.schemata"
            f" WHERE schema_name = '{db}'"
        ) == "1"

    def table_count(self, db: str) -> int:
        return int(self.scalar(
            f"SELECT COUNT(*) FROM information_schema.tables"
            f" WHERE table_schema = '{db}' AND table_type = 'BASE TABLE'"
        ))

    def row_count(self, db: str, table: str, where: str = "") -> int:
        return int(self.scalar(f"SELECT COUNT(*) FROM `{table}`{(' ' + where) if where else ''}", db))

    def resource_row(self) -> list[str]:
        rows = self.rows(
            "SELECT r.db_name, r.jdbc_url, r.db_username FROM plt_tenant_resource r"
            f" JOIN plt_tenant t ON t.id = r.tenant_id WHERE t.code = '{TENANT}'",
            db="crdp_platform",
        )
        return rows[0]

    # ---- 夹具：复制演示库造出演练租户与 PITR 快照 --------------------------

    def restored_dbs(self) -> list[str]:
        # 脚本生成的新库：t_rstcheck_rst<时间戳>（可带 _<序号> 后缀）
        rows = self.rows(
            "SELECT schema_name FROM information_schema.schemata"
            f" WHERE schema_name REGEXP '^{OLD_DB}_rst[0-9]+(_[0-9]+)?$'"
        )
        return [row[0] for row in rows]

    def cleanup_fixture(self) -> None:
        self.exec_mysql(
            f"DELETE FROM plt_provision_step WHERE tenant_id IN"
            f" (SELECT id FROM plt_tenant WHERE code = '{TENANT}');"
            f"DELETE FROM plt_tenant_resource WHERE tenant_id IN"
            f" (SELECT id FROM plt_tenant WHERE code = '{TENANT}');"
            f"DELETE FROM plt_tenant WHERE code = '{TENANT}';",
            db="crdp_platform",
        )
        for db in [OLD_DB, PITR_DB, *self.restored_dbs()]:
            self.exec_mysql(f"DROP DATABASE IF EXISTS `{db}`")

    def setup_fixture(self) -> None:
        if not self.db_exists(SOURCE_TENANT_DB) or self.table_count(SOURCE_TENANT_DB) == 0:
            raise RuntimeError(
                f"{SOURCE_TENANT_DB} 未迁移（空库）：先启动应用完成 Flyway 迁移再演练"
            )
        self.cleanup_fixture()
        self.exec_mysql(
            f"CREATE DATABASE `{OLD_DB}`"
            " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
        )
        self.exec_mysql(
            f"CREATE DATABASE `{PITR_DB}`"
            " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
        )
        # 生产库（恢复点之后仍在演进）
        self.copy_db(SOURCE_TENANT_DB, OLD_DB)
        # PITR 快照：停在恢复点（比生产库早，不含标记行）
        self.copy_db(OLD_DB, PITR_DB)
        # 平台侧登记：与开通编排同构（plt_tenant + plt_tenant_resource）
        self.exec_mysql(
            f"INSERT INTO plt_tenant (code, name, status)"
            f" VALUES ('{TENANT}', '恢复演练医院（示例）', 'ENABLED')",
            db="crdp_platform",
        )
        tenant_id = self.scalar(f"SELECT id FROM plt_tenant WHERE code = '{TENANT}'", db="crdp_platform")
        self.exec_mysql(
            "INSERT INTO plt_tenant_resource"
            " (tenant_id, db_name, jdbc_url, db_username, password_cipher, secret_mode)"
            f" VALUES ({tenant_id}, '{OLD_DB}', '{JDBC_TPL.format(db=OLD_DB)}',"
            " 'crdp_app', 'local:RestoreDrill2026', 'local')",
            db="crdp_platform",
        )
        # 恢复点之后生产库新增的数据（演练结束时必须只存在于旧库）
        self.exec_mysql(MARKER_SQL, db=OLD_DB)

    def copy_db(self, src: str, dst: str) -> None:
        # 容器内 mysqldump | mysql：演练夹具保留 DEFINER 无妨（root 导入）
        dump = (
            'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction'
            " --set-gtid-purged=OFF --no-tablespaces --triggers"
            f" --default-character-set=utf8mb4 {src}"
            f' | mysql -uroot -p"$MYSQL_ROOT_PASSWORD" {dst}'
        )
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c", dump],
            cwd=ROOT, capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"复制库失败 {src}→{dst}：{proc.stderr.strip()[:400]}")


def run_drill() -> int:
    drill = Drill()
    try:
        drill.setup_fixture()
        password = drill.root_password()

        # 1) 护栏：非法租户编码 / 非法恢复点必须拒绝
        bad_code = drill.run_restore(
            ["--tenant", "Bad_Code", "--restore-point", drill.restore_point(),
             "--mode", "local-drill", "--source-db", PITR_DB, "--yes"],
            password,
        )
        drill.ok(bad_code.returncode != 0, "非法租户编码应非零退出（白名单未生效）")
        bad_point = drill.run_restore(
            ["--tenant", TENANT, "--restore-point", "2027-02-30T25:00:00+08:00",
             "--mode", "local-drill", "--source-db", PITR_DB, "--yes"],
            password,
        )
        drill.ok(bad_point.returncode != 0, "非法恢复点应非零退出（ISO-8601 校验未生效）")

        # 2) 未加 --yes：只出计划，零改动（护栏照样全跑，ENABLED 提示靠 --allow-enabled）
        plan = drill.run_restore(
            ["--tenant", TENANT, "--restore-point", drill.restore_point(),
             "--mode", "local-drill", "--source-db", PITR_DB, "--allow-enabled"],
            password,
        )
        drill.ok(plan.returncode == 0, f"计划模式应成功退出，实际 {plan.returncode}：{plan.stderr[-300:]}")
        drill.ok(drill.resource_row()[0] == OLD_DB, "计划模式不得改写注册表")
        drill.ok(not drill.restored_dbs(), "计划模式不得建库")

        # 3) --stop-after import：只建库不切换
        partial = drill.run_restore(
            ["--tenant", TENANT, "--restore-point", drill.restore_point(),
             "--mode", "local-drill", "--source-db", PITR_DB, "--allow-enabled",
             "--stop-after", "import", "--yes",
             "--work-dir", str(drill.work_dir / "stop-after")],
            password,
        )
        drill.ok(partial.returncode == 0,
                 f"--stop-after import 应成功，实际 {partial.returncode}：{partial.stderr[-400:]}")
        drill.ok(drill.resource_row()[0] == OLD_DB, "--stop-after import 不得切换注册表")

        # 4) 全流程：恢复到指定时间点并切换注册表
        restored = drill.run_restore(
            ["--tenant", TENANT, "--restore-point", drill.restore_point(),
             "--mode", "local-drill", "--source-db", PITR_DB, "--allow-enabled",
             "--yes", "--work-dir", str(drill.work_dir / "full")],
            password,
        )
        drill.ok(restored.returncode == 0,
                 f"恢复应成功，实际 {restored.returncode}：{restored.stderr[-600:]}")
        if drill.problems:
            return report(drill.problems)  # 脚本都没跑通，后续断言无意义

        db_name, jdbc_url, db_user = drill.resource_row()
        if not re.fullmatch(rf"{OLD_DB}_rst\d+(_\d+)?", db_name):
            drill.problems.append(f"注册表应指向新库 {OLD_DB}_rst*，实际 {db_name}")
            return report(drill.problems)
        drill.ok(f"/{db_name}?" in jdbc_url, f"jdbc_url 应指向新库，实际 {jdbc_url}")
        drill.ok(db_user == "crdp_app", "切换不得改用户名（沿用租户账号）")
        drill.ok(drill.table_count(db_name) == drill.table_count(PITR_DB),
                 f"新库表数 {drill.table_count(db_name)} 应与恢复点快照 {drill.table_count(PITR_DB)} 一致")
        drill.ok(drill.row_count(db_name, "flyway_schema_history") > 0,
                 "新库应有 flyway_schema_history（迁移历史随库恢复）")
        drill.ok(drill.row_count(db_name, "sys_user") == drill.row_count(PITR_DB, "sys_user"),
                 "新库 sys_user 行数应与恢复点快照一致")
        drill.ok(drill.row_count(db_name, MARKER_TABLE, f"WHERE user_id = {MARKER_USER_ID}") == 0,
                 "新库不得包含恢复点之后的标记行（时间点未生效）")
        drill.ok(drill.row_count(OLD_DB, MARKER_TABLE, f"WHERE user_id = {MARKER_USER_ID}") == 1,
                 "旧库应原样保留（标记行还在）")
        grants = drill.exec_mysql("SHOW GRANTS FOR 'crdp_app'@'%'")
        drill.ok(db_name in grants, "租户账号应获得新库授权（GRANT 未生效）")

        # 5) 回滚：按 manifest 恢复注册表行
        manifests = sorted((drill.work_dir / "full").glob("**/rollback.env"))
        if not manifests:
            drill.problems.append("缺少 rollback.env（switch 阶段应写回滚清单）")
        else:
            rollback = drill.run_restore(
                ["--rollback", str(manifests[-1]), "--yes"],
                password,
            )
            drill.ok(rollback.returncode == 0,
                     f"回滚应成功，实际 {rollback.returncode}：{rollback.stderr[-400:]}")
            rolled = drill.resource_row()
            drill.ok(rolled[0] == OLD_DB, f"回滚后 db_name 应为 {OLD_DB}，实际 {rolled[0]}")
            drill.ok(rolled[1] == JDBC_TPL.format(db=OLD_DB),
                     f"回滚后 jdbc_url 应恢复原值，实际 {rolled[1]}")
        return report(drill.problems)
    finally:
        try:
            drill.cleanup_fixture()
        except RuntimeError as exc:
            drill.problems.append(f"演练清理失败：{exc}")


def report(problems: list[str]) -> int:
    if problems:
        print(f"单院恢复脚本校验未通过，共 {len(problems)} 处：")
        for message in problems:
            print(f"  - {message}")
        return 1
    print("单院恢复脚本校验通过：五阶段护栏齐备，本地演练恢复到指定时间点、切换与回滚全部符合预期。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--drill", action="store_true",
                        help="在 docker compose 的 MySQL 上做本地演练（需先完成租户库迁移）")
    args = parser.parse_args()
    problems: list[str] = []
    check_static(problems)
    if problems:
        return report(problems)
    if args.drill:
        return run_drill()
    print("单院恢复脚本静态校验通过（加 --drill 可在本地 MySQL 演练全流程）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
