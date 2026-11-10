#!/usr/bin/env python3
"""校验跨区域恢复演练脚本（E22-T07 · scripts/cross_region_drill.sh）不漂移，并可本地演练。

按 `AGENTS.md` 第 9 节，跨区域恢复演练涉及华为云真实资源（上海一的跨区域备份、备用区域 IaC 重建、
PITR 恢复新实例），不在本地执行；「演练报告存档，区域内 RPO ≤ 5 分钟」的验收落在两层：

1. 静态校验（CI 恒跑）：脚本语法、安全护栏（区域白名单、ISO-8601 时间戳、--yes 确认门、
   绝不 DROP 任何库 / 账号、区域内 RPO ≤ 300 秒验收门、RTO ≤ 4 小时判据）、七阶段
   （plan → inventory → rebuild → restore → switch → verify → report）与报告产物
   （report.json / report.md / 存档目录）齐备，runbook / 存档记录 / CI 相互引用一致；
2. `--drill` 本地演练（CI 在 MySQL 起来后跑）：在 docker compose 的 MySQL 上，以虚构租户
   xrgcheck 演练「北京四区域不可用 → 上海一化身从跨区域副本重建恢复」全流程：

   - 夹具复制演示库造「北京四生产」（xrga_ 前缀化身库 + 区域 A 对象化身目录），按两个口径
     各做一次逻辑快照充当备份副本：跨区域副本（较早，滞后于区域内）与区域内副本；
   - 三个标记行锚定恢复点：M0 = 跨区域同步前（恢复后在）、M1 = 跨区域同步后（恢复后不在）、
     M2 = 区域内同步后（恢复后不在）——「实测 RPO」的直接数据证据；
   - 跑脚本 local-drill 模式：区域内 RPO = 宣告时刻 − 区域内最近可恢复点 ≤ 300 秒（验收门）、
     跨区域 RPO = 宣告时刻 − 跨区域最近可恢复点（无 SLA，仅实测记录）、
     RTO = 宣告 → 重建 → 恢复 → 切换 → 验证完成；报告落盘且字段与实测一致；
   - 「区域内口径」数据证据：区域内副本单独导入对照库，M1 在而 M2 不在（区域内恢复丢数据
     仅限最后一次同步之后），M0/M1/M2 三态与两份副本的时间点一一对应；
   - 注册表切换：恢复后的平台库化身里全部 jdbc_url 主机改写到备用区域实例地址；
     对象对齐：恢复点之前上传的对象在备用区域副本中必须存在；
   - 护栏：非法区域 / 非法时间戳 / 未加 --yes（只出计划零改动）/ 区域内 RPO 超 5 分钟必须拒绝。

演练数据全部虚构（医院名称带「示例」），结束后清理干净，不留任何残留。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import argparse
import datetime
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "cross_region_drill.sh"
RUNBOOK = ROOT / "docs" / "ops" / "cross-region-drill.md"
RECORDS_README = ROOT / "docs" / "ops" / "drill-records" / "README.md"
CI = ROOT / ".github" / "workflows" / "ci.yml"

# 演练租户与化身库（全部为虚构数据，AGENTS.md 第 5 节第 5 条）
TENANT = "xrgcheck"
REGION_A_PLATFORM = "xrga_platform"          # 「北京四生产」平台库化身（复制自 crdp_platform）
REGION_A_TENANT = f"xrga_t_{TENANT}"         # 「北京四生产」租户库化身（复制自 t_demoa）
WHATIF_DB = "xrgi_whatif"                    # 区域内副本对照库（数据证据）
RESTORED_PLATFORM = f"xrgb_platform_{TENANT}"  # 「上海一重建」平台库化身
JDBC_HOST_REGION_A = "xrga-prod.invalid:3306"
JDBC_HOST_STANDBY = "127.0.0.1:13306"
JDBC_TPL = (
    "jdbc:mysql://{host}/{db}?useUnicode=true&characterEncoding=utf-8"
    "&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"
)
# 标记行锚定恢复点：M0 跨区域同步前（恢复后在）、M1 跨区域同步后（不在）、M2 区域内同步后（不在）
MARKER_TABLE = "fu_saved_filter"
M0, M1, M2 = 999999, 999998, 999997
MARKER_SQL = (
    "INSERT INTO {table} (user_id, name, payload) VALUES ({uid}, '{label}', '{{\"filters\": []}}')"
)
RPO_TARGET_SECONDS = 300     # 区域内 RPO ≤ 5 分钟（NFR-AVL-002 验收门）
RTO_TARGET_SECONDS = 14400   # RTO ≤ 4 小时（NFR-AVL-002）

# ---------------------------------------------------------------------------
# 静态校验：安全护栏与七阶段齐备，且与 runbook / 存档记录 / CI 相互引用
# ---------------------------------------------------------------------------

REQUIRED_PRESENCE = [
    "set -euo pipefail",
    "--drill-id",
    "--mode",
    "local-drill",
    "cn-north-4",   # 区域白名单（与 iac/rds-prod/params-baseline.json 一致）
    "cn-east-3",
    "--last-restorable-in-region",
    "--last-restorable-cross-region",
    "fromisoformat",        # ISO-8601 带时区校验（python3）
    "--backup-dir",         # 跨区域备份副本化身（生产 = 上海一备份文件 PITR）
    "--standby-jdbc-host",  # 注册表改写目标（应用侧地址，可与连接地址不同：本地演练容器内外有别）
    "--proof-file",         # 验证清单（表 / 条件 / 期望行数），E22-T12 按同一清单核对
    "--objects-manifest",   # 对象对齐清单（恢复点之前上传的对象必须在备用区域副本中）
    "--report-archive-dir", # 演练报告存档目录
    "report.json",
    "report.md",
    "REPLACE(jdbc_url",     # 注册表 jdbc_url 主机改写到备用区域实例
    "300",                  # 区域内 RPO ≤ 5 分钟验收门（NFR-AVL-002）
    "14400",                # RTO ≤ 4 小时判据（NFR-AVL-002）
    "flyway_schema_history",
    "plt_tenant_resource",
    "--stop-after",
    "--yes",
]

PHASES = [
    "phase_plan", "phase_inventory", "phase_rebuild",
    "phase_restore", "phase_switch", "phase_verify", "phase_report",
]

FORBIDDEN = {
    "DROP DATABASE": "演练脚本不得删除数据库（化身库清理由校验脚本负责，生产库绝不触碰）",
    "DROP USER": "演练脚本不得删除账号",
    "DevOnly#": "本地 / 演示口令不得复制进脚本（AGENTS.md 第 4 节）",
}

REQUIRED_DOC = [
    "E22-T07",
    "tech-plan",            # §14 跨区域备份 + IaC 重建、§2 RDS 能力核实记录
    "NFR-AVL-002",          # RPO ≤ 5 分钟、RTO ≤ 4 小时
    "NFR-AVL-004",          # 每半年恢复演练
    "iac/rds-prod",         # 依赖 E22-T04 的跨区域备份与 IaC
    "iac/cce-prod",         # 依赖 E22-T05 的备用区域重建入口
    "cn-east-3",            # 上海一（备用区域）
    "最近可恢复时间点",       # 生产实测口径：控制台读备份侧最近可恢复点
    "cross_region_drill.sh",
    "DEW",                  # 凭据 / 密钥跨区域可用性核对项
    "## 4. 验证清单",
    "## 5. 报告与存档",
    "drill-records",
    "半年",                  # 演练频次（与 E22-T08 归档演练同窗口）
]

REQUIRED_RECORDS = [
    "E22-T07",
    "存档",       # 存档规则（本地 / CI 报告入仓库，生产报告入运维 wiki 并登记）
    "区域内 RPO",
    "实测",
]


def check_static(problems: list[str]) -> None:
    if not SCRIPT.exists():
        problems.append(f"缺少 {SCRIPT.relative_to(ROOT)}（E22-T07 跨区域恢复演练脚本）")
        return
    if not SCRIPT.stat().st_mode & 0o111:
        problems.append(f"{SCRIPT.relative_to(ROOT)} 必须可执行（chmod +x）")
    syntax = subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True, text=True)
    if syntax.returncode != 0:
        problems.append(f"bash -n 语法错误：{syntax.stderr.strip()}")
    text = SCRIPT.read_text(encoding="utf-8")
    for token in REQUIRED_PRESENCE:
        if token not in text:
            problems.append(f"cross_region_drill.sh 缺少片段：{token}")
    pos = -1
    for phase in PHASES:  # 函数定义序
        found = text.find(f"{phase}()", pos + 1)
        if found < 0:
            problems.append(f"cross_region_drill.sh 缺少阶段函数定义（或顺序不对）：{phase}()")
        else:
            pos = found
    defs_end = max(text.find(f"{phase}()") for phase in PHASES)
    pos = defs_end
    for phase in PHASES:  # 主流程调用序（在全部定义之后）
        found = text.find(f"\n{phase}\n", pos + 1)
        if found < 0:
            problems.append(f"cross_region_drill.sh 主流程缺少对 {phase} 的顺序调用")
        else:
            pos = found
    for token, why in FORBIDDEN.items():
        if token in text:
            problems.append(f"cross_region_drill.sh 出现禁止内容 {token!r}：{why}")
    if not RUNBOOK.exists():
        problems.append(f"缺少 {RUNBOOK.relative_to(ROOT)}（跨区域恢复演练 runbook）")
    else:
        doc = RUNBOOK.read_text(encoding="utf-8")
        for token in REQUIRED_DOC:
            if token not in doc:
                problems.append(f"runbook 缺少内容：{token}")
    if not RECORDS_README.exists():
        problems.append(f"缺少 {RECORDS_README.relative_to(ROOT)}（演练报告存档规则与登记表）")
    else:
        records = RECORDS_README.read_text(encoding="utf-8")
        for token in REQUIRED_RECORDS:
            if token not in records:
                problems.append(f"存档 README 缺少内容：{token}")
        # 验收「演练报告存档」：至少一份已存档的演练报告（本地 / CI 化身），生产报告占位不算
        archived = [
            p for p in (RECORDS_README.parent).glob("*.md")
            if p.name != "README.md" and "E22-T07" in p.read_text(encoding="utf-8")
        ]
        if not archived:
            problems.append("docs/ops/drill-records/ 下没有已存档的 E22-T07 演练报告（验收：演练报告存档）")
    ci = CI.read_text(encoding="utf-8") if CI.exists() else ""
    if "check_cross_region_drill.py" not in ci:
        problems.append("ci.yml 未接入 check_cross_region_drill.py（防漂移需进 CI）")


# ---------------------------------------------------------------------------
# 本地演练：在 docker compose 的 MySQL 上全流程验证（--drill）
# ---------------------------------------------------------------------------


class Drill:
    def __init__(self) -> None:
        self.problems: list[str] = []
        self.work = Path(tempfile.mkdtemp(prefix="crdp-xrg-drill-"))
        self.backup_dir = self.work / "snapshots" / "cross-region"
        self.inregion_dir = self.work / "snapshots" / "in-region"
        self.objects_region_a = self.work / "objects" / "region-a"
        self.objects_standby = self.work / "objects" / "standby"

    # ---- 基础设施：全部经容器内 root 执行，口令不落主机 --------------------

    def exec_mysql(self, sql: str, db: str | None = None) -> str:
        tail = f" {db}" if db else ""
        # --default-character-set=utf8mb4：中文值读回不乱码；--raw：值内反斜杠不被批处理再转义
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
             'exec mysql --default-character-set=utf8mb4 --raw'
             f' -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B{tail}'],
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

    # ---- 断言 --------------------------------------------------------------

    def ok(self, cond: bool, message: str) -> None:
        if not cond:
            self.problems.append(message)

    def db_exists(self, db: str) -> bool:
        return self.scalar(
            f"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = '{db}'"
        ) == "1"

    def table_count(self, db: str) -> int:
        return int(self.scalar(
            f"SELECT COUNT(*) FROM information_schema.tables"
            f" WHERE table_schema = '{db}' AND table_type = 'BASE TABLE'"
        ))

    def row_count(self, db: str, table: str, where: str = "") -> int:
        return int(self.scalar(
            f"SELECT COUNT(*) FROM `{table}`{(' ' + where) if where else ''}", db))

    def registry_rows(self, db: str) -> list[list[str]]:
        return self.rows(
            "SELECT t.code, r.db_name, r.jdbc_url FROM plt_tenant_resource r"
            " JOIN plt_tenant t ON t.id = r.tenant_id", db=db)

    # ---- 夹具：北京四生产化身 + 两口径备份副本 ------------------------------

    def dump_db(self, src: str, out: Path) -> None:
        dump = (
            'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction'
            " --set-gtid-purged=OFF --no-tablespaces --triggers"
            f" --default-character-set=utf8mb4 {src}"
        )
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c", dump],
            cwd=ROOT, capture_output=True, text=True,
        )
        if proc.returncode != 0 or not out.parent.exists():
            raise RuntimeError(f"导出 {src} 失败：{proc.stderr.strip()[:400]}")
        out.write_text(proc.stdout, encoding="utf-8")
        if not out.stat().st_size:
            raise RuntimeError(f"导出 {src} 为空")

    def restore_db(self, dump_file: Path, dst: str) -> None:
        self.exec_mysql(
            f"CREATE DATABASE IF NOT EXISTS `{dst}`"
            " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        proc = subprocess.run(
            ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
             f'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" {dst}'],
            cwd=ROOT, input=dump_file.read_text(encoding="utf-8"),
            capture_output=True, text=True,
        )
        if proc.returncode != 0:
            raise RuntimeError(f"导入 {dst} 失败：{proc.stderr.strip()[:400]}")

    def write_marker(self, uid: int, label: str) -> None:
        self.exec_mysql(
            MARKER_SQL.format(table=MARKER_TABLE, uid=uid, label=label),
            db=REGION_A_TENANT)

    def copy_db(self, src: str, dst: str) -> None:
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

    def counts_tsv(self, db: str, out: Path) -> None:
        rows = self.rows(
            "SELECT table_name, table_rows FROM information_schema.tables"
            f" WHERE table_schema = '{db}' AND table_type = 'BASE TABLE' ORDER BY table_name")
        # information_schema.table_rows 是估算值，改为逐表 COUNT(*)（与恢复脚本同口径）
        lines = []
        for (table,) in [(r[0],) for r in rows]:
            count = self.scalar(f"SELECT COUNT(*) FROM `{table}`", db)
            lines.append(f"{table}\t{count}")
        out.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def cleanup_fixture(self) -> None:
        self.exec_mysql(
            f"DELETE FROM plt_provision_step WHERE tenant_id IN"
            f" (SELECT id FROM plt_tenant WHERE code = '{TENANT}');"
            f"DELETE FROM plt_tenant_resource WHERE tenant_id IN"
            f" (SELECT id FROM plt_tenant WHERE code = '{TENANT}');"
            f"DELETE FROM plt_tenant WHERE code = '{TENANT}';",
            db="crdp_platform",
        )
        # 只清理演练化身库（xrga_/xrgi_/xrgb_ 前缀 + 演练租户库），生产库绝不触碰
        for db in [REGION_A_PLATFORM, REGION_A_TENANT, WHATIF_DB,
                   f"t_{TENANT}", RESTORED_PLATFORM]:
            self.exec_mysql(f"DROP DATABASE IF EXISTS `{db}`")

    def setup_fixture(self) -> None:
        if not self.db_exists("t_demoa") or self.table_count("t_demoa") == 0:
            raise RuntimeError("t_demoa 未迁移（空库）：先启动应用完成 Flyway 迁移再演练")
        if not self.db_exists("crdp_platform"):
            raise RuntimeError("crdp_platform 未迁移：先启动应用完成 Flyway 迁移再演练")
        self.cleanup_fixture()
        for db in [REGION_A_PLATFORM, REGION_A_TENANT]:
            self.exec_mysql(
                f"CREATE DATABASE `{db}`"
                " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
        self.copy_db("crdp_platform", REGION_A_PLATFORM)
        self.copy_db("t_demoa", REGION_A_TENANT)
        # 平台侧登记演练租户（与开通编排同构），jdbc 指向「区域 A 实例」化身地址
        self.exec_mysql(
            f"INSERT INTO plt_tenant (code, name, status)"
            f" VALUES ('{TENANT}', '跨区域演练医院（示例）', 'ENABLED')",
            db=REGION_A_PLATFORM,
        )
        tenant_id = self.scalar(
            f"SELECT id FROM plt_tenant WHERE code = '{TENANT}'", db=REGION_A_PLATFORM)
        self.exec_mysql(
            "INSERT INTO plt_tenant_resource"
            " (tenant_id, db_name, jdbc_url, db_username, password_cipher, secret_mode)"
            f" VALUES ({tenant_id}, 't_{TENANT}',"
            f" '{JDBC_TPL.format(host=JDBC_HOST_REGION_A, db=f't_{TENANT}')}',"
            " 'crdp_app', 'local:XrgDrill2026', 'local')",
            db=REGION_A_PLATFORM,
        )
        # M0：跨区域同步之前写入（两份副本都有，恢复后必须在）
        self.write_marker(M0, "跨区域演练标记 M0（示例）")
        self.objects_region_a.mkdir(parents=True)
        (self.objects_region_a / "rpt-m0.bin").write_bytes(b"object-before-cross-sync")

    def snapshot(self, target_dir: Path, objects: list[str]) -> tuple[Path, str]:
        """做一次「备份副本」化身快照，返回（目录, 完成时刻 ISO-8601 带时区）。"""
        target_dir.mkdir(parents=True)
        self.dump_db(REGION_A_PLATFORM, target_dir / "platform.sql")
        self.dump_db(REGION_A_TENANT, target_dir / f"tenant_{TENANT}.sql")
        self.counts_tsv(REGION_A_TENANT, target_dir / f"counts_{TENANT}.tsv")
        objects_dir = target_dir / "objects"
        objects_dir.mkdir()
        for name in objects:
            (objects_dir / name).write_bytes((self.objects_region_a / name).read_bytes())
        (target_dir / "objects-manifest.txt").write_text(
            "\n".join(objects) + ("\n" if objects else ""), encoding="utf-8")
        moment = datetime.datetime.now(
            datetime.timezone(datetime.timedelta(hours=8))).replace(microsecond=0)
        return target_dir, moment.isoformat()

    # ---- 演练脚本调用 ------------------------------------------------------

    def run_drill(self, args: list[str], password: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [str(SCRIPT), "--mode", "local-drill", "--drill-id", "xrg-local-check",
             "--tenant-code", TENANT,
             "--standby-host", "127.0.0.1", "--standby-port", "3306",
             "--standby-jdbc-host", JDBC_HOST_STANDBY,
             "--standby-user", "root", "--docker-service", "mysql",
             "--region-a-jdbc-host", JDBC_HOST_REGION_A, *args],
            cwd=ROOT,
            env={"PATH": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin",
                 "CRDP_DRILL_STANDBY_PASSWORD": password},
            capture_output=True, text=True, timeout=600,
        )

    def now_iso(self) -> str:
        return (datetime.datetime.now(
            datetime.timezone(datetime.timedelta(hours=8)))
            .replace(microsecond=0).isoformat())


def run_drill_mode(save_report_to: Path | None = None) -> list[str]:
    drill = Drill()
    try:
        drill.setup_fixture()
        password = drill.root_password()
        proof_file = drill.work / "proof.tsv"
        proof_file.write_text(
            f"{MARKER_TABLE}\tuser_id = {M0}\t1\n"   # 恢复点前：必须在
            f"{MARKER_TABLE}\tuser_id = {M1}\t0\n"   # 跨区域同步后：不在（跨区域 RPO 的数据体现）
            f"{MARKER_TABLE}\tuser_id = {M2}\t0\n"   # 区域内同步后：不在
            , encoding="utf-8")

        # 1) 跨区域副本（先同步）：此后写 M1 与对象 M1-obj
        _, t_sync_x = drill.snapshot(
            drill.backup_dir, ["rpt-m0.bin"])
        drill.write_marker(M1, "跨区域演练标记 M1（示例）")
        (drill.objects_region_a / "rpt-m1.bin").write_bytes(b"object-after-cross-sync")
        # 2) 区域内副本（后同步）：此后写 M2 与对象 M2-obj（均不进任何副本）
        _, t_sync_in = drill.snapshot(
            drill.inregion_dir, ["rpt-m0.bin", "rpt-m1.bin"])
        drill.write_marker(M2, "跨区域演练标记 M2（示例）")
        (drill.objects_region_a / "rpt-m2.bin").write_bytes(b"object-after-inregion-sync")

        # 3) 护栏：非法区域 / 非法时间戳必须拒绝
        bad_region = drill.run_drill(
            ["--region-failed", "cn-mars-1",
             "--last-restorable-in-region", drill.now_iso(),
             "--last-restorable-cross-region", drill.now_iso(),
             "--backup-dir", str(drill.backup_dir),
             "--restored-platform-db", RESTORED_PLATFORM,
             "--objects-standby-dir", str(drill.objects_standby),
             "--yes"], password)
        drill.ok(bad_region.returncode != 0, "非法区域应非零退出（区域白名单未生效）")
        bad_ts = drill.run_drill(
            ["--last-restorable-in-region", "2027-02-30T25:00:00+08:00",
             "--last-restorable-cross-region", drill.now_iso(),
             "--backup-dir", str(drill.backup_dir),
             "--restored-platform-db", RESTORED_PLATFORM,
             "--objects-standby-dir", str(drill.objects_standby),
             "--yes"], password)
        drill.ok(bad_ts.returncode != 0, "非法时间戳应非零退出（ISO-8601 校验未生效）")

        # 4) 护栏：区域内 RPO 超 5 分钟必须拒绝（不进入重建，零改动）
        old_point = (datetime.datetime.now(
            datetime.timezone(datetime.timedelta(hours=8)))
            - datetime.timedelta(minutes=6)).replace(microsecond=0).isoformat()
        rpo_breach = drill.run_drill(
            ["--last-restorable-in-region", old_point,
             "--last-restorable-cross-region", t_sync_x,
             "--backup-dir", str(drill.backup_dir),
             "--restored-platform-db", RESTORED_PLATFORM,
             "--objects-standby-dir", str(drill.objects_standby),
             "--yes"], password)
        drill.ok(rpo_breach.returncode != 0, "区域内 RPO > 5 分钟应非零退出（验收门未生效）")
        drill.ok(not drill.db_exists(RESTORED_PLATFORM), "RPO 超标拒绝时不得已建化身库")

        # 5) 未加 --yes：只出计划，零改动
        plan = drill.run_drill(
            ["--last-restorable-in-region", t_sync_in,
             "--last-restorable-cross-region", t_sync_x,
             "--backup-dir", str(drill.backup_dir),
             "--restored-platform-db", RESTORED_PLATFORM,
             "--objects-standby-dir", str(drill.objects_standby),
             "--proof-file", str(proof_file)], password)
        drill.ok(plan.returncode == 0,
                 f"计划模式应成功退出，实际 {plan.returncode}：{plan.stderr[-300:]}")
        drill.ok(not drill.db_exists(RESTORED_PLATFORM), "计划模式不得建化身库")
        drill.ok(not drill.db_exists(f"t_{TENANT}"), "计划模式不得建租户库")

        # 6) 全流程：区域不可用 → 备用区域从跨区域副本重建恢复 → 报告存档
        archive_dir = drill.work / "archive"
        full = drill.run_drill(
            ["--last-restorable-in-region", t_sync_in,
             "--last-restorable-cross-region", t_sync_x,
             "--backup-dir", str(drill.backup_dir),
             "--restored-platform-db", RESTORED_PLATFORM,
             "--objects-standby-dir", str(drill.objects_standby),
             "--proof-file", str(proof_file),
             "--report-archive-dir", str(archive_dir),
             "--work-dir", str(drill.work / "run"),
             "--yes"], password)
        drill.ok(full.returncode == 0,
                 f"演练应成功，实际 {full.returncode}：{full.stderr[-800:]}")
        if drill.problems:
            return drill.problems  # 脚本都没跑通，后续断言无意义

        # 报告：字段齐全、实测值与重算一致、验收门全部 PASS、报告已存档
        report_json = drill.work / "run" / "report.json"
        if not report_json.exists():
            drill.problems.append(f"缺少演练报告 {report_json}")
            return drill.problems
        data = json.loads(report_json.read_text(encoding="utf-8"))
        declared = datetime.datetime.fromisoformat(data["declared_at"])
        sync_in = datetime.datetime.fromisoformat(t_sync_in)
        sync_x = datetime.datetime.fromisoformat(t_sync_x)
        expect_rpo_in = (declared - sync_in).total_seconds()
        expect_rpo_x = (declared - sync_x).total_seconds()
        got_in = data["measured"]["in_region_rpo_seconds"]
        got_x = data["measured"]["cross_region_rpo_seconds"]
        got_rto = data["measured"]["rto_seconds"]
        drill.ok(0 <= got_in <= RPO_TARGET_SECONDS,
                 f"区域内 RPO 实测 {got_in}s 应在 (0, {RPO_TARGET_SECONDS}] 内")
        drill.ok(abs(got_in - expect_rpo_in) <= 2,
                 f"区域内 RPO 报告值 {got_in}s 与重算 {expect_rpo_in}s 不一致")
        drill.ok(got_x >= got_in, "跨区域 RPO 应不小于区域内 RPO（跨区域副本滞后）")
        drill.ok(abs(got_x - expect_rpo_x) <= 2,
                 f"跨区域 RPO 报告值 {got_x}s 与重算 {expect_rpo_x}s 不一致")
        drill.ok(0 <= got_rto <= RTO_TARGET_SECONDS,
                 f"RTO 实测 {got_rto}s 应在 (0, {RTO_TARGET_SECONDS}] 内")
        drill.ok(data["verdicts"]["in_region_rpo"] == "PASS"
                 and data["verdicts"]["cross_region_rpo_recorded"] == "PASS"
                 and data["verdicts"]["rto"] == "PASS",
                 f"验收门判定应全 PASS，实际 {data['verdicts']}")
        archived = list(archive_dir.glob("*.md"))
        drill.ok(bool(archived), "报告应已存档到 --report-archive-dir")
        if archived:
            md = archived[0].read_text(encoding="utf-8")
            for token in ["实测 RPO", "实测 RTO", "cn-east-3", "PASS"]:
                drill.ok(token in md, f"存档报告缺少内容：{token}")

        # 恢复结果：跨区域恢复点生效（M0 在、M1/M2 不在）、迁移历史随库恢复
        drill.ok(drill.db_exists(f"t_{TENANT}"), "备用区域应重建出演练租户库")
        drill.ok(drill.row_count(f"t_{TENANT}", MARKER_TABLE, f"WHERE user_id = {M0}") == 1,
                 "恢复点之前的标记行 M0 应在（跨区域副本里有）")
        drill.ok(drill.row_count(f"t_{TENANT}", MARKER_TABLE, f"WHERE user_id = {M1}") == 0,
                 "跨区域同步之后的标记行 M1 不应恢复（跨区域 RPO 生效）")
        drill.ok(drill.row_count(f"t_{TENANT}", MARKER_TABLE, f"WHERE user_id = {M2}") == 0,
                 "区域内同步之后的标记行 M2 不应恢复")
        drill.ok(drill.row_count(f"t_{TENANT}", "flyway_schema_history") > 0,
                 "重建库应有 flyway_schema_history（迁移历史随库恢复）")
        base_counts = (drill.backup_dir / f"counts_{TENANT}.tsv").read_text(
            encoding="utf-8").splitlines()
        mismatch = [
            line for line in base_counts
            if drill.row_count(f"t_{TENANT}", line.split("\t")[0]) != int(line.split("\t")[1])
        ]
        drill.ok(not mismatch, f"重建库行数与副本基线不一致：{mismatch[:5]}")

        # 注册表切换：全部 jdbc_url 主机改写到备用区域实例地址
        registry = drill.registry_rows(RESTORED_PLATFORM)
        drill.ok(len(registry) >= 1, "重建平台库应有租户资源行")
        drill.ok(all(f"//{JDBC_HOST_STANDBY}/" in row[2] for row in registry),
                 f"全部 jdbc_url 应改写到备用区域地址，实际 {registry[:3]}")
        drill.ok(any(row[0] == TENANT and row[1] == f"t_{TENANT}" for row in registry),
                 f"演练租户行应指向 t_{TENANT}，实际 {registry[:3]}")
        drill.ok(not any(JDBC_HOST_REGION_A in row[2] for row in registry),
                 f"改写后不得残留区域 A 地址（{JDBC_HOST_REGION_A}），实际 {registry[:3]}")

        # 区域内口径数据证据：区域内副本导入对照库，M0/M1 在、M2 不在
        drill.restore_db(drill.inregion_dir / f"tenant_{TENANT}.sql", WHATIF_DB)
        drill.ok(drill.row_count(WHATIF_DB, MARKER_TABLE, f"WHERE user_id = {M0}") == 1,
                 "区域内副本应含 M0")
        drill.ok(drill.row_count(WHATIF_DB, MARKER_TABLE, f"WHERE user_id = {M1}") == 1,
                 "区域内副本应含 M1（丢数据仅限最后一次同步之后 = 区域内 RPO 证据）")
        drill.ok(drill.row_count(WHATIF_DB, MARKER_TABLE, f"WHERE user_id = {M2}") == 0,
                 "区域内副本不应含 M2")

        # 对象对齐：恢复点之前的对象在备用区域副本中必须存在；之后的对象不得计入清单
        drill.ok((drill.objects_standby / "rpt-m0.bin").exists(),
                 "恢复点之前的对象应在备用区域副本中（对象对齐）")
        drill.ok(not (drill.objects_standby / "rpt-m2.bin").exists(),
                 "区域内同步之后的对象不得出现在备用区域副本")
        if save_report_to is not None and not drill.problems:
            save_report_to.mkdir(parents=True, exist_ok=True)
            for name in ("report.md", "report.json"):
                (save_report_to / name).write_bytes(
                    (drill.work / "run" / name).read_bytes())
            print(f"演练报告已导出：{save_report_to / 'report.md'}（存档用，见 docs/ops/drill-records/）")
        return drill.problems
    finally:
        try:
            drill.cleanup_fixture()
        except RuntimeError as exc:
            drill.problems.append(f"演练清理失败：{exc}")


def report(problems: list[str]) -> int:
    if problems:
        print(f"跨区域恢复演练校验未通过，共 {len(problems)} 处：")
        for message in problems:
            print(f"  - {message}")
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--drill", action="store_true",
                        help="在 docker compose 的 MySQL 上做本地演练（需先完成租户库迁移）")
    parser.add_argument("--save-report-to", type=Path, default=None,
                        help="演练通过后把 report.md / report.json 导出到该目录"
                             "（用于 docs/ops/drill-records/ 存档，仅配合 --drill）")
    args = parser.parse_args()
    problems: list[str] = []
    if args.drill:  # 先演练后静态：演练报告是存档记录的来源，静态校验含「报告已存档」
        problems += run_drill_mode(args.save_report_to)
    check_static(problems)
    if problems:
        return report(problems)
    if args.drill:
        print("跨区域恢复演练校验通过：七阶段护栏齐备，本地演练实测区域内 RPO / 跨区域 RPO / RTO、"
              "恢复点三态标记、注册表切换、对象对齐与报告存档全部符合预期。")
    else:
        print("跨区域恢复演练脚本静态校验通过（加 --drill 可在本地 MySQL 演练全流程）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
