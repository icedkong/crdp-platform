package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientInsert;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 出院登记 / 患者登记用例（E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002；接口 docs/api/patient.md §2）。登记只落
 * {@code pt_patient} 的最小必填集（实体列），其余基线分类随 E13-T02 分节补录；入组随 E14。
 *
 * <p>查重两层口径（A-PAT-007「病案号在本院唯一」+「重复提示按当前用户范围计算」）：
 *
 * <ul>
 *   <li>check-duplicate：{@code exists} 按全院（含范围外，只报存在）；脱敏摘要 {@code patient} 只在范围内
 *       下发（范围外不暴露存在之外的任何信息）；姓名 + 出生日期相同（C-PAT-002）为可能重复提示（不阻止）；
 *   <li>create：登记时全院阻止，重复 40902，{@code data} 带范围内已有患者脱敏摘要；并发同号由 uk_pt_patient_mrn
 *       兜底（DuplicateKeyException 同口径转 40902）。
 * </ul>
 *
 * <p>病案号本院唯一 = 租户库内唯一（每院独立库，天然隔离）；软删除患者不占用病案号（A-PAT-012 恢复冲突随 E13-T09）。姓名、病案号只存加密列 + 盲索引（tech-plan
 * §6 混合存储），明文不进日志与审计（敏感值只存摘要）。
 */
