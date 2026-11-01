package com.crdp.framework.fieldpolicy;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在 DTO 的敏感字符串字段上，序列化时按当前会话的字段策略输出（E07-T05）： {@code MASKED} → 掩码、{@code HIDDEN} → null（配
 * {@code @JsonInclude(NON_NULL)} 即整字段不出现）、 {@code VISIBLE} → 明文。无会话（定时作业、异步旁路）fail closed
 * 一律脱敏。列表、详情、导出、站内信共用。
 *
 * <p>明文永不经此通道返回「隐藏 / 脱敏」以外的新口径；明文查看只走专用接口（docs/api/patient.md §5）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@JacksonAnnotationsInside
@JsonSerialize(using = PolicyMaskedSerializer.class)
public @interface MaskedByPolicy {

    /** 资源对象（{@link SensitiveFieldCatalog.Entry#resource()}，如 patient）。 */
    String resource();

    /** 目录字段码（{@link SensitiveFieldCatalog.Entry#field()}，如 name）。 */
    String field();
}
