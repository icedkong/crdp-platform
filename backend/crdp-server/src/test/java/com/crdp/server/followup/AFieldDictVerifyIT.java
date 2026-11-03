package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E23-T01 核对（端到端 / 真实 MySQL）：迁移 V38 落库后的医院 A 字段字典与规格附录 A「266 个字段」一致（tasks.csv 验收原文）。
 *
 * <p>基准同 {@link AFieldDictSqlTest}（附录 A 由 {@link AFieldDictAppendix} 解析），这里是库侧核对：fe_field
 * 全量（键、显示名、编码、类型、状态）、 9 个分类与随访四分组字段数（规格 §3「9 个分类概览」）、敏感四字段加密存储、必填口径、#217 合并停用（Q22）、 v1 版本快照（基线 213
 * / 随访 53）与 A-FLD-006 规则链。A-FLD-013：出生日期 f267 与手术年龄派生 AGE_YEARS(f267,f001) 在库。 门控
 * CRDP_IT_MYSQL_PORT，全部虚构数据（只读字典，不写患者数据）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AFieldDictVerifyIT {

    /** 附录类型 → fe_field.type（与 AFieldDictSqlTest 同一口径）。 */
    private static final Map<String, String> TYPE_BY_APPENDIX =
            Map.ofEntries(
                    Map.entry("是否", "radio"),
                    Map.entry("单选", "select"),
                    Map.entry("数值", "number"),
                    Map.entry("整数", "number"),
                    Map.entry("数值×2", "pair_number"),
                    Map.entry("日期", "date"),
                    Map.entry("日期时间", "datetime"),
                    Map.entry("文本", "text"),
                    Map.entry("文本（敏感）", "text"),
                    Map.entry("引用", "select"),
                    Map.entry("派生·整数", "derived"),
                    Map.entry("派生·数值", "derived"));

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        SecurityContextHolder.clearContext();
    }

    /** 库内 266 行与附录逐行一致（键 = 序号 f001–f266；显示名 / 编码 / 类型 / 状态）+ 出生日期 f267。 */
    @Test
    void databaseMatchesAppendixRowByRow() {
        runInTenant(
                "demoa",
                () -> {
                    Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
                    for (Map<String, Object> rs :
                            jdbc.queryForList(
                                    "SELECT field_key AS fieldKey, form_code AS formCode,"
                                            + " field_code AS fieldCode, label, type, status,"
                                            + " help_text AS help, sensitive_flag AS sensitiveFlag,"
                                            + " storage_type AS storageType, required,"
                                            + " category_key AS categoryKey FROM fe_field"
                                            + " WHERE form_code IN ('A_BASELINE', 'A_ANNUAL_FU')")) {
                        Map<String, Object> row = new LinkedHashMap<>(rs);
                        row.put("sensitive", ((Number) row.remove("sensitiveFlag")).intValue());
                        row.put("storage", row.remove("storageType"));
                        byKey.put((String) row.get("fieldKey"), row);
                    }
                    assertEquals(267, byKey.size(), "附录 266 + 出生日期 = 267 行");
                    for (AFieldDictAppendix.Row row : AFieldDictAppendix.rows()) {
                        String key = String.format("f%03d", row.no());
                        Map<String, Object> field = byKey.get(key);
                        assertTrue(field != null, "附录 #" + row.no() + " 落库为 " + key);
                        assertEquals(
                                row.no() <= 212 ? "A_BASELINE" : "A_ANNUAL_FU",
                                field.get("formCode"),
                                "#" + row.no() + " 所属表单");
                        assertEquals(row.label(), field.get("label"), "#" + row.no() + " 显示名");
                        assertTrue(
                                ((String) field.get("fieldCode")).matches("[A-Za-z0-9_]{1,64}"),
                                "#" + row.no() + " 字段编码 ASCII（OQ-72）");
                        assertTrue(
                                field.get("help") != null
                                        && ((String) field.get("help"))
                                                .contains("原表列名「" + row.code() + "」"),
                                "#" + row.no() + " 原表列名保留在帮助说明");
                        assertEquals(
                                TYPE_BY_APPENDIX.get(row.appendixType()),
                                field.get("type"),
                                "#" + row.no() + " 类型");
                        assertEquals(
                                "DISABLED".equals(field.get("status")),
                                row.no() == 217,
                                "#217 死亡随访时间合并停用（Q22），其余启用");
                        assertEquals(
                                row.required() ? 1 : 0,
                                ((Number) field.get("required")).intValue(),
                                "#" + row.no() + " 必填口径");
                        if ("文本（敏感）".equals(row.appendixType())) {
                            assertEquals(1, ((Number) field.get("sensitive")).intValue());
                            assertEquals("encrypted", field.get("storage"));
                        }
                    }
                    assertEquals("date", byKey.get("f267").get("type"), "A-FLD-013 出生日期");
                    assertEquals("birth_date", byKey.get("f267").get("fieldCode"));
                });
    }

    /** 分类字段数与规格 §3「9 个分类概览」一致；随访表单 4 分组（A-FLD-007）。 */
    @Test
    void categoryCountsMatchSpec() {
        runInTenant(
                "demoa",
                () -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    for (Map<String, Object> rs :
                            jdbc.queryForList(
                                    "SELECT category_key AS categoryKey, COUNT(*) AS total FROM fe_field"
                                            + " WHERE form_code IN ('A_BASELINE', 'A_ANNUAL_FU')"
                                            + " GROUP BY form_code, category_key")) {
                        counts.put(
                                (String) rs.get("categoryKey"),
                                ((Number) rs.get("total")).intValue());
                    }
                    assertEquals(
                            Map.ofEntries(
                                    Map.entry("basic", 13),
                                    Map.entry("case", 37),
                                    Map.entry("lab", 18),
                                    Map.entry("echo", 49),
                                    Map.entry("angio", 18),
                                    Map.entry("surgery", 42),
                                    Map.entry("periop", 16),
                                    Map.entry("discharge_med", 20),
                                    Map.entry("fu_contact", 6),
                                    Map.entry("fu_event", 17),
                                    Map.entry("fu_echo", 23),
                                    Map.entry("fu_med", 8)),
                            counts,
                            "基本信息 12 + 出生日期；分类 1–8 与随访四分组");
                });
    }

    /** v1 版本快照就位：基线 213（含停用的 #217 不进快照）、随访 53；快照字段与字典一致。 */
    @Test
    void versionOneSnapshotsMatchEnabledFields() {
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            213,
                            jdbc.queryForObject(
                                            "SELECT field_count FROM fe_form_version"
                                                    + " WHERE form_code = 'A_BASELINE' AND version = 1",
                                            Integer.class)
                                    .intValue(),
                            "基线 v1 = 212 附录字段 + 出生日期");
                    assertEquals(
                            53,
                            jdbc.queryForObject(
                                            "SELECT field_count FROM fe_form_version"
                                                    + " WHERE form_code = 'A_ANNUAL_FU' AND version = 1",
                                            Integer.class)
                                    .intValue(),
                            "随访 v1 = 54 − 停用 #217");
                    assertEquals(
                            213,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fe_form_version_field"
                                                    + " WHERE form_code = 'A_BASELINE' AND version = 1",
                                            Integer.class)
                                    .intValue(),
                            "基线 v1 快照行数与 field_count 一致");
                });
    }

    /** A-FLD-006 规则链在库（OQ-64①）；演示规则已随 V38 清理。 */
    @Test
    void crossFieldRulesAndDerivedWiringInDatabase() {
        runInTenant(
                "demoa",
                () -> {
                    List<String> ruleIds =
                            jdbc.queryForList(
                                    "SELECT rule_id FROM fe_rule WHERE form_code = 'A_BASELINE'"
                                            + " AND status = 'ENABLED' ORDER BY rule_id",
                                    String.class);
                    assertEquals(
                            List.of("icu_in_lt_out", "surgery_start_lt_end"),
                            ruleIds,
                            "引擎可表达的两条（主档日期链随 E13-T02 / E15 患者域编辑校验）");
                    assertEquals(
                            "AGE_YEARS",
                            jdbc.queryForObject(
                                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(derived_def, '$.fn'))"
                                            + " FROM fe_field WHERE form_code = 'A_BASELINE'"
                                            + " AND field_key = 'f005'",
                                    String.class),
                            "A-FLD-013：手术年龄派生（出生日期 f267 + 手术日期 f001）");
                });
    }

    /** 双租户：B 院（配置差异版）同样持有 A 配置模板的字典（E23-T15 由 A 模板初始化 B 的基础）。 */
    @Test
    void dictPresentForSecondTenant() {
        runInTenant(
                "demob",
                () ->
                        assertEquals(
                                267,
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM fe_field WHERE form_code IN"
                                                        + " ('A_BASELINE', 'A_ANNUAL_FU')",
                                                Integer.class)
                                        .intValue(),
                                "同一套租户库迁移，B 院字典同样就位"));
    }

    private void runInTenant(String tenant, Runnable action) {
        long tenantId = "demob".equals(tenant) ? 2L : 1L;
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("verify-it", null, List.of()));
        TenantContext.runAsTenant(tenantId, tenant, action);
    }
}
