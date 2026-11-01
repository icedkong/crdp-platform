package com.crdp.framework.fieldpolicy;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 字段策略 Jackson 模块（E07-T05，技术方案 §5「实现」：Jackson 序列化器读取当前请求的字段策略）。 注册为 Spring Bean 后由 Boot 自动并入主
 * ObjectMapper；DTO 敏感字段标 {@link MaskedByPolicy} 即按当前会话输出 脱敏 / 明文 / 整字段隐藏（{@link
 * PolicySkippingPropertyWriter}）。
 *
 * <p>列表、详情、导出、站内信共用；动态结构（Map 行）走 {@link SensitiveFieldSanitizer}，两条路径同一套算法与口径。
 */
@Component
public class FieldPolicyJacksonModule extends com.fasterxml.jackson.databind.module.SimpleModule {

    public FieldPolicyJacksonModule() {
        setSerializerModifier(
                new BeanSerializerModifier() {
                    @Override
                    public List<BeanPropertyWriter> changeProperties(
                            SerializationConfig config,
                            BeanDescription bean,
                            List<BeanPropertyWriter> properties) {
                        for (int i = 0; i < properties.size(); i++) {
                            BeanPropertyWriter writer = properties.get(i);
                            MaskedByPolicy meta = writer.getAnnotation(MaskedByPolicy.class);
                            if (meta != null) {
                                properties.set(i, PolicySkippingPropertyWriter.wrap(writer, meta));
                            }
                        }
                        return properties;
                    }
                });
    }
}
