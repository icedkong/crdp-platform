package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.*;

import com.crdp.followup.importer.infra.mapper.ImportCommitMapper;
import com.crdp.framework.tenant.TenantContext;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** 同一作业并发确认只能有一个赢家；失败请求不得覆盖策略或范围，双租户均独立成立。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ImportClaimConcurrencyIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportCommitMapper mapper;

    @Test
    void failedConcurrentClaimCannotOverwriteWinningOptionsInEitherTenant() throws Exception {
        for (String tenant : List.of("demoa", "demob")) {
            String marker = "IT-业务修复（示例）-" + UUID.randomUUID();
            long tenantId = "demoa".equals(tenant) ? 1L : 2L;
            long id =
                    TenantContext.callAsTenant(
                            tenantId,
                            tenant,
                            () -> {
                                jdbc.update(
                                        "INSERT INTO imp_job(file_name,file_size,status,operator_id,operator_name) VALUES (?,0,'PRECHECKED',1,'修复回归（示例）')",
                                        marker);
                                return jdbc.queryForObject(
                                        "SELECT id FROM imp_job WHERE file_name=?",
                                        Long.class,
                                        marker);
                            });
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Integer>> results = new ArrayList<>();
                for (String strategy : List.of("SKIP", "UPDATE")) {
                    results.add(
                            pool.submit(
                                    () -> {
                                        start.await(5, TimeUnit.SECONDS);
                                        return TenantContext.callAsTenant(
                                                tenantId,
                                                tenant,
                                                () ->
                                                        mapper.saveCommitOptions(
                                                                id,
                                                                "{\"duplicateStrategy\":\""
                                                                        + strategy
                                                                        + "\"}",
                                                                "{\"wardIds\":[1]}"));
                                    }));
                }
                int first = results.get(0).get(10, TimeUnit.SECONDS);
                int second = results.get(1).get(10, TimeUnit.SECONDS);
                assertEquals(1, first + second);
                String expected = first == 1 ? "SKIP" : "UPDATE";
                TenantContext.runAsTenant(
                        tenantId,
                        tenant,
                        () -> {
                            assertTrue(
                                    jdbc.queryForObject(
                                                    "SELECT options_json FROM imp_job WHERE id=?",
                                                    String.class,
                                                    id)
                                            .contains(expected));
                            assertEquals(0, mapper.saveCommitOptions(id, "{}", "{}"));
                            assertTrue(
                                    jdbc.queryForObject(
                                                    "SELECT options_json FROM imp_job WHERE id=?",
                                                    String.class,
                                                    id)
                                            .contains(expected));
                        });
            } finally {
                pool.shutdownNow();
                TenantContext.runAsTenant(
                        tenantId,
                        tenant,
                        () ->
                                jdbc.update(
                                        "DELETE FROM imp_job WHERE id=? AND file_name=?",
                                        id,
                                        marker));
            }
        }
    }
}
