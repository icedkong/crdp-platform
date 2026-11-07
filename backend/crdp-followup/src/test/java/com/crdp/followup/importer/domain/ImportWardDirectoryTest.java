package com.crdp.followup.importer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.importer.domain.ImportWardDirectory.MatchStatus;
import com.crdp.followup.importer.domain.ImportWardDirectory.WardEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E18-T04 病区值映射字典测试（A-WARD-006「导入与录入时『病区/中心』字段必须对应病区字典；历史写法可配置别名映射」； tech-plan
 * §12④「病区值按病区字典与别名映射」）： 取值按编码 / 名称 / 别名精确匹配（别名即历史写法，OQ-74④ 定稿复用 ward.alias 列）；
 * 匹配到停用病区、一个取值匹配多个病区都按可报告的失败口径返回， 未映射值由校验层报 ERROR（tasks.csv 验收「未映射值导入前必须处理」）。全部虚构数据。
 */
class ImportWardDirectoryTest {

    /** 心外一病区（别名「心外一」「心外」，历史写法）+ 冠心病监护中心（编码 CCU）+ 已停用的老病区。 */
    private static final ImportWardDirectory DIRECTORY =
            new ImportWardDirectory(
                    List.of(
                            new WardEntry(11, "CVSW1", "心外一病区", "ENABLED", List.of("心外一", "心外")),
                            new WardEntry(12, "CCU", "冠心病监护中心", "ENABLED", List.of()),
                            new WardEntry(13, "OLD1", "老病区（示例）", "DISABLED", List.of("老病区"))));

    @Test
    void resolvesByCodeNameAndAlias() {
        assertEquals(11, DIRECTORY.resolve("CVSW1").ward().id(), "按编码匹配");
        assertEquals(11, DIRECTORY.resolve("心外一病区").ward().id(), "按名称匹配");
        assertEquals(11, DIRECTORY.resolve("心外").ward().id(), "按别名（历史写法）匹配");
        assertEquals(12, DIRECTORY.resolve("CCU").ward().id(), "编码也可以是字母");
        assertEquals(MatchStatus.MATCHED, DIRECTORY.resolve("心外一病区").status());
    }

    @Test
    void unmatchedValueReportsUnmapped() {
        ImportWardDirectory.Resolution resolution = DIRECTORY.resolve("心内科三病区");
        assertEquals(MatchStatus.UNMAPPED, resolution.status());
        assertEquals(null, resolution.ward());
    }

    @Test
    void disabledWardMatchesButReportsDisabled() {
        ImportWardDirectory.Resolution resolution = DIRECTORY.resolve("老病区");
        assertEquals(MatchStatus.DISABLED, resolution.status(), "匹配到停用病区：不是未映射，是病区不可用");
        assertEquals(13, resolution.ward().id());
    }

    @Test
    void valueMatchingSeveralWardsReportsAmbiguous() {
        ImportWardDirectory ambiguous =
                new ImportWardDirectory(
                        List.of(
                                new WardEntry(21, "W1", "心病区一（示例）", "ENABLED", List.of("心病区")),
                                new WardEntry(22, "W2", "心病区二（示例）", "ENABLED", List.of("心病区"))));
        ImportWardDirectory.Resolution resolution = ambiguous.resolve("心病区");
        assertEquals(MatchStatus.AMBIGUOUS, resolution.status());
        assertEquals(2, resolution.candidates().size());
    }

    @Test
    void blankOrUnknownInputsAreUnmapped() {
        assertEquals(MatchStatus.UNMAPPED, DIRECTORY.resolve(null).status());
        assertEquals(MatchStatus.UNMAPPED, DIRECTORY.resolve("  ").status());
        assertEquals(MatchStatus.UNMAPPED, DIRECTORY.resolve("N/A").status());
    }

    @Test
    void enabledSummaryListsEnabledWardNamesForGuidance() {
        String summary = DIRECTORY.enabledSummary(8);
        assertTrue(summary.contains("心外一病区"), summary);
        assertTrue(summary.contains("冠心病监护中心"), summary);
        assertTrue(!summary.contains("老病区"), "停用病区不作为候选提示");
    }

    @Test
    void emptyDirectoryReportsUnmapped() {
        ImportWardDirectory empty = new ImportWardDirectory(List.of());
        assertEquals(MatchStatus.UNMAPPED, empty.resolve("心外一病区").status());
        assertEquals("", empty.enabledSummary(8));
    }
}
