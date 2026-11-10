package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.crdp.framework.tenant.TenantContext;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * G8（M3 审查）/ 性能防回归：全院报告单列表（按上传时间倒序）与任务列表（按计划日期排序） 的排序列必须有索引（followup V79：idx_rpt_report_created_at
 * / idx_fu_task_plan_date）—— 防后续迁移漂移。门控 CRDP_IT_MYSQL_PORT（CI 起 compose MySQL 预迁移后执行）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ListQueryIndexIT {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void reportUploadTimeAndTaskPlanDateIndexesExist() {
        Integer reportIndex =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(DISTINCT INDEX_NAME) FROM"
                                                + " information_schema.STATISTICS WHERE TABLE_SCHEMA ="
                                                + " DATABASE() AND TABLE_NAME = 'rpt_report' AND"
                                                + " INDEX_NAME = 'idx_rpt_report_created_at'",
                                        Integer.class));
        assertEquals(1, reportIndex, "全院报告单列表按上传时间排序需要 idx_rpt_report_created_at（V79）");

        Integer taskIndex =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(DISTINCT INDEX_NAME) FROM"
                                                + " information_schema.STATISTICS WHERE TABLE_SCHEMA ="
                                                + " DATABASE() AND TABLE_NAME = 'fu_task' AND"
                                                + " INDEX_NAME = 'idx_fu_task_plan_date'",
                                        Integer.class));
        assertEquals(1, taskIndex, "任务列表按计划日期排序需要 idx_fu_task_plan_date（V79）");
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        Long tenantId =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM plt_tenant WHERE code = ?",
                                        Long.class,
                                        tenant));
        assertNotNull(tenantId, "演示租户未就绪（先跑 docker compose 与预迁移）");
        return TenantContext.callAsTenant(tenantId, tenant, action::get);
    }
}
