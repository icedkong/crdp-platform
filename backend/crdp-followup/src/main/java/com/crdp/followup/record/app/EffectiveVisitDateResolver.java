package com.crdp.followup.record.app;

import com.crdp.form.record.app.FormService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 实际随访日期：实体列优先，否则按记录固化模板的字段编码解析；未知值不伪造为今天。 */
@Service
public class EffectiveVisitDateResolver {
    private final FormService forms;

    public EffectiveVisitDateResolver(FormService forms) {
        this.forms = forms;
    }

    public LocalDate resolve(
            LocalDate visitDate, String formCode, int version, Map<String, Object> data) {
        return resolve(visitDate, formCode, version, data, new java.util.HashMap<>());
    }

    /** 单次重算可复用不可变的模板版本，避免逐记录重复读取元数据；不缓存患者结果。 */
    public LocalDate resolve(
            LocalDate visitDate,
            String formCode,
            int version,
            Map<String, Object> data,
            Map<String, FormService.SchemaView> schemas) {
        if (visitDate != null) return visitDate;
        var schema =
                schemas.computeIfAbsent(
                        formCode + "#" + version, ignored -> forms.schema(formCode, version));
        if (schema == null) return null;
        for (var category : schema.categories()) {
            for (var field : category.fields()) {
                if ("followup_date".equals(field.code())
                        && data.get(field.key()) instanceof String text) {
                    try {
                        return LocalDate.parse(text.trim());
                    } catch (java.time.DateTimeException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
