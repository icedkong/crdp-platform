package com.crdp.server.platform;

import com.crdp.followup.file.app.FileUploadParamDefaults;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 上传参数平台默认值适配器（E10-T01 / PLT-CFG-003、010）。
 *
 * <p>crdp-followup 只依赖 form → framework，读不到 crdp-system 的 {@link PlatformConfigPort}；
 * 这里在装配模块桥接（端口在消费方 followup.file.app 声明），复用 {@link PlatformConfigCache} 的 60 秒缓存与保存后失效（PLT-CFG-011
 * 即时生效），不为上传单独查平台库。
 */
@Component
public class FileUploadParamDefaultsAdapter implements FileUploadParamDefaults {

    private final PlatformConfigCache cache;

    public FileUploadParamDefaultsAdapter(PlatformConfigCache cache) {
        this.cache = cache;
    }

    @Override
    public Optional<ParamDefault> param(String paramKey) {
        return cache.param(paramKey)
                .map(def -> new ParamDefault(def.paramKey(), def.platformValue(), def.valueType()));
    }
}
