package com.crdp.framework.fieldpolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * E07-T05 / A-PAT-010、C-PAT-012：敏感字段脱敏算法（hospital-a-spec §3「脱敏」示例「张*」「138****5678」、 design-system
 * §17 「张*明（示例）」）。全部虚构数据。
 */
class SensitiveValueMaskerTest {

    @Test
    void nameKeepsHeadAndTailCharacters() {
        assertEquals("张*明", SensitiveValueMasker.mask("name", "张小明"));
        assertEquals("张*明", SensitiveValueMasker.mask("name", "张*明")); // 已脱敏的值再脱敏不变形
        assertEquals("张*", SensitiveValueMasker.mask("name", "张三"));
        assertEquals("*", SensitiveValueMasker.mask("name", "张"));
    }

    @Test
    void phoneKeepsFirstThreeAndLastFour() {
        assertEquals("138****5678", SensitiveValueMasker.mask("phone", "13800005678"));
        assertEquals("010****5123", SensitiveValueMasker.mask("phone", "01012345123"));
        // 短值走通用规则（保留首尾）
        assertEquals("1**3", SensitiveValueMasker.mask("phone", "1233"));
    }

    @Test
    void idCardKeepsFirstSixAndLastFour() {
        assertEquals(
                "110102********567X", SensitiveValueMasker.mask("idCard", "11010219900101567X"));
        assertEquals("A******D", SensitiveValueMasker.mask("idCard", "AB1234CD"));
    }

    @Test
    void medicalRecordNoKeepsFirstTwoAndLastTwo() {
        assertEquals("MR*****01", SensitiveValueMasker.mask("medicalRecordNo", "MR1000001"));
        assertEquals("12**89", SensitiveValueMasker.mask("medicalRecordNo", "123489"));
    }

    @Test
    void addressKeepsPrefixThenFixedStars() {
        assertEquals("北京市东城区****", SensitiveValueMasker.mask("address", "北京市东城区某某街道某小区"));
        // 短址走通用规则
        assertEquals("北*", SensitiveValueMasker.mask("address", "北路"));
    }

    @Test
    void blankAndNullPassThrough() {
        assertNull(SensitiveValueMasker.mask("name", null));
        assertEquals("", SensitiveValueMasker.mask("name", ""));
        assertEquals("  ", SensitiveValueMasker.mask("name", "  "));
    }

    @Test
    void catalogFieldOutsideKnownCodesFallsBackToGenericRule() {
        // 防御：目录将来的新字段码（如 contactPhone）没有专属规则时仍被通用规则盖住，不会漏明文
        assertEquals("张*三", SensitiveValueMasker.mask("guardianName", "张小三"));
    }
}
