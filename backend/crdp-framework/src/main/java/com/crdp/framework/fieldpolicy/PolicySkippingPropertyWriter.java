package com.crdp.framework.fieldpolicy;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;

/**
 * {@link MaskedByPolicy} 字段的属性写出器（E07-T05）：策略为 {@code HIDDEN} 时整字段不出现（与 {@link
 * SensitiveFieldSanitizer} 的「隐藏 → 键不出现」同口径），其余策略交给 {@link PolicyMaskedSerializer}（脱敏 / 明文）。
 *
 * <p>策略按「当前请求」读取（{@link CurrentSession}），序列化器缓存不影响逐请求生效；无会话 fail closed 按脱敏值写出（不是隐藏—— 掩码值可安全展示占位）。
 */
final class PolicySkippingPropertyWriter extends BeanPropertyWriter {

    private final MaskedByPolicy meta;

    private PolicySkippingPropertyWriter(BeanPropertyWriter base, MaskedByPolicy meta)
            throws Exception {
        super(base);
        this.meta = meta;
    }

    /** 拷贝构造在 Jackson 里声明 throws Exception（反射克隆成员），这里收敛为非法状态。 */
    static PolicySkippingPropertyWriter wrap(BeanPropertyWriter base, MaskedByPolicy meta) {
        try {
            return new PolicySkippingPropertyWriter(base, meta);
        } catch (Exception failure) {
            throw new IllegalStateException("无法包装字段策略属性写出器：" + base.getName(), failure);
        }
    }

    @Override
    public void serializeAsField(Object bean, JsonGenerator generator, SerializerProvider provider)
            throws Exception {
        FieldPolicySnapshot policies =
                CurrentSession.current()
                        .map(SessionPrincipal::fieldPolicies)
                        .orElse(FieldPolicySnapshot.defaults());
        String decided =
                (policies == null ? FieldPolicySnapshot.defaults() : policies)
                        .decide(meta.resource(), meta.field());
        if (SensitiveFieldCatalog.POLICY_HIDDEN.equals(decided)) {
            // 隐藏：整字段不出现（不是 null，避免前端误渲染空占位）
            return;
        }
        super.serializeAsField(bean, generator, provider);
    }
}
