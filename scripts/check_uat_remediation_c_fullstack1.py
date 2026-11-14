#!/usr/bin/env python3
"""医院 C UAT 缺陷修复（E23-T24，全栈 1）防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/testing/uat-remediation-hospital-c-fullstack1.md` 修复手册：处置流程 / 三选一处置口径 /
   自查发现表（每条有处置结论）/ 人工事项清单 / 复测闭环判据齐备，验收句与 tasks.csv
   E23-T24 行一致；
2. 自查修复的代码与文档在位：
   - 植入记录编辑修改原因（UAT-SELF-01，C-PAT-011）：ImplantService.UpdateRequest 带 reason、
     update 校验必填（40001）并把「修改植入记录：{原因}」写入字段级历史、审计带原因；
     控制器 DTO 带 reason；前端 implant.ts 的 ImplantUpdatePayload / buildImplantUpdatePayload /
     implantEditReasonError、implant-form-modal.vue 编辑态原因输入；implant.md §1 PUT 契约与
     components.md ReasonModal 行同步；
   - UAT 就绪开关清单（UAT-SELF-02）：uat-hospital-c.md §3 激活链④为八项开关清单
     （含 record_edit_submitted / record_sequence_no），E-3 角色勘误为「医生」；
   - 修复有测试锁定（ImplantServiceTest / ImplantRecordIT / implant.spec.ts 的新用例名）；
3. 索引同步：ci.yml 含本校验步骤、docs/README.md、C UAT 手册 §10 / §11、AGENTS.md 脚本清单。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/testing/uat-remediation-hospital-c-fullstack1.md"
UAT_MANUAL = ROOT / "docs/testing/uat-hospital-c.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
README = ROOT / "docs/README.md"
AGENTS = ROOT / "AGENTS.md"
IMPLANT_MD = ROOT / "docs/api/implant.md"
COMPONENTS_MD = ROOT / "docs/design/components.md"
IMPLANT_SERVICE = (
    ROOT
    / "backend/crdp-followup/src/main/java/com/crdp/followup/implant/app/ImplantService.java"
)
IMPLANT_CONTROLLER = (
    ROOT
    / "backend/crdp-followup/src/main/java/com/crdp/followup/implant/api/ImplantController.java"
)
IMPLANT_SERVICE_TEST = (
    ROOT
    / "backend/crdp-followup/src/test/java/com/crdp/followup/implant/app/ImplantServiceTest.java"
)
IMPLANT_IT = (
    ROOT / "backend/crdp-server/src/test/java/com/crdp/server/followup/ImplantRecordIT.java"
)
IMPLANT_TS = ROOT / "frontend/packages/api/src/implant.ts"
IMPLANT_MODAL = (
    ROOT
    / "frontend/apps/web-hospital/src/components/followup/implant-form-modal.vue"
)
IMPLANT_SPEC = ROOT / "frontend/packages/api/src/implant.spec.ts"

# 自查发现表行格式：| UAT-SELF-nn | 级别 | module | ...（级别列取第二列）
FINDING_ROW = re.compile(r"^\|\s*(UAT-SELF-\d+)\s*\|\s*(阻断|严重|一般|建议)\s*\|", re.MULTILINE)

# UAT-SELF-02：C 手册 §3 激活链的八项开关（漏任一项即失败）
REQUIRED_FLAGS = (
    "implant",
    "scan_progress",
    "eri_cycle",
    "param_trend",
    "remote_monitor_record",
    "report_inbox",
    "record_edit_submitted",
    "record_sequence_no",
)


def e23_t24_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E23-T24":
                return row
    return None


def main() -> int:
    checks: list[tuple[str, bool]] = []

    def check(name: str, ok: bool) -> None:
        checks.append((name, ok))

    # ---- 1. 修复手册 ----
    manual = MANUAL.read_text(encoding="utf-8") if MANUAL.exists() else ""
    check("修复手册存在", bool(manual))
    row = e23_t24_row()
    check("tasks.csv 有 E23-T24 行", row is not None)
    if row:
        check("手册验收句与 tasks.csv 一致（UAT 缺陷关闭）", row.get("验收标准", "") in manual)
    for anchor in [
        "## 1. 目标与验收口径",
        "## 2. 处置流程（Issue → 关闭）",
        "## 3. 处置口径（三选一）",
        "## 4. UAT 脚本自查",
        "## 5. 待人工 / 外部事项清单（窗口 W44–W45 内执行）",
        "## 6. 复测与闭环判据",
        "## 7. 与其他任务的衔接",
        "## 8. 验证留痕",
    ]:
        check(f"手册章节「{anchor}」在位", anchor in manual)
    findings = FINDING_ROW.findall(manual)
    check("自查发现表至少 2 条（UAT-SELF-01 / 02）", len(findings) >= 2)
    check(
        "自查发现编号含 UAT-SELF-01 / 02（C 侧池，与 A 侧按院区隔离）",
        {"UAT-SELF-01", "UAT-SELF-02"} <= {fid for fid, _ in findings},
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
    check("手册注明发现不进缺陷库（同 A 侧两册口径）", "不进缺陷库" in manual)
    check(
        "手册引用 E23-T25 / E21-T30 / E23-T26 衔接",
        all(k in manual for k in ("E23-T25", "E21-T30", "E23-T26")),
    )
    check("手册注明 UAT-SELF 编号与 UAT-W 区分（同 PT-SELF 口径）", "UAT-W{周}" in manual)
    check("手册注明 C 侧编号池与 A 侧隔离（OQ-153①）", "OQ-153①" in manual)

    # ---- 2a. 植入编辑修改原因（UAT-SELF-01）----
    service = IMPLANT_SERVICE.read_text(encoding="utf-8") if IMPLANT_SERVICE.exists() else ""
    check("ImplantService 在位", bool(service))
    if service:
        check(
            "UpdateRequest 带 reason 字段（C-PAT-011 / UAT-SELF-01）",
            "record UpdateRequest(int baseVersion, ImplantPayload implant, String reason)" in service,
        )
        check(
            "update 校验原因必填（40001）",
            "修改植入记录必须填写修改原因" in service and "trimToNull(request.reason(), 255" in service,
        )
        check(
            "字段级历史写入带原因（修改植入记录：{原因}）",
            '"修改植入记录：" + reason' in service,
        )
        check("IMPLANT_UPDATE 审计带原因（.reason(reason)）", ".reason(reason)" in service)
    controller = (
        IMPLANT_CONTROLLER.read_text(encoding="utf-8") if IMPLANT_CONTROLLER.exists() else ""
    )
    check("ImplantController 在位", bool(controller))
    if controller:
        check(
            "控制器编辑 DTO 带 reason（@Size 255）",
            "String reason" in controller and "@Size(max = 255) String reason" in controller,
        )
        check("控制器把 reason 传入服务层 UpdateRequest", "request.reason()" in controller)

    frontend_ts = IMPLANT_TS.read_text(encoding="utf-8") if IMPLANT_TS.exists() else ""
    check("前端 implant.ts 在位", bool(frontend_ts))
    if frontend_ts:
        check(
            "ImplantUpdatePayload 带 reason 字段",
            "reason: string;" in frontend_ts and "export interface ImplantUpdatePayload" in frontend_ts,
        )
        check(
            "buildImplantUpdatePayload 携带并 trim 原因",
            "buildImplantUpdatePayload(\n  baseVersion: number,\n  form: ImplantForm,\n  reason: string,\n)" in frontend_ts
            and "reason: reason.trim()" in frontend_ts,
        )
        check("implantEditReasonError 校验（必填 / ≤255）", "export function implantEditReasonError" in frontend_ts)
    modal = IMPLANT_MODAL.read_text(encoding="utf-8") if IMPLANT_MODAL.exists() else ""
    check("implant-form-modal.vue 在位", bool(modal))
    if modal:
        check(
            "编辑弹窗含必填原因输入（data-testid / show-count）",
            'data-testid="implant-edit-reason"' in modal and "修改原因（必填）" in modal,
        )
        check(
            "handleSave 编辑态校验原因并随保存提交",
            "implantEditReasonError(reason.value)" in modal
            and "buildImplantUpdatePayload(props.record.version, form, reason.value)" in modal,
        )

    implant_md = IMPLANT_MD.read_text(encoding="utf-8") if IMPLANT_MD.exists() else ""
    put_row = ""
    for line in implant_md.splitlines():
        if line.startswith("| PUT | `/api/hospital/followup/implants/{implantId}` |"):
            put_row = line
            break
    check("implant.md 的 PUT /implants 行在位（契约同步对象）", bool(put_row))
    if put_row:
        check("该行注明 reason 必填 40001（UAT-SELF-01）", "reason" in put_row and "40001" in put_row)
        check("该行关联 C-PAT-011", "C-PAT-011" in put_row)
    components = COMPONENTS_MD.read_text(encoding="utf-8") if COMPONENTS_MD.exists() else ""
    check(
        "components.md ReasonModal 行注明植入编辑原因内嵌（必填）",
        "植入记录编辑的原因内嵌在编辑弹窗" in components,
    )

    # ---- 2b. UAT 手册开关清单与勘误（UAT-SELF-02 / E-3）----
    uat_manual = UAT_MANUAL.read_text(encoding="utf-8") if UAT_MANUAL.exists() else ""
    check("C UAT 手册在位", bool(uat_manual))
    if uat_manual:
        activation = ""
        for line in uat_manual.splitlines():
            if "功能开关按 C 上线配置全开" in line:
                activation = line
                break
        check("§3 激活链④行在位", bool(activation))
        if activation:
            check(
                "§3 激活链为八项开关清单（含两项易漏开关）",
                all(f"`{flag}`" in activation for flag in REQUIRED_FLAGS),
            )
            check("§3 注明漏开后果（OQ-136① / OQ-143②）", "OQ-136①" in activation and "OQ-143②" in activation)
        e3 = ""
        for line in uat_manual.splitlines():
            if line.startswith("| E-3 |"):
                e3 = line
                break
        check("§5 E-3 角色勘误为「医生」（科主任 → 医生）", bool(e3) and "| 医生（原列「科主任」" in e3)
        check("§10.2 自查小节在位（E23-T24 留痕）", "### 10.2 E23-T24 分担面自查" in uat_manual)

    # ---- 2c. 修复有测试锁定 ----
    unit_test = IMPLANT_SERVICE_TEST.read_text(encoding="utf-8") if IMPLANT_SERVICE_TEST.exists() else ""
    check("ImplantServiceTest 在位", bool(unit_test))
    if unit_test:
        check(
            "单测覆盖 原因必填 40001 / 历史带原因",
            "updateRequiresReasonForFieldHistory" in unit_test
            and "修改植入记录：升级前更正日期（示例）" in unit_test,
        )
    it = IMPLANT_IT.read_text(encoding="utf-8") if IMPLANT_IT.exists() else ""
    check("ImplantRecordIT 有端到端用例（编辑原因入历史行）",
          "editRecordsReasonInFieldHistory" in it and "修改植入记录：型号抄录更正（示例）" in it)
    spec = IMPLANT_SPEC.read_text(encoding="utf-8") if IMPLANT_SPEC.exists() else ""
    check("implant.spec.ts 在位", bool(spec))
    if spec:
        check(
            "前端用例覆盖 payload 原因 / 原因校验",
            "编辑请求体携带 baseVersion 乐观锁与修改原因" in spec
            and "编辑修改原因必填、≤255 字" in spec,
        )

    # ---- 3. 索引同步 ----
    ci = CI_YML.read_text(encoding="utf-8") if CI_YML.exists() else ""
    check("ci.yml 含本校验步骤", "check_uat_remediation_c_fullstack1.py" in ci)
    readme = README.read_text(encoding="utf-8") if README.exists() else ""
    check("docs/README.md 索引含修复手册", "uat-remediation-hospital-c-fullstack1.md" in readme)
    check("C UAT 手册指向修复手册（全栈 1）", "uat-remediation-hospital-c-fullstack1.md" in uat_manual)
    check("C UAT 手册 §10 留痕含本轮自查（E23-T24）", "E23-T24" in uat_manual)
    agents = AGENTS.read_text(encoding="utf-8") if AGENTS.exists() else ""
    check("AGENTS.md 脚本清单含本校验", "check_uat_remediation_c_fullstack1.py" in agents)

    failed = [n for n, ok in checks if not ok]
    for n, ok in checks:
        print(("✓ " if ok else "✗ ") + n)
    print(f"UAT 修复（E23-T24 · C 全栈 1）校验：{len(checks) - len(failed)}/{len(checks)} 通过")
    if failed:
        print("失败项：" + "；".join(failed), file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
