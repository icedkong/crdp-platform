package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;

import com.crdp.followup.record.app.*;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.datascope.DataScopeContext;
import com.crdp.framework.security.*;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OQR-01 / C-ACC-27：真实数据库与 HTTP、双租户、窗口 / 选择 / 并发 / 回滚及软删统计。只用虚构示例。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class BackfillTaskLinkIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoService crypto;
    @Autowired BackfillTaskLinkService links;
    @Autowired FollowupRecordService records;
    @Autowired RecordEntryService entry;
    @Autowired NodeOccurrenceMapper facts;
    @Autowired com.crdp.followup.stats.app.MonthlyDueService monthly;
    @Autowired SessionStore sessions;
    @Autowired TestRestTemplate rest;
    @Autowired PlatformTransactionManager transactions;
    private final String marker = "OQR01-" + UUID.randomUUID();
    private final LocalDate visit =
            LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).minusDays(10);
    private final Map<Long, List<Fixture>> fixtures = new HashMap<>();
    private final Map<Long, Map<String, Integer>> oldFeatures = new HashMap<>();
    private final List<String> tokens = new ArrayList<>();

    record Fixture(long patient, long implant, long plan, long node, long enrollment) {}

    private SessionPrincipal principal(long tenant, ScopeSnapshot scope, List<String> perms) {
        return new SessionPrincipal(
                UUID.randomUUID().toString(),
                "HOSPITAL",
                tenant,
                tenant == 1 ? "demoa" : "demob",
                "医院（示例）",
                1L,
                "user.example",
                "医生（示例）",
                List.of(),
                List.of(),
                perms,
                List.of("followup"),
                Long.MAX_VALUE,
                scope,
                false,
                null);
    }

    private <T> T tenant(long id, Supplier<T> action) {
        var p =
                principal(
                        id,
                        ScopeSnapshot.allowAll(),
                        List.of("followup:record:edit", "followup:task:view"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
        try {
            return TenantContext.callAsTenant(id, id == 1 ? "demoa" : "demob", action);
        } finally {
            DataScopeContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @BeforeEach
    void enable() {
        for (long id : List.of(1L, 2L))
            tenant(
                    id,
                    () -> {
                        Map<String, Integer> values = new HashMap<>();
                        for (String code : List.of("implant", "backfill_task_link")) {
                            values.put(
                                    code,
                                    jdbc.queryForObject(
                                            "SELECT enabled FROM cfg_feature WHERE feature_code=?",
                                            Integer.class,
                                            code));
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled=1 WHERE feature_code=?", code);
                        }
                        oldFeatures.put(id, values);
                        return null;
                    });
    }

    @AfterEach
    void cleanup() {
        for (var e : fixtures.entrySet())
            tenant(
                    e.getKey(),
                    () -> {
                        for (var f : e.getValue()) {
                            jdbc.update(
                                    "DELETE FROM fe_field_history WHERE record_type='FU_RECORD' AND record_id IN (SELECT id FROM fu_record WHERE patient_id=?)",
                                    f.patient());
                            jdbc.update("DELETE FROM fu_record WHERE patient_id=?", f.patient());
                            jdbc.update("DELETE FROM fu_task WHERE patient_id=?", f.patient());
                            jdbc.update(
                                    "DELETE FROM fu_enrollment WHERE patient_id=?", f.patient());
                            jdbc.update("DELETE FROM pt_lead WHERE patient_id=?", f.patient());
                            jdbc.update("DELETE FROM pt_implant WHERE patient_id=?", f.patient());
                            jdbc.update(
                                    "DELETE FROM pt_patient_summary WHERE patient_id=?",
                                    f.patient());
                            jdbc.update(
                                    "DELETE FROM pt_status_history WHERE patient_id=?",
                                    f.patient());
                            jdbc.update("DELETE FROM pt_patient WHERE id=?", f.patient());
                            jdbc.update("DELETE FROM fu_plan_node WHERE plan_id=?", f.plan());
                            jdbc.update("DELETE FROM fu_plan WHERE id=?", f.plan());
                        }
                        return null;
                    });
        for (var e : oldFeatures.entrySet())
            tenant(
                    e.getKey(),
                    () -> {
                        e.getValue()
                                .forEach(
                                        (k, v) ->
                                                jdbc.update(
                                                        "UPDATE cfg_feature SET enabled=? WHERE feature_code=?",
                                                        v,
                                                        k));
                        return null;
                    });
        tokens.forEach(sessions::delete);
    }

    private Fixture fixture(long tenant) {
        String key = marker + "-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO pt_patient(mrn_cipher,mrn_blind,name_cipher,name_blind,gender,birth_date,ward_id) VALUES (?,?,?,?,'F','1950-01-01',(SELECT MIN(id) FROM ward))",
                crypto.encrypt("pt-patient-mrn", key),
                crypto.blindIndex("pt-patient-mrn", key),
                crypto.encrypt("pt-patient-name", "患者（示例）"),
                crypto.blindIndex("pt-patient-name", "患者（示例）"));
        long patient =
                jdbc.queryForObject(
                        "SELECT id FROM pt_patient WHERE mrn_blind=?",
                        Long.class,
                        crypto.blindIndex("pt-patient-mrn", key));
        jdbc.update(
                "INSERT INTO pt_implant(patient_id,implant_type,implant_date,device_type) VALUES (?,'FIRST',?,'DDD')",
                patient,
                visit.minusYears(1));
        long implant =
                jdbc.queryForObject(
                        "SELECT id FROM pt_implant WHERE patient_id=?", Long.class, patient);
        jdbc.update(
                "INSERT INTO fu_plan(code,name,version,status,start_date_field,cycle_type) VALUES (?,'联动计划（示例）',1,'PUBLISHED','IMPLANT_DATE','ONGOING')",
                key);
        long plan = jdbc.queryForObject("SELECT id FROM fu_plan WHERE code=?", Long.class, key);
        jdbc.update(
                "INSERT INTO fu_plan_node(plan_id,node_type,name,basis,offset_value,offset_unit,task_type,form_code) VALUES (?,'NODE','窗口（示例）','PLAN_START',1,'YEAR','FORM','C_PROGRAM_FU')",
                plan);
        long node =
                jdbc.queryForObject(
                        "SELECT id FROM fu_plan_node WHERE plan_id=?", Long.class, plan);
        jdbc.update(
                "INSERT INTO fu_enrollment(patient_id,plan_id,plan_code,start_date) VALUES (?,?,?,?)",
                patient,
                plan,
                key,
                visit.minusYears(1));
        long enrollment =
                jdbc.queryForObject(
                        "SELECT id FROM fu_enrollment WHERE patient_id=?", Long.class, patient);
        var f = new Fixture(patient, implant, plan, node, enrollment);
        fixtures.computeIfAbsent(tenant, k -> new ArrayList<>()).add(f);
        return f;
    }

    private long task(Fixture f, int occurrence, LocalDate from, LocalDate to) {
        jdbc.update(
                "INSERT INTO fu_task(enrollment_id,patient_id,plan_id,node_id,occurrence,task_type,form_code,plan_date,remind_start,window_start,window_end,open_date,created_logical_date,status) VALUES (?,?,?,?,?,'FORM','C_PROGRAM_FU',?,?,?,?,?,?,'OVERDUE')",
                f.enrollment(),
                f.patient(),
                f.plan(),
                f.node(),
                occurrence,
                visit,
                from,
                from,
                to,
                from,
                from);
        return jdbc.queryForObject(
                "SELECT id FROM fu_task WHERE enrollment_id=? AND occurrence=?",
                Long.class,
                f.enrollment(),
                occurrence);
    }

    private long draft(Fixture f) {
        return records.createDraft(
                        new FollowupRecordService.CreateCommand(
                                f.patient(),
                                null,
                                "BACKFILL",
                                visit,
                                "C_PROGRAM_FU",
                                Map.of(),
                                Map.of("source", "BACKFILL")))
                .id();
    }

    private RecordEntryService.SubmitCommand command() {
        return new RecordEntryService.SubmitCommand(
                0, Map.of("f303", visit.toString(), "f304", 6), Map.of("source", "BACKFILL"), null);
    }

    private String taskStatus(long task) {
        return jdbc.queryForObject("SELECT status FROM fu_task WHERE id=?", String.class, task);
    }

    private long errorCode(Runnable action) {
        try {
            action.run();
            fail("应拒绝操作");
            return -1;
        } catch (ApiException rejected) {
            return rejected.code();
        }
    }

    @Test
    void uniqueCandidateCompletesWithinWindowAndRepeatsWithoutDuplicateSideEffectsInBothTenants() {
        for (long tenant : List.of(1L, 2L))
            tenant(
                    tenant,
                    () -> {
                        var f = fixture(tenant);
                        long task = task(f, 1, visit, visit);
                        long record = draft(f);
                        assertThat(links.candidates(record))
                                .extracting(BackfillTaskLinkService.CandidateView::taskId)
                                .containsExactly(String.valueOf(task));
                        long monthBefore =
                                monthly.count(
                                        visit.withDayOfMonth(1),
                                        visit.withDayOfMonth(visit.lengthOfMonth()),
                                        null);
                        var result = links.submit(record, command(), null);
                        assertThat(
                                        monthly.count(
                                                visit.withDayOfMonth(1),
                                                visit.withDayOfMonth(visit.lengthOfMonth()),
                                                null))
                                .isEqualTo(monthBefore);

                        assertThat(result.taskId()).isEqualTo(String.valueOf(task));
                        assertThat(result.completeKind()).isEqualTo("ON_TIME");
                        assertThat(taskStatus(task)).isEqualTo("COMPLETED");
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT task_id FROM fu_record WHERE id=?",
                                                Long.class,
                                                record))
                                .isEqualTo(task);
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT record_id FROM fu_task WHERE id=?",
                                                Long.class,
                                                task))
                                .isEqualTo(record);
                        var repeat = links.submit(record, command(), null);
                        assertThat(repeat).isEqualTo(result);
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT version FROM fu_record WHERE id=?",
                                                Integer.class,
                                                record))
                                .isEqualTo(1);
                        return null;
                    });
    }

    @Test
    void multipleCandidatesRequireSelectionAndDoNotOverwriteOtherTaskRecords() {
        tenant(
                1,
                () -> {
                    var f = fixture(1);
                    long a = task(f, 1, visit.minusDays(1), visit);
                    long b = task(f, 2, visit, visit.plusDays(1));
                    long record = draft(f);
                    var wrongDate =
                            new RecordEntryService.SubmitCommand(
                                    0,
                                    Map.of("f303", visit.minusDays(1).toString(), "f304", 6),
                                    null,
                                    null);
                    assertThat(errorCode(() -> links.submit(record, wrongDate, a)))
                            .isEqualTo(40001);
                    assertThat(errorCode(() -> links.submit(record, command(), null)))
                            .isEqualTo(40901);
                    assertThat(taskStatus(a)).isEqualTo("OVERDUE");
                    assertThat(records.record(record).status()).isEqualTo("DRAFT");
                    links.submit(record, command(), b);
                    assertThat(taskStatus(a)).isEqualTo("OVERDUE");
                    assertThat(taskStatus(b)).isEqualTo("COMPLETED");
                    long second = draft(f);
                    assertThat(links.candidates(second))
                            .extracting(BackfillTaskLinkService.CandidateView::taskId)
                            .containsExactly(String.valueOf(a));
                    assertThat(errorCode(() -> links.submit(second, command(), b)))
                            .isEqualTo(40901);
                    return null;
                });
    }

    @Test
    void disabledFeatureKeepsIndependentBackfillAndRejectsCandidateEndpoint() {
        tenant(
                1,
                () -> {
                    var f = fixture(1);
                    long task = task(f, 1, visit, visit);
                    long record = draft(f);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled=0 WHERE feature_code='backfill_task_link'");
                    assertThat(errorCode(() -> links.candidates(record))).isEqualTo(40301);
                    assertThat(links.submit(record, command(), null).taskId()).isNull();
                    assertThat(taskStatus(task)).isEqualTo("OVERDUE");
                    return null;
                });
    }

    @Test
    void historicalDeviceAndPausedEnrollmentCannotChangeCurrentSchedule() {
        tenant(
                1,
                () -> {
                    for (boolean historical : List.of(false, true)) {
                        var f = fixture(1);
                        long task = task(f, 1, visit, visit);
                        if (historical) {
                            jdbc.update(
                                    "UPDATE pt_implant SET status='REPLACED',end_date=? WHERE id=?",
                                    visit.plusDays(1),
                                    f.implant());
                            jdbc.update(
                                    "INSERT INTO pt_implant(patient_id,implant_type,implant_date,device_type) VALUES (?,'REPLACE',?,'DDD')",
                                    f.patient(),
                                    visit.plusDays(1));
                        } else
                            jdbc.update(
                                    "UPDATE fu_enrollment SET status='PAUSED' WHERE id=?",
                                    f.enrollment());
                        long record = draft(f);
                        assertThat(links.candidates(record)).isEmpty();
                        var cmd =
                                new RecordEntryService.SubmitCommand(
                                        0,
                                        Map.of("f303", visit.toString(), "f304", 6, "f323", 1),
                                        null,
                                        null);
                        assertThat(links.submit(record, cmd, null).taskId()).isNull();
                        assertThat(taskStatus(task)).isEqualTo("OVERDUE");
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT mode FROM fu_enrollment WHERE id=?",
                                                String.class,
                                                f.enrollment()))
                                .isEqualTo("NORMAL");
                    }
                    return null;
                });
    }

    @Test
    void wrongTypeOutsideWindowAndAlreadyLinkedTasksAreExcluded() {
        tenant(
                1,
                () -> {
                    var f = fixture(1);
                    long reminder = task(f, 1, visit, visit);
                    jdbc.update("UPDATE fu_task SET task_type='REMINDER' WHERE id=?", reminder);
                    long outside = task(f, 2, visit.plusDays(1), visit.plusDays(2));
                    long completed = task(f, 3, visit, visit);
                    jdbc.update("UPDATE fu_task SET status='COMPLETED' WHERE id=?", completed);
                    long temp = task(f, 4, visit, visit);
                    jdbc.update("UPDATE fu_task SET create_type='TEMP' WHERE id=?", temp);
                    long owned = task(f, 5, visit, visit);
                    long old = draft(f);
                    jdbc.update("UPDATE fu_record SET task_id=? WHERE id=?", owned, old);
                    jdbc.update("UPDATE fu_task SET record_id=? WHERE id=?", old, owned);
                    long record = draft(f);
                    assertThat(links.candidates(record)).isEmpty();
                    assertThat(links.submit(record, command(), null).taskId()).isNull();
                    assertThat(taskStatus(outside)).isEqualTo("OVERDUE");
                    return null;
                });
    }

    @Test
    void failureAfterSubmissionRollsBackBothLinksCompletionAndFirstSubmissionFact() {
        tenant(
                1,
                () -> {
                    var f = fixture(1);
                    long task = task(f, 1, visit, visit);
                    long record = draft(f);
                    assertThatThrownBy(
                                    () ->
                                            new TransactionTemplate(transactions)
                                                    .execute(
                                                            status -> {
                                                                links.submit(
                                                                        record, command(), task);
                                                                throw new IllegalStateException(
                                                                        "后续失败（示例）");
                                                            }))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(taskStatus(task)).isEqualTo("OVERDUE");
                    assertThat(records.record(record).status()).isEqualTo("DRAFT");
                    assertThat(records.record(record).taskId()).isNull();
                    assertThat(records.record(record).firstSubmittedAt()).isNull();
                    return null;
                });
    }

    @Test
    void competingRecordsCannotCompleteOrOverwriteTheSameTask() throws Exception {
        long[] ids =
                tenant(
                        1,
                        () -> {
                            var f = fixture(1);
                            return new long[] {task(f, 1, visit, visit), draft(f), draft(f)};
                        });
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (long record : List.of(ids[1], ids[2]))
                results.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    start.await();
                                    return tenant(
                                            1,
                                            () -> {
                                                try {
                                                    links.submit(record, command(), ids[0]);
                                                    return 0;
                                                } catch (ApiException failure) {
                                                    return failure.code();
                                                }
                                            });
                                }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(
                            List.of(
                                    results.get(0).get(20, TimeUnit.SECONDS),
                                    results.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 40901);
            tenant(
                    1,
                    () -> {
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM fu_record WHERE task_id=?",
                                                Integer.class,
                                                ids[0]))
                                .isEqualTo(1);
                        return null;
                    });
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingLinkedRecordKeepsTaskCompletedButExcludesItFromStatistics() {
        tenant(
                1,
                () -> {
                    var f = fixture(1);
                    long task = task(f, 1, visit, visit);
                    long record = draft(f);
                    links.submit(record, command(), task);
                    entry.deleteRecord(record, "更正重复记录（示例）");
                    assertThat(taskStatus(task)).isEqualTo("COMPLETED");
                    assertThat(
                                    facts.findRegularTasks().stream()
                                            .filter(t -> t.taskId() == task)
                                            .findFirst()
                                            .orElseThrow()
                                            .status())
                            .isEqualTo("RECORD_DELETED");
                    assertThat(facts.findFloatingBackfills())
                            .noneMatch(r -> r.recordId() == record);
                    assertThat(facts.findAnchoredRecords()).noneMatch(r -> r.recordId() == record);
                    return null;
                });
    }

    @Test
    void candidateHttpRequiresPermissionScopeAndFeature() {
        long record =
                tenant(
                        1,
                        () -> {
                            var f = fixture(1);
                            task(f, 1, visit, visit);
                            return draft(f);
                        });
        assertThat(httpCandidates(record, principal(1, ScopeSnapshot.allowAll(), List.of())))
                .isEqualTo(40300);
        assertThat(
                        httpCandidates(
                                record,
                                principal(
                                        1,
                                        ScopeSnapshot.restricted(List.of(), List.of(), false),
                                        List.of("followup:record:edit"))))
                .isEqualTo(40400);
        assertThat(
                        httpCandidates(
                                record,
                                principal(
                                        1,
                                        ScopeSnapshot.allowAll(),
                                        List.of("followup:record:edit"))))
                .isZero();
        tenant(
                1,
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled=0 WHERE feature_code='backfill_task_link'");
                    return null;
                });
        assertThat(
                        httpCandidates(
                                record,
                                principal(
                                        1,
                                        ScopeSnapshot.allowAll(),
                                        List.of("followup:record:edit"))))
                .isEqualTo(40301);
    }

    @Test
    void httpSubmissionUsesLinkingAndSameRequestRetryDoesNotCompleteTwice() {
        long[] ids =
                tenant(
                        1,
                        () -> {
                            var f = fixture(1);
                            return new long[] {task(f, 1, visit, visit), draft(f)};
                        });
        var p = principal(1, ScopeSnapshot.allowAll(), List.of("followup:record:edit"));
        sessions.save(p);
        tokens.add(p.token());
        var headers = new HttpHeaders();
        headers.setBearerAuth(p.token());
        Map<String, Object> body =
                Map.of(
                        "baseVersion",
                        0,
                        "data",
                        Map.of("f303", visit.toString(), "f304", 6),
                        "backfillTaskId",
                        String.valueOf(ids[0]));
        for (int attempt = 0; attempt < 2; attempt++) {
            var result =
                    rest.exchange(
                                    "/api/hospital/followup/records/" + ids[1] + "/submit",
                                    HttpMethod.POST,
                                    new HttpEntity<>(body, headers),
                                    Map.class)
                            .getBody();
            assertThat(result).isNotNull();
            assertThat(((Number) result.get("code")).intValue()).isZero();
            assertThat(((Map<?, ?>) result.get("data")).get("taskId"))
                    .isEqualTo(String.valueOf(ids[0]));
        }
        tenant(
                1,
                () -> {
                    assertThat(taskStatus(ids[0])).isEqualTo("COMPLETED");
                    return null;
                });
    }

    private int httpCandidates(long id, SessionPrincipal principal) {
        sessions.save(principal);
        tokens.add(principal.token());
        var headers = new HttpHeaders();
        headers.setBearerAuth(principal.token());
        Map<?, ?> result =
                rest.exchange(
                                "/api/hospital/followup/records/" + id + "/backfill-candidates",
                                HttpMethod.GET,
                                new HttpEntity<>(headers),
                                Map.class)
                        .getBody();
        return ((Number) result.get("code")).intValue();
    }
}
