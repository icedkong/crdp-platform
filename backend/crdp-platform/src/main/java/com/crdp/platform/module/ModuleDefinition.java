package com.crdp.platform.module;

import java.util.List;

/**
 * 随发版登记的模块定义（PLT-MOD-002）：模块由代码定义并注册到目录，平台端不能在页面上新建可运行模块。
 *
 * <p>名称与说明只是首次注册时的默认值，之后由平台端维护（展示信息），注册同步不覆盖；功能版本、迁移版本、依赖与 配置项清单每次发版由代码重新注册。
 *
 * @param migrationLocation 该模块迁移脚本的 classpath 目录（版本号取其中最大的 Flyway 脚本版本）
 */
public record ModuleDefinition(
        String code,
        String defaultName,
        String defaultDescription,
        boolean baseModule,
        String featureVersion,
        String changeNote,
        List<String> dependsOn,
        String migrationLocation,
        List<ConfigItem> configItems) {

    /** 模块配置项（PLT-MOD-001 目录展示；租户级取值在开通模块时初始化，见 PLT-MOD-008）。 */
    public record ConfigItem(String code, String name, String defaultValue, String description) {}
}
