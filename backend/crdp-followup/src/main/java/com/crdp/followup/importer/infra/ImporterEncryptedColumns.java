package com.crdp.followup.importer.infra;

import com.crdp.framework.crypto.EncryptedColumn;
import com.crdp.framework.crypto.EncryptedColumnProvider;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 导入域加密列登记（E18-T02，followup V68 imp_row）：整行解析结果的密文列—— 行内含姓名 / 病案号等敏感明文，按「敏感字段不落明文列」的既有口径整体加密
 * （tech-plan §12③「解析结果 JSON」的落库形态）。 病案号盲索引列与 {@code pt_patient.mrn_blind} 同
 * purpose（pt-patient-mrn）， 校验阶段（E18-T03「按盲索引查病案号重复」）直接比对；密钥轮换后重加密作业按本清单巡检（NFR-CRY-002）。
 */
@Component
public class ImporterEncryptedColumns implements EncryptedColumnProvider {

    /** 整行解析结果的数据密钥用途（payload_cipher）。 */
    public static final String PURPOSE_ROW_PAYLOAD = "imp-row-payload";

    @Override
    public List<EncryptedColumn> encryptedColumns() {
        return List.of(
                new EncryptedColumn(
                        "imp_row",
                        "id",
                        "payload_cipher",
                        PURPOSE_ROW_PAYLOAD,
                        "mrn_blind",
                        // 与 pt_patient.mrn_blind 同 purpose：盲索引可直接互比（查重 / 关联基线行）
                        "pt-patient-mrn"));
    }
}
