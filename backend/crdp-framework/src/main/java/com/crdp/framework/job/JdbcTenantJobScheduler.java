package com.crdp.framework.job;

import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 按租户扇出并写 job_tenant_run 执行记录（租户库表，迁移 V3；补跑起点 = 最近 SUCCESS 日）。单租户失败
 * 不中断其他租户：业务失败或该院库不可达都记入结果列表后继续。设计取舍见 ADR 0003。
 */
@Component
public class JdbcTenantJobScheduler implements JobScheduler {

    private static final int ERROR_MAX_LENGTH = 1000;

    private final TenantDirectory directory;
    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantJobScheduler(TenantDirectory directory, JdbcTemplate jdbcTemplate) {
        this.directory = directory;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TenantJobResult> runForEachTenant(
            String jobCode, LocalDate logicalDate, TenantJob job) {
        List<TenantJobResult> results = new ArrayList<>();
        for (TenantRef tenant : directory.enabledTenants()) {
            String error;
            try {
                error =
                        TenantContext.callAsTenant(
                                tenant.tenantId(),
                                tenant.tenantCode(),
                                () -> runForOneTenant(jobCode, logicalDate, job));
            } catch (RuntimeException ex) {
                // 租户库不可达、写 RUNNING 记录失败等平台侧异常：本租户记失败，继续其他租户
                error = abbreviate(ex);
            }
            results.add(
                    new TenantJobResult(
                            tenant.tenantId(), tenant.tenantCode(), error == null, error));
        }
        return results;
    }

    @Override
    public Optional<LocalDate> lastSuccessDate(String jobCode) {
        LocalDate last =
                jdbcTemplate.queryForObject(
                        "SELECT MAX(run_date) FROM job_tenant_run WHERE job_code = ? AND status = 'SUCCESS'",
                        LocalDate.class,
                        jobCode);
        return Optional.ofNullable(last);
    }

    /** 在租户上下文内执行一个租户的作业；返回 null 表示成功，否则为失败原因摘要。 */
    private String runForOneTenant(String jobCode, LocalDate logicalDate, TenantJob job) {
        // 先占位再执行：RUNNING 记录同时是按日幂等键（重复执行覆盖当天）
        jdbcTemplate.update(
                """
                INSERT INTO job_tenant_run (job_code, run_date, status, started_at)
                VALUES (?, ?, 'RUNNING', CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                    status = 'RUNNING', started_at = CURRENT_TIMESTAMP(3), finished_at = NULL, error_text = NULL
                """,
                jobCode,
                logicalDate);
        try {
            job.run(logicalDate);
        } catch (Exception ex) {
            markFinished(jobCode, logicalDate, "FAILED", abbreviate(ex));
            return abbreviate(ex);
        }
        markFinished(jobCode, logicalDate, "SUCCESS", null);
        return null;
    }

    private void markFinished(
            String jobCode, LocalDate logicalDate, String status, String errorText) {
        jdbcTemplate.update(
                """
                UPDATE job_tenant_run
                SET status = ?, finished_at = CURRENT_TIMESTAMP(3), error_text = ?
                WHERE job_code = ? AND run_date = ?
                """,
                status,
                errorText,
                jobCode,
                logicalDate);
    }

    private static String abbreviate(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            message = ex.getClass().getSimpleName();
        }
        return message.length() > ERROR_MAX_LENGTH
                ? message.substring(0, ERROR_MAX_LENGTH)
                : message;
    }
}
