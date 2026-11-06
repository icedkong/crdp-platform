package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.BaselineLockMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基线锁定与解锁用例（E13-T02 / A-PAT-006；tasks.csv 验收「锁定后只读，解锁需权限并留痕」；接口 docs/api/patient.md §4 POST
 * /patients/{id}/lock）。
 *
 * <p>医院 A 已确认口径（Q25）：出院后 30 天内由病区医生确认无误后<b>锁定</b>；<b>解锁</b>需专门权限（A 规格权限表
 * followup:patient:lock「锁定与解锁基线」——与编辑权分离的专用权限，控制器 {@code @RequiresPermission} 强制）并填写原因。 「30
 * 天内」是工作流程约定（提醒病区医生及时确认），不作为硬性拦截——纸质补录患者的确认时点不受该窗口限制； 如医院要求硬拦截，加日期校验即可（见 PR 说明）。
 *
 * <ul>
 *   <li>锁定：锁定说明可选（如「出院 30 天内确认无误」）；重复锁定 40001（状态不变不产生新留痕）；
 *   <li>解锁：原因<b>必填</b>（≤255 字，空白 40001）；未锁定时解锁 40001；
 *   <li>留痕（验收）：锁定 / 解锁各写一条操作审计（BASELINE_LOCK / BASELINE_UNLOCK，只追加、不可改删，
 *       docs/api/audit.md），当前态与最近一次流转的人 / 时间 / 原因随 pt_baseline_lock 行下发（患者详情「操作日志」 页签可见审计行）；
 *   <li>锁定后只读：保存与暂存在 {@link BaselineSectionService} 经 {@link #requireUnlocked} 拦截（40901）。
 * </ul>
 *
 * <p>越范围 / 不存在的患者 40400（不暴露对象存在，AGENTS.md §5.2）。
 */
@Service
public class BaselineLockService {

    /** 原因上限（与 pt_baseline_lock 列宽一致）。 */
    private static final int MAX_REASON = 255;

    private final BaselineGateway gateway;
    private final BaselineLockMapper mapper;
    private final AuditLogger audit;

    public BaselineLockService(
            BaselineGateway gateway, BaselineLockMapper mapper, AuditLogger audit) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 锁定请求（POST /patients/{id}/lock）：locked=true 锁定 / false 解锁；解锁 reason 必填。 */
    public record LockCommand(Boolean locked, String reason) {}

    /** 锁定状态视图（pages.md §4.2：锁定横幅 + 最近一次锁定 / 解留痕展示；未建行 = 未锁定）。 */
    public record LockView(
            boolean locked,
            String lockReason,
            String lockedBy,
            String lockedAt,
            String unlockReason,
            String unlockedBy,
            String unlockedAt) {}

    // ---------------------------------------------------------------- 锁定 / 解锁（A-PAT-006）

    /** 锁定 / 解锁入口：状态校验 → 落行 → 审计留痕；返回最新状态视图。 */
    @Transactional
    public LockView setLocked(long patientId, LockCommand command) {
        if (command == null || command.locked() == null) {
            throw new ApiException(400, 40001, "必须指明 locked（true 锁定 / false 解锁）");
        }
        if (gateway.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在");
        }
        long operator = CurrentSession.requireHospital().userId();
        BaselineLockMapper.LockRowRaw current = gateway.lock(patientId);
        boolean lockedNow = current != null && current.locked();
        if (command.locked()) {
            if (lockedNow) {
                throw new ApiException(400, 40001, "基线已是锁定状态，不能重复锁定");
            }
            String reason = optionalReason(command.reason());
            mapper.lock(patientId, reason, operator);
            audit.append(
                    AuditEntry.operation("followup", "BASELINE_LOCK")
                            .objectType("PATIENT")
                            .objectId(String.valueOf(patientId))
                            .description(
                                    "锁定基线（患者 "
                                            + patientId
                                            + "）"
                                            + (reason == null ? "" : "：" + reason)
                                            + "——锁定后基线只读")
                            .result(AuditResult.SUCCESS)
                            .build());
        } else {
            if (!lockedNow) {
                throw new ApiException(400, 40001, "基线未锁定，无需解锁");
            }
            String reason = requiredReason(command.reason());
            mapper.unlock(patientId, reason, operator);
            audit.append(
                    AuditEntry.operation("followup", "BASELINE_UNLOCK")
                            .objectType("PATIENT")
                            .objectId(String.valueOf(patientId))
                            .description("解锁基线（患者 " + patientId + "）原因：" + reason + "——解锁后基线可编辑")
                            .result(AuditResult.SUCCESS)
                            .build());
        }
        return lockView(patientId);
    }

    /** 锁定状态视图（GET /patients/{id}/baseline 附带；未建行 = 未锁定）。 */
    public LockView lockView(long patientId) {
        BaselineLockMapper.LockRowRaw row = gateway.lock(patientId);
        if (row == null) {
            return new LockView(false, null, null, null, null, null, null);
        }
        return new LockView(
                row.locked(),
                row.lockReason(),
                displayName(row.lockedBy()),
                Time.format(row.lockedAt()),
                row.unlockReason(),
                displayName(row.unlockedBy()),
                Time.format(row.unlockedAt()));
    }

    /** 锁定拦截（保存 / 暂存入口共用，A-PAT-006「锁定后只读」）：已锁定 40901，message 由调用方给场景化提示。 */
    public void requireUnlocked(long patientId, String message) {
        BaselineLockMapper.LockRowRaw row = gateway.lock(patientId);
        if (row != null && row.locked()) {
            throw new ApiException(409, 40901, message);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 解锁原因（必填，A-PAT-006）：空白 40001；上限 255 字。 */
    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "解锁基线必须填写原因");
        }
        return normalizeReason(reason);
    }

    /** 锁定说明（可选）：去首尾空白，空串归一为 null；超长 40001。 */
    private static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return normalizeReason(reason);
    }

    private static String normalizeReason(String reason) {
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON) {
            throw new ApiException(400, 40001, "原因不超过 " + MAX_REASON + " 字");
        }
        return trimmed;
    }

    private String displayName(Long userId) {
        return userId == null ? null : mapper.findDisplayName(userId);
    }

    /** 时间展示口径（北京时间 ISO-8601 带时区，与表单引擎一致）。 */
    static final class Time {
        private Time() {}

        static String format(java.time.LocalDateTime time) {
            return time == null
                    ? null
                    : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
    }
}
