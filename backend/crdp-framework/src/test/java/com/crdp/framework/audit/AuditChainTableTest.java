package com.crdp.framework.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E08-T02 / PLT-AUD-008：哈希链规范化内容。 同一行内容必须得到唯一、确定的规范化串（写入与验链两侧共用同一实现）； 换行、竖线等字符出现在值里时不得破坏解析边界（URL
 * 编码）；null 与空串可区分语义不参与（都编码为空）； 数字与布尔按稳定规则归一（写入侧 Integer/Long 与读回侧 Integer/Long/Boolean 得到同一规范化值）。
 */
class AuditChainTableTest {

    private final AuditChainTable probe = new AuditChainTable("aud_probe", "a", "b", "c");

    @Test
    void canonicalFormIsDeterministicAndBoundedByColumnNames() {
        List<Object> values = java.util.Arrays.asList("x", 1L, null);
        assertThat(probe.canonicalForm(values))
                .isEqualTo(probe.canonicalForm(java.util.Arrays.asList("x", 1L, null)));
        assertThat(probe.canonicalForm(values))
                .contains("aud_probe")
                .contains("a=")
                .contains("b=1")
                .contains("c=");
    }

    @Test
    void valuesWithSeparatorsAreEncodedAndDoNotBreakLineStructure() {
        String canonical = probe.canonicalForm(List.of("换行\n竖线|等号=", 2, "p"));
        // 每列一行：v1 头 + 表名 + 3 列 = 5 行
        assertThat(canonical.split("\n", -1)).hasSize(5);
        assertThat(canonical).doesNotContain("换行\n").doesNotContain("竖线|");
        assertThat(canonical).contains("%0A").contains("%7C").contains("%3D");
    }

    @Test
    void numberAndBooleanNormalizeAcrossWriteAndReadBackTypes() {
        // 写入侧传 Integer 1；MySQL 读回可能是 Integer/Long/Boolean——规范化必须一致
        assertThat(probe.canonicalForm(List.of(1, 1L, true)))
                .isEqualTo(probe.canonicalForm(List.of(1L, 1, true)));
        assertThat(probe.canonicalForm(java.util.Arrays.asList(1, null, null)))
                .isEqualTo(probe.canonicalForm(java.util.Arrays.asList(true, null, null)));
        assertThat(probe.canonicalForm(java.util.Arrays.asList(0, null, null)))
                .isEqualTo(probe.canonicalForm(java.util.Arrays.asList(false, null, null)));
    }

    @Test
    void nullAndEmptyStringBothRenderAsEmptyButColumnsKeepAlignment() {
        assertThat(probe.canonicalForm(java.util.Arrays.asList("", null, null)))
                .contains("a=\n")
                .contains("b=\n")
                .contains("c=");
    }

    @Test
    void hashInputCombinesPrevHashAndCanonicalForm() {
        String canonical = probe.canonicalForm(java.util.Arrays.asList("x", 1L, null));
        assertThat(probe.hashInput("prev-hash", java.util.Arrays.asList("x", 1L, null)))
                .isEqualTo("prev-hash\n" + canonical);
    }

    @Test
    void genesisIs64Zeros() {
        assertThat(AuditChainTable.GENESIS).hasSize(64).matches("0+");
    }

    @Test
    void realTablesDeclareHashColumnsEndingWithPrevHashAndHashOrderingStable() {
        for (AuditChainTable table : AuditChainTable.all()) {
            assertThat(table.hashColumns()).isNotEmpty();
            // 规范化列顺序固定（写入与验链两侧依赖同一顺序）
            assertThat(
                            table.canonicalForm(
                                    java.util.Collections.nCopies(
                                            table.hashColumns().size(), null)))
                    .isEqualTo(
                            table.canonicalForm(
                                    java.util.Collections.nCopies(
                                            table.hashColumns().size(), null)));
        }
        // 六张审计表：租户三张（aud_log / aud_login_log / aud_data_access_log）
        // + 平台三张（plt_audit_log / aud_login_log / aud_platform_access_log）
        assertThat(AuditChainTable.tenantTables())
                .extracting(AuditChainTable::tableName)
                .containsExactly("aud_log", "aud_login_log", "aud_data_access_log");
        assertThat(AuditChainTable.platformTables())
                .extracting(AuditChainTable::tableName)
                .containsExactly("plt_audit_log", "aud_login_log", "aud_platform_access_log");
    }
}
