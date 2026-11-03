package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.ContactVersionInsert;
import com.crdp.followup.patient.infra.mapper.PatientContactMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联系方式版本用例（E13-T03 / tasks.csv 验收「联系方式改动保留历史」；接口 docs/api/patient.md §6）。
 *
 * <p>hospital-a-spec §2：联系方式（电话1、电话2、是否已加微信）「换号时新增一条并保留旧号，记录生效日期与来源（哪次随访）」； tech-plan §7
 * pt_contact「每个号码一个版本……沟通记录引用具体版本，换号不改历史」。因此：
 *
 * <ul>
 *   <li>新增版本只 INSERT（永不覆盖旧行），槽位内 version 递增，「当前版本」= 最大 version；
 *   <li>与当前版本完全相同（号码与微信标志都没变）→ 40001，不制造无变化版本；
 *   <li>生效日期不得晚于今天、不得早于当前版本的生效日期（版本单调向前）；
 *   <li>电话只存加密列 + 盗索引（purpose {@link #PURPOSE_PHONE}），查询输出一律脱敏（A-PAT-010），明文只走 /plaintext
 *       专用通道（当前电话1，见 PatientPlaintextSourceImpl）；
 *   <li>每次新增写 PATIENT_CONTACT_ADD 操作审计（敏感值只存脱敏摘要，AGENTS.md §5.4）。
 * </ul>
 *
 * <p>越范围 / 不存在的患者 40400（不暴露对象存在，AGENTS.md §5.2）。
 */
@Service
public class PatientContactService {

    private static final Logger log = LoggerFactory.getLogger(PatientContactService.class);

    /** 电话加密用途（重加密作业登记见 PatientEncryptedColumns）。 */
    static final String PURPOSE_PHONE = "pt-contact-phone";

    /** 号码槽位（A 规格 §2：电话1、电话2；C 沿用医院 A 联系方式版本，可多个号码逐槽位扩展）。 */
    static final Set<String> SLOTS = Set.of("PHONE_1", "PHONE_2");

    /** 版本来源：登记 REGISTRATION / 随访 FOLLOWUP（引用任务或记录，E15 接入）/ 导入 IMPORT / 手工 MANUAL。 */
    static final Set<String> SOURCES = Set.of("REGISTRATION", "FOLLOWUP", "IMPORT", "MANUAL");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PatientContactGateway gateway;
    private final PatientContactMapper mapper;
    private final CryptoService crypto;
    private final AuditLogger audit;

    public PatientContactService(
            PatientContactGateway gateway,
            PatientContactMapper mapper,
            CryptoService crypto,
            AuditLogger audit) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 新增版本请求：slot 与 phone 必填；effectiveDate 缺省今天；source 缺省 MANUAL。 */
    public record AddRequest(
            String slot,
            String phone,
            LocalDate effectiveDate,
            String source,
            Long sourceRefId,
            Boolean wechatAdded,
            String remark) {}

    /** 版本视图（脱敏；current = 是否该槽位当前版本）。 */
    public record ContactVersionView(
            String id,
            String slot,
            String phoneMasked,
            Boolean wechatAdded,
            LocalDate effectiveDate,
            String source,
            Long sourceRefId,
            int version,
            String remark,
            boolean current) {}

    /** GET contact-info 响应：全部版本（电话1 在前、版本新在前），当前与历史并列。 */
    public record ContactInfoView(List<ContactVersionView> items) {}

    // ---------------------------------------------------------------- 新增版本（保留历史）

    /**
     * 新增联系方式版本（不覆盖旧值）。同一事务内完成可见性校验、版本推进与写入；并发同槽位提交由 uk_pt_contact_version 兜底（DuplicateKeyException
     * → 40900 提示刷新重试）。
     */
    @Transactional
    public ContactVersionView addVersion(long patientId, AddRequest request) {
        String slot = normalizeSlot(request.slot());
        String phone = normalizePhone(request.phone());
        String source = normalizeSource(request.source());
        Long sourceRefId = "FOLLOWUP".equals(source) ? request.sourceRefId() : null;
        LocalDate effectiveDate =
                request.effectiveDate() == null ? LocalDate.now() : request.effectiveDate();
        if (effectiveDate.isAfter(LocalDate.now())) {
            throw new ApiException(400, 40001, "生效日期不能晚于今天");
        }
        String remark =
                request.remark() == null || request.remark().isBlank()
                        ? null
                        : request.remark().trim();
        if (remark != null && remark.length() > 255) {
            throw new ApiException(400, 40001, "备注过长（最多 255 个字符）");
        }

        requirePatientInScope(patientId);
        PatientContactMapper.ContactRow current = gateway.currentVersion(patientId, slot);
        int nextVersion = 1;
        if (current != null) {
            String currentPhone = crypto.decrypt(current.phoneCipher());
            if (currentPhone.equals(phone)
                    && Objects.equals(current.wechatAdded(), request.wechatAdded())) {
                throw new ApiException(400, 40001, "联系方式与当前版本一致，无需新增版本");
            }
            if (effectiveDate.isBefore(current.effectiveDate())) {
                throw new ApiException(
                        400, 40001, "生效日期不能早于当前版本的生效日期（" + current.effectiveDate() + "）");
            }
            nextVersion = current.version() + 1;
        }

        ContactVersionInsert row = new ContactVersionInsert();
        row.setPatientId(patientId);
        row.setSlot(slot);
        row.setPhoneCipher(crypto.encrypt(PURPOSE_PHONE, phone));
        row.setPhoneBlind(crypto.blindIndex(PURPOSE_PHONE, phone));
        row.setWechatAdded(request.wechatAdded());
        row.setEffectiveDate(effectiveDate);
        row.setSource(source);
        row.setSourceRefId(sourceRefId);
        row.setVersion(nextVersion);
        row.setRemark(remark);
        row.setCreatedBy(CurrentSession.requireHospital().userId());
        try {
            mapper.insertVersion(row);
        } catch (DuplicateKeyException race) {
            // 并发更新同一槽位：版本号撞车，按冲突提示刷新重试（不覆盖他人版本）
            log.warn("联系方式版本并发冲突：患者 {} 槽位 {}", patientId, slot);
            throw new ApiException(409, 40900, "该联系方式刚被他人更新，请刷新后重试");
        }

        String phoneMasked = SensitiveValueMasker.mask("phone", phone);
        audit.append(
                AuditEntry.operation("followup", "PATIENT_CONTACT_ADD")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description(
                                "新增联系方式版本："
                                        + slotLabel(slot)
                                        + " 第 "
                                        + nextVersion
                                        + " 版（"
                                        + phoneMasked
                                        + "），生效 "
                                        + effectiveDate
                                        + "，来源 "
                                        + source)
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "slot", slot,
                                                "version", nextVersion,
                                                "phoneMasked", phoneMasked,
                                                "effectiveDate", effectiveDate.toString(),
                                                "source", source)))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new ContactVersionView(
                String.valueOf(row.getId()),
                slot,
                phoneMasked,
                request.wechatAdded(),
                effectiveDate,
                source,
                sourceRefId,
                nextVersion,
                remark,
                true);
    }

    // ---------------------------------------------------------------- 查询（当前与历史，脱敏）

    /** 当前与历史版本（GET contact-info）：电话1 在前、版本新在前，当前版本带 current=true。 */
    public ContactInfoView infoOf(long patientId) {
        requirePatientInScope(patientId);
        List<PatientContactMapper.ContactRow> rows = gateway.versionsOf(patientId);

        Map<String, Integer> currentVersionBySlot = new HashMap<>();
        for (PatientContactMapper.ContactRow row : rows) {
            currentVersionBySlot.merge(row.slot(), row.version(), Math::max);
        }
        List<ContactVersionView> items = new ArrayList<>();
        for (PatientContactMapper.ContactRow row : rows) {
            String phone = row.phoneCipher() == null ? null : crypto.decrypt(row.phoneCipher());
            items.add(
                    new ContactVersionView(
                            String.valueOf(row.id()),
                            row.slot(),
                            phone == null ? null : SensitiveValueMasker.mask("phone", phone),
                            row.wechatAdded(),
                            row.effectiveDate(),
                            row.source(),
                            row.sourceRefId(),
                            row.version(),
                            row.remark(),
                            row.version() == currentVersionBySlot.get(row.slot())));
        }
        return new ContactInfoView(List.copyOf(items));
    }

    // ---------------------------------------------------------------- 内部

    /** 越范围 / 不存在一律 40400（AGENTS.md §5.2，不暴露对象存在）。 */
    private void requirePatientInScope(long patientId) {
        if (gateway.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
    }

    private static String normalizeSlot(String slot) {
        if (slot == null || slot.isBlank()) {
            throw new ApiException(400, 40001, "请选择号码槽位（PHONE_1 电话1 / PHONE_2 电话2）");
        }
        String normalized = slot.trim().toUpperCase();
        if (!SLOTS.contains(normalized)) {
            throw new ApiException(400, 40001, "号码槽位只支持 PHONE_1（电话1）或 PHONE_2（电话2）");
        }
        return normalized;
    }

    /** 电话归一：去掉空格、连字符与括号后必须为「可选 + 前缀的 5–20 位数字」（座区号 / 手机号均覆盖）； 归一化结果既入加密列也是盲索引与「同号判断」的口径。 */
    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new ApiException(400, 40001, "请填写电话号码");
        }
        String normalized = phone.trim().replaceAll("[\\s\\-()]", "");
        if (!normalized.matches("\\+?\\d{5,20}")) {
            throw new ApiException(400, 40001, "电话号码格式不正确（5–20 位数字，可带 + 前缀）");
        }
        return normalized;
    }

    private static String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "MANUAL";
        }
        String normalized = source.trim().toUpperCase();
        if (!SOURCES.contains(normalized)) {
            throw new ApiException(400, 40001, "来源只支持 REGISTRATION / FOLLOWUP / IMPORT / MANUAL");
        }
        return normalized;
    }

    private static String slotLabel(String slot) {
        return "PHONE_1".equals(slot) ? "电话1" : "电话2";
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            return null;
        }
    }
}
