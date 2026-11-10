#!/usr/bin/env python3
"""医院 A UAT 缺陷修复（E23-T09，全栈 1）防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/testing/uat-remediation-hospital-a-fullstack1.md` 修复手册：处置流程 / 三选一处置口径 /
   自查发现表（每条有处置结论）/ 人工事项清单 / 复测闭环判据齐备，验收句与 tasks.csv
   E23-T09 行一致；
2. 自查修复的迁移与代码在位：
   - 租户库种子 `V134__grant_notice_menu.sql`（UAT-SELF-04）：幂等 NOT EXISTS、只新增不改旧
     （无 DELETE / UPDATE）、补授面恰为 TENANT_ADMIN←36、限定 built_in=1、
     敏感菜单 211/212/217/232 不出现；
   - 登记即死亡（UAT-SELF-05）：PatientInsert 带 followupStatus、INSERT 显式写 followup_status、
     PatientRegistrationService 按出院状态 = 3 写 DEAD 并走 recordRegistrationDead、
     PatientStatusHistoryService 提供 recordRegistrationDead；
   - 文档更正（UAT-SELF-06）：patient.md 的 PUT /patients/{id} 行标注未实现并指向 OQ-124，
     open-questions.md 有 OQ-124 行；
   - 修复有测试锁定（NoticeMenuGrantSqlTest / NoticeMenuGrantVerifyIT /
     PatientRegistrationServiceTest / PatientRegistrationIT 的新用例名）；
3. 索引同步：ci.yml 含本校验步骤、docs/README.md、UAT 手册 §10 / §11、AGENTS.md 脚本清单。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/uat-remediation-hospital-a-fullstack1.md"
UAT_MANUAL = ROOT / "docs/testing/uat-hospital-a.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
AGENTS = ROOT / "AGENTS.md"
PATIENT_MD = ROOT / "docs/api/patient.md"
OQ_MD = ROOT / "docs/open-questions.md"
GRANT_SQL = (
    ROOT / "backend/crdp-server/src/main/resources/db/tenant/seed/V134__grant_notice_menu.sql"
)
REG_SERVICE = (
    ROOT
    / "backend/crdp-followup/src/main/java/com/crdp/followup/patient/app/PatientRegistrationService.java"
)
HISTORY_SERVICE = (
    ROOT
    / "backend/crdp-followup/src/main/java/com/crdp/followup/patient/app/PatientStatusHistoryService.java"
)
PATIENT_INSERT = (
    ROOT
    / "backend/crdp-followup/src/main/java/com/crdp/followup/patient/infra/mapper/PatientInsert.java"
)
PATIENT_MAPPER = (
    ROOT
    / "backend/crdp-followup/src/main/java/com/crdp/followup/patient/infra/mapper/PatientMapper.java"
)
GRANT_TEST = (
    ROOT / "backend/crdp-server/src/test/java/com/crdp/server/followup/NoticeMenuGrantSqlTest.java"
)
GRANT_VERIFY_IT = (
    ROOT
    / "backend/crdp-server/src/test/java/com/crdp/server/followup/NoticeMenuGrantVerifyIT.java"
)
REG_SERVICE_TEST = (
    ROOT
    / "backend/crdp-followup/src/test/java/com/crdp/followup/patient/app/PatientRegistrationServiceTest.java"
)
REG_IT = (
    ROOT / "backend/crdp-server/src/test/java/com/crdp/server/followup/PatientRegistrationIT.java"
)

# 自查发现表行格式：| UAT-SELF-nn | 级别 | module | ...（级别列取第二列）
FINDING_ROW = re.compile(r"^\|\s*(UAT-SELF-\d+)\s*\|\s*(阻断|严重|一般|建议)\s*\|", re.MULTILINE)


def e23_t09_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T09":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    # ---- 1. 修复手册 ----
    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    check("修复手册存在", bool(manual))
    row = e23_t09_row()
    check("tasks.csv 有 E23-T09 行", row is not None)
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
    check("自查发现表至少 3 条（UAT-SELF-04 / 05 / 06）", len(findings) >= 3)
    check(
        "自查发现编号含 UAT-SELF-04 / 05 / 06",
        {"UAT-SELF-04", "UAT-SELF-05", "UAT-SELF-06"} <= {fid for fid, _ in findings},
    )
    for fid, _ in findings:
        seg = manual.split(f"| {fid} |", 1)
        check(
            f"{fid} 行有处置结论（修复 / 缓解 / 延期上线）",
            len(seg) > 1
            and any(
                keyword in seg[1][:600]
                for keyword in ("**修复**", "**缓解**", "**延期上线**")
            ),
        )
    check("手册注明发现不进缺陷库（同 PT-SELF / UAT-SELF-01~03 口径）", "不进缺陷库" in manual)
    check("手册引用 E23-T10 / E19-T10 / E23-T11 衔接", all(k in manual for k in ("E23-T10", "E19-T10", "E23-T11")))
    check("手册注明 UAT-SELF 编号与 UAT-W 区分（同 PT-SELF 口径）", "UAT-W{周}" in manual)

    # ---- 2a. V134 补授脚本（UAT-SELF-04）----
    grant = GRANT_SQL.read_text(encoding="utf-8") if GRANT_SQL.exists() else ""
    check("V134 种子脚本在位", bool(grant))
    if grant:
        grant_code = "\n".join(
            line for line in grant.splitlines() if not line.lstrip().startswith("--")
        )
        start = grant_code.find("INSERT INTO sys_role_menu")
        statement = grant_code[start:] if start >= 0 else ""
        check("V134 是 sys_role_menu 的 INSERT", start >= 0)
        check("V134 幂等守卫（NOT EXISTS）", "NOT EXISTS" in statement)
        check(
            "V134 只新增（无 DELETE / UPDATE）",
            "DELETE FROM" not in grant_code and "UPDATE " not in grant_code,
        )
        check(
            "V134 补授对恰为 TENANT_ADMIN ← 36",
            "'TENANT_ADMIN' AS code, 36 AS menu_id" in statement,
        )
        check("V134 限定内置角色（built_in = 1）", "built_in = 1" in statement)
        check(
            "V134 授予语句不含敏感菜单（211 / 212 / 217 / 232）",
            not re.search(r"\b(211|212|217|232)\b", statement),
        )
        check("V134 注明依据（A 规格 §10）与根因（V22 / V100）", "§10" in grant and "V22" in grant and "V100" in grant)

    # ---- 2b. 登记即死亡（UAT-SELF-05）----
    reg = REG_SERVICE.read_text(encoding="utf-8") if REG_SERVICE.exists() else ""
    check("PatientRegistrationService 在位", bool(reg))
    if reg:
        check("登记服务有死亡常量与分支（DISCHARGE_STATUS_DEAD / recordRegistrationDead）",
              "DISCHARGE_STATUS_DEAD" in reg and "recordRegistrationDead" in reg)
        check(
            "登记服务写入 followupStatus（dischargedDead ? DEAD : ACTIVE）",
            "setFollowupStatus" in reg and '"DEAD"' in reg and '"ACTIVE"' in reg,
        )
    history = HISTORY_SERVICE.read_text(encoding="utf-8") if HISTORY_SERVICE.exists() else ""
    check("PatientStatusHistoryService 提供 recordRegistrationDead（DEAD / REGISTRATION）",
          "recordRegistrationDead" in history and "DEAD" in history and "REGISTRATION" in history)
    insert = PATIENT_INSERT.read_text(encoding="utf-8") if PATIENT_INSERT.exists() else ""
    check("PatientInsert 带 followupStatus 字段", "followupStatus" in insert)
    mapper = PATIENT_MAPPER.read_text(encoding="utf-8") if PATIENT_MAPPER.exists() else ""
    check("insertPatient 显式写 followup_status 列（COALESCE 兜底 ACTIVE）",
          "followup_status" in mapper and "COALESCE(#{row.followupStatus}, 'ACTIVE')" in mapper)

    # ---- 2c. 文档更正（UAT-SELF-06）----
    patient_md = PATIENT_MD.read_text(encoding="utf-8") if PATIENT_MD.exists() else ""
    put_row = ""
    for line in patient_md.splitlines():
        if line.startswith("| PUT | `/api/hospital/followup/patients/{id}` |"):
            put_row = line
            break
    check("patient.md 的 PUT /patients/{id} 行在位（口径更正对象）", bool(put_row))
    if put_row:
        check("该行标注未实现（UAT-SELF-06 更正）", "未实现" in put_row and "40307" in put_row)
        check("该行指向 OQ-124（是否实现待产品定夺）", "OQ-124" in put_row)
    oq = OQ_MD.read_text(encoding="utf-8") if OQ_MD.exists() else ""
    check("open-questions.md 有 OQ-124 行（患者基础信息编辑接口定夺）", "| OQ-124 |" in oq)

    # ---- 2d. 修复有测试锁定 ----
    check("NoticeMenuGrantSqlTest 在位", GRANT_TEST.exists())
    if GRANT_TEST.exists():
        t = GRANT_TEST.read_text(encoding="utf-8")
        check(
            "种子测试覆盖 幂等只增/补授面恰为一对/built_in 限定/根因注释",
            all(
                k in t
                for k in (
                    "grantScriptIsIdempotentAppendOnly",
                    "grantsNoticeMenuToTenantAdminExactly",
                    "grantTargetsBuiltInRoleOnly",
                    "grantDocumentsRootCauseAndSpecBasis",
                )
            ),
        )
    check("NoticeMenuGrantVerifyIT 在位（双租户授予 + 规则清单 200）", GRANT_VERIFY_IT.exists())
    if GRANT_VERIFY_IT.exists():
        t = GRANT_VERIFY_IT.read_text(encoding="utf-8")
        check(
            "端到端核对含 默认规则口径（提前 7 天 / 2 天 / 站内+短信+抄送）",
            all(k in t for k in ("tenantAdminHoldsNoticeMenuAfterSeedV134", "tenantAdminCanReadNoticeRulesWithSpecDefaults")),
        )
    check("PatientRegistrationServiceTest 在位", REG_SERVICE_TEST.exists())
    if REG_SERVICE_TEST.exists():
        t = REG_SERVICE_TEST.read_text(encoding="utf-8")
        check(
            "登记单测覆盖 死亡登记 DEAD / 存活与病重仍 ACTIVE",
            "createMarksDeadFollowupStatusWhenDischargedDead" in t
            and "createKeepsActiveForAliveAndCriticalDischarge" in t,
        )
    check("PatientRegistrationIT 有死亡登记端到端用例（SKIP/DEAD）",
          REG_IT.exists()
          and "dischargedDeadRegistrationMarksDeadAndSkipsBatchEnrollment" in REG_IT.read_text(encoding="utf-8"))

    # ---- 3. 索引同步 ----
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 含本校验步骤", "check_uat_remediation_fullstack1.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引含修复手册", "uat-remediation-hospital-a-fullstack1.md" in readme)
    uat_manual = UAT_MANUAL.read_text(encoding="utf-8") if UAT_MANUAL.exists() else ""
    check("UAT 手册指向修复手册（全栈 1）", "uat-remediation-hospital-a-fullstack1.md" in uat_manual)
    check("UAT 手册 §10 留痕含本轮自查（E23-T09）", "E23-T09" in uat_manual)
    agents = AGENTS.read_text(encoding="utf-8") if AGENTS.exists() else ""
    check("AGENTS.md 脚本清单含本校验", "check_uat_remediation_fullstack1.py" in agents)

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("✓ " if ok else "✗ ") + n)
    print(f"UAT 修复（E23-T09）校验：{len(checks) - len(failed)}/{len(checks)} 通过")
    if failed:
        print("失败项：" + "；".join(failed), file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
