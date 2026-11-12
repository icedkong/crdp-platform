package com.crdp.followup.patient.app;

import java.util.Optional;

/**
 * 平台库随访编号参数定义的读取端口（E21-T10 / C-PAT-003；plt_param 在平台库，followup V71 登记）。
 *
 * <p>crdp-followup 不依赖 crdp-system（依赖方向：followup → form → framework），经本端口取平台默认值； 实现是 crdp-server
 * 里的适配器（复用 PlatformConfigCache 的 60 秒缓存与保存后失效，PLT-CFG-011「保存后即时生效」）。 与
 * FollowupParamDefaults（E15-T05）同款「消费方声明端口、装配方实现」的做法。
 */
public interface VisitNoParamDefaults {

    /** 按参数键取平台默认定义；不存在返回空（读取方回落代码内置缺省）。 */
    Optional<ParamDefault> param(String paramKey);

    /** 平台参数定义快照（只含规则计算需要的字段）。 */
    record ParamDefault(String paramKey, String platformValue, String valueType) {}
}
