package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 盲索引规范化（E03-T04）。规则为保守推断（技术方案只写"规范化后计算"，未定细则），集中在 BlindIndexNormalizer
 * 一处，医院确认后如需调整只改这里；已入库索引需随规则重算（切换路径见 技术方案第 4 章）。
 *
 * <p>夹具全部虚构（AGENTS.md §5.5）：姓名带「（示例）」标记，号码（13800138000、01012345678 等）为虚构测试号段。
 */
class BlindIndexNormalizerTest {

    @Test
    void nullStaysNull() {
        assertNull(BlindIndexNormalizer.normalize(null));
    }

    @Test
    void trimsOuterAndInnerWhitespaceIncludingFullWidth() {
        // 姓名夹具带「（示例）」虚构标记（AGENTS.md §5.5）；全角括号属分隔符被全局去除，
        // 归一结果为「张三示例」——标记不会漏出，也不影响等值检索口径
        assertEquals("张三示例", BlindIndexNormalizer.normalize(" 张三（示例） "));
        assertEquals("张三示例", BlindIndexNormalizer.normalize("张 三（示例）"));
        assertEquals("张三示例", BlindIndexNormalizer.normalize("张\u3000三（示例）"));
    }

    @Test
    void convertsFullWidthLettersAndDigitsToHalfWidth() {
        assertEquals("MR001", BlindIndexNormalizer.normalize("ＭＲ００１"));
        assertEquals("MR001", BlindIndexNormalizer.normalize("ｍｒ００１"));
    }

    @Test
    void upperCasesLetters() {
        assertEquals("MR001X", BlindIndexNormalizer.normalize("mr001x"));
    }

    @Test
    void stripsPhoneFriendlySeparators() {
        assertEquals("13800138000", BlindIndexNormalizer.normalize("138 0013 8000"));
        assertEquals("13800138000", BlindIndexNormalizer.normalize("138-0013-8000"));
        assertEquals("01012345678", BlindIndexNormalizer.normalize("(010)1234-5678"));
        assertEquals("01012345678", BlindIndexNormalizer.normalize("（010）1234-5678"));
    }

    @Test
    void stripsPlus86CountryCodePrefix() {
        assertEquals("13800138000", BlindIndexNormalizer.normalize("+8613800138000"));
        assertEquals("13800138000", BlindIndexNormalizer.normalize("+86 13800138000"));
    }

    @Test
    void separatorsAreAlwaysStrippedForConsistency() {
        // 连字符与括号全局去除（电话习惯优先）；同一值无论怎么写都归一
        assertEquals("MRDEMO001", BlindIndexNormalizer.normalize("MR-DEMO-001"));
        assertEquals("MRDEMO001", BlindIndexNormalizer.normalize("mr demo 001"));
    }
}
