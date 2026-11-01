package com.crdp.platform.module;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 本发行版随包登记的模块清单（PLT-MOD-002 / E05-T04 验收：只登记「随访」模块）。
 *
 * <p>平台基础（base）是底座、必选，作为种子数据存在，不在代码登记清单里；RCT（rct）等为后续模块，首期不登记 （platform-spec §3）。新增模块 =
 * 在这里加定义并随发版注册，不是在平台端页面新建。
 */
@Component
public class ModuleRegistry {

    private static final Pattern SCRIPT_NAME = Pattern.compile("V(\\d+)__.+\\.sql");

    /** 随访模块：医院 A、B、C 共用，差异由表单模板与功能开关承担（platform-spec §3 已确认）。 */
    private static final List<ModuleDefinition> MODULES =
            List.of(
                    new ModuleDefinition(
                            "followup",
                            "随访",
                            "医院 A、B、C 共用的随访模块，差异由表单模板与功能开关承担",
                            false,
                            "0.1.0",
                            "首期登记随访模块：患者档案、随访任务等业务能力随后续里程碑交付",
                            List.of("base"),
                            "db/tenant/followup",
                            List.of(
                                    new ModuleDefinition.ConfigItem(
                                            "defaultNotifyChannel",
                                            "随访默认渠道",
                                            "站内信",
                                            "任务通知的默认渠道；租户级取值在开通模块时初始化（PLT-MOD-008）"))));

    public List<ModuleDefinition> definitions() {
        return MODULES;
    }

    public Optional<ModuleDefinition> find(String code) {
        return MODULES.stream().filter(def -> def.code().equals(code)).findFirst();
    }

    /** 模块的数据库迁移版本（PLT-MOD-003）：取其迁移目录中最大的 Flyway 脚本版本号，随发版自动变化，无需手工维护。 */
    public String migrationVersionOf(ModuleDefinition definition) {
        return latestMigrationVersion(definition.migrationLocation());
    }

    /** 扫描 classpath 目录里的 V{n}__*.sql，返回最大版本号字符串。 */
    static String latestMigrationVersion(String location) {
        Resource[] resources;
        try {
            resources =
                    new PathMatchingResourcePatternResolver()
                            .getResources("classpath*:" + location + "/V*.sql");
        } catch (IOException ex) {
            throw new IllegalStateException("读取模块迁移目录失败：" + location, ex);
        }
        Optional<Long> latest =
                List.of(resources).stream()
                        .map(resource -> filenameOf(resource, location))
                        .map(SCRIPT_NAME::matcher)
                        .filter(Matcher::matches)
                        .map(matcher -> Long.parseLong(matcher.group(1)))
                        .max(Comparator.naturalOrder());
        if (latest.isEmpty()) {
            throw new IllegalStateException("模块迁移目录里没有 V*.sql 脚本：" + location);
        }
        return String.valueOf(latest.get());
    }

    private static String filenameOf(Resource resource, String location) {
        String name = resource.getFilename();
        if (name == null) {
            throw new IllegalStateException("模块迁移目录里存在无名资源：" + location);
        }
        return name;
    }
}
