package com.crdp.followup.contact.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 沟通方式（E15-T04 / A-FU-006）：电话 / 微信 / 面谈 / 其他。
 *
 * <p>功能清单写「电话 / 微信 / 面谈」、「关键字段 · 沟通记录」表另含「其他」（如门诊，pages.md §4.8）； 平台库 V45 预置字典 {@code
 * contact_method} 同枚举，本院可新增自有项并调整排序（写入按本枚举校验，OQ-84）。
 */
public enum ContactMethod {
    PHONE("PHONE", "电话"),
    WECHAT("WECHAT", "微信"),
    FACE_TO_FACE("FACE_TO_FACE", "面谈"),
    OTHER("OTHER", "其他");

    /** 全集（错误提示与下拉用，顺序即展示顺序）。 */
    public static final List<ContactMethod> ALL = List.of(values());

    private final String code;
    private final String label;

    ContactMethod(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 存储编码（fu_contact_log.method，创建后不改名）。 */
    public String code() {
        return code;
    }

    /** 中文显示名（与平台库 V45 字典一致）。 */
    public String label() {
        return label;
    }

    /** 按编码解析（未知值空，写入侧 40001 不静默吞掉）。 */
    public static Optional<ContactMethod> of(String code) {
        return Arrays.stream(values()).filter(method -> method.code.equals(code)).findFirst();
    }
}
