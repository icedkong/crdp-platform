#!/usr/bin/env python3
"""等保测评整改（E22-T16，前端 / 全栈 2）防漂移校验（CI 恒跑，不联网）。

校验对象：

1. `docs/tasks/tasks.csv` E22-T16 行：类型 = 全栈、依赖 E22-T14、验收原句「整改项全部关闭」；
2. `docs/ops/mlps/remediation-frontend.md` 整改手册：验收拆解（先行整改 + 窗口 Issue 全关闭）、
   处置流程（分诊 → 决策 → 整改 → 复测 → 关闭）、三选一处置口径、D-01~D-08 前端面处置表
   （D-01 前端面 = 整改，40001 / 50300 呈现口径）、INFECTED 不进医院端界面、待人工清单
   （EICAR 三端呈现核验）、复测闭环与 E22-T17 衔接；
3. D-01 前端面修复的代码在位（运行时语义断言的静态面）：
   - `packages/api` `upload-rejection`（docs/api/README.md §2.3 错误码登记三步曲的前端一步）：
     50300 = 扫描不可用、40001 + message「病毒检测」标记 = 病毒检出、其余 other；
   - 批量上传行错误 `batchUploadRowError`（病毒 / 扫描不可用显示后端原文，其余通用文案）；
   - 上传报告单拍照队列失败页内联原因（`markFailed` 带 message + `queue-reason` 模板）；
   - H5 手机上传原文透传（XHR `load` 解析统一响应体，既有行为核验）；
   - `docs/design/pages.md` §0.2 错误表登记 50300；
4. 自查回写：`self-assessment.md` §5 E22-T16 记录（D-01 前端面补齐、判定无翻转）；
5. 索引同步：ci.yml 含本校验步骤、docs/README.md、docs/ops/mlps/README.md、AGENTS.md 脚本清单。

主仓库：https://github.com/icedkong/crdp-platform
"""

from __future__ import annotations

import csv
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANUAL = ROOT / "docs/ops/mlps/remediation-frontend.md"
TASKS_CSV = ROOT / "docs/tasks/tasks.csv"
CI_YML = ROOT / ".github/workflows/ci.yml"
DOCS_README = ROOT / "docs/README.md"
MLPS_README = ROOT / "docs/ops/mlps/README.md"
ASSESSMENT = ROOT / "docs/ops/mlps/self-assessment.md"
AGENTS = ROOT / "AGENTS.md"
PAGES_MD = ROOT / "docs/design/pages.md"
API_PKG = ROOT / "frontend/packages/api/src"
API_INDEX = API_PKG / "index.ts"
BATCH_LOGIC = (
    ROOT
    / "frontend/apps/web-hospital/src/components/followup/batch-upload-logic.ts"
)
BATCH_DIALOG = (
    ROOT
    / "frontend/apps/web-hospital/src/components/followup/batch-upload-dialog.vue"
)
UPLOAD_DIALOG = (
    ROOT
    / "frontend/apps/web-hospital/src/components/followup/upload-report-dialog.vue"
)
H5_API = ROOT / "frontend/apps/mobile-upload/src/api.ts"
TESTS = (
    API_PKG / "upload-rejection.spec.ts",
    BATCH_LOGIC.parent / "batch-upload-logic.spec.ts",
    UPLOAD_DIALOG.parent / "photo-upload-queue.spec.ts",
)


