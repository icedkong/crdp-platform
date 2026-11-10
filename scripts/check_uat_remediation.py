#!/usr/bin/env python3
"""医院 A UAT 缺陷修复（E23-T10，全栈 2）防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/testing/uat-remediation-hospital-a.md` 修复手册：处置流程 / 三选一处置口径 /
   自查发现表（阻断行必须清零口径一致、每条有处置结论）/ 人工事项清单 / 复测闭环
   判据齐备，验收句与 tasks.csv E23-T10 行一致；
2. 自查修复的迁移与配置在位：
   - 租户库种子 `V133__grant_a_report_import_assign.sql`：幂等 NOT EXISTS、
     只新增不改旧（无 DELETE / UPDATE）、补授面恰为四对（231→病区医生 / 随访员、
     353→数据录入员、224→科主任）、敏感菜单 211/212/217 不出现；
   - `docker-compose.yml`：mysql 带 `--default-time-zone=+08:00`（与
     `iac/rds-prod/params-baseline.json` 的 `time_zone: UTC+08:00` 基线一致）；
   - 修复有测试锁定（`APresetUatGrantSqlTest`、`UatEnvTimezoneBaselineIT`）；
3. 索引同步：ci.yml 含本校验步骤、docs/README.md、UAT 手册 §11、AGENTS.md 脚本清单。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/uat-remediation-hospital-a.md"
UAT_MANUAL = ROOT / "docs/testing/uat-hospital-a.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
AGENTS = ROOT / "AGENTS.md"
COMPOSE = ROOT / "docker-compose.yml"
RDS_BASELINE = ROOT / "iac/rds-prod/params-baseline.json"
GRANT_SQL = (
    ROOT / "backend/crdp-server/src/main/resources/db/tenant/seed/V133__grant_a_report_import_assign.sql"
)
GRANT_TEST = (
    ROOT
    / "backend/crdp-server/src/test/java/com/crdp/server/followup/APresetUatGrantSqlTest.java"
)
TZ_TEST = ROOT / "backend/crdp-server/src/test/java/com/crdp/server/UatEnvTimezoneBaselineIT.java"

# 自查发现表行格式：| UAT-SELF-nn | 级别 | module | ...（级别列取第二列）
FINDING_ROW = re.compile(r"^\|\s*(UAT-SELF-\d+)\s*\|\s*(阻断|严重|一般|建议)\s*\|", re.MULTILINE)


def e23_t10_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T10":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    # ---- 1. 修复手册 ----
    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    check("修复手册存在", bool(manual))
    row = e23_t10_row()
    check("tasks.csv 有 E23-T10 行", row is not None)
    if row:
        check("手册验收句与 tasks.csv 一致（UAT 缺陷关闭）", row.get("验收标准", "") in manual)
    for anchor in [
        "## 1. 目标与验收口径",
        "## 2. 处置流程（Issue → 关闭）",
        "## 3. 处置口径（三选一）",
        "## 4. UAT 脚本自查",
        "## 5. 待人工 / 外部事项清单（窗口 W33–W35 内执行）",
        "## 6. 复测与闭环判据",
        "## 7. 与其他任务的衔接",
        "## 8. 验证留痕",
    ]:
        check(f"手册章节「{anchor}」在位", anchor in manual)
    findings = FINDING_ROW.findall(manual)
    check("自查发现表至少 2 条（UAT-SELF-01 / 02）", len(findings) >= 2)
    check(
        "自查发现编号含 UAT-SELF-01 与 UAT-SELF-02",
        {"UAT-SELF-01", "UAT-SELF-02"} <= {fid for fid, _ in findings},
    )
    blocked = [fid for fid, sev in findings if sev == "阻断"]
    check(
        "阻断级发现（UAT-SELF-01）处置词为「修复」",
        all(
            # 行形：| UAT-SELF-nn | 阻断 | module | 发现 | 处置结论(**修复**) | 证据 |
            re.search(
                rf"^\|\s*{fid}\s*\|\s*阻断\s*\|[^|]+\|[^|]+\|[^|]*\*\*修复\*\*",
                manual,
                re.MULTILINE,
            )
            is not None
            for fid in blocked
        ),
    )
    for fid, _ in findings:
        seg = manual.split(f"| {fid} |", 1)
        check(f"{fid} 行有处置结论（修复 / 缓解 / 延期上线）", len(seg) > 1 and any(k in seg[1][:600] for k in ("**修复**", "**缓解**", "**延期上线**")))
    check("手册引用 E23-T09 / E19-T10 / E23-T11 衔接", all(k in manual for k in ("E23-T09", "E19-T10", "E23-T11")))
    check("手册注明 UAT-SELF 编号与 UAT-W 区分（同 PT-SELF 口径）", "UAT-W{周}" in manual)

    # ---- 2a. V133 补授脚本 ----
    grant = GRANT_SQL.read_text(encoding="utf-8") if GRANT_SQL.exists() else ""
    check("V133 种子脚本在位", bool(grant))
    if grant:
        # 语句体 = 去掉注释行（敏感菜单号只允许出现在说明注释里，不得进入授予语句）
        grant_code = "\n".join(line for line in grant.splitlines() if not line.lstrip().startswith("--"))
        check("V133 幂等守卫（NOT EXISTS）", "WHERE NOT EXISTS" in grant_code)
        check("V133 只新增（无 DELETE / UPDATE）", "DELETE FROM" not in grant_code and "UPDATE " not in grant_code)
        for pair in [
            "'A_WARD_DOCTOR' AS code, 231 AS menu_id",
            "'A_FOLLOWUP_NURSE', 231",
            "'A_DATA_CLERK', 353",
            "'A_DIRECTOR', 224",
        ]:
            check(f"V133 补授对 {pair}", pair in grant_code)
        check(
            "V133 授予语句不含敏感菜单（211 / 212 / 217）",
            not re.search(r"\b(211|212|217)\b", grant_code),
        )
        check("V133 注明依据（A 规格 §10）", "§10" in grant)

    # ---- 2b. compose MySQL 时区基线 ----
    compose = COMPOSE.read_text(encoding="utf-8") if COMPOSE.exists() else ""
    check("docker-compose mysql 带 --default-time-zone=+08:00", "--default-time-zone=+08:00" in compose)
    baseline = RDS_BASELINE.read_text(encoding="utf-8") if RDS_BASELINE.exists() else ""
    check("生产 RDS 基线仍为 UTC+08:00（对齐目标不变）", '"time_zone": "UTC+08:00"' in baseline)

    # ---- 2c. 修复有测试锁定 ----
    check("APresetUatGrantSqlTest 在位", GRANT_TEST.exists())
    if GRANT_TEST.exists():
        t = GRANT_TEST.read_text(encoding="utf-8")
        check(
            "补授测试覆盖 幂等只增/补授面/§10 矩阵并集/敏感不预授",
            all(
                k in t
                for k in (
                    "grantScriptIsIdempotentAppendOnly",
                    "grantsCoverUatSelf01GapsExactly",
                    "combinedGrantsSatisfySpecMatrixWithoutSensitive",
                    "SENSITIVE_MENU_IDS",
                )
            ),
        )
    check("UatEnvTimezoneBaselineIT 在位", TZ_TEST.exists())
    if TZ_TEST.exists():
        t = TZ_TEST.read_text(encoding="utf-8")
        check(
            "时区基线测试锁定 global.time_zone 与北京墙钟比对",
            "@@global.time_zone" in t and "Asia/Shanghai" in t and "CRDP_IT_MYSQL_PORT" in t,
        )

    # ---- 3. 索引同步 ----
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 含本校验步骤", "check_uat_remediation.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引含修复手册", "uat-remediation-hospital-a.md" in readme)
    uat_manual = UAT_MANUAL.read_text(encoding="utf-8") if UAT_MANUAL.exists() else ""
    check("UAT 手册 §11 指向修复手册", "uat-remediation-hospital-a.md" in uat_manual)
    check("UAT 手册 §10 留痕含本轮自查（E23-T10）", "E23-T10" in uat_manual)
    agents = AGENTS.read_text(encoding="utf-8") if AGENTS.exists() else ""
    check("AGENTS.md 脚本清单含本校验", "check_uat_remediation.py" in agents)

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("✓ " if ok else "✗ ") + n)
    print(f"UAT 修复（E23-T10）校验：{len(checks) - len(failed)}/{len(checks)} 通过")
    if failed:
        print("失败项：" + "；".join(failed), file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
