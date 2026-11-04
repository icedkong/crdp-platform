package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientBatchMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 批量指定责任医生（E13-T07 / A-PAT-017、C-PAT-016；docs/api/patient.md §2 batch-assign）。
 *
 * <p>「在患者列表或批量入组页为选中患者批量指定责任医生，只能选该病区的医生」： 医生必须是启用用户（整体校验，40001）；逐条只处理操作者数据范围内的患者（范围外 / 不存在按
 * NOT_FOUND 列明，不暴露存在，AGENTS §5.2）， 且医生须负责该患者当前病区（sys_user_ward）——跨病区选中的患者按 DOCTOR_NOT_IN_WARD 跳过。
 * 批量操作结果逐条可查（tasks.csv 验收）：每条返回 患者脱敏摘要 + 成功 / 跳过 + 原因码。
 *
 * <p>写入只改 {@code pt_patient.responsible_user_id} 并递增乐观锁版本（ward_id 守卫防并发转病区），
 * 不动随访状态与任务；批量入组页带责任医生的场景经 {@link #assignDoctorToPatient} 复用同一入口（跨子域调用走 app 层，AGENTS §6）。
 *
 * <p>审计：一次批量一条汇总 PATIENT_BATCH_ASSIGN（OPERATION，afterJson 含计数与逐条结果摘要——patientId / outcome /
 * 原因码，不含姓名明文；逐条明细前 20 条 + 全量原因计数，与响应可对账）。
 */
@Service
public class PatientBatchAssignService {

    /** 单次批量上限（docs/api/patient.md §2：patientIds 单次 ≤ 500）。 */
    public static final int MAX_BATCH_SIZE = 500;

    /** 逐条结果码：成功。 */
    public static final String OUTCOME_SUCCESS = "SUCCESS";

    /** 逐条结果码：跳过（原因见 reason；批量语义下跳过不是被拒操作，是正常结果）。 */
    public static final String OUTCOME_SKIP = "SKIP";

    /** 跳过原因：患者不存在或不在操作者数据范围内（不暴露存在）。 */
    public static final String REASON_NOT_FOUND = "NOT_FOUND";

    /** 跳过原因：医生不负责该患者当前病区（A-PAT-017「只能选该病区的医生」）。 */
    public static final String REASON_DOCTOR_NOT_IN_WARD = "DOCTOR_NOT_IN_WARD";

    /** 跳过原因：指定未落行（并发转病区 / 软删，ward_id 守卫 0 行）。 */
    public static final String REASON_WARD_CHANGED = "WARD_CHANGED";

    private final PatientBatchMapper mapper;
    private final AuditLogger audit;
    private final CryptoService crypto;

    public PatientBatchAssignService(
            PatientBatchMapper mapper, AuditLogger audit, CryptoService crypto) {
        this.mapper = mapper;
        this.audit = audit;
        this.crypto = crypto;
    }

    /** 逐条结果（name / patientNo 只含脱敏值）。 */
    public record BatchItemView(
            String patientId,
            String name,
            String patientNo,
            String wardName,
            String outcome,
            String reason) {}

    /** 批量结果（items 与请求顺序一致，逐条可查）。 */
    public record BatchAssignResult(List<BatchItemView> items, int succeeded, int skipped) {}

    /** 责任医生候选（doctor-options；wardId / wardName = 负责病区）。 */
    public record DoctorOptionView(
            String id, String username, String displayName, String wardId, String wardName) {}

    /** 医生候选响应。 */
    public record DoctorOptionsView(List<DoctorOptionView> doctors) {}

    /** 批量指定责任医生：医生须启用（40001）；patientIds 去重、非空、≤500（40001）。 逐条结果按请求顺序返回，成功 / 跳过计数并入。 */
    public BatchAssignResult assign(List<Long> patientIds, Long doctorId) {
        if (doctorId == null || doctorId <= 0) {
            throw new ApiException(400, 40001, "请选择责任医生");
        }
        requireEnabledDoctor(doctorId);
        List<Long> ids = normalizeIds(patientIds);
        Map<Long, PatientBatchMapper.BatchPatientRow> patients = patientsInScope(ids);
        Long operatorId =
                CurrentSession.current().map(principal -> principal.userId()).orElse(null);

        List<BatchItemView> items = new ArrayList<>(ids.size());
        int succeeded = 0;
        int skipped = 0;
        for (Long id : ids) {
            PatientBatchMapper.BatchPatientRow patient = patients.get(id);
            if (patient == null) {
                items.add(itemOf(id, null, OUTCOME_SKIP, REASON_NOT_FOUND));
                skipped++;
                continue;
            }
            String blocked = whyBlocked(patient, doctorId);
            if (blocked == null
                    && mapper.updateResponsibleUser(id, patient.wardId(), doctorId, operatorId)
                            == 0) {
                // ward_id 守卫 0 行：批量取数后并发转病区 / 软删——按病区已变化跳过
                blocked = REASON_WARD_CHANGED;
            }
            if (blocked != null) {
                items.add(itemOf(id, patient, OUTCOME_SKIP, blocked));
                skipped++;
                continue;
            }
            items.add(itemOf(id, patient, OUTCOME_SUCCESS, null));
            succeeded++;
        }
        audit.append(
                AuditEntry.operation("followup", "PATIENT_BATCH_ASSIGN")
                        .objectType("PATIENT")
                        .description(
                                "批量指定责任医生（A-PAT-017）：医生 "
                                        + doctorId
                                        + "，成功 "
                                        + succeeded
                                        + "、跳过 "
                                        + skipped)
                        .afterJson(resultJson(items, succeeded, skipped))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new BatchAssignResult(items, succeeded, skipped);
    }

    /**
     * 单患者指定责任医生的领域入口（批量入组页带责任医生时经此复用，E13-T07 / A-PAT-016）。
     *
     * @return null = 已指定；否则为跳过原因码（医生不负责该病区 / 并发病区变化）
     */
    public String assignDoctorToPatient(
            long patientId, long doctorId, long wardId, Long operatorId) {
        requireEnabledDoctor(doctorId);
        if (!doctorManagesWard(doctorId, wardId)) {
            return REASON_DOCTOR_NOT_IN_WARD;
        }
        return mapper.updateResponsibleUser(patientId, wardId, doctorId, operatorId) == 0
                ? REASON_WARD_CHANGED
                : null;
    }

    /** 责任医生候选（A-PAT-017「只能选该病区的医生」的前端数据面；wardIds 可选过滤）。 */
    public DoctorOptionsView doctorOptions(List<Long> wardIds) {
        List<DoctorOptionView> doctors = new ArrayList<>();
        for (PatientBatchMapper.DoctorOptionRow row : mapper.findDoctorOptions(wardIds)) {
            doctors.add(
                    new DoctorOptionView(
                            String.valueOf(row.id()),
                            row.username(),
                            row.displayName(),
                            String.valueOf(row.wardId()),
                            row.wardName()));
        }
        return new DoctorOptionsView(doctors);
    }

    /** 医生是否负责该病区（A-PAT-017「只能选该病区的医生」）。 */
    public boolean doctorManagesWard(long doctorId, long wardId) {
        for (Long ward : mapper.findDoctorWardIds(doctorId)) {
            if (ward != null && ward == wardId) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 内部

    private void requireEnabledDoctor(long doctorId) {
        if (mapper.countEnabledDoctor(doctorId) == 0) {
            throw new ApiException(400, 40001, "责任医生不存在或已停用");
        }
    }

    /** 该患者被指定该医生时的跳过原因（null = 可以指定）。 */
    private String whyBlocked(PatientBatchMapper.BatchPatientRow patient, long doctorId) {
        if (patient.wardId() == null || !doctorManagesWard(doctorId, patient.wardId())) {
            return REASON_DOCTOR_NOT_IN_WARD;
        }
        return null;
    }

    /** 批量取操作者数据范围内的患者行（按 id 索引；范围外不返回；批量入组预检复用，E13-T07）。 */
    public Map<Long, PatientBatchMapper.BatchPatientRow> patientsInScope(List<Long> ids) {
        Map<Long, PatientBatchMapper.BatchPatientRow> byId = new LinkedHashMap<>();
        for (PatientBatchMapper.BatchPatientRow row : mapper.findPatientsInScope(ids)) {
            byId.put(row.id(), row);
        }
        return byId;
    }

    /** patientIds 规整：非空（40001）、去重保序、≤500（40001；批量入组复用）。 */
    public static List<Long> normalizeIds(List<Long> patientIds) {
        if (patientIds == null || patientIds.isEmpty()) {
            throw new ApiException(400, 40001, "请先选择患者");
        }
        List<Long> ids = patientIds.stream().filter(id -> id != null && id > 0).distinct().toList();
        if (ids.isEmpty()) {
            throw new ApiException(400, 40001, "请先选择患者");
        }
        if (ids.size() > MAX_BATCH_SIZE) {
            throw new ApiException(400, 40001, "单次批量最多 " + MAX_BATCH_SIZE + " 名患者");
        }
        return ids;
    }

    /** 逐条结果构造（name / patientNo 脱敏；patient 为 null = 越范围 / 不存在，不带摘要不暴露存在；批量入组复用）。 */
    public BatchItemView itemOf(
            long id, PatientBatchMapper.BatchPatientRow patient, String outcome, String reason) {
        if (patient == null) {
            return new BatchItemView(String.valueOf(id), null, null, null, outcome, reason);
        }
        return new BatchItemView(
                String.valueOf(id),
                SensitiveValueMasker.mask("name", decrypt(patient.nameCipher())),
                SensitiveValueMasker.mask("medicalRecordNo", decrypt(patient.mrnCipher())),
                patient.wardName(),
                outcome,
                reason);
    }

    private String decrypt(String cipher) {
        return cipher == null ? null : crypto.decrypt(cipher);
    }

    /** 审计摘要（只含 patientId / outcome / 原因码，不落姓名明文；逐条前 20 条 + 全量原因计数，与响应可对账）。 */
    private static String resultJson(List<BatchItemView> items, int succeeded, int skipped) {
        StringBuilder json =
                new StringBuilder("{\"succeeded\":")
                        .append(succeeded)
                        .append(",\"skipped\":")
                        .append(skipped)
                        .append(",\"items\":[");
        Map<String, Integer> reasonCount = new HashMap<>();
        int listed = 0;
        for (BatchItemView item : items) {
            if (item.reason() != null) {
                reasonCount.merge(item.reason(), 1, Integer::sum);
            }
            if (listed >= 20) {
                continue;
            }
            if (listed > 0) {
                json.append(',');
            }
            json.append("{\"patientId\":")
                    .append(item.patientId())
                    .append(",\"outcome\":\"")
                    .append(item.outcome())
                    .append('"');
            if (item.reason() != null) {
                json.append(",\"reason\":\"").append(item.reason()).append('"');
            }
            json.append('}');
            listed++;
        }
        json.append("],\"reasonCount\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : reasonCount.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        json.append("}}");
        return json.toString();
    }
}
