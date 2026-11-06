package com.crdp.server.configtemplate;

import static org.assertj.core.api.Assertions.assertThat;

import com.crdp.platform.configtemplate.ConfigTableEntry;
import com.crdp.platform.configtemplate.ConfigTableRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * E12-T03 / A-FLD-014：包含与排除规则的完备性闸门。租户库（base + followup）迁移里每一张 CREATE TABLE 的表 都必须被显式分类——
 * 要么登记为配置表（{@link ConfigTableRegistry#ENTRIES}，含 STRUCTURE_ONLY）， 要么显式排除（{@link
 * ConfigTableRegistry#NEVER_EXPORT_TABLES}）。
 *
 * <p>新增迁移带来新表时本测试失败，强迫登记或排除二选一，杜绝「忘了分类」的灰色表（未标注的表一律不导出， 但分类本身必须是有意识的决定）。 反向同样把关：注册表 /
 * 排除清单里不允许悬空表名（迁移里不存在的表）。
 */
class ConfigTableCoverageTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile(
                    "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-z][a-z0-9_]*)`?", 2);
    private static final List<String> MODULES = List.of("base", "followup");

    @Test
    void everyTenantMigrationTableIsClassified() throws IOException {
        Set<String> created = createdTables();
        assertThat(created).isNotEmpty();
        Set<String> classified = new LinkedHashSet<>();
        classified.addAll(ConfigTableRegistry.tables());
        classified.addAll(ConfigTableRegistry.NEVER_EXPORT_TABLES);
        // 迁移里的表全部已分类（未分类 = 漏登记或漏排除，二者必居其一）
        assertThat(created)
                .allSatisfy(table -> assertThat(classified).as("表 %s 未分类", table).contains(table));
        // 反向：分类清单无悬空表名
        assertThat(classified)
                .allSatisfy(table -> assertThat(created).as("表 %s 在迁移中不存在", table).contains(table));
    }

    @Test
    void copyEntriesDeclareImportSpecs() {
        // 包含规则配套：每个 COPY 条目都有导入规格（E12-T02 互锁的另一面），敏感检查依赖注册表口径
        for (ConfigTableEntry entry : ConfigTableRegistry.ENTRIES) {
            if (entry.mode() == com.crdp.platform.configtemplate.ConfigCopyMode.COPY) {
                assertThat(com.crdp.platform.configtemplate.ConfigImportSpecs.of(entry.table()))
                        .as("COPY 表 %s 缺少导入规格", entry.table())
                        .isNotNull();
            }
        }
    }

    private static Set<String> createdTables() throws IOException {
        Set<String> tables = new LinkedHashSet<>();
        for (String module : MODULES) {
            Path dir = Path.of("src/main/resources/db/tenant", module);
            assertThat(dir).as("迁移目录 %s（测试须从 crdp-server 模块目录运行）", dir).exists();
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> scripts =
                        files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                                .sorted()
                                .toList();
                assertThat(scripts).isNotEmpty();
                for (Path script : scripts) {
                    String sql = Files.readString(script, StandardCharsets.UTF_8);
                    Matcher matcher = CREATE_TABLE.matcher(sql);
                    while (matcher.find()) {
                        tables.add(matcher.group(1));
                    }
                }
            }
        }
        return tables;
    }
}
