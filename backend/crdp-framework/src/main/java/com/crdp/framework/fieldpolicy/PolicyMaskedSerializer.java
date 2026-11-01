package com.crdp.framework.fieldpolicy;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import java.io.IOException;

/**
 * {@link MaskedByPolicy} 的序列化器（E07-T05）：读取当前请求会话的 {@link SessionPrincipal#fieldPolicies()}，
 * 按生效策略输出明文 / 掩码 / null（隐藏）。无会话或快照缺失（定时作业、异步旁路、旧会话兼容值） 一律脱敏——fail closed，绝不漏明文。
 *
 * <p>技术方案 §5「实现」：Jackson 序列化器读取当前请求的字段策略；列表、详情、导出、站内信共用同一套。 平台租户视角的「同字段取更严」随 E20
 * 在快照装配侧落地，本序列化器只消费快照。
 */
public class PolicyMaskedSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final String resource;
    private final String field;

    public PolicyMaskedSerializer() {
        this(null, null);
    }

    private PolicyMaskedSerializer(String resource, String field) {
        this.resource = resource;
        this.field = field;
    }

    /** 从字段注解取资源与字段码，绑出该字段专用的序列化器实例。 */
    @Override
    public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property)
            throws JsonMappingException {
        if (property == null) {
            return provider.findNullValueSerializer(null);
        }
        MaskedByPolicy meta = property.getAnnotation(MaskedByPolicy.class);
        if (meta == null) {
            throw new IllegalStateException(
                    "PolicyMaskedSerializer 只能通过 @MaskedByPolicy 使用，字段：" + property.getName());
        }
        return new PolicyMaskedSerializer(meta.resource(), meta.field());
    }

    @Override
    public void serialize(String value, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        if (value == null || resource == null || field == null) {
            generator.writeNull();
            return;
        }
        FieldPolicySnapshot policies =
                CurrentSession.current()
                        .map(SessionPrincipal::fieldPolicies)
                        .orElse(FieldPolicySnapshot.defaults());
        String decided =
                (policies == null ? FieldPolicySnapshot.defaults() : policies)
                        .decide(resource, field);
        switch (decided) {
            // 隐藏：null（DTO 配 @JsonInclude(NON_NULL) 即整字段不出现）
            case SensitiveFieldCatalog.POLICY_HIDDEN -> generator.writeNull();
            case SensitiveFieldCatalog.POLICY_MASKED ->
                    generator.writeString(SensitiveValueMasker.mask(field, value));
            default -> generator.writeString(value);
        }
    }
}
