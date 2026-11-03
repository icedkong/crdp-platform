package com.crdp.followup.patient.infra;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.patient.app.PatientRegistrationGateway;
import com.crdp.followup.patient.domain.PatientPlaintextSource;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.framework.crypto.CryptoService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link PatientPlaintextSource} 的真实实现（E13-T01 落地，替换 E07-T05 的占位实现）：查询走 {@code @DataScope}（越范围 /
 * 不存在一律 empty，上层按 40400 处理，不暴露对象存在），解密只经 {@link CryptoService}（AGENTS.md §5.3）。
 *
 * <p>E13-T03 起并入电话：患者表无电话列，{@code phone} 取 {@code pt_contact} 当前电话1版本（槽位 PHONE_1 的最大
 * version；无任何版本时不返回该字段）。平台租户视角的取数口径随 E20 另行装配。
 */
@Component
public class PatientPlaintextSourceImpl implements PatientPlaintextSource {

    private final PatientRegistrationGateway gateway;
    private final PatientContactGateway contactGateway;
    private final CryptoService crypto;

    public PatientPlaintextSourceImpl(
            PatientRegistrationGateway gateway,
            PatientContactGateway contactGateway,
            CryptoService crypto) {
        this.gateway = gateway;
        this.contactGateway = contactGateway;
        this.crypto = crypto;
    }

    @Override
    public Optional<Map<String, String>> plaintextOf(long patientId) {
        PatientMapper.SensitiveRow row = gateway.sensitiveRowOf(patientId);
        if (row == null) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        putDecrypted(values, "name", row.nameCipher());
        putDecrypted(values, "medicalRecordNo", row.mrnCipher());
        putDecrypted(values, "idCard", row.idCardCipher());
        putDecrypted(values, "address", row.addressCipher());
        putDecrypted(values, "phone", contactGateway.currentPhone1Cipher(patientId));
        return Optional.of(values);
    }

    private void putDecrypted(Map<String, String> values, String field, String cipher) {
        if (cipher != null && !cipher.isBlank()) {
            values.put(field, crypto.decrypt(cipher));
        }
    }
}
