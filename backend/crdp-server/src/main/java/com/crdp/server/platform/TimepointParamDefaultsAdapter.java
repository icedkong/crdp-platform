package com.crdp.server.platform;

import com.crdp.followup.record.app.TimepointParamDefaults;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 补录建议随访时间点参数平台默认值适配器（E21-T14 / C-FU-014；平台库 V73 登记 followup.timepoint.ranges）。
 *
 * <p>与 {@link VisitNoParamDefaultsAdapter}（E21-T10）同款「消费方声明端口、装配方实现」：crdp-followup 只依赖 form →
 * framework，读不到 crdp-system 的 {@link PlatformConfigPort}；这里在装配模块桥接，复用 {@link PlatformConfigCache} 的
 * 60 秒缓存与保存后失效（PLT-CFG-011 即时生效），不为区间表单独查平台库。
 */
@Component
public class TimepointParamDefaultsAdapter implements TimepointParamDefaults {

    private final PlatformConfigCache cache;

    public TimepointParamDefaultsAdapter(PlatformConfigCache cache) {
        this.cache = cache;
    }

    @Override
    public Optional<ParamDefault> param(String paramKey) {
        return cache.param(paramKey)
                .map(def -> new ParamDefault(def.paramKey(), def.platformValue(), def.valueType()));
    }
}
