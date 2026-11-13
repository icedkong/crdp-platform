package com.crdp.followup.record.app;

import java.util.Optional;

/**
 * 平台库补录建议随访时间点参数定义的读取端口（E21-T14 / C-FU-014；plt_param 在平台库，平台库 V73 登记）。
 *
 * <p>与 {@code VisitNoParamDefaults}（E21-T10）同款「消费方声明端口、装配方实现」：crdp-followup 不依赖 crdp-system（依赖方向：
 * followup → form → framework），经本端口取平台默认值；实现是 crdp-server 里的适配器（复用 PlatformConfigCache 的 60
 * 秒缓存与保存后失效）。
 */
public interface TimepointParamDefaults {

    /** 按参数键取平台默认定义；不存在返回空（读取方回落代码内置缺省区间）。 */
    Optional<ParamDefault> param(String paramKey);

    /** 平台参数定义快照（只取规则计算需要的字段）。 */
    record ParamDefault(String paramKey, String platformValue, String valueType) {}
}
