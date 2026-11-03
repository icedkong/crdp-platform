package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 删除患者（软删除）与恢复用例（E13-T09 / A-PAT-012、A-NFR-008、C-PAT-015、C-NFR-008）。 验收原文：删除只软删。
 *
 * <p>口径：
 *
 * <ul>
 *   <li>只软删除——置 {@code pt_patient.deleted = 1}、{@code delete_token = id}（释放病案号，uk_pt_patient_mrn
 *       允许软删后复用）、记录原因；服务期内全部数据保留（A-NFR-008「长期保留，只软删除」）， 子表（联系方式 / 状态历史 / 报告单等）不搬不改，经 patient_id
 *       关联随患者一并不可见；
 *   <li>删除是敏感操作——权限 {@code followup:patient:delete}（C 规格权限表；A 按 OQ-17 新增同名码）+
 *       二次认证（Controller @RequireReauth）+ 原因必填（≤ 255）；
 *   <li>恢复由租户管理员经同一权限执行——病案号已被新在册患者占用时 40902（V37 预留的冲突流程兜底）。
 * </ul>
 *
 * <p>审计：PATIENT_DELETE / PATIENT_RESTORE 各一条 OPERATION（越范围删除写 DENIED + 40400，不暴露对象存在）。
 */
@Service
public class PatientDeleteService {

    private final PatientDeleteGateway gateway;
    private final PatientMapper patientMapper;
    private final AuditLogger audit;

    public PatientDeleteService(
            PatientDeleteGateway gateway, PatientMapper patientMapper, AuditLogger audit) {
        this.gateway = gateway;
        this.patientMapper = patientMapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 删除请求：原因必填（A-PAT-012「软删除并填写原因」）。 */
    public record DeleteRequest(String reason) {}

    /** 删除结果。 */
    public record DeletedView(String patientId, String status) {}

    /** 恢复结果。 */
    public record RestoredView(String patientId, String status) {}

    // ---------------------------------------------------------------- 删除（A-PAT-012）

    /**
     * 软删除：范围内校验（越范围 40400 不暴露存在并写 DENIED 审计）→ 原因必填 → 置删除标记（0 行更新 = 已不在册 40901）→ PATIENT_DELETE
     * 审计。不做任何物理删除，也不改动子表。
     */
    @Transactional
    public DeletedView delete(long patientId, DeleteRequest request) {
        if (gateway.patientIdInScope(patientId) == null) {
            audit.append(
                    AuditEntry.operation("followup", "PATIENT_DELETE")
                            .objectType("PATIENT")
                            .objectId(String.valueOf(patientId))
                            .description("删除患者被拒绝：患者不在操作者数据范围内或已删除")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        String reason = requireReason(request == null ? null : request.reason());
        long operatorId = CurrentSession.requireHospital().userId();
        int updated = patientMapper.softDelete(patientId, reason, operatorId);
        if (updated == 0) {
            // 并发兜底：校验与更新之间被他人删除（deleted = 0 条件不命中）
            throw new ApiException(409, 40901, "患者已被删除，无需重复操作");
        }
        audit.append(
                AuditEntry.operation("followup", "PATIENT_DELETE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description("软删除患者（数据保留，病案号释放可复用）")
                        .beforeJson("{\"deleted\":false}")
                        .afterJson("{\"deleted\":true,\"reason\":\"" + reason + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new DeletedView(String.valueOf(patientId), "DELETED");
    }

    // ---------------------------------------------------------------- 恢复（A-PAT-012「可由租户管理员恢复」）

    /**
     * 恢复：定位已软删行（不存在 / 在册 40400）→ 病案号占位检查（被新患者占用 40902）→ 清除删除标记（0 行更新 = 已恢复 40901）→ PATIENT_RESTORE
     * 审计。已软删行不可见（全部在册查询都带 deleted = 0），按 id 经 IgnoreScope 定位（只取存在性与盲索引，不输出患者数据，与登记查重同口径）。
     */
    @Transactional
    public RestoredView restore(long patientId) {
        PatientMapper.DeletedRow row = patientMapper.findDeletedById(patientId);
        if (row == null) {
            throw new ApiException(404, 40400, "没有可恢复的已删除患者");
        }
        Long occupied = patientMapper.findLiveIdByMrnBlind(row.mrnBlind());
        if (occupied != null) {
            throw new ApiException(409, 40902, "该患者的病案号已被新登记患者占用，不能恢复；如需保留两人请先为占用者变更病案号");
        }
        long operatorId = CurrentSession.requireHospital().userId();
        if (patientMapper.restore(patientId, operatorId) == 0) {
            throw new ApiException(409, 40901, "患者已被恢复，无需重复操作");
        }
        audit.append(
                AuditEntry.operation("followup", "PATIENT_RESTORE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description("恢复已软删患者（病案号占位检查通过）")
                        .beforeJson("{\"deleted\":true,\"reason\":\"" + row.deleteReason() + "\"}")
                        .afterJson("{\"deleted\":false}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new RestoredView(String.valueOf(patientId), "ACTIVE");
    }

    // ---------------------------------------------------------------- 内部

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "请填写删除原因");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > 255) {
            throw new ApiException(400, 40001, "删除原因最多 255 个字符");
        }
        return trimmed;
    }
}
