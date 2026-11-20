package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientCorrectionMapper;
import com.crdp.followup.task.app.StartDateRecalculationService;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.crypto.DigestService;
import com.crdp.framework.fieldpolicy.FieldPolicySnapshot;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OQR-04：登记实体就地纠错；元数据驱动呈现，固定实体绑定，不经记录 JSON 存敏感值。 */
@Service
public class PatientCorrectionService {
    private static final Set<String> ORDINARY =
            Set.of(
                    "gender",
                    "birthDate",
                    "surgeryDate",
                    "admissionDate",
                    "dischargeDate",
                    "patientType");
    private static final Map<String, String> PURPOSES =
            Map.of(
                    "name",
                    "pt-patient-name",
                    "medicalRecordNo",
                    "pt-patient-mrn",
                    "idCard",
                    "pt-patient-idcard",
                    "address",
                    "pt-patient-address");
    private final PatientCorrectionMapper mapper;
    private final PatientCorrectionGateway gateway;
    private final PatientRegistrationGateway registration;
    private final FormService forms;
    private final FieldHistoryService history;
    private final StartDateRecalculationService dates;
    private final PatientSummaryService summary;
    private final CryptoService crypto;
    private final DigestService digest;
    private final AuditLogger audit;
    private final com.crdp.followup.implant.app.ImplantService implants;

    public PatientCorrectionService(
            PatientCorrectionMapper mapper,
            PatientCorrectionGateway gateway,
            PatientRegistrationGateway registration,
            FormService forms,
            FieldHistoryService history,
            StartDateRecalculationService dates,
            PatientSummaryService summary,
            CryptoService crypto,
            DigestService digest,
            AuditLogger audit,
            com.crdp.followup.implant.app.ImplantService implants) {
        this.implants = implants;
        this.mapper = mapper;
        this.gateway = gateway;
        this.registration = registration;
        this.forms = forms;
        this.history = history;
        this.dates = dates;
        this.summary = summary;
        this.crypto = crypto;
        this.digest = digest;
        this.audit = audit;
    }

    public record Command(
            Long baseVersion, Map<String, Object> changes, String reason, Boolean confirmed) {}

    public record View(
            String patientId,
            long version,
            FormService.SchemaView schema,
            Map<String, Object> data,
            FormService.SchemaView identitySchema,
            Map<String, String> identity,
            FormService.SchemaView statusSchema,
            Map<String, Object> status,
            boolean locked) {}

    @Transactional(readOnly = true)
    public View view(long id) {
        requireHospital();
        var row = requireRow(id, false);
        Map<String, String> masked = new LinkedHashMap<>();
        var policy = CurrentSession.requireHospital().fieldPolicies();
        for (var entry : ciphers(row).entrySet()) {
            String field = entry.getKey();
            if ("HIDDEN"
                    .equals(
                            (policy == null ? FieldPolicySnapshot.defaults() : policy)
                                    .decide("patient", field))) continue;
            masked.put(
                    field,
                    SensitiveValueMasker.mask(
                            field,
                            entry.getValue() == null ? null : crypto.decrypt(entry.getValue())));
        }
        var registrationSchema = ordinarySchema();
        Map<String, Object> data = ordinary(row);
        Set<String> displayed = new HashSet<>();
        registrationSchema
                .categories()
                .forEach(c -> c.fields().forEach(f -> displayed.add(f.key())));
        data.keySet().retainAll(displayed);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("wardName", row.wardName());
        status.put("responsibleUserName", row.responsibleUserName());
        status.put("followupStatus", row.followupStatus());
        status.put(
                "dischargeStatus",
                row.dischargeStatus() == null ? null : row.dischargeStatus().toString());
        return new View(
                String.valueOf(id),
                row.version(),
                registrationSchema,
                data,
                forms.schema("PT_IDENTITY", null),
                masked,
                forms.schema("PT_REG_STATUS", null),
                status,
                gateway.locked(id, false));
    }

    @com.crdp.framework.audit.AuditLog(
            module = "followup",
            action = "PATIENT_REGISTRATION_CORRECT_REQUEST",
            objectType = "PATIENT",
            objectId = "#id",
            description = "登记纠错请求（不记录请求载荷）")
    @Transactional
    public View save(long id, Command command) {
        return correct(id, command, false);
    }

    @com.crdp.framework.audit.AuditLog(
            module = "followup",
            action = "PATIENT_IDENTITY_CORRECT_REQUEST",
            objectType = "PATIENT",
            objectId = "#id",
            description = "敏感身份纠错请求（不记录请求载荷）")
    @Transactional
    public View correctIdentity(long id, Command command) {
        return correct(id, command, true);
    }

