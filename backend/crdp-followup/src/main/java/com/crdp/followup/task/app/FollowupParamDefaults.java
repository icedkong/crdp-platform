package com.crdp.followup.task.app;

import java.util.Optional;

/**
 * 平台库随访参数定义的读取端口（E15-T05 / A-FU-012、A-FU-024）。
 *
 * <p>plt_param 在平台库，crdp-followup 不依赖 crdp-system（依赖方向：followup → form → framework）， 经本端口取平台默认值；实现是
 * crdp-server 里的适配器（复用 PlatformConfigCache 的 60 秒缓存， 平台端保存参数后缓存立即失效，PLT-CFG-011「保存后即时生效」）。 与
 * FileUploadParamDefaults（E10-T01）同款「消费方声明端口、装配方实现」的做法。
 */
public interface FollowupParamDefaults {

    /** 按参数键取平台默认定义；不存在返回空（读取方回落代码内置缺省）。 */
    Optional<ParamDefault> param(String paramKey);

    /** 平台参数定义快照（只含策略计算需要的字段）。 */
    record ParamDefault(String paramKey, String platformValue, String valueType) {}
}
