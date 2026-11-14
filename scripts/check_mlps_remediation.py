#!/usr/bin/env python3
"""等保测评整改（E22-T15，后端）防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E22-T15 行：类型 = 后端、依赖 E22-T14、验收原句「整改项全部关闭」；
2. `docs/ops/mlps/remediation-backend.md` 整改手册：验收拆解（先行整改 + 窗口 Issue 全关闭）、
   处置流程（分诊 → 决策 → 整改 → 复测 → 关闭）、三选一处置口径、D-01~D-08 后端面处置表
   （D-01 = 整改，含 tech-plan §10 方案锚点）、检出存量不自动删除口径、待人工清单
   （clamd 部署 / 启用 / HSS）、复测闭环与 E22-T17 衔接；
3. D-01 修复的代码与配置在位（运行时语义断言的静态面）：
   - 端口与适配器：`VirusScanner`（fail closed 语义）、`ClamdVirusScanner`（INSTREAM + 50300）、
     `VirusScanConfiguration`（mode 二选一装配）、`DisabledVirusScanner`；
   - 上传链路：`FileUploadService` 在真实类型校验后扫描——检出 40001 + FILE_VIRUS_REJECTED
     审计、台账 virus_scan_status（CLEAN / UNSCANNED）；
   - 台账与补扫：followup V139 扫描列、`VirusScanRetroService`（SHA-256 完整性核对 +
     CLEAN / INFECTED / HASH_MISMATCH 三态、检出不删对象）、`VirusScanRetroScheduler`（03:20）；
   - 配置：application.yml 默认 off；application-prod.yml 经 `CRDP_VIRUS_SCAN_MODE` 注入
     （缺省 off，clamd 部署核验后置 clamd）；
   - 错误码 50300 已在 `docs/api/README.md` 登记（AGENTS §6：新码先登记再用）、
     `docs/api/report.md` §1 上传行为同步；
4. 自查回写：`self-assessment.md` §2.4 恶意代码防范转「符合」（E22-T15）、D-01 已整改、
   `onsite-assessment.md` §6 D-01 行回写；
5. 索引同步：ci.yml 含本校验步骤、docs/README.md、docs/ops/mlps/README.md、AGENTS.md 脚本清单、
   open-questions.md OQ-154（暂行口径）。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/ops/mlps/remediation-backend.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
DOCS_README = ROOT / "docs/README.md"
MLPS_README = ROOT / "docs/ops/mlps/README.md"
ASSESSMENT = ROOT / "docs/ops/mlps/self-assessment.md"
ONSITE = ROOT / "docs/ops/mlps/onsite-assessment.md"
AGENTS = ROOT / "AGENTS.md"
OPEN_QUESTIONS = ROOT / "docs/open-questions.md"
API_README = ROOT / "docs/api/README.md"
REPORT_API = ROOT / "docs/api/report.md"
SCAN_DIR = ROOT / "backend/crdp-followup/src/main/java/com/crdp/followup/file/infra/scan"
UPLOAD_SVC = ROOT / "backend/crdp-followup/src/main/java/com/crdp/followup/file/app/FileUploadService.java"
RETRO_SVC = ROOT / "backend/crdp-followup/src/main/java/com/crdp/followup/file/app/VirusScanRetroService.java"
RETRO_SCHED = ROOT / "backend/crdp-server/src/main/java/com/crdp/server/followup/VirusScanRetroScheduler.java"
MIGRATION = ROOT / "backend/crdp-server/src/main/resources/db/tenant/followup/V139__sys_file_virus_scan.sql"
APP_YML = ROOT / "backend/crdp-server/src/main/resources/application.yml"
PROD_YML = ROOT / "backend/crdp-server/src/main/resources/application-prod.yml"
TESTS = (
    ROOT / "backend/crdp-followup/src/test/java/com/crdp/followup/file/infra/scan/ClamdVirusScannerTest.java",
    ROOT / "backend/crdp-followup/src/test/java/com/crdp/followup/file/app/VirusScanRetroServiceTest.java",
    ROOT / "backend/crdp-followup/src/test/java/com/crdp/followup/file/app/FileUploadServiceTest.java",
    ROOT / "backend/crdp-server/src/test/java/com/crdp/server/followup/SysFileVirusScanSqlTest.java",
    ROOT / "backend/crdp-server/src/test/java/com/crdp/server/config/ProdProfileHardeningTest.java",
)


def e22_t15_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E22-T15":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    def read(p: Path) -> str:
        return p.read_text(encoding="utf-8") if p.exists() else ""

    manual = read(MANUAL)

    # ---- 1. tasks.csv 行 ----
    row = e22_t15_row()
    check("tasks.csv 有 E22-T15 行", row is not None)
    if row:
        check(
            "任务标题与类型（测评整改 · 后端）",
            "测评整改" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "后端",
        )
        check("依赖 E22-T14（现场测评）", (row.get("依赖") or "").strip() == "E22-T14")
        check("验收原句（整改项全部关闭）", (row.get("验收标准") or "").strip() == "整改项全部关闭")

    # ---- 2. 整改手册 ----
    check("整改手册存在（docs/ops/mlps/remediation-backend.md）", bool(manual))
    check(
        "手册头部任务行（E22-T15 · 依赖 E22-T14 · 验收「整改项全部关闭」 · 上游 onsite-assessment）",
        all(k in manual for k in ("E22-T15", "E22-T14", "整改项全部关闭", "onsite-assessment.md")),
    )
    for section in (
        "## 1. 目标与验收口径",
        "## 2. 处置流程（Issue → 关闭）",
        "## 3. 处置口径（三选一",
        "## 4. 已知偏差后端面先行整改",
        "## 5. 待人工 / 外部事项清单",
        "## 6. 复测与闭环判据",
        "## 7. 与其他任务的衔接",
        "## 8. 验证留痕",
    ):
        check(f"手册章节「{section}」", section in manual)
    check(
        "手册验收拆解（先行整改 + 窗口 Issue 全关闭双轨）",
        "先行整改" in manual and "窗口整改" in manual and "MLPS-ID" in manual,
    )
    check(
        "手册处置三选一（整改 / 缓解 / 接受风险）",
        all(k in manual for k in ("**整改**", "**缓解**", "**接受风险**")),
    )
    check(
        "手册 D-01 处置 = 整改（tech-plan §10 方案：ClamAV 同步扫描 + 补扫 + 不用云沙箱）",
        "D-01" in manual
        and "INSTREAM" in manual
        and "补扫" in manual
        and "云沙箱" in manual
        and "tech-plan §10" in manual,
    )
    check(
        "手册 D-01~D-08 后端面全处置（无悬空项：含 D-02 接受、D-05 条件触发、D-07 由人）",
        all(k in manual for k in ("| D-02 |", "| D-05 |", "| D-07 |", "| D-08 |")),
    )
    check(
        "手册检出存量口径（不自动删除 + 安全会诊 + INFECTED 台账即待处置清单）",
        "不自动删除" in manual and "安全会诊" in manual and "INFECTED" in manual,
    )
    check(
        "手册待人工清单（clamd 部署 / CRDP_VIRUS_SCAN_MODE 启用 / HSS 主机层）",
        "clamd" in manual and "CRDP_VIRUS_SCAN_MODE" in manual and "HSS" in manual,
    )
    check(
        "手册复测闭环（E22-T17 · Issue 状态为证 · 回写自查 §4）",
        "E22-T17" in manual and "self-assessment.md" in manual and "closed" in manual,
    )

    # ---- 3. 代码与配置在位 ----
    scanner_port = read(SCAN_DIR / "VirusScanner.java")
    check("病毒扫描端口 VirusScanner（fail closed 语义）", "50300" in scanner_port and "enabled()" in scanner_port)
    clamd = read(SCAN_DIR / "ClamdVirusScanner.java")
    check(
        "ClamdVirusScanner（INSTREAM 协议 + 分块 + 0 长度结束 + 不可用 50300）",
        all(k in clamd for k in ("INSTREAM", "CHUNK_SIZE", "50300")),
    )
    config = read(SCAN_DIR / "VirusScanConfiguration.java")
    check("装配按 mode 二选一（ConditionalOnProperty · off 缺省匹配）", "ConditionalOnProperty" in config)
    disabled = read(SCAN_DIR / "DisabledVirusScanner.java")
    check("禁用态替身 DisabledVirusScanner", "UNSCANNED" in disabled)
    upload = read(UPLOAD_SVC)
    check(
        "上传链路接入扫描（检出 40001 + FILE_VIRUS_REJECTED 审计 + 台账 CLEAN/UNSCANNED）",
        all(k in upload for k in ("FILE_VIRUS_REJECTED", "setVirusScanStatus", "UNSCANNED")),
    )
    migration = read(MIGRATION)
    check(
        "followup V139 扫描列（virus_scan_status / virus_scanned_at / 索引 / 默认 UNSCANNED）",
        all(k in migration for k in ("virus_scan_status", "virus_scanned_at", "idx_sys_file_virus_scan", "'UNSCANNED'")),
    )
    retro = read(RETRO_SVC)
    check(
        "补扫作业（SHA-256 完整性核对 + CLEAN/INFECTED/HASH_MISMATCH + 检出不删 + 审计）",
        all(
            k in retro
            for k in ("sha256Hex", "HASH_MISMATCH", "FILE_VIRUS_FOUND", "FILE_VIRUS_HASH_MISMATCH", "retro-batch-limit")
        ),
    )
    sched = read(RETRO_SCHED)
    check("补扫定时注册（每日 03:20 Asia/Shanghai）", "0 20 3 * * *" in sched and "Asia/Shanghai" in sched)
    app = read(APP_YML)
    check(
        "application.yml 默认 off + 补扫批量上限（引号形式：裸 off 是 YAML 布尔）",
        "virus-scan:" in app and 'mode: "off"' in app,
    )
    prod = read(PROD_YML)
    check(
        "application-prod.yml 经 CRDP_VIRUS_SCAN_MODE 注入（缺省 off，不静默默认启用）",
        "${CRDP_VIRUS_SCAN_MODE:off}" in prod and "${CRDP_CLAMD_HOST" in prod,
    )
    api = read(API_README)
    check("错误码 50300 已登记（docs/api/README.md，AGENTS §6 先登记再用）", "| 50300 |" in api)
    report = read(REPORT_API)
    check(
        "report.md §1 上传行为同步（病毒扫描 + 50300 + 补扫口径）",
        "病毒扫描" in report and "50300" in report and "补扫" in report,
    )

    # ---- 4. 测试锁定 ----
    for t in TESTS:
        check(f"测试在位「{t.name}」", t.exists())
    upload_test = read(TESTS[2])
    check(
        "FileUploadServiceTest 病毒用例（检出拒绝 / 干净 CLEAN / 不可用 50300）",
        all(k in upload_test for k in ("FILE_VIRUS_REJECTED", "CLEAN", "50300")),
    )
    prod_test = read(TESTS[4])
    check("ProdProfileHardeningTest 病毒扫描接线用例（字面量锁定）", "CRDP_VIRUS_SCAN_MODE" in prod_test)

    # ---- 5. 自查回写 ----
    assessment = read(ASSESSMENT)
    check(
        "自查 §2.4 恶意代码防范转「符合」（E22-T15 回写）",
        "恶意代码防范 | 符合" in assessment and "E22-T15" in assessment,
    )
    check(
        "自查 D-01 已整改（处置列指向整改手册）",
        "D-01" in assessment and "已整改" in assessment and "remediation-backend.md" in assessment,
    )
    onsite = read(ONSITE)
    check("现场测评手册 §6 D-01 行回写已整改", "D-01" in onsite and "已整改" in onsite)

    # ---- 6. 索引与接线 ----
    ci = read(CI_YML)
    check("ci.yml 接入本校验（check_mlps_remediation.py）", "check_mlps_remediation.py" in ci)
    check("docs/README.md 收录整改手册", "remediation-backend.md" in read(DOCS_README))
    check("mlps README 衔接整改手册与校验脚本", "remediation-backend.md" in read(MLPS_README))
    check("AGENTS.md 脚本清单含本校验", "check_mlps_remediation.py" in read(AGENTS))
    check("open-questions.md 有 OQ-154（整改暂行口径）", "OQ-154" in read(OPEN_QUESTIONS))

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
