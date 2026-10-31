package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * E02-T09 租户库 SQL 静态扫描（技术方案 §隔离验收：“静态扫描确保租户库 Mapper 不会出现跨库限定名”； AGENTS.md 规则 1：租户库表不带
 * tenant_id、不引用平台库）。纯单元测试，无基础设施，随 CI 每次构建执行：
 *
 * <ul>
 *   <li>租户库模块（crdp-system / crdp-form / crdp-followup）Mapper 注解 SQL 与 db/tenant/** 迁移脚本
 *       不得出现跨库限定名（`库`.`表`、FROM/JOIN 库.表、crdp_platform）；
 *   <li>不得引用平台库表（plt_ 前缀）——平台数据只能经平台库上下文访问；
 *   <li>不得出现 tenant_id 列——隔离靠“每租户一个库”，不是行级过滤，混入该列即设计回退。
 * </ul>
 *
 * <p>平台库模块（crdp-platform）按职责本来就该访问 plt_*，不在扫描范围。注释先剥离再匹配，避免迁移脚本 头部的说明文字误报；检测器本身的识别能力由 {@link
 * #detectorCatchesKnownViolations()} 用正反例钉死。
 */
class TenantSqlIsolationScanTest {

    /** 租户库模块的 Java 包（AGENTS.md §3：crdp-system / crdp-form / crdp-followup 的 SQL 都落在这三包）。 */
    private static final List<String> TENANT_MODULES =
            List.of("com.crdp.system", "com.crdp.form", "com.crdp.followup");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\r\\n]*");
    private static final Pattern BACKTICK_QUALIFIED =
            Pattern.compile("`[^`]+`\\s*\\.\\s*(`[^`]+`|[a-z_][a-z0-9_]*)");
    private static final Pattern FROM_JOIN_QUALIFIED =
            Pattern.compile("\\b(from|join)\\s+[a-z_][a-z0-9_]*\\s*\\.\\s*[a-z_][a-z0-9_]*");
    private static final Pattern PLATFORM_DATABASE = Pattern.compile("\\bcrdp_platform\\b");
    private static final Pattern PLATFORM_TABLE = Pattern.compile("\\bplt_[a-z0-9_]+\\b");
    private static final Pattern TENANT_ID_COLUMN = Pattern.compile("\\btenant_id\\b");

    @Test
    void tenantMappersContainNoCrossSchemaOrPlatformReferences() throws Exception {
        List<String> violations = new ArrayList<>();
        List<String> scanned = new ArrayList<>();
        for (Class<?> mapper : tenantMappers()) {
            for (String sql : mapperSql(mapper)) {
                scanned.add(mapper.getSimpleName());
                violations.addAll(violationsOf(mapper.getSimpleName(), sql));
            }
        }
        assertFalse(scanned.isEmpty(), "未扫到任何租户库 Mapper SQL——扫描器失效（比如 @Mapper 注解或包结构调整），需修复本用例");
        assertTrue(scanned.contains("HospitalMapper"), "应至少扫到 crdp-system 的 HospitalMapper");
        assertTrue(
                violations.isEmpty(), () -> "租户库 Mapper 出现跨库引用:\n" + String.join("\n", violations));
    }

    @Test
    void tenantFlywayScriptsContainNoCrossSchemaOrPlatformReferences() throws IOException {
        List<String> violations = new ArrayList<>();
        List<String> scripts = new ArrayList<>();
        for (Resource resource : tenantScripts()) {
            String fileName = resource.getFilename();
            scripts.add(fileName);
            violations.addAll(violationsOf(fileName, read(resource)));
        }
        assertFalse(scripts.isEmpty(), "未扫到 db/tenant/** 迁移脚本——资源模式失效，需修复本用例");
        assertTrue(violations.isEmpty(), () -> "租户库迁移脚本出现跨库引用:\n" + String.join("\n", violations));
    }

    /** 检测器自检：正反例钉死识别能力，防止扫描器悄悄失效（“永远通过”的静态扫描没有价值）。 */
    @Test
    void detectorCatchesKnownViolations() {
        // FROM/JOIN 限定名 + 平台库名 + 平台表名可同时命中，断言只验证“至少识别到”，不锁死条数
        assertTrue(violationsOf("demo", "SELECT 1 FROM `t_demoa`.`sys_user`").size() == 1);
        assertFalse(violationsOf("demo", "select * from crdp_platform.plt_tenant").isEmpty());
        assertTrue(
                violationsOf("demo", "SELECT id FROM t_demoa.sys_user WHERE id = 1").size() == 1);
        assertTrue(violationsOf("demo", "UPDATE plt_tenant SET status = 'TRIAL'").size() == 1);
        assertTrue(violationsOf("demo", "INSERT INTO task (tenant_id) VALUES (1)").size() == 1);
        assertFalse(violationsOf("demo", "SELECT name FROM crdp_platform.sys_user").isEmpty());

        // 合法写法不得误报：别名限定列、本库表、注释里的说明文字
        assertEquals(
                List.of(),
                violationsOf(
                        "demo",
                        """
                SELECT r.code, ur.role_id
                FROM sys_role r
                JOIN sys_user_role ur ON ur.role_id = r.id
                WHERE rm.menu_id = #{id}
                """));
        assertEquals(
                List.of(),
                violationsOf(
                        "demo",
                        """
                -- 租户库底座。表上不设 tenant_id，平台库表（plt_*）与本库无关，见 AGENTS.md。
                /* 块注释同样忽略：FROM crdp_platform.plt_tenant */
                SELECT id FROM sys_user
                """));
    }

    /** 扫租户库模块的字节码找 @Mapper 接口。不用 Spring 的组件扫描：它默认排除接口，扫不到 MyBatis Mapper。 */
    private static List<Class<?>> tenantMappers() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Class<?>> mappers = new ArrayList<>();
        for (String basePackage : TENANT_MODULES) {
            String pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
            for (Resource resource : resolver.getResources(pattern)) {
                String name = classNameOf(basePackage, resource);
                Class<?> type = loadClass(name);
                if (type.isAnnotationPresent(Mapper.class)) {
                    mappers.add(type);
                }
            }
        }
        return mappers;
    }

    /** 资源路径 → 全限定类名（跳过内部类与无效条目）。 */
    private static String classNameOf(String basePackage, Resource resource) {
        try {
            String path = resource.getURL().getPath();
            int start = path.indexOf(basePackage.replace('.', '/'));
            if (start < 0) {
                return "";
            }
            return path.substring(start).replace('/', '.').replaceAll("\\.class$", "");
        } catch (IOException ex) {
            return "";
        }
    }

    private static Class<?> loadClass(String name) {
        if (name.isEmpty() || name.contains("$")) {
            return Void.class; // 内部类与无效条目直接跳过（Void 无 @Mapper 注解）
        }
        try {
            return Class.forName(name, false, TenantSqlIsolationScanTest.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("无法加载 Mapper 类: " + name, ex);
        }
    }

    /** 读取 Mapper 注解里的 SQL（@Select/@Insert/@Update/@Delete；Provider 类不含内联 SQL，无需展开）。 */
    private static List<String> mapperSql(Class<?> mapper) {
        List<String> sql = new ArrayList<>();
        for (var method : mapper.getMethods()) {
            for (var annotation : method.getAnnotations()) {
                if (annotation instanceof Select select) {
                    sql.add(String.join(" ", select.value()));
                } else if (annotation instanceof Insert insert) {
                    sql.add(String.join(" ", insert.value()));
                } else if (annotation instanceof Update update) {
                    sql.add(String.join(" ", update.value()));
                } else if (annotation instanceof Delete delete) {
                    sql.add(String.join(" ", delete.value()));
                }
            }
        }
        return sql;
    }

    private static List<Resource> tenantScripts() throws IOException {
        return List.of(
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:db/tenant/**/*.sql"));
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 归一化（小写、剥注释）后套全部规则，返回“位置 → 违规说明”列表。 */
    private static List<String> violationsOf(String where, String rawSql) {
        String sql =
                LINE_COMMENT
                        .matcher(BLOCK_COMMENT.matcher(rawSql).replaceAll(" "))
                        .replaceAll(" ")
                        .toLowerCase(Locale.ROOT);
        List<String> violations = new ArrayList<>();
        if (BACKTICK_QUALIFIED.matcher(sql).find()) {
            violations.add(where + ": 跨库限定名（`库`.`表`）: " + excerpt(sql));
        }
        if (FROM_JOIN_QUALIFIED.matcher(sql).find()) {
            violations.add(where + ": FROM/JOIN 使用了库.表限定名: " + excerpt(sql));
        }
        if (PLATFORM_DATABASE.matcher(sql).find()) {
            violations.add(where + ": 引用平台库 crdp_platform: " + excerpt(sql));
        }
        if (PLATFORM_TABLE.matcher(sql).find()) {
            violations.add(where + ": 引用平台库表（plt_*）: " + excerpt(sql));
        }
        if (TENANT_ID_COLUMN.matcher(sql).find()) {
            violations.add(where + ": 租户库出现 tenant_id 列（隔离靠每租户一库，不是行级过滤）: " + excerpt(sql));
        }
        return violations;
    }

    private static String excerpt(String sql) {
        String compact = sql.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "…" : compact;
    }
}
