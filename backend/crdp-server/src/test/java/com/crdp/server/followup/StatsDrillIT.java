package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;

import com.crdp.followup.stats.app.*;
import com.crdp.followup.stats.infra.mapper.NodeOccurrenceMapper;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.datascope.DataScopeContext;
import com.crdp.framework.security.*;
import com.crdp.framework.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
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

/** OQR-05B：月度虚拟次数、同口径钻取、快照刷新、权限 / 范围与双租户；只用虚构数据。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class StatsDrillIT {
    private static final long WARD = 988888888L;
    private final String marker = "OQR05B-" + UUID.randomUUID();
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoService crypto;
    @Autowired ObjectMapper json;
    @Autowired MonthlyDueService monthly;
    @Autowired StatsDrillService drills;
    @Autowired NodeOccurrenceStatsService rates;
    @Autowired NodeOccurrenceMapper occurrenceMapper;
    @Autowired CompletenessStatsService completeness;
    @Autowired SessionStore sessions;
    @Autowired TestRestTemplate rest;
    @Autowired PlatformTransactionManager transactions;
    private final Map<Long, List<Fixture>> fixtures = new HashMap<>();
    private final Map<Long, Integer> featureBefore = new HashMap<>();
    private final Map<Long, List<Map<String, Object>>> refreshBefore = new HashMap<>();
    private final List<String> tokens = new ArrayList<>();

    record Fixture(
            long patient, long plan, long parent, long node, long enrollment, LocalDate start) {}

    private SessionPrincipal principal(long tenant, ScopeSnapshot scope, List<String> permissions) {
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
                permissions,
                List.of("followup"),
                Long.MAX_VALUE,
                scope,
                false,
                null);
    }

    private List<String> permissions() {
        return List.of("followup:dashboard:view", "followup:patient:view");
    }

    private <T> T tenant(long id, Supplier<T> work) {
        var p =
                principal(
                        id,
                        ScopeSnapshot.restricted(List.of(WARD), List.of(), false),
                        permissions());
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(p, null, List.of()));
        try {
            return TenantContext.callAsTenant(id, id == 1 ? "demoa" : "demob", work);
        } finally {
            DataScopeContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    @BeforeEach
    void prepare() {
        for (long tenant : List.of(1L, 2L))
            tenant(
                    tenant,
                    () -> {
                        featureBefore.put(
                                tenant,
                                jdbc.queryForObject(
                                        "SELECT enabled FROM cfg_feature WHERE feature_code='node_follow_rate'",
                                        Integer.class));
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled=1 WHERE feature_code='node_follow_rate'");
                        refreshBefore.put(
                                tenant,
                                jdbc.queryForList(
                                        "SELECT * FROM st_refresh_state WHERE code='node_occurrence'"));
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
                                    "DELETE FROM st_node_occurrence WHERE patient_id=?",
                                    f.patient());
                            jdbc.update("DELETE FROM fu_record WHERE patient_id=?", f.patient());
                            jdbc.update("DELETE FROM fu_task WHERE patient_id=?", f.patient());
                            jdbc.update(
                                    "DELETE FROM fu_enrollment WHERE patient_id=?", f.patient());
                            jdbc.update("DELETE FROM pt_patient WHERE id=?", f.patient());
                            jdbc.update("DELETE FROM fu_plan_node WHERE plan_id=?", f.plan());
                            jdbc.update("DELETE FROM fu_plan WHERE id=?", f.plan());
                        }
                        return null;
                    });
        for (long id : featureBefore.keySet())
            tenant(
                    id,
                    () -> {
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled=? WHERE feature_code='node_follow_rate'",
                                featureBefore.get(id));
                        var old = refreshBefore.get(id);
                        if (old.isEmpty())
                            jdbc.update(
                                    "DELETE FROM st_refresh_state WHERE code='node_occurrence'");
                        else
                            jdbc.update(
                                    "INSERT INTO st_refresh_state(code,logical_date,refreshed_at) VALUES ('node_occurrence',?,?) ON DUPLICATE KEY UPDATE logical_date=VALUES(logical_date),refreshed_at=VALUES(refreshed_at)",
                                    old.getFirst().get("logical_date"),
                                    old.getFirst().get("refreshed_at"));
                        return null;
                    });
        tokens.forEach(sessions::delete);
    }

    private Fixture fixture(long tenant, LocalDate start) {
        String key = marker + "-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update(
                "INSERT INTO pt_patient(mrn_cipher,mrn_blind,name_cipher,name_blind,gender,ward_id,surgery_date,discharge_date,responsible_user_id) VALUES (?,?,?,?,'F',?,?,?,1)",
                crypto.encrypt("pt-patient-mrn", key),
                crypto.blindIndex("pt-patient-mrn", key),
                crypto.encrypt("pt-patient-name", "随访患者（示例）"),
                crypto.blindIndex("pt-patient-name", "随访患者（示例）"),
                WARD,
                start,
                start);
        long patient =
                jdbc.queryForObject(
                        "SELECT id FROM pt_patient WHERE mrn_blind=?",
                        Long.class,
                        crypto.blindIndex("pt-patient-mrn", key));
        jdbc.update(
                "INSERT INTO fu_plan(code,name,version,status,start_date_field,cycle_type) VALUES (?,'年度统计（示例）',1,'PUBLISHED','SURGERY_DATE','ONGOING')",
                key);
        long plan = jdbc.queryForObject("SELECT id FROM fu_plan WHERE code=?", Long.class, key);
        jdbc.update(
                "INSERT INTO fu_plan_node(plan_id,node_type,name,repeat_mode,cycle_value,cycle_unit) VALUES (?,'SUBPLAN','年度（示例）','CYCLE',1,'YEAR')",
                plan);
        long parent =
                jdbc.queryForObject(
                        "SELECT id FROM fu_plan_node WHERE plan_id=?", Long.class, plan);
        jdbc.update(
                "INSERT INTO fu_plan_node(plan_id,parent_id,node_type,name,basis,offset_value,offset_unit,task_type,form_code) VALUES (?,?,'NODE','年度记录（示例）','PLAN_START',1,'YEAR','FORM','A_ANNUAL_FU')",
                plan,
                parent);
        long node =
                jdbc.queryForObject(
                        "SELECT id FROM fu_plan_node WHERE plan_id=? AND parent_id=?",
                        Long.class,
                        plan,
                        parent);
        jdbc.update(
                "INSERT INTO fu_enrollment(patient_id,plan_id,plan_code,start_date,created_at) VALUES (?,?,?,?,?)",
                patient,
                plan,
                key,
                start,
                start.atStartOfDay());
        long enrollment =
                jdbc.queryForObject(
                        "SELECT id FROM fu_enrollment WHERE patient_id=?", Long.class, patient);
        var f = new Fixture(patient, plan, parent, node, enrollment, start);
        fixtures.computeIfAbsent(tenant, k -> new ArrayList<>()).add(f);
        return f;
    }

    private long task(Fixture f, int occurrence, LocalDate date, int seq, String type) {
        jdbc.update(
                "INSERT INTO fu_task(enrollment_id,patient_id,plan_id,node_id,occurrence,followup_seq,task_type,form_code,plan_date,remind_start,window_start,window_end,open_date,created_logical_date,status,create_type) VALUES (?,?,?,?,?,?,'FORM','A_ANNUAL_FU',?,?,?,?,?,?,'PENDING',?)",
                f.enrollment(),
                f.patient(),
                f.plan(),
                f.node(),
                occurrence,
                seq,
                date,
                date,
                date,
                date,
                date,
                date,
                type);
        return jdbc.queryForObject(
                "SELECT id FROM fu_task WHERE enrollment_id=? AND node_id=? AND occurrence=? AND followup_seq=?",
                Long.class,
                f.enrollment(),
                f.node(),
                occurrence,
                seq);
    }

    private StatsDrillService.Query query(Map<String, Object> data) {
        return json.convertValue(data, StatsDrillService.Query.class);
    }

    private StatsDrillService.Page month(LocalDate from, LocalDate to) {
        return drills.search(
                query(
                        Map.of(
                                "kind",
                                "MONTH",
                                "from",
                                from.toString(),
                                "to",
                                to.toString(),
                                "wardId",
                                WARD)));
    }

    private void fact(Fixture f, long enrollment, int year, boolean followed) {
        jdbc.update(
                "INSERT INTO st_node_occurrence(patient_id,enrollment_id,plan_id,plan_code,plan_version,node_id,occurrence,followup_year,plan_date,window_start,window_end,cohort_year,due,due_reason,followed,in_window,refreshed_date) VALUES (?,?,?,(SELECT code FROM fu_plan WHERE id=?),1,?,?,?,?,?,?,?,1,'DUE',?,?,'2026-11-18')",
                f.patient(),
                enrollment,
                f.plan(),
                f.plan(),
                f.node(),
                year,
                year,
                f.start().plusYears(year),
                f.start().plusYears(year),
                f.start().plusYears(year),
                f.start().getYear(),
                followed,
                followed);
    }

    @Test
    void leapMonthIncludesFutureUnmaterializedOccurrencesAndNeverWritesTasksInBothTenants() {
        for (long tenant : List.of(1L, 2L))
            tenant(
                    tenant,
                    () -> {
                        fixture(tenant, LocalDate.of(2024, 2, 29));
                        var from = LocalDate.of(2028, 2, 1);
                        var to = LocalDate.of(2028, 2, 29);
                        assertThat(monthly.count(from, to, WARD)).isEqualTo(1);
                        var page = month(from, to);
                        assertThat(page.total()).isEqualTo(1);
                        assertThat(page.items().getFirst().contexts().getFirst().virtual())
                                .isTrue();
                        assertThat(page.items().getFirst().contexts().getFirst().planDate())
                                .isEqualTo(to);
                        assertThat(
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM fu_task WHERE patient_id=?",
                                                Long.class,
                                                fixtures.get(tenant).getFirst().patient()))
                                .isZero();
                        return null;
                    });
    }

    @Test
    void existingTasksWinDeduplicationAndMovedDatesDoNotProducePhantomVirtualCounts() {
        tenant(
                1,
                () -> {
                    var f = fixture(1, LocalDate.of(2024, 2, 29));
                    var from = LocalDate.of(2028, 2, 1);
                    var to = from.withDayOfMonth(29);
                    long actual = task(f, 4, to, 0, "NORMAL");
                    task(f, -1, to, 0, "TEMP");
                    task(f, 4, to, 1, "FOLLOWUP");
                    assertThat(monthly.count(from, to, WARD)).isEqualTo(1);
                    assertThat(month(from, to).items().getFirst().contexts().getFirst().virtual())
                            .isFalse();
                    jdbc.update("UPDATE fu_task SET plan_date='2028-03-01' WHERE id=?", actual);
                    assertThat(monthly.count(from, to, WARD)).isZero();
                    assertThat(
                                    monthly.count(
                                            LocalDate.of(2028, 3, 1),
                                            LocalDate.of(2028, 3, 31),
                                            WARD))
                            .isEqualTo(1);
                    return null;
                });
    }

    @Test
    void pausedCountsButRemovedSwitchedAndDeadDoNotProjectNewOccurrences() {
        tenant(
                1,
                () -> {
                    var f = fixture(1, LocalDate.of(2024, 2, 29));
                    var from = LocalDate.of(2028, 2, 1);
                    var to = from.withDayOfMonth(29);
                    jdbc.update(
                            "UPDATE fu_enrollment SET status='PAUSED' WHERE id=?", f.enrollment());
                    assertThat(monthly.count(from, to, WARD)).isEqualTo(1);
                    jdbc.update("UPDATE fu_plan SET status='DISABLED' WHERE id=?", f.plan());
                    assertThat(monthly.count(from, to, WARD)).isEqualTo(1);
                    for (String status : List.of("REMOVED", "SWITCHED")) {
                        jdbc.update(
                                "UPDATE fu_enrollment SET status=? WHERE id=?",
                                status,
                                f.enrollment());
                        assertThat(monthly.count(from, to, WARD)).isZero();
                    }
                    jdbc.update(
                            "UPDATE fu_enrollment SET status='ACTIVE' WHERE id=?", f.enrollment());
                    jdbc.update(
                            "UPDATE pt_patient SET followup_status='DEAD' WHERE id=?", f.patient());
                    assertThat(monthly.count(from, to, WARD)).isZero();
                    return null;
                });
    }

    @Test
    void yearlyDrillDeduplicatesPatientsWhileNodeDrillPreservesEnrollmentUnits() {
        tenant(
                1,
                () -> {
                    var f = fixture(1, LocalDate.of(2024, 2, 29));
                    jdbc.update(
                            "INSERT INTO fu_enrollment(patient_id,plan_id,plan_code,start_date,status) VALUES (?,?,(SELECT code FROM fu_plan WHERE id=?),?,'REMOVED')",
                            f.patient(),
                            f.plan(),
                            f.plan(),
                            f.start());
                    long old =
                            jdbc.queryForObject(
                                    "SELECT MAX(id) FROM fu_enrollment WHERE patient_id=?",
                                    Long.class,
                                    f.patient());
                    fact(f, f.enrollment(), 1, true);
                    fact(f, old, 1, true);
                    var year =
                            drills.search(
                                    query(
                                            Map.of(
                                                    "kind",
                                                    "YEAR",
                                                    "year",
                                                    1,
                                                    "wardId",
                                                    WARD,
                                                    "metric",
                                                    "FOLLOWED")));
                    assertThat(year.total())
                            .isEqualTo(
                                    rates.yearlyRate(WARD, null).rows().getFirst().followedCount())
                            .isEqualTo(1);
                    assertThat(year.items().getFirst().contexts()).hasSize(2);
                    var node =
                            drills.search(
                                    query(
                                            Map.of(
                                                    "kind",
                                                    "NODE",
                                                    "planId",
                                                    f.plan(),
                                                    "nodeId",
                                                    f.node(),
                                                    "occurrence",
                                                    1,
                                                    "wardId",
                                                    WARD,
                                                    "metric",
                                                    "FOLLOWED")));
                    assertThat(node.total())
                            .isEqualTo(
                                    rates.nodeRate(f.plan(), WARD, null)
                                            .rows()
                                            .getFirst()
                                            .followedCount())
                            .isEqualTo(2);
                    assertThat(node.items())
                            .allSatisfy(i -> assertThat(i.name()).doesNotContain("随访患者（示例）"));
                    return null;
                });
    }

    @Test
    void missingFollowupDrillShowsEachAnnualRecordAndKeepsUnanchoredBackfillExcluded() {
        tenant(
                1,
                () -> {
                    var f = fixture(1, LocalDate.of(2020, 1, 1));
                    for (int year : List.of(1, 2)) {
                        long task = task(f, year, f.start().plusYears(year), 0, "NORMAL");
                        jdbc.update(
                                "INSERT INTO fu_record(patient_id,task_id,source,form_code,template_version,status,data,data_meta) VALUES (?,?,'TASK','A_ANNUAL_FU',1,'SUBMITTED','{}','{}')",
                                f.patient(),
                                task);
                    }
                    jdbc.update(
                            "INSERT INTO fu_record(patient_id,source,visit_date,form_code,template_version,status,data) VALUES (?,'BACKFILL','2021-01-01','A_ANNUAL_FU',1,'SUBMITTED','{}')",
                            f.patient());
                    var card =
                            completeness.followupCard(
                                    new CompletenessStatsService.Query(
                                            WARD, null, null, null, null, null));
                    assertThat(card.recordCount()).isEqualTo(2);
                    var missing = card.topMissing().getFirst();
                    var page =
                            drills.search(
                                    query(
                                            Map.of(
                                                    "kind",
                                                    "FOLLOWUP_MISSING",
                                                    "fieldKey",
                                                    missing.fieldKey(),
                                                    "wardId",
                                                    WARD)));
                    assertThat(page.total()).isEqualTo(missing.missingCount()).isEqualTo(2);
                    var all = drills.search(query(Map.of("kind", "FOLLOWUP_ALL", "wardId", WARD)));
                    assertThat(all.total()).isEqualTo(card.recordCount());
                    var singleYear =
                            drills.search(
                                    query(
                                            Map.of(
                                                    "kind",
                                                    "FOLLOWUP_ALL",
                                                    "wardId",
                                                    WARD,
                                                    "year",
                                                    1)));
                    assertThat(singleYear.total()).isEqualTo(1);

                    assertThat(page.items())
                            .flatExtracting(StatsDrillService.Item::contexts)
                            .extracting(StatsDrillService.Context::year)
                            .containsExactlyInAnyOrder(1, 2);
                    assertThat(page.items())
                            .flatExtracting(StatsDrillService.Item::contexts)
                            .allSatisfy(c -> assertThat(c.recordId()).isNotNull());
                    return null;
                });
    }

    @Test
    void actualRefreshTimeIsTransactionalAndLegacyTimeIsNeverInvented() {
        tenant(
                1,
                () -> {
                    jdbc.update("DELETE FROM st_refresh_state WHERE code='node_occurrence'");
                    var f = fixture(1, LocalDate.of(2024, 1, 1));
                    fact(f, f.enrollment(), 1, false);
                    assertThat(rates.yearlyRate(WARD, null).refreshedAt()).isNull();
                    assertThatThrownBy(
                                    () ->
                                            new TransactionTemplate(transactions)
                                                    .execute(
                                                            s -> {
                                                                occurrenceMapper.markRefresh(
                                                                        LocalDate.of(2026, 11, 19));
                                                                throw new IllegalStateException(
                                                                        "失败（示例）");
                                                            }))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(occurrenceMapper.lastRefresh()).isNull();
                    occurrenceMapper.markRefresh(LocalDate.of(2026, 11, 19));
                    assertThat(rates.yearlyRate(WARD, null).refreshedAt()).endsWith("+08:00");
                    return null;
                });
    }

    @Test
    void httpEnforcesPermissionsScopeFeatureAndPaginationWithoutCachingOldPatientSets() {
        long[] ids =
                tenant(
                        1,
                        () -> {
                            var f = fixture(1, LocalDate.of(2024, 1, 1));
                            fact(f, f.enrollment(), 1, true);
                            return new long[] {f.plan(), f.node()};
                        });
        var body =
                Map.<String, Object>of(
                        "kind",
                        "NODE",
                        "planId",
                        ids[0],
                        "nodeId",
                        ids[1],
                        "occurrence",
                        1,
                        "wardId",
                        WARD);
        var full =
                principal(
                        1,
                        ScopeSnapshot.restricted(List.of(WARD), List.of(), false),
                        permissions());
        assertThat(
                        code(
                                http(
                                        body,
                                        principal(
                                                1,
                                                ScopeSnapshot.allowAll(),
                                                List.of("followup:dashboard:view")))))
                .isEqualTo(40300);
        assertThat(
                        code(
                                http(
                                        body,
                                        principal(
                                                1,
                                                ScopeSnapshot.allowAll(),
                                                List.of("followup:patient:view")))))
                .isEqualTo(40300);
        var response = http(body, full);
        assertThat(code(response)).isZero();
        assertThat(((Number) ((Map<?, ?>) response.get("data")).get("total")).longValue())
                .isEqualTo(1);
        var empty =
                principal(1, ScopeSnapshot.restricted(List.of(), List.of(), false), permissions())
                        .withToken(full.token());
        sessions.save(empty);
        var refreshed = http(body, empty);
        assertThat(((Number) ((Map<?, ?>) refreshed.get("data")).get("total")).longValue())
                .isZero();
        var invalid = new HashMap<>(body);
        invalid.put("pageSize", 101);
        assertThat(code(http(invalid, full))).isEqualTo(40001);
        tenant(
                1,
                () -> {
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled=0 WHERE feature_code='node_follow_rate'");
                    return null;
                });
        assertThat(code(http(body, full))).isEqualTo(40301);
        assertThat(code(http(body, principal(2, ScopeSnapshot.allowAll(), permissions()))))
                .isZero();
    }

    @Test
    void expectedCountChangeIsExplicitAndDoctorGroupingUsesTheSameMonthlySet() {
        tenant(
                1,
                () -> {
                    fixture(1, LocalDate.of(2024, 2, 29));
                    var from = LocalDate.of(2028, 2, 1);
                    var to = from.withDayOfMonth(29);
                    var page =
                            drills.search(
                                    query(
                                            Map.of(
                                                    "kind",
                                                    "MONTH",
                                                    "from",
                                                    from.toString(),
                                                    "to",
                                                    to.toString(),
                                                    "wardId",
                                                    WARD,
                                                    "doctorId",
                                                    1,
                                                    "expectedCount",
                                                    99)));
                    assertThat(page.changed()).isTrue();
                    assertThat(page.total())
                            .isEqualTo(monthly.byDoctor(from, to, WARD).get(1L))
                            .isEqualTo(1);
                    return null;
                });
    }

    private int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private Map<?, ?> http(Map<String, Object> body, SessionPrincipal principal) {
        sessions.save(principal);
        tokens.add(principal.token());
        var headers = new HttpHeaders();
        headers.setBearerAuth(principal.token());
        return rest.exchange(
                        "/api/hospital/followup/stats/drill",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers),
                        Map.class)
                .getBody();
    }
}