    private View correct(long id, Command command, boolean sensitive) {
        requireHospital();
        var row = requireRow(id, true);
        if (command == null || command.baseVersion() == null) throw bad("baseVersion 必填");
        if (command.baseVersion() != row.version()) throw conflict(row.version());
        String reason = command.reason() == null ? "" : command.reason().strip();
        if (reason.isEmpty() || reason.length() > 255) throw bad("请填写不超过 255 字的更正原因，不要包含患者敏感值");
        if (sensitive && !Boolean.TRUE.equals(command.confirmed())) throw bad("敏感身份更正须二次确认");
        Map<String, Object> changes = command.changes();
        Set<String> allowed = sensitive ? PURPOSES.keySet() : ORDINARY;
        if (changes == null || changes.isEmpty() || !allowed.containsAll(changes.keySet()))
            throw bad("包含未授权、只读或不支持的登记字段");
        // 锁行存在性与状态一并保护，避免在并发锁定基线后仍继续更正。
        mapper.ensureLockRow(id);
        if (gateway.locked(id, true)) throw new ApiException(409, 40901, "基线已锁定，请先解锁后更正登记信息");
        if (sensitive) saveIdentity(row, changes, reason);
        else saveOrdinary(row, changes, reason);
        return view(id);
    }

    private void saveOrdinary(
            PatientCorrectionMapper.Row row, Map<String, Object> changes, String reason) {
        var schema = ordinarySchema();
        var old = ordinary(row);
        Map<String, Object> values = new LinkedHashMap<>(old);
        values.putAll(changes);
        validate(values, schema, changes.keySet());
        boolean implant = implants.implantEnabled();
        if (!Set.of("M", "F").contains(String.valueOf(values.get("gender")))) throw bad("请选择有效性别");
        LocalDate birth = date(values, "birthDate"),
                surgery = date(values, "surgeryDate"),
                admission = date(values, "admissionDate"),
                discharge = date(values, "dischargeDate");
        if ((implant && birth == null)
                || (!implant && (surgery == null || admission == null || discharge == null)))
            throw bad("登记最小必填日期不能为空");
        if (birth != null && birth.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
            throw bad("出生日期不能晚于今天");
        if ((admission != null && surgery != null && admission.isAfter(surgery))
                || (surgery != null && discharge != null && surgery.isAfter(discharge))
                || (admission != null && discharge != null && admission.isAfter(discharge)))
            throw bad("日期顺序须为入院日期不晚于手术日期、手术日期不晚于出院日期");
        if ((surgery == null
                        && row.surgeryDate() != null
                        && gateway.dateUsers(row.id(), "SURGERY_DATE") > 0)
                || (discharge == null
                        && row.dischargeDate() != null
                        && gateway.dateUsers(row.id(), "DISCHARGE_DATE") > 0)) {
            throw new ApiException(409, 40901, "该日期仍被在组计划作为起算依据，不能清空");
        }
        if (values.equals(old)) return;
        if (mapper.updateOrdinary(
                        row.id(),
                        row.version(),
                        actor(),
                        text(values.get("gender")),
                        birth,
                        surgery,
                        admission,
                        discharge,
                        text(values.get("patientType")))
                != 1) throw conflict(row.version());
        for (String key : ORDINARY)
            if (!Objects.equals(old.get(key), values.get(key)))
                history.logChange(
                        "PT_PATIENT",
                        row.id(),
                        schema.code(),
                        schema.version(),
                        key,
                        old.get(key),
                        values.get(key),
                        ChangeSource.USER,
                        reason,
                        List.of());
        if (!Objects.equals(row.surgeryDate(), surgery)
                || !Objects.equals(row.dischargeDate(), discharge))
            dates.recalculateOnBaseDateChange(row.id(), actor());
        summary.recompute(row.id(), actor());
        audit.append(
                AuditEntry.operation("followup", "PATIENT_REGISTRATION_CORRECT")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(row.id()))
                        .description("登记信息就地纠错")
                        .reason(reason)
                        .success()
                        .build());
    }

