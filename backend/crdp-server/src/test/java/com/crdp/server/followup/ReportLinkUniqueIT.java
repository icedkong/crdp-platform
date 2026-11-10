package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * G1（M3 审查）/ E16-T13、A-ATT-020、C-SCAN-010：同一报告单同一位置唯一必须在**数据库层**挡住。
 *
 * <p>V63 的 uk_rpt_report_link 含可空列，MySQL 里 NULL 互不相等——修复迁移 followup V78 用生成列 把 NULL 换成固定占位（0 /
 * 空串）再建唯一索引。本用例直接走 JDBC 绕过服务层去重，验证两条 「整条随访记录」（锚点全 NULL）与两条基线分类（记录 id NULL）不能同时插入；不同位置组合不受影响。
 * 全部虚构数据（示例标记），门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ReportLinkUniqueIT {

    /** 本用例的虚构标记（示例）：报告单类型与名称快照都带 G1-UNQ 前缀，结束后整段清理。 */
    private static final String MARKER = "G1-UNQ";

    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "DELETE l FROM rpt_report_link l JOIN rpt_report r ON r.id = l.report_id"
                                    + " WHERE r.type_code = ?",
                            MARKER);
                    jdbc.update("DELETE FROM rpt_report WHERE type_code = ?", MARKER);
                    jdbc.update(
                            "DELETE FROM pt_patient WHERE mrn_blind = ?", MARKER + "-mrn-blind");
                });
    }

    @Test
    void twoWholeRecordLinksWithNullAnchorCannotBothInsert() {
        long patientId = patientId();
        long reportId = reportId(patientId);
        insertLink(reportId, patientId, "followup_record", 601L, null, null);
        // 同一条随访记录（link_record_id 相同、锚点全 NULL）再挂一次「整条」：V78 之前
        // anchor_type / anchor_key 的 NULL 互不相等，两行可同时插入
        DuplicateKeyException rejected =
                assertThrows(
                        DuplicateKeyException.class,
                        () -> insertLink(reportId, patientId, "followup_record", 601L, null, null));
        assertTrue(
                rejected.getMessage().contains("uk_rpt_report_link"),
                "由报告单位置唯一索引拒绝：" + rejected.getMessage());
        assertEquals(1, linkCount(reportId), "同一报告单同一条随访记录（整条）只允许一行——服务层去重之外数据库兜底拒绝");
    }

    @Test
    void twoBaselineCategoryLinksWithNullRecordIdCannotBothInsert() {
        long patientId = patientId();
        long reportId = reportId(patientId);
        insertLink(reportId, patientId, "baseline", null, "category", "basic_info");
        // 基线分类的 link_record_id 为 NULL：第二条同分类位置被 V78 唯一索引拒绝
        assertThrows(
                DuplicateKeyException.class,
                () -> insertLink(reportId, patientId, "baseline", null, "category", "basic_info"));
        assertEquals(1, linkCount(reportId));
    }

    @Test
    void distinctAnchorsAndDifferentReportsStillInsert() {
        long patientId = patientId();
        long first = reportId(patientId);
        long second = reportId(patientId);
        // 同一报告单：整条 + 分组（anchor_type 不同）各一行；基线两个不同分类各一行
        insertLink(first, patientId, "followup_record", 603L, null, null);
        insertLink(first, patientId, "followup_record", 603L, "section", "echo");
        insertLink(first, patientId, "baseline", null, "category", "basic_info");
        insertLink(first, patientId, "baseline", null, "category", "surgery");
        // 另一份报告单同样组合不受影响
        insertLink(second, patientId, "followup_record", 604L, null, null);
        insertLink(second, patientId, "baseline", null, "category", "basic_info");
        assertEquals(4, linkCount(first));
        assertEquals(2, linkCount(second));
    }

    /** V78 生成列唯一索引已就位（防迁移漂移：名字与占位生成列都来自该迁移）。 */
    @Test
    void uniqueIndexOnGeneratedColumnsExists() {
        List<Map<String, Object>> columns =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT COLUMN_NAME, EXTRA FROM information_schema.COLUMNS"
                                                + " WHERE TABLE_SCHEMA = DATABASE()"
                                                + " AND TABLE_NAME = 'rpt_report_link'"
                                                + " AND COLUMN_NAME IN"
                                                + " ('link_record_id_nn','anchor_type_nn','anchor_key_nn')"));
        assertEquals(3, columns.size(), "V78 的三个空值占位生成列必须存在");
        assertTrue(
                columns.stream()
                        .allMatch(row -> String.valueOf(row.get("EXTRA")).contains("VIRTUAL")),
                "生成列保持 VIRTUAL（不落盘）");
        Integer indexes =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(DISTINCT INDEX_NAME) FROM"
                                                + " information_schema.STATISTICS WHERE TABLE_SCHEMA ="
                                                + " DATABASE() AND TABLE_NAME = 'rpt_report_link'"
                                                + " AND INDEX_NAME = 'uk_rpt_report_link_nn'",
                                        Integer.class));
        assertEquals(1, indexes, "唯一索引 uk_rpt_report_link_nn 必须存在");
    }

    // ---------------------------------------------------------------- 数据准备（全部虚构，示例标记）

    private long patientId() {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO pt_patient (mrn_cipher, mrn_blind, name_cipher, name_blind,"
                                    + " gender, ward_id, followup_status) VALUES (?, ?, ?, ?, 'F',"
                                    + " (SELECT MIN(id) FROM ward), 'ACTIVE')",
                            MARKER + "-mrn-cipher（示例）",
                            MARKER + "-mrn-blind",
                            MARKER + "-name-cipher（示例）",
                            MARKER + "-name-blind");
                    return jdbc.queryForObject(
                            "SELECT id FROM pt_patient WHERE mrn_blind = ?",
                            Long.class,
                            MARKER + "-mrn-blind");
                });
    }

    private long reportId(long patientId) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO rpt_report (patient_id, type_code, type_name, name_snapshot,"
                                    + " report_date) VALUES (?, ?, ?, ?, '2026-11-01')",
                            patientId,
                            MARKER,
                            "G1 用例报告单类型（示例）",
                            "G1 用例报告单（示例）");
                    return jdbc.queryForObject(
                            "SELECT id FROM rpt_report WHERE type_code = ? ORDER BY id DESC LIMIT 1",
                            Long.class,
                            MARKER);
                });
    }

    private void insertLink(
            long reportId,
            long patientId,
            String linkType,
            Long linkRecordId,
            String anchorType,
            String anchorKey) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO rpt_report_link (report_id, patient_id, link_type,"
                                        + " link_record_id, anchor_type, anchor_key, template_version)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, 1)",
                                reportId,
                                patientId,
                                linkType,
                                linkRecordId,
                                anchorType,
                                anchorKey));
    }

    private int linkCount(long reportId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM rpt_report_link WHERE report_id = ?",
                                Integer.class,
                                reportId));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        // plt_tenant 在平台库：平台上下文内查（与 ReportLocationFallbackScheduler 同口径）
        Long id =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM plt_tenant WHERE code = ?",
                                        Long.class,
                                        tenant));
        assertNotNull(id, "演示租户未就绪（先跑 docker compose 与预迁移）");
        return id;
    }
}
