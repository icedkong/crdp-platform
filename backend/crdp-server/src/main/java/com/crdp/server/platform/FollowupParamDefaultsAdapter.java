package com.crdp.server.platform;

import com.crdp.followup.task.app.FollowupParamDefaults;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 随访任务参数平台默认值适配器（E15-T05 / A-FU-012、A-FU-024；平台库 V46 登记）。
 *
 * <p>与 {@link FileUploadParamDefaultsAdapter}（E10-T01）同款「消费方声明端口、装配方实现」： crdp-followup 只依赖 form →
 * framework，读不到 crdp-system 的 {@link PlatformConfigPort}； 这里在装配模块桥接，复用 {@link PlatformConfigCache}
 * 的 60 秒缓存与保存后失效（PLT-CFG-011 即时生效）， 不为失访 / 跟进策略单独查平台库。
 */
@Component
public class FollowupParamDefaultsAdapter implements FollowupParamDefaults {

    private final PlatformConfigCache cache;

    public FollowupParamDefaultsAdapter(PlatformConfigCache cache) {
        this.cache = cache;
    }

    @Override
    public Optional<ParamDefault> param(String paramKey) {
        return cache.param(paramKey)
                .map(def -> new ParamDefault(def.paramKey(), def.platformValue(), def.valueType()));
    }
}
