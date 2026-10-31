package com.crdp.framework.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** E02-T06：按租户扇出的调度执行记录与补跑框架。单租户失败不影响其他租户，可按上次成功日补跑。 */
class JdbcTenantJobSchedulerTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TenantDirectory directory = mock(TenantDirectory.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private JdbcTenantJobScheduler scheduler() {
        return new JdbcTenantJobScheduler(directory, jdbcTemplate);
    }

    @Test
    void runsTheJobForEachTenantInsideItsOwnContextAndRecordsSuccess() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));
        List<String> contextsSeen = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 11, 16);

        List<TenantJobResult> results =
                scheduler()
                        .runForEachTenant(
                                "daily_demo",
                                date,
                                d -> contextsSeen.add(TenantContext.lookupKey()));

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(TenantJobResult::success));
        assertEquals(List.of("tenant:1", "tenant:2"), contextsSeen, "作业体应分别在各自租户上下文内执行");
        assertTrue(TenantContext.current().isEmpty(), "调度线程自身不残留租户上下文");
        verify(jdbcTemplate, times(2))
                .update(contains("INSERT INTO job_tenant_run"), eq("daily_demo"), eq(date));
        verify(jdbcTemplate, times(2))
                .update(
                        contains("UPDATE job_tenant_run"),
                        eq("SUCCESS"),
                        any(),
                        eq("daily_demo"),
                        eq(date));
    }

    @Test
    void aFailingTenantIsRecordedAndDoesNotStopTheOthers() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));

        List<TenantJobResult> results =
                scheduler()
                        .runForEachTenant(
                                "daily_demo",
                                LocalDate.of(2026, 11, 16),
                                d -> {
                                    if ("tenant:1".equals(TenantContext.lookupKey())) {
                                        throw new IllegalStateException("租户一的业务失败");
                                    }
                                });

        assertEquals(2, results.size());
        assertEquals(false, results.get(0).success());
        assertEquals("租户一的业务失败", results.get(0).error());
        assertEquals("demoa", results.get(0).tenantCode());
        assertEquals(true, results.get(1).success());
        verify(jdbcTemplate)
                .update(
                        contains("UPDATE job_tenant_run"),
                        eq("FAILED"),
                        contains("租户一的业务失败"),
                        eq("daily_demo"),
                        eq(LocalDate.of(2026, 11, 16)));
        verify(jdbcTemplate)
                .update(
                        contains("UPDATE job_tenant_run"),
                        eq("SUCCESS"),
                        any(),
                        eq("daily_demo"),
                        eq(LocalDate.of(2026, 11, 16)));
    }

    @Test
    void anUnreachableTenantDatabaseDoesNotStopTheOthers() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));
        doThrow(new RuntimeException("连接失败"))
                .doReturn(1)
                .when(jdbcTemplate)
                .update(contains("INSERT INTO job_tenant_run"), anyString(), any(LocalDate.class));

        List<TenantJobResult> results =
                scheduler().runForEachTenant("daily_demo", LocalDate.of(2026, 11, 16), d -> {});

        assertEquals(2, results.size());
        assertEquals(false, results.get(0).success(), "租户库不可达应记为失败");
        assertTrue(results.get(0).error().contains("连接失败"));
        assertEquals(true, results.get(1).success(), "其他租户照常执行");
    }

    @Test
    void lastSuccessDateReadsTheLatestSuccessfulRun() {
        when(jdbcTemplate.queryForObject(anyString(), eq(LocalDate.class), eq("daily_demo")))
                .thenReturn(LocalDate.of(2026, 11, 15));

        TenantContext.useTenant(1L, "demoa");
        Optional<LocalDate> last = scheduler().lastSuccessDate("daily_demo");

        assertEquals(Optional.of(LocalDate.of(2026, 11, 15)), last);
        verify(jdbcTemplate)
                .queryForObject(contains("MAX(run_date)"), eq(LocalDate.class), eq("daily_demo"));
    }

    @Test
    void lastSuccessDateIsEmptyWhenNothingSucceededYet() {
        when(jdbcTemplate.queryForObject(anyString(), eq(LocalDate.class), any(Object[].class)))
                .thenReturn(null);

        Optional<LocalDate> last = scheduler().lastSuccessDate("daily_demo");

        assertTrue(last.isEmpty());
    }

    @Test
    void noTenantsMeansNoWorkAndNoRecords() {
        when(directory.enabledTenants()).thenReturn(List.of());

        List<TenantJobResult> results =
                scheduler().runForEachTenant("daily_demo", LocalDate.of(2026, 11, 16), d -> {});

        assertTrue(results.isEmpty());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        assertNull(TenantContext.lookupKey());
    }
}
