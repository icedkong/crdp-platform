package com.crdp.framework.fieldpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E07-T05 / A-PAT-010、C-PAT-012、PLT-PERM-009：脱敏序列化（tech-plan §5「实现」：返回前按字段策略
 * 脱敏或隐藏，列表、详情、导出、站内信共用同一套）。 隐藏 → 键不出现；脱敏 → 掩码值；可见 → 原值；目录外字段不受字段级权限控制（SensitiveFieldCatalog 口径）。
 */
class SensitiveFieldSanitizerTest {

    private static final FieldPolicySnapshot DEFAULTS = FieldPolicySnapshot.defaults();

    private static FieldPolicySnapshot overrides(String key, String policy) {
        return new FieldPolicySnapshot(Map.of(key, policy));
    }

    @Test
    void defaultPolicyMasksEveryCatalogField() {
        Map<String, Object> row = patientRow();

        Map<String, Object> sanitized = SensitiveFieldSanitizer.apply(DEFAULTS, "patient", row);

        assertEquals("张*明", sanitized.get("name"));
        assertEquals("138****5678", sanitized.get("phone"));
        assertEquals("110102********567X", sanitized.get("idCard"));
        assertEquals("MR*****01", sanitized.get("medicalRecordNo"));
        assertEquals("北京市东城区****", sanitized.get("address"));
        // 非敏感字段原样返回，键顺序保持
        assertEquals("男", sanitized.get("gender"));
        assertEquals("DEMO-A-0001", sanitized.get("patientNo"));
        assertEquals(
                java.util.List.of(
                        "name",
                        "idCard",
                        "phone",
                        "medicalRecordNo",
                        "address",
                        "gender",
                        "patientNo"),
                java.util.List.copyOf(sanitized.keySet()));
    }

    @Test
    void hiddenFieldIsOmittedAndVisibleFieldStaysPlain() {
        FieldPolicySnapshot snapshot =
                FieldPolicySnapshot.merge(
                        java.util.List.of(
                                new FieldPolicyOverride("patient", "name", "HIDDEN"),
                                new FieldPolicyOverride("patient", "phone", "VISIBLE")));

        Map<String, Object> sanitized =
                SensitiveFieldSanitizer.apply(snapshot, "patient", patientRow());

        assertFalse(sanitized.containsKey("name"), "隐藏字段不出现（不是空串）");
        assertEquals("13800005678", sanitized.get("phone"), "可见 = 明文返回");
        assertEquals("110102********567X", sanitized.get("idCard"), "未覆盖字段回落默认脱敏");
    }

    @Test
    void nullPolicyFailsClosedToMask() {
        Map<String, Object> sanitized =
                SensitiveFieldSanitizer.apply(null, "patient", patientRow());

        assertEquals("张*明", sanitized.get("name"), "无快照（平台端 / 旁路）fail closed 脱敏");
        assertEquals("138****5678", sanitized.get("phone"));
    }

    @Test
    void doesNotMutateSourceMapAndKeepsUnknownResourceUntouched() {
        Map<String, Object> row = patientRow();
        Map<String, Object> copy = new LinkedHashMap<>(row);

        Map<String, Object> sanitized = SensitiveFieldSanitizer.apply(DEFAULTS, "task", row);

        assertEquals(copy, row, "源 Map 不被修改");
        assertEquals("张小明", sanitized.get("name"), "目录外资源不受字段级权限控制");
        assertTrue(sanitized.containsValue("13800005678"));
    }

    @Test
    void nonStringSensitiveValueMasksItsStringForm() {
        Map<String, Object> row = new LinkedHashMap<>(patientRow());
        row.put("medicalRecordNo", 12345678L);

        Map<String, Object> sanitized = SensitiveFieldSanitizer.apply(DEFAULTS, "patient", row);

        assertEquals("12****78", sanitized.get("medicalRecordNo"), "非字符串按字符串形式脱敏");
    }

    private static Map<String, Object> patientRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "张小明");
        row.put("idCard", "11010219900101567X");
        row.put("phone", "13800005678");
        row.put("medicalRecordNo", "MR1000001");
        row.put("address", "北京市东城区某某街道某小区");
        row.put("gender", "男");
        row.put("patientNo", "DEMO-A-0001");
        return row;
    }
}
