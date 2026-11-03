package com.crdp.followup.patient.infra;

import com.crdp.framework.crypto.EncryptedColumn;
import com.crdp.framework.crypto.EncryptedColumnProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * pt_patient 加密列登记（E13-T01，E03-T05 重加密作业扩展点）：密文列与盲索引成对，密钥轮换后作业按本清单 逐列重加密并重算盲索引（NFR-CRY-002）。电话列随
 * E13-T03 落 pt_contact 时另行登记。
 */
@Component
public class PatientEncryptedColumns implements EncryptedColumnProvider {

    @Override
    public List<EncryptedColumn> encryptedColumns() {
        return List.of(
                new EncryptedColumn(
                        "pt_patient",
                        "id",
                        "mrn_cipher",
                        "pt-patient-mrn",
                        "mrn_blind",
                        "pt-patient-mrn"),
                new EncryptedColumn(
                        "pt_patient",
                        "id",
                        "name_cipher",
                        "pt-patient-name",
                        "name_blind",
                        "pt-patient-name"),
                new EncryptedColumn(
                        "pt_patient", "id", "id_card_cipher", "pt-patient-idcard", null, null),
                new EncryptedColumn(
                        "pt_patient", "id", "address_cipher", "pt-patient-address", null, null));
    }
}