@Service
public class PatientRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(PatientRegistrationService.class);

    /** 加密用途（重加密作业登记见 PatientEncryptedColumns）。 */
    static final String PURPOSE_MRN = "pt-patient-mrn";

    static final String PURPOSE_NAME = "pt-patient-name";

    /** C-PAT-002 可能重复提示的上限（提示即可，不整页拉人）。 */
    static final int IDENTITY_MATCH_LIMIT = 5;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatientMapper mapper;
    private final PatientRegistrationGateway gateway;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final PatientStatusHistoryService statusHistory;
    private final PatientSummaryService summary;
    private final PlanEnrollmentService enrollments;

    public PatientRegistrationService(
            PatientMapper mapper,
            PatientRegistrationGateway gateway,
            CryptoService crypto,
            AuditLogger audit,
            PatientStatusHistoryService statusHistory,
            PatientSummaryService summary,
            PlanEnrollmentService enrollments) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.crypto = crypto;
        this.audit = audit;
        this.statusHistory = statusHistory;
        this.summary = summary;
        this.enrollments = enrollments;
    }

    // ---------------------------------------------------------------- DTO

    /** 查重请求：病案号必填；姓名 + 出生日期可选（C-PAT-002 可能重复提示）。 */
    public record DuplicateCheckRequest(String medicalRecordNo, String name, LocalDate birthDate) {}

    /** 已有患者脱敏摘要（patient.md §2：只返回脱敏值，不暴露明文）。 */
    public record DuplicatePatientView(
            String id, String nameMasked, String wardName, LocalDate dischargeDate) {}

    /** 查重结果：exists 按全院；patient 仅当已有患者在当前用户范围内；possibleMatches 为姓名 + 出生日期相同的 范围内患者（可能重复，不阻止）。 */
    public record DuplicateCheckView(
            boolean exists,
            DuplicatePatientView patient,
            List<DuplicatePatientView> possibleMatches) {}

    /** 登记请求：字段即 pt_patient 实体列（A 出院登记口径的最小必填集见 {@link #validate}）。 */
    public record CreateRequest(
            String medicalRecordNo,
            String name,
            String gender,
            LocalDate birthDate,
            Long deptId,
            Long wardId,
            LocalDate surgeryDate,
            LocalDate admissionDate,
            LocalDate dischargeDate,
            Integer dischargeStatus,
            String legacyNo) {}

    /** 登记结果（敏感值脱敏；进入详情 / 基线补录由后续任务页面承接）。 */
    public record CreatedView(
            String id, String nameMasked, String wardName, LocalDate dischargeDate) {}

    /** 登记候选（页面下拉）：性别 / 出院状态为实体列编码，病区 / 科室来自字典。 */
    public record OptionItem(String code, String label) {}

    public record WardOption(long id, String name) {}

    public record RegistrationOptionsView(
            List<WardOption> wards,
            List<WardOption> depts,
            List<OptionItem> genders,
            List<OptionItem> dischargeStatuses) {}

    // ---------------------------------------------------------------- 查重（A-PAT-007、C-PAT-002）

    /** 病案号查重：新建页实时调用（A 规格 §4 验收「输入已存在的病案号，系统立即提示并给出已有患者链接」）。 纯查询不写审计（与 schema 读取同口径）。 */
    public DuplicateCheckView check(DuplicateCheckRequest request) {
        String mrn = requireText(request.medicalRecordNo(), "请填写病案号");
        String mrnBlind = crypto.blindIndex(PURPOSE_MRN, mrn);

        boolean exists = gateway.liveIdByMrnBlind(mrnBlind) != null;
        DuplicatePatientView patient = null;
        if (exists) {
            patient = toView(gateway.duplicateSummaryByMrnBlind(mrnBlind));
        }

        List<DuplicatePatientView> possibleMatches = List.of();
        if (request.name() != null && !request.name().isBlank() && request.birthDate() != null) {
            String nameBlind = crypto.blindIndex(PURPOSE_NAME, request.name().trim());
            List<DuplicatePatientView> matches = new ArrayList<>();
            for (PatientMapper.DuplicateRow row :
                    gateway.sameIdentityMatches(
                            nameBlind, request.birthDate(), mrnBlind, IDENTITY_MATCH_LIMIT)) {
                matches.add(toView(row));
            }
            possibleMatches = List.copyOf(matches);
        }
        return new DuplicateCheckView(exists, patient, possibleMatches);
    }

    // ---------------------------------------------------------------- 登记（A-PAT-003、C-PAT-001）

    /**
     * 出院登记：先全院查重（40902 阻止），再校验与写入；成功写 PATIENT_CREATE 操作审计（摘要不含明文）， 重复拒绝写 DENIED 审计（AGENTS.md
     * §5.4「被拒绝的操作」）。
     *
     * <p>E13-T03 起同一事务补写两行派生数据（tech-plan §7）：状态时间线初始行（登记即 ACTIVE，OQ-71②）与 当前状态摘要建行（后续提交重算的锚点）。
     */
    @Transactional
    public CreatedView create(CreateRequest request) {
        String mrn = requireText(request.medicalRecordNo(), "请填写病案号");
        String name = requireText(request.name(), "请填写姓名");
        String gender = normalizeGender(request.gender());
        Long wardId = request.wardId();
        if (wardId == null || wardId <= 0) {
            throw new ApiException(400, 40001, "请选择病区");
        }
        validateWard(wardId);
        if (request.deptId() != null) {
            validateDept(request.deptId());
        }
        LocalDate surgeryDate = requireDate(request.surgeryDate(), "请填写手术日期");
        LocalDate admissionDate = requireDate(request.admissionDate(), "请填写入院日期");
        LocalDate dischargeDate = requireDate(request.dischargeDate(), "请填写出院日期");
        Integer dischargeStatus = normalizeDischargeStatus(request.dischargeStatus());
        validateDateOrder(admissionDate, surgeryDate, dischargeDate);
        if (request.birthDate() != null && request.birthDate().isAfter(LocalDate.now())) {
            throw new ApiException(400, 40001, "出生日期不能晚于今天");
        }
        if (request.legacyNo() != null && request.legacyNo().length() > 64) {
            throw new ApiException(400, 40001, "原系统编号过长");
        }

        String mrnBlind = crypto.blindIndex(PURPOSE_MRN, mrn);
        if (gateway.liveIdByMrnBlind(mrnBlind) != null) {
            throw duplicateBlocked(mrn, mrnBlind);
        }

        PatientInsert row = new PatientInsert();
        row.setMrnCipher(crypto.encrypt(PURPOSE_MRN, mrn));
        row.setMrnBlind(mrnBlind);
        row.setNameCipher(crypto.encrypt(PURPOSE_NAME, name));
        row.setNameBlind(crypto.blindIndex(PURPOSE_NAME, name));
        row.setGender(gender);
        row.setBirthDate(request.birthDate());
        row.setDeptId(request.deptId());
        row.setWardId(wardId);
        row.setSurgeryDate(surgeryDate);
        row.setAdmissionDate(admissionDate);
        row.setDischargeDate(dischargeDate);
        row.setDischargeStatus(dischargeStatus);
        row.setLegacyNo(blankToNull(request.legacyNo()));
        row.setCreatedBy(CurrentSession.requireHospital().userId());
        try {
            mapper.insertPatient(row);
        } catch (DuplicateKeyException race) {
            // 并发登记同一病案号：唯一键兜底，与前置查重同口径
            log.warn("病案号并发登记冲突，按重复处理（E13-T01）");
            throw duplicateBlocked(mrn, mrnBlind);
        }

        // E13-T03：状态时间线初始行（登记即随访中，OQ-71②）与当前状态摘要建行（同一事务）
        statusHistory.recordRegistration(
                row.getId(), LocalDate.now(), CurrentSession.requireHospital().userId());
        summary.recompute(row.getId(), CurrentSession.requireHospital().userId());

        // E14-T05 / Q35 自动入组：出院登记后自动加入预置计划（auto_enroll 的已发布计划），
        // 出院状态为死亡由 plan 侧判定不加入；登记是主操作，自动入组故障不回滚登记（记 FAIL 审计提示人工入组）
        autoEnrollQuietly(row.getId(), dischargeStatus);

        audit.append(
                AuditEntry.operation("followup", "PATIENT_CREATE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(row.getId()))
                        .description(
                                "出院登记新建患者：病区 "
                                        + wardId
                                        + "，出院日期 "
                                        + dischargeDate
                                        + "（姓名与病案号为敏感字段，审计只记摘要）")
                        .afterJson(
                                writeJson(
                                        new AuditSummary(
                                                SensitiveValueMasker.mask("medicalRecordNo", mrn),
                                                SensitiveValueMasker.mask("name", name),
                                                wardId,
                                                dischargeDate.toString())))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new CreatedView(
                String.valueOf(row.getId()),
                SensitiveValueMasker.mask("name", name),
                wardNameOf(wardId),
                dischargeDate);
    }

    /** 登记候选（页面下拉）：病区 / 科室来自字典（启用项），性别与出院状态为实体列编码（A 规格字段 191）。 */
    public RegistrationOptionsView options() {
        List<WardOption> wards =
                mapper.findWards().stream()
                        .map(item -> new WardOption(item.id(), item.name()))
                        .toList();
        List<WardOption> depts =
                mapper.findDepts().stream()
                        .map(item -> new WardOption(item.id(), item.name()))
                        .toList();
        return new RegistrationOptionsView(
                wards,
                depts,
                List.of(new OptionItem("M", "男"), new OptionItem("F", "女")),
                List.of(
                        new OptionItem("1", "存活"),
                        new OptionItem("2", "病重自动出院"),
                        new OptionItem("3", "死亡")));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 自动入组的登记侧兜底（E14-T05 / Q35）：登记是主操作，自动入组故障不回滚登记， 只记 FAIL 审计提示人工入组（业务性跳过——死亡、无预置计划、已在组——由 plan
     * 侧静默处理，不进本分支）。
     */
    private void autoEnrollQuietly(long patientId, Integer dischargeStatus) {
        try {
            enrollments.autoEnrollOnRegistration(patientId, dischargeStatus);
        } catch (RuntimeException ex) {
            log.warn("自动入组失败，登记不受影响（可人工入组）patient={} 原因={}", patientId, ex.getMessage());
            audit.append(
                    AuditEntry.operation("followup", "PLAN_ENROLL")
                            .objectType("PATIENT")
                            .objectId(String.valueOf(patientId))
                            .description("自动入组失败，登记不受影响（可人工入组）：" + ex.getMessage())
                            .result(AuditResult.FAIL)
                            .build());
        }
    }

    /** 40902 阻止（A-PAT-007「提示并阻止」）：data 带范围内已有患者脱敏摘要；范围外只报重复不泄露摘要。 */
    private ApiException duplicateBlocked(String mrn, String mrnBlind) {
        DuplicatePatientView patient = null;
        PatientMapper.DuplicateRow summary = gateway.duplicateSummaryByMrnBlind(mrnBlind);
        if (summary != null) {
            patient = toView(summary);
        }
        audit.append(
                AuditEntry.operation("followup", "PATIENT_CREATE")
                        .objectType("PATIENT")
                        .description(
                                "出院登记被阻止：病案号已存在（"
                                        + SensitiveValueMasker.mask("medicalRecordNo", mrn)
                                        + "）")
                        .result(AuditResult.DENIED)
                        .errorCode(40902)
                        .reason("DUPLICATE_MEDICAL_RECORD_NO")
                        .build());
        return new ApiException(
                409,
                40902,
                patient == null ? "该病案号已在本院登记，请核对后重试" : "该病案号已在本院登记，可打开已有患者档案继续随访",
                new DuplicateCheckView(true, patient, List.of()));
    }

    private DuplicatePatientView toView(PatientMapper.DuplicateRow row) {
        if (row == null) {
            return null;
        }
        String name = row.nameCipher() == null ? null : crypto.decrypt(row.nameCipher());
        return new DuplicatePatientView(
                String.valueOf(row.id()),
                name == null ? "" : SensitiveValueMasker.mask("name", name),
                row.wardName(),
                row.dischargeDate());
    }

    private void validateWard(long wardId) {
        if (mapper.countEnabledWard(wardId) == 0) {
            throw new ApiException(400, 40001, "病区不存在或已停用");
        }
    }

    private void validateDept(long deptId) {
        if (mapper.countEnabledDept(deptId) == 0) {
            throw new ApiException(400, 40001, "科室不存在或已停用");
        }
    }

    /** A-PAT-003 最小必填集口径：入院 ≤ 手术 ≤ 出院（A 规格 §3 出院日期 ≥ 手术日期）。 */
    private static void validateDateOrder(
            LocalDate admission, LocalDate surgery, LocalDate discharge) {
        if (surgery.isBefore(admission)) {
            throw new ApiException(400, 40001, "手术日期不能早于入院日期");
        }
        if (discharge.isBefore(surgery)) {
            throw new ApiException(400, 40001, "出院日期不能早于手术日期");
        }
    }

    private static String normalizeGender(String gender) {
        if (gender == null || gender.isBlank()) {
            throw new ApiException(400, 40001, "请选择性别");
        }
        String normalized = gender.trim().toUpperCase();
        if (!"M".equals(normalized) && !"F".equals(normalized)) {
            throw new ApiException(400, 40001, "性别只支持男（M）或女（F）");
        }
        return normalized;
    }

    /** 出院状态：A 规格字段 191——1=存活 / 2=病重自动出院 / 3=死亡。 */
    private static Integer normalizeDischargeStatus(Integer status) {
        if (status == null) {
            throw new ApiException(400, 40001, "请选择出院状态");
        }
        if (status < 1 || status > 3) {
            throw new ApiException(400, 40001, "出院状态取值不正确");
        }
        return status;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(400, 40001, message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > 64) {
            throw new ApiException(400, 40001, "内容过长（最多 64 个字符）");
        }
        return trimmed;
    }

    private static LocalDate requireDate(LocalDate value, String message) {
        if (value == null) {
            throw new ApiException(400, 40001, message);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String wardNameOf(long wardId) {
        return mapper.findWards().stream()
                .filter(item -> item.id() == wardId)
                .map(PatientMapper.OptionRow::name)
                .findFirst()
                .orElse("");
    }

    /** 审计摘要：敏感值只存脱敏结果（AGENTS.md §5.4）。 */
    private record AuditSummary(
            String mrnMasked, String nameMasked, Long wardId, String dischargeDate) {}

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }
}