def e22_t16_row() -> dict[str, str] | None:
    # tasks.csv 表头为中文且带 BOM（utf-8-sig）
    with TASKS_CSV.open(encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            if row.get("任务编号") == "E22-T16":
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
    row = e22_t16_row()
    check("tasks.csv 有 E22-T16 行", row is not None)
    if row:
        check(
            "任务标题与类型（测评整改 · 全栈）",
            "测评整改" in (row.get("标题") or "") and (row.get("类型") or "").strip() == "全栈",
        )
        check("依赖 E22-T14（现场测评）", (row.get("依赖") or "").strip() == "E22-T14")
        check("验收原句（整改项全部关闭）", (row.get("验收标准") or "").strip() == "整改项全部关闭")

    # ---- 2. 整改手册 ----
    check("整改手册存在（docs/ops/mlps/remediation-frontend.md）", bool(manual))
    check(
        "手册头部任务行（E22-T16 · 依赖 E22-T14 · 验收「整改项全部关闭」 · 上游 onsite-assessment）",
        all(k in manual for k in ("E22-T16", "E22-T14", "整改项全部关闭", "onsite-assessment.md")),
    )
    for section in (
        "## 1. 目标与验收口径",
        "## 2. 处置流程（Issue → 关闭）",
        "## 3. 处置口径（三选一",
        "## 4. 已知偏差前端面先行整改",
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
        "手册 D-01 前端面处置 = 整改（40001 病毒检出 / 50300 扫描不可用 + 行级文案不误导重试）",
        "D-01（前端面）" in manual
        and "40001" in manual
        and "50300" in manual
        and "误导重试" in manual
        and "upload-rejection" in manual,
    )
    check(
        "手册 D-01~D-08 前端面全处置（无悬空项：D-05 / D-06 条件触发、D-02~04 / D-07 / D-08 无前端面）",
        all(k in manual for k in ("| D-05 |", "| D-06 |", "| D-02 / D-03 / D-04 |", "| D-07 |", "| D-08 |")),
    )
    check(
        "手册 INFECTED 呈现口径（不进医院端界面，与补扫不自动删除同源）",
        "INFECTED" in manual and "不进医院端界面" in manual,
    )
    check(
        "手册待人工清单（EICAR 三端呈现核验 + 停 clamd 的 50300 fail closed 核验）",
        "EICAR" in manual and "CRDP_VIRUS_SCAN_MODE=clamd" in manual and "fail closed" in manual,
    )
    check(
        "手册复测闭环（E22-T17 · Issue 状态为证 · 回写自查）",
        "E22-T17" in manual and "self-assessment.md" in manual and "closed" in manual,
    )

    # ---- 3. 代码在位 ----
    rejection = read(API_PKG / "upload-rejection.ts")
    check(
        "packages/api upload-rejection（uploadFailureInfo / uploadFailureText · 50300 与病毒检测标记分类）",
        all(
            k in rejection
            for k in ("uploadFailureInfo", "uploadFailureText", "50_300", "病毒检测", "errorBody")
        ),
    )
    index = read(API_INDEX)
    check(
        "packages/api index.ts 导出 upload-rejection（三步曲的前端一步）",
        "from './upload-rejection'" in index
        and "uploadFailureInfo" in index
        and "uploadFailureText" in index,
    )
    logic = read(BATCH_LOGIC)
    check(
        "批量上传行文案 batchUploadRowError（病毒 / 扫描不可用原文，其余通用文案）",
        "batchUploadRowError" in logic
        and "uploadFailureText" in logic
        and "BATCH_UPLOAD_ROW_ERROR" in logic,
    )
    dialog = read(BATCH_DIALOG)
    check(
        "批量上传对话框接线（catch 走 batchUploadRowError，不再硬编码通用文案）",
        "batchUploadRowError(error)" in dialog
        and "row.error = '上传失败，可重试（格式与大小按租户配置）'" not in dialog,
    )
    upload_dialog = read(UPLOAD_DIALOG)
    check(
        "上传报告单拍照队列内联原因（uploadFailureInfo 分类 + markFailed 带 message）",
        "uploadFailureInfo(error)" in upload_dialog
        and "markFailed(" in upload_dialog
        and "queue-reason" in upload_dialog,
    )
    h5 = read(H5_API)
    check(
        "H5 手机上传原文透传（XHR load 解析统一响应体——既有行为核验）",
        "H5ApiError(payload.code, payload.message)" in h5,
    )
    pages = read(PAGES_MD)
    check(
        "pages.md §0.2 错误表登记 50300（message + traceId，自带稍后重试）",
        "50000/50002/50300" in pages and "病毒扫描暂不可用" in pages,
    )

    # ---- 4. 测试锁定 ----
    for t in TESTS:
        check(f"测试在位「{t.name}」", t.exists())
    rejection_spec = read(TESTS[0])
    check(
        "upload-rejection.spec（50300 / 病毒 40001 / other / 双形态兼容 / 回落）",
        all(k in rejection_spec for k in ("50_300", "病毒检测", "Network Error", "dataError", "FALLBACK")),
    )
    batch_spec = read(TESTS[1])
    check(
        "batch-upload-logic.spec 含 E22-T16 行文案用例（病毒原文 / 50300 原文 / 其他通用）",
        "batchUploadRowError" in batch_spec and "e22-t16" in batch_spec,
    )
    photo_spec = read(TESTS[2])
    check(
        "photo-upload-queue.spec 含失败原因用例（带原因保留原文、状态文案不变）",
        "e22-t16" in photo_spec and "上传失败，可重试" in photo_spec,
    )

    # ---- 5. 自查回写 ----
    assessment = read(ASSESSMENT)
    check(
        "自查 §5 E22-T16 回写（D-01 前端面补齐 + 判定无翻转）",
        "E22-T16" in assessment and "remediation-frontend.md" in assessment and "无翻转" in assessment,
    )

    # ---- 6. 索引与接线 ----
    ci = read(CI_YML)
    check("ci.yml 接入本校验（check_mlps_remediation_frontend.py）", "check_mlps_remediation_frontend.py" in ci)
    check("docs/README.md 收录整改手册（前端）", "remediation-frontend.md" in read(DOCS_README))
    check("mlps README 衔接整改手册与校验脚本（前端）", "remediation-frontend.md" in read(MLPS_README))
    check("AGENTS.md 脚本清单含本校验", "check_mlps_remediation_frontend.py" in read(AGENTS))

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
