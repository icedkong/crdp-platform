package com.crdp.followup.importer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.CrdpApplication;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;

/** OQR-03：真实数据库互斥、释放、失败原子性与迁移前置校验；全为虚构作业，无患者写入。 */
@ActiveProfiles("local")
@SpringBootTest(
        classes = CrdpApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ImportActiveSlotMySqlIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ImportStagingService staging;
    @Autowired private ImportStagingMapper mapper;
    @Autowired private ImportCommitService commit;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private final String marker = "OQR03（示例）-" + UUID.randomUUID();
    private final List<Ref> created = new ArrayList<>();

    private record Ref(long tenant, long job) {}

    private record Result(Long id, int code) {}

    private <T> T tenant(long id, Supplier<T> action) {
        return TenantContext.callAsTenant(id, id == 1 ? "demoa" : "demob", action);
    }

    private long seed(long tenantId, String status) {
        return tenant(
                tenantId,
                () -> {
                    jdbc.update(
                            "INSERT INTO imp_job(file_name,file_size,status,operator_id,operator_name) VALUES (?,0,?,1,'互斥验证（示例）')",
                            marker,
                            status);
                    return jdbc.queryForObject(
                            "SELECT MAX(id) FROM imp_job WHERE file_name=?", Long.class, marker);
                });
    }

    private void track(long tenantId, long job) {
        created.add(new Ref(tenantId, job));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (Ref ref : created)
            tenant(
                    ref.tenant(),
                    () -> {
                        jdbc.update("DELETE FROM imp_batch WHERE job_id=?", ref.job());
                        jdbc.update(
                                "DELETE FROM imp_job WHERE id=? AND file_name=?",
                                ref.job(),
                                marker);
                        return null;
                    });
        // createJob 的成功 ID 由并发工作线程返回后登记，不在线程间共享可变集合。
        for (long id : List.of(1L, 2L))
            tenant(
                    id,
                    () -> {
                        jdbc.update("DELETE FROM imp_job WHERE file_name=?", marker);
                        return null;
                    });
    }

    private List<Result> race(Callable<Result> first, Callable<Result> second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                return first.call();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await(10, TimeUnit.SECONDS);
                                return second.call();
                            });
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private Result create(long tenantId) {
        try {
            return new Result(
                    tenant(
                            tenantId,
                            () ->
                                    staging.createJob(
                                            new ImportStagingService.NewImportFile(marker, 0, null),
                                            1,
                                            "互斥验证（示例）")),
                    0);
        } catch (ApiException e) {
            return new Result(null, e.code());
        }
    }

    private Result claim(long tenantId, long job, boolean retry) {
        try {
            tenant(
                    tenantId,
                    () -> {
                        if (retry) commit.prepareRetry(job);
                        else
                            commit.prepare(
                                    job, new ImportCommitService.ConfirmOptions("SKIP", null));
                        return null;
                    });
            return new Result(job, 0);
        } catch (ApiException e) {
            return new Result(job, e.code());
        }
    }

    @Test
    void concurrentCreationHasOneWinnerInEachTenant() throws Exception {
        for (long id : List.of(1L, 2L)) {
            var results = race(() -> create(id), () -> create(id));
            assertThat(results).extracting(Result::code).containsExactlyInAnyOrder(0, 40901);
            tenant(
                    id,
                    () -> {
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM imp_job WHERE active_slot=1",
                                                Long.class))
                                .isEqualTo(1L);
                        jdbc.update(
                                "UPDATE imp_job SET status='CANCELLED' WHERE file_name=?", marker);
                        return null;
                    });
        }
    }

    @Test
    void differentPrecheckedJobsCompeteAndLoserKeepsOptionsAndBatches() throws Exception {
        for (long id : List.of(1L, 2L)) {
            long a = seed(id, "PRECHECKED");
            track(id, a);
            long b = seed(id, "PRECHECKED");
            track(id, b);
            var results = race(() -> claim(id, a, false), () -> claim(id, b, false));
            assertThat(results).extracting(Result::code).containsExactlyInAnyOrder(0, 40901);
            long loser =
                    results.stream().filter(r -> r.code() == 40901).findFirst().orElseThrow().id();
            tenant(
                    id,
                    () -> {
                        assertThat(mapper.findJob(loser).status()).isEqualTo("PRECHECKED");
                        assertThat(mapper.findJob(loser).optionsJson()).isNull();
                        assertThat(mapper.findJob(loser).scopeJson()).isNull();
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM imp_batch WHERE job_id=?",
                                                Long.class,
                                                loser))
                                .isZero();
                        // 完成释放槽，原失败方无需重建作业即可重新争用。
                        jdbc.update(
                                "UPDATE imp_job SET status='DONE' WHERE file_name=? AND active_slot=1",
                                marker);
                        return null;
                    });
            assertThat(claim(id, loser, false).code()).isZero();
            tenant(
                    id,
                    () -> {
                        jdbc.update("UPDATE imp_job SET status='DONE' WHERE file_name=?", marker);
                        return null;
                    });
        }
    }

    @Test
    void creationAndConfirmationShareTheSameDatabaseSlot() throws Exception {
        long existing = seed(1, "PRECHECKED");
        track(1, existing);
        var results = race(() -> create(1), () -> claim(1, existing, false));
        assertThat(results).extracting(Result::code).containsExactlyInAnyOrder(0, 40901);
        tenant(
                1,
                () -> {
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM imp_job WHERE active_slot=1",
                                            Long.class))
                            .isEqualTo(1L);
                    return null;
                });
    }

    @Test
    void duplicateRetryOfOneJobHasOneWinner() throws Exception {
        long job = seed(1, "PARTIAL_FAILED");
        track(1, job);
        var results = race(() -> claim(1, job, true), () -> claim(1, job, true));
        assertThat(results).extracting(Result::code).containsExactlyInAnyOrder(0, 40901);
    }

    @Test
    void retryCompetesWithConfirmationAndFailedRetryRemainsPartialFailed() throws Exception {
        long retry = seed(1, "PARTIAL_FAILED");
        track(1, retry);
        long confirm = seed(1, "PRECHECKED");
        track(1, confirm);
        var results = race(() -> claim(1, retry, true), () -> claim(1, confirm, false));
        assertThat(results).extracting(Result::code).containsExactlyInAnyOrder(0, 40901);
        tenant(
                1,
                () -> {
                    String expected =
                            results.getFirst().code() == 0 ? "IMPORTING" : "PARTIAL_FAILED";
                    assertThat(mapper.findJob(retry).status()).isEqualTo(expected);
                    return null;
                });
    }

    @Test
    void activeStatesKeepSlotAndEveryExitReleasesItWithoutManualUnlock() {
        for (long id : List.of(1L, 2L)) {
            long job = seed(id, "UPLOADED");
            track(id, job);
            tenant(
                    id,
                    () -> {
                        assertThat(mapper.casStatus(job, "UPLOADED", "PRECHECKING")).isEqualTo(1);
                        assertThat(create(id).code()).isEqualTo(40901);
                        assertThat(mapper.finishPrecheck(job, "LONG", 0)).isEqualTo(1);
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT active_slot FROM imp_job WHERE id=?",
                                                Integer.class,
                                                job))
                                .isNull();
                        return null;
                    });
            for (String exit :
                    List.of(
                            "PRECHECK_FAILED",
                            "PRECHECKED",
                            "DONE",
                            "PARTIAL_FAILED",
                            "CANCELLED",
                            "ROLLED_BACK")) {
                long other = seed(id, "UPLOADED");
                track(id, other);
                tenant(
                        id,
                        () -> {
                            assertThat(mapper.casStatus(other, "UPLOADED", exit)).isEqualTo(1);
                            return null;
                        });
            }
        }
    }

    @Test
    void tenantsCanHoldIndependentActiveSlotsAtTheSameTime() throws Exception {
        var results = race(() -> create(1), () -> create(2));
        assertThat(results).extracting(Result::code).containsOnly(0).hasSize(2);
    }

    @Test
    void rolledBackConfirmationRestoresPrecheckedStateAndReleasesSlot() {
        long job = seed(1, "PRECHECKED");
        track(1, job);
        tenant(
                1,
                () -> {
                    var tx =
                            new org.springframework.transaction.support.TransactionTemplate(
                                    transactionManager);
                    assertThatThrownBy(
                                    () ->
                                            tx.executeWithoutResult(
                                                    status -> {
                                                        commit.prepare(
                                                                job,
                                                                new ImportCommitService
                                                                        .ConfirmOptions(
                                                                        "UPDATE", null));
                                                        throw new IllegalStateException(
                                                                "模拟确认事务失败（示例）");
                                                    }))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(mapper.findJob(job).status()).isEqualTo("PRECHECKED");
                    assertThat(mapper.findJob(job).optionsJson()).isNull();
                    assertThat(mapper.findJob(job).scopeJson()).isNull();
                    assertThat(create(1).code()).isZero();
                    return null;
                });
    }

    @Test
    void migrationRejectsLegacyConflictsWithoutChangingJobs() {
        tenant(
                1,
                () ->
                        jdbc.execute(
                                (ConnectionCallback<Void>)
                                        connection -> {
                                            var migration =
                                                    new ClassPathResource(
                                                            "db/tenant/followup/V140__imp_job_active_slot.sql");
                                            try (var statement = connection.createStatement()) {
                                                // 同连接临时表遮蔽真实 imp_job；原样执行迁移，不修改真实作业。
                                                statement.execute(
                                                        "CREATE TEMPORARY TABLE imp_job(id BIGINT PRIMARY KEY,status VARCHAR(24))");
                                                statement.execute(
                                                        "INSERT INTO imp_job VALUES(1,'UPLOADED'),(2,'IMPORTING')");
                                                assertThatThrownBy(
                                                                () ->
                                                                        ScriptUtils
                                                                                .executeSqlScript(
                                                                                        connection,
                                                                                        migration))
                                                        .hasStackTraceContaining(
                                                                "ck_imp_job_resolve_active_conflicts");
                                                try (var rows =
                                                        statement.executeQuery(
                                                                "SELECT COUNT(*) FROM imp_job WHERE status IN ('UPLOADED','IMPORTING')")) {
                                                    assertThat(rows.next()).isTrue();
                                                    assertThat(rows.getInt(1)).isEqualTo(2);
                                                }
                                                // 只修正临时夹具，验证相同迁移可重试，成功后真实唯一索引生效。
                                                statement.execute(
                                                        "UPDATE imp_job SET status='PRECHECKED' WHERE id=2");
                                                ScriptUtils.executeSqlScript(connection, migration);
                                                assertThatThrownBy(
                                                                () ->
                                                                        statement.execute(
                                                                                "UPDATE imp_job SET status='IMPORTING' WHERE id=2"))
                                                        .hasMessageContaining(
                                                                "uk_imp_job_active_slot");
                                            } finally {
                                                try (var cleanup = connection.createStatement()) {
                                                    cleanup.execute(
                                                            "DROP TEMPORARY TABLE IF EXISTS crdp_imp_active_migration_guard");
                                                    cleanup.execute(
                                                            "DROP TEMPORARY TABLE IF EXISTS imp_job");
                                                }
                                            }
                                            return null;
                                        }));
    }
}
