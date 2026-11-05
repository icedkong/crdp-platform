package com.crdp.followup.contact.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E15-T04 / A-FU-006：联系结果与沟通方式枚举（tasks.csv 验收「联系结果字典完整」）。
 *
 * <p>锁定：联系结果六值与 A 规格「关键字段 · 沟通记录」逐一对应（已联系 / 未联系到 / 号码错误 / 拒访 / 家属代答 / 告知已故，components.md §2 contact
 * 域同枚举）；沟通方式含功能清单「电话 / 微信 / 面谈」与关键字段表「其他」； 未知值解析为 null（写入侧 40001，不静默吞掉）。
 */
class ContactLogEnumsTest {

    @Test
    void contactResultDictionaryIsComplete() {
        List<String> codes = ContactResult.ALL.stream().map(ContactResult::code).toList();
        assertEquals(
                List.of("REACHED", "NOT_REACHED", "WRONG_NUMBER", "REFUSED", "FAMILY", "DECEASED"),
                codes,
                "联系结果字典完整：A-FU-006 六值（E15-T04 验收）");
        assertEquals("已联系", ContactResult.REACHED.label());
        assertEquals("未联系到", ContactResult.NOT_REACHED.label());
        assertEquals("号码错误", ContactResult.WRONG_NUMBER.label());
        assertEquals("拒访", ContactResult.REFUSED.label());
        assertEquals("家属代答", ContactResult.FAMILY.label());
        assertEquals("告知已故", ContactResult.DECEASED.label());
    }

    @Test
    void deceasedResultIsMarkedForDeathTermination() {
        assertEquals(true, ContactResult.DECEASED.triggersDeathTermination());
        assertEquals(
                false,
                ContactResult.ALL.stream()
                        .anyMatch(
                                result ->
                                        result.triggersDeathTermination()
                                                && result != ContactResult.DECEASED),
                "只有「告知已故」触发死亡终止（A-FU-006 / OQ-79②）");
    }

    @Test
    void notReachedAndWrongNumberCountTowardLostFollowup() {
        assertEquals(true, ContactResult.NOT_REACHED.countsTowardLost());
        assertEquals(true, ContactResult.WRONG_NUMBER.countsTowardLost());
        assertEquals(false, ContactResult.REFUSED.countsTowardLost(), "「拒访」不计入失访，改走移出计划（A-FU-012）");
    }

    @Test
    void methodDictionaryCoversSpecValues() {
        assertEquals(
                List.of("PHONE", "WECHAT", "FACE_TO_FACE", "OTHER"),
                ContactMethod.ALL.stream().map(ContactMethod::code).toList(),
                "沟通方式：电话 / 微信 / 面谈 / 其他（A-FU-006 功能清单 + 关键字段表）");
        assertEquals("电话", ContactMethod.PHONE.label());
        assertEquals("微信", ContactMethod.WECHAT.label());
        assertEquals("面谈", ContactMethod.FACE_TO_FACE.label());
        assertEquals("其他", ContactMethod.OTHER.label());
    }

    @Test
    void unknownCodesResolveToEmpty() {
        assertEquals(true, ContactResult.of("GAVE_UP").isEmpty());
        assertEquals(true, ContactResult.of(null).isEmpty());
        assertEquals(true, ContactMethod.of("SMS").isEmpty());
        assertEquals(true, ContactMethod.of("").isEmpty());
        assertEquals(ContactResult.REACHED, ContactResult.of("REACHED").orElseThrow());
        assertEquals(ContactMethod.PHONE, ContactMethod.of("PHONE").orElseThrow());
    }
}
