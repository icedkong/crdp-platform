package com.crdp.followup.patient.app;

import com.crdp.followup.patient.domain.PatientPlaintextSource;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.fieldpolicy.SensitiveFieldCatalog;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 敏感字段明文查看用例（E07-T05 / A-PAT-010、C-PAT-012；验收：查看明文需权限并记审计）。 接口契约见 docs/api/patient.md §5：权限 {@code
 * followup:patient:plain} 由 Controller 声明式校验，本用例再核对「该字段对当前角色策略为 MASKED」（
 * 可见字段明文随普通接口返回、隐藏字段禁止任何输出，都不走明文通道）。
 *
 * <p>审计（PLT-AUD-003 数据访问，{@code VIEW_PLAIN}）：每次调用一条——成功 {@code SUCCESS}、越范围 {@code DENIED}
 * (40400)、被频控 {@code DENIED}(42900，{@link #REASON_RATE_LIMITED} 触发安全告警 PLT-AUD-012)。 记录只含字段码、场景与
 * traceId，<b>绝不记明文值</b>。按 OQ-57 定论：明文查看不做密码二次认证（UI 定稿 popconfirm 二次确认），若产品改口径只需给接口加
 * {@code @RequireReauth}。
 */
@Service
public class PlaintextRevealService {

    /** 频控拒绝原因（aud_data_access_log.reason，安全告警规则按它识别，见 SecurityAlertService）。 */
    public static final String REASON_RATE_LIMITED = "RATE_LIMITED";

    /** 前端 60 秒自动恢复脱敏（design tokens timings.plaintextRevealMs 同口径）。 */
    static final int EXPIRES_IN_SECONDS = 60;

    private static final String RESOURCE = "patient";
    private static final Set<String> SCENES = Set.of("detail", "form");

    private final PatientPlaintextSource source;
    private final AuditLogger audit;
    private final PlaintextRateLimiter rateLimiter;

    public PlaintextRevealService(
            PatientPlaintextSource source, AuditLogger audit, PlaintextRateLimiter rateLimiter) {
        this.source = source;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    /** 明文查看结果：values 键为目录字段码、只含请求到的字段；expiresInSeconds 前端据此定时恢复脱敏。 */
    public record RevealView(Map<String, String> values, int expiresInSeconds) {}

    /**
     * @param patientId 患者编号（租户库内）
     * @param fields 目录字段码（name / idCard / phone / medicalRecordNo / address）
     * @param scene 场景：detail | form；缺省 detail
     */
    public RevealView reveal(long patientId, List<String> fields, String scene) {
        SessionPrincipal principal = CurrentSession.requireHospital();
        String normalizedScene = normalizeScene(scene);
        List<String> requested = validateFields(fields);
        requireMaskedPolicy(principal, requested);

        if (!rateLimiter.tryAcquire(principal.tenantId(), principal.userId())) {
            audit.append(
                    dataAccess(patientId, requested, normalizedScene)
                            .result(AuditResult.DENIED)
                            .errorCode(42900)
                            .reason(REASON_RATE_LIMITED)
                            .build());
            throw new ApiException(429, 42900, "查看明文过于频繁，请一分钟后再试");
        }

        Optional<Map<String, String>> plaintext = source.plaintextOf(patientId);
        if (plaintext.isEmpty()) {
            // 越范围 / 不存在一律 40400，不暴露对象存在（AGENTS.md §5.2）
            audit.append(
                    dataAccess(patientId, requested, normalizedScene)
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("PATIENT_NOT_FOUND_OR_OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String field : requested) {
            String value = plaintext.get().get(field);
            if (value != null) {
                values.put(field, value);
            }
        }
        audit.append(
                dataAccess(patientId, requested, normalizedScene)
                        .result(AuditResult.SUCCESS)
                        .recordCount((long) values.size())
                        .build());
        return new RevealView(values, EXPIRES_IN_SECONDS);
    }

    /** 场景归一：空 → detail；其余必须是 detail | form（患者档案 / 表单，components.md §3）。 */
    private static String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return "detail";
        }
        if (!SCENES.contains(scene)) {
            throw new ApiException(400, 40001, "场景只支持 detail（患者档案）或 form（表单）");
        }
        return scene;
    }

    /** 字段校验：非空、都在患者敏感字段目录内，去重保序。 */
    private static List<String> validateFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new ApiException(400, 40001, "请至少选择一个要查看的敏感字段");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String field : fields) {
            if (SensitiveFieldCatalog.find(RESOURCE, field).isEmpty()) {
                throw new ApiException(400, 40001, "未知敏感字段：" + field);
            }
            unique.add(field);
        }
        return List.copyOf(unique);
    }

    /** 明文通道只服务 MASKED 字段（patient.md §5）：VISIBLE 的明文已随普通接口返回（无需也不应再走敏感通道）， HIDDEN 禁止任何输出。 */
    private static void requireMaskedPolicy(SessionPrincipal principal, List<String> fields) {
        FieldPolicySnapshot policies =
                principal.fieldPolicies() == null
                        ? FieldPolicySnapshot.defaults()
                        : principal.fieldPolicies();
        for (String field : fields) {
            String decided = policies.decide(RESOURCE, field);
            if (SensitiveFieldCatalog.POLICY_VISIBLE.equals(decided)) {
                throw new ApiException(400, 40001, "字段「" + labelOf(field) + "」对当前角色为可见，明文已随接口返回");
            }
            if (SensitiveFieldCatalog.POLICY_HIDDEN.equals(decided)) {
                throw new ApiException(400, 40001, "字段「" + labelOf(field) + "」对当前角色为隐藏，不能查看明文");
            }
        }
    }

    /** 数据访问审计骨架：对象固定 PATIENT，描述只含字段中文名与场景（无明文）。 */
    private static AuditEntry.Builder dataAccess(
            long patientId, List<String> fields, String scene) {
        StringBuilder labels = new StringBuilder();
        for (String field : fields) {
            if (labels.length() > 0) {
                labels.append('、');
            }
            labels.append(labelOf(field));
        }
        return AuditEntry.dataAccess("followup", "VIEW_PLAIN")
                .objectType("PATIENT")
                .objectId(String.valueOf(patientId))
                .description("查看患者敏感字段明文：" + labels + "（场景 " + scene + "）");
    }

    private static String labelOf(String field) {
        return SensitiveFieldCatalog.find(RESOURCE, field)
                .map(SensitiveFieldCatalog.Entry::label)
                .orElse(field);
    }
}
