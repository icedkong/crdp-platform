package com.crdp.followup.patient.domain;

import java.util.Map;
import java.util.Optional;

/**
 * 患者敏感字段明文来源（E07-T05 端口；患者表与 Mapper 随 E13-T01 落地）。
 *
 * <p>实现方返回「当前用户数据范围内」该患者的敏感字段明文（键 = {@link com.crdp.framework.fieldpolicy.SensitiveFieldCatalog}
 * 字段码），不存在或越范围一律 {@link Optional#empty()} ——上层按 40400 处理（AGENTS.md §5.2：不暴露对象存在）。真实实现必须走
 * {@code @DataScope} 查询， 解密只经 {@code CryptoService}；平台租户视角的取数口径随 E20 另行装配。
 */
public interface PatientPlaintextSource {

    /**
     * @param patientId 患者编号（租户库内）
     * @return 数据范围内患者的敏感字段明文；患者不存在或不在当前用户数据范围 → empty
     */
    Optional<Map<String, String>> plaintextOf(long patientId);
}
