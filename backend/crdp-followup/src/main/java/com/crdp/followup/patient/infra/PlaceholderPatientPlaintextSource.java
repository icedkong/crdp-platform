package com.crdp.followup.patient.infra;

import com.crdp.followup.patient.domain.PatientPlaintextSource;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link PatientPlaintextSource} 的占位实现（M0 约定的 Unsupported* 占位模式）：患者表 {@code pt_patient} 随 E13-T01
 * 建表，落地前库里没有任何患者，本实现恒返回 empty（上层按 40400「患者不存在或不在数据范围」返回，不伪造数据）。
 *
 * <p>E13-T01 接入真实 Mapper（加密列经 CryptoService 解密、查询带 {@code @DataScope}）时<b>删除本类</b>。
 */
@Component
public class PlaceholderPatientPlaintextSource implements PatientPlaintextSource {

    private static final Logger log =
            LoggerFactory.getLogger(PlaceholderPatientPlaintextSource.class);

    @Override
    public Optional<Map<String, String>> plaintextOf(long patientId) {
        log.debug("患者明文来源仍为占位（E13-T01 接入真实 Mapper）patientId={}", patientId);
        return Optional.empty();
    }
}