    private void saveIdentity(
            PatientCorrectionMapper.Row row, Map<String, Object> changes, String reason) {
        Map<String, String> old = ciphers(row), updated = new LinkedHashMap<>(old);
        var schema = forms.schema("PT_IDENTITY", null);
        Set<String> configured = new HashSet<>();
        schema.categories().forEach(c -> c.fields().forEach(f -> configured.add(f.key())));
        if (!configured.containsAll(changes.keySet())) throw bad("字段已从身份模板移除，请重新加载");
        var policy = CurrentSession.requireHospital().fieldPolicies();
        for (var change : changes.entrySet()) {
            String key = change.getKey();
            if ("HIDDEN"
                    .equals(
                            (policy == null ? FieldPolicySnapshot.defaults() : policy)
                                    .decide("patient", key)))
                throw new ApiException(403, 40300, "没有该身份字段的更正权限");
            if (change.getValue() != null && !(change.getValue() instanceof String))
                throw bad("身份字段须为文本");
            String value = text(change.getValue());
            if ((key.equals("name") || key.equals("medicalRecordNo")) && value == null)
                throw bad("姓名和病案号不能为空");
            if (value != null && value.length() > (key.equals("address") ? 255 : 64))
                throw bad("身份字段长度超出限制");
            String previous = old.get(key) == null ? null : crypto.decrypt(old.get(key));
            if (Objects.equals(previous, value)) continue;
            if ((value != null && reason.contains(value))
                    || (previous != null && reason.contains(previous)))
                throw bad("更正原因不能包含敏感字段原值或新值");
            updated.put(key, value == null ? null : crypto.encrypt(PURPOSES.get(key), value));
        }
        if (old.equals(updated)) return;
        String nameBlind =
                Objects.equals(old.get("name"), updated.get("name"))
                        ? row.nameBlind()
                        : crypto.blindIndex(
                                PURPOSES.get("name"), crypto.decrypt(updated.get("name")));
        String mrnBlind =
                Objects.equals(old.get("medicalRecordNo"), updated.get("medicalRecordNo"))
                        ? row.mrnBlind()
                        : crypto.blindIndex(
                                PURPOSES.get("medicalRecordNo"),
                                crypto.decrypt(updated.get("medicalRecordNo")));
        Long duplicate = registration.liveIdByMrnBlind(mrnBlind);
        if (duplicate != null && duplicate != row.id()) throw duplicate();
        try {
            if (mapper.updateIdentity(
                            row.id(),
                            row.version(),
                            actor(),
                            updated.get("name"),
                            nameBlind,
                            updated.get("medicalRecordNo"),
                            mrnBlind,
                            updated.get("idCard"),
                            updated.get("address"))
                    != 1) throw conflict(row.version());
        } catch (DuplicateKeyException collision) {
            throw duplicate();
        }
        for (String key : PURPOSES.keySet())
            if (!Objects.equals(old.get(key), updated.get(key)))
                history.logDigestChange(
                        row.id(),
                        schema.code(),
                        schema.version(),
                        key,
                        old.get(key) == null ? null : digest.hashHex(old.get(key)),
                        updated.get(key) == null ? null : digest.hashHex(updated.get(key)),
                        reason);
        summary.recompute(row.id(), actor());
        audit.append(
                AuditEntry.operation("followup", "PATIENT_IDENTITY_CORRECT")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(row.id()))
                        .description("敏感身份就地更正（仅留密文摘要）")
                        .reason(reason)
                        .success()
                        .build());
    }

    public List<FieldHistoryService.HistoryView> history(long id) {
        requireHospital();
        requireRow(id, false);
        return history.history("PT_PATIENT", id);
    }

    private FormService.SchemaView ordinarySchema() {
        return forms.schema(implants.implantEnabled() ? "PT_REG_C" : "PT_REG_A", null);
    }

    private PatientCorrectionMapper.Row requireRow(long id, boolean locking) {
        var row = gateway.find(id, locking);
        if (row == null) throw new ApiException(404, 40400, "患者不存在");
        return row;
    }

    private static void requireHospital() {
        if (CurrentSession.requireHospital().tenantView())
            throw new ApiException(403, 40303, "租户视角暂不开放登记纠错入口");
    }

    private static Long actor() {
        return CurrentSession.requireHospital().userId();
    }

    private static Map<String, Object> ordinary(PatientCorrectionMapper.Row r) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("gender", r.gender());
        values.put("birthDate", string(r.birthDate()));
        values.put("surgeryDate", string(r.surgeryDate()));
        values.put("admissionDate", string(r.admissionDate()));
        values.put("dischargeDate", string(r.dischargeDate()));
        values.put("patientType", r.patientType());
        return values;
    }

    private static Map<String, String> ciphers(PatientCorrectionMapper.Row r) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("name", r.nameCipher());
        result.put("medicalRecordNo", r.mrnCipher());
        result.put("idCard", r.idCardCipher());
        result.put("address", r.addressCipher());
        return result;
    }

    private static String string(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }

    private static LocalDate date(Map<String, Object> values, String key) {
        if (text(values.get(key)) == null) return null;
        try {
            return LocalDate.parse(text(values.get(key)));
        } catch (RuntimeException invalid) {
            throw bad("日期格式须为 YYYY-MM-DD");
        }
    }

    private static void validate(
            Map<String, Object> values, FormService.SchemaView schema, Set<String> changed) {
        Set<String> configured = new HashSet<>();
        for (var category : schema.categories())
            for (var field : category.fields()) {
                configured.add(field.key());
                Object value = values.get(field.key());
                if (field.required() && text(value) == null) throw bad("请填写必填登记字段");
                if (value == null) continue;
                if (!(value instanceof String)) throw bad("登记字段须为文本或日期");
                if (changed.contains(field.key())
                        && field.type().equals("select")
                        && (field.optionSet() == null
                                || field.optionSet().stream()
                                        .noneMatch(
                                                option ->
                                                        !option.disabled()
                                                                && Objects.equals(
                                                                        option.code(), value))))
                    throw bad("所选字典值不存在或已停用");
                if (value.toString().length() > 64) throw bad("登记字段长度超出限制");
            }
        if (!configured.containsAll(changed)) throw bad("登记模板已变更，请重新加载");
    }

    private static ApiException conflict(long version) {
        return new ApiException(409, 40900, "登记信息已被更新，请重新加载后确认", Map.of("currentVersion", version));
    }

    private static ApiException duplicate() {
        return new ApiException(409, 40902, "病案号 / 病历号已被使用，请核对后更正");
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
