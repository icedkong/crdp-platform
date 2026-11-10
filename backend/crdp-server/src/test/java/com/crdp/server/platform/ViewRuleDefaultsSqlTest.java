package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * S4（M3 审查）：生产迁移 V64 与本机种子 V109 登记同一套默认视角规则（PLT-VIEW-008）， 启动校验（{@link
 * ViewRuleDefaultsVerifier}）按同一编码清单核对——三处编码集必须一致，改一处不改另一处先红。
 *
 * <p>纯 SQL / 常量比对（不连库）：落库效果由 PlatformViewRuleMySqlIT（local 剖面，V64 + V109 幂等共存）覆盖。
 */
class ViewRuleDefaultsSqlTest {

    /** 从迁移 / 种子 SQL 里抽取规则编码清单（保持出现顺序；VALUES 行以 ('VR-…' 开头，注释与绑定语句不匹配）。 */
    private static Set<String> codesOf(String path) throws IOException {
        String sql = read(path);
        Set<String> codes = new LinkedHashSet<>();
        Matcher only = Pattern.compile("\\('(VR-[A-Z0-9-]+)'").matcher(sql);
        while (only.find()) {
            codes.add(only.group(1));
        }
        return codes;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** V64（主迁移，生产也执行）与 V109（本机种子）编码清单一致：生产与本地默认规则同内容。 */
    @Test
    void productionMigrationMatchesSeed() throws IOException {
        Set<String> seed = codesOf("db/platform/seed/V109__seed_view_rules.sql");
        Set<String> production = codesOf("db/platform/V64__platform_view_rule_defaults.sql");
        assertNotNull(seed);
        assertEquals(seed, production, "V64 与 V109 必须登记同一套默认规则编码（S4）");
        assertEquals(16, production.size(), "默认规则共 16 条（PLT-VIEW-008）");
    }

    /** 启动校验的编码清单与迁移一致：V64 新增 / 改名规则而漏改校验清单时先红。 */
    @Test
    void verifierCoversAllDefaultCodes() throws IOException {
        Set<String> production = codesOf("db/platform/V64__platform_view_rule_defaults.sql");
        Set<String> verifier = new LinkedHashSet<>(ViewRuleDefaultsVerifier.DEFAULT_RULE_CODES);
        assertEquals(production, verifier, "启动校验清单必须覆盖 V64 的全部默认规则编码");
    }
}
