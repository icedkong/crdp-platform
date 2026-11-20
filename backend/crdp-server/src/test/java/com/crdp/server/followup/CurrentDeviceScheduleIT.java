package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;

import com.crdp.followup.plan.app.*;
import com.crdp.followup.plan.infra.mapper.*;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OQR-06：真实租户数据库的派生值、次数绑定、保护和事务回滚；所有数据仅为示例并整体回滚。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class CurrentDeviceScheduleIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoService crypto;
    @Autowired CurrentDeviceScheduleService service;
    @Autowired CurrentDeviceScheduleMapper mapper;
    @Autowired PlanMapper plans;
    @Autowired PlanSwitchService switches;
    @Autowired FollowupTaskCreationService creation;
    @Autowired PlatformTransactionManager transactions;
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    record Fixture(
            long patient, long device, long plan, long node, long enrollment, LocalDate start) {}

    private <T> T tenant(long id, Supplier<T> work) {
        return TenantContext.callAsTenant(
                id,
                id == 1 ? "demoa" : "demob",
                () ->
                        new TransactionTemplate(transactions)
                                .execute(
                                        tx -> {
                                            try {
                                                return work.get();
                                            } finally {
                                                tx.setRollbackOnly();
                                            }
                                        }));
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Fixture fixture() {
        String code = "OQR06-" + UUID.randomUUID();
        LocalDate start = today.minusYears(2);
        jdbc.update(
                "INSERT INTO pt_patient(mrn_cipher,mrn_blind,name_cipher,name_blind,gender,ward_id,discharge_date) VALUES (?,?,?,?,'F',1,?)",
                crypto.encrypt("pt-patient-mrn", code),
                crypto.blindIndex("pt-patient-mrn", code),
                crypto.encrypt("pt-patient-name", "排期患者（示例）"),
                crypto.blindIndex("pt-patient-name", "排期患者（示例）"),
                start);
        long patient =
                id(
                        "SELECT id FROM pt_patient WHERE mrn_blind=?",
                        crypto.blindIndex("pt-patient-mrn", code));
        jdbc.update(
                "INSERT INTO pt_implant(patient_id,implant_type,implant_date,device_type) VALUES (?,'FIRST',?,'DDD')",
                patient,
                start);
        long device = id("SELECT id FROM pt_implant WHERE patient_id=?", patient);
        jdbc.update(
                "INSERT INTO fu_plan(code,name,version,status,start_date_field,cycle_type) VALUES (?,'排期计划（示例）',1,'PUBLISHED','IMPLANT_DATE','ONGOING')",
                code);
        long plan = id("SELECT id FROM fu_plan WHERE code=?", code);
        jdbc.update(
                "INSERT INTO fu_plan_node(plan_id,node_type,name,repeat_mode,cycle_value,cycle_unit) VALUES (?,'SUBPLAN','常规（示例）','CYCLE',12,'MONTH')",
                plan);
        long parent = id("SELECT id FROM fu_plan_node WHERE plan_id=?", plan);
        jdbc.update(
                "INSERT INTO fu_plan_node(plan_id,parent_id,node_type,name,basis,offset_value,offset_unit,window_before_value,window_before_unit,window_after_value,window_after_unit,task_type,form_code,enroll_mode) VALUES (?,?,'NODE','程控（示例）','LAST_VISIT',12,'MONTH',30,'DAY',30,'DAY','FORM','C_PROGRAM_FU','NORMAL')",
                plan,
                parent);
        long node = id("SELECT id FROM fu_plan_node WHERE plan_id=? AND node_type='NODE'", plan);
        jdbc.update(
                "INSERT INTO fu_enrollment(patient_id,plan_id,plan_code,start_date,created_at) VALUES (?,?,?,?,?)",
                patient,
                plan,
                code,
                start,
                start.atStartOfDay());
        return new Fixture(
                patient,
                device,
                plan,
                node,
                id("SELECT id FROM fu_enrollment WHERE patient_id=?", patient),
                start);
    }

    private long record(Fixture f, LocalDate visit) {
        jdbc.update(
                "INSERT INTO fu_record(patient_id,implant_id,source,visit_date,form_code,template_version,status,data,data_meta) VALUES (?,?,'BACKFILL',?,'C_PROGRAM_FU',1,'SUBMITTED','{}','{}')",
                f.patient,
                f.device,
                visit);
        return id("SELECT MAX(id) FROM fu_record WHERE patient_id=?", f.patient);
    }

    private long task(Fixture f, int occurrence, LocalDate date, String status) {
        jdbc.update(
                "INSERT INTO fu_task(enrollment_id,patient_id,plan_id,node_id,occurrence,task_type,status,plan_date,remind_start,window_start,window_end,open_date,create_type,followup_seq,created_logical_date) VALUES (?,?,?,?,?,'FORM',?,?,?,?,?,?,'NORMAL',0,CURRENT_DATE)",
                f.enrollment,
                f.patient,
                f.plan,
                f.node,
                occurrence,
                status,
                date,
                date.minusDays(30),
                date.minusDays(30),
                date.plusDays(30),
                date.minusDays(30));
        return id(
                "SELECT id FROM fu_task WHERE enrollment_id=? AND occurrence=?",
                f.enrollment,
                occurrence);
    }

    private LocalDate last(Fixture f) {
        return mapper.enrollment(f.enrollment).lastVisitDate();
    }

    private void bind(Fixture f, long record, LocalDate date, boolean reselect) {
        service.register(
                f.patient, record, today.minusDays(10), Map.of("f333", date.toString()), reselect);
    }

    private void apply(Fixture f) {
        service.apply(
                f.enrollment,
                new TaskCreationPlanner.PlanResult(List.of(), null),
                FollowupTaskCreationService.nodeDefs(plans.findNodesByPlanId(f.plan)),
                today,
                true);
    }

    @Test
    void correctionAndDeleteRecomputeAndClearInBothTenants() {
        for (long tenant : List.of(1L, 2L))
            tenant(
                    tenant,
                    () -> {
                        var f = fixture();
                        long older = record(f, today.minusDays(30)),
                                latest = record(f, today.minusDays(10));
                        service.refresh(f.patient);
                        assertThat(last(f)).isEqualTo(today.minusDays(10));
                        jdbc.update(
                                "UPDATE fu_record SET visit_date=? WHERE id=?",
                                today.minusDays(40),
                                latest);
                        service.refresh(f.patient);
                        assertThat(last(f)).isEqualTo(today.minusDays(30));
                        jdbc.update("UPDATE fu_record SET deleted=1 WHERE id=?", older);
                        service.refresh(f.patient);
                        assertThat(last(f)).isEqualTo(today.minusDays(40));
                        jdbc.update("UPDATE fu_record SET deleted=1 WHERE id=?", latest);
                        service.refresh(f.patient);
                        assertThat(last(f)).isNull();
                        return null;
                    });
    }

    @Test
    void templateDateAndSameDayTieAreDeterministicAndUnknownIsNotToday() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long first = record(f, null), second = record(f, null);
                    jdbc.update(
                            "UPDATE fu_record SET data=JSON_OBJECT('f303',?) WHERE id IN (?,?)",
                            today.minusDays(15).toString(),
                            first,
                            second);
                    service.refresh(f.patient);
                    assertThat(last(f)).isEqualTo(today.minusDays(15));
                    assertThat(service.isLatest(f.patient, first)).isFalse();
                    assertThat(service.isLatest(f.patient, second)).isTrue();
                    jdbc.update("UPDATE fu_record SET data='{}' WHERE patient_id=?", f.patient);
                    service.refresh(f.patient);
                    assertThat(last(f)).isNull();
                    assertThat(service.isLatest(f.patient, second)).isFalse();
                    return null;
                });
    }

    @Test
    void onlyCurrentDeviceRecordsContributeAndOldRecordCannotIssueCommands() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long old = record(f, today.minusDays(5));
                    jdbc.update("UPDATE pt_implant SET status='REPLACED' WHERE id=?", f.device);
                    jdbc.update(
                            "INSERT INTO pt_implant(patient_id,implant_type,implant_date,device_type) VALUES (?,'REPLACE',?,'DDD')",
                            f.patient,
                            today.minusDays(2));
                    service.refresh(f.patient);
                    assertThat(last(f)).isNull();
                    switches.onRecordSubmitted(
                            f.patient,
                            old,
                            today.minusDays(5),
                            Map.of("f323", "1", "f333", today.plusDays(40).toString()));
                    assertThatThrownBy(
                                    () ->
                                            switches.onRecordReselected(
                                                    f.patient,
                                                    old,
                                                    today.minusDays(5),
                                                    Map.of("f333", today.plusDays(40).toString()),
                                                    false))
                            .isInstanceOf(ApiException.class);
                    assertThat(mapper.enrollment(f.enrollment).mode()).isEqualTo("NORMAL");
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNull();
                    return null;
                });
    }

    @Test
    void boundDateIsStableAndConsumptionCreatesOnlyTheBoundOccurrence() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long record = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    bind(f, record, today.plusDays(7), false);
                    var e = mapper.enrollment(f.enrollment);
                    assertThat(e.nodeId()).isEqualTo(f.node);
                    assertThat(e.occurrence()).isEqualTo(1);
                    assertThat(e.implantId()).isEqualTo(f.device);
                    assertThat(e.recordId()).isEqualTo(record);
                    creation.initializeForResumedEnrollment(
                            f.enrollment, f.patient, f.plan, "NORMAL", today.minusDays(1));
                    var rows = mapper.tasks(f.enrollment);
                    assertThat(rows).hasSize(1);
                    assertThat(rows.getFirst().planDate()).isEqualTo(today.plusDays(7));
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNull();
                    return null;
                });
    }

    @Test
    void existingPendingTargetIsRescheduledWithExactNodeWindow() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    long task = task(f, 1, today.plusMonths(3), "PENDING");
                    bind(f, r, today.plusDays(5), false);
                    creation.initializeForResumedEnrollment(
                            f.enrollment, f.patient, f.plan, "NORMAL", today.minusDays(1));
                    assertThat(
                                    id(
                                            "SELECT COUNT(*) FROM fu_task WHERE id=? AND plan_date=? AND window_start=? AND window_end=?",
                                            task,
                                            today.plusDays(5),
                                            today.minusDays(25),
                                            today.plusDays(35)))
                            .isEqualTo(1);
                    return null;
                });
    }

    @Test
    void startedTargetIsConflictAndExplicitReselectionBindsTheFollowingOccurrence() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    bind(f, r, today.plusDays(7), false);
                    task(f, 1, today.plusDays(6), "IN_PROGRESS");
                    assertThatThrownBy(() -> apply(f)).isInstanceOf(ApiException.class);
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate())
                            .isEqualTo(today.plusDays(7));
                    assertThat(service.commands(f.patient).getFirst().state())
                            .isEqualTo("CONFLICT");
                    bind(f, r, today.plusDays(7), true);
                    assertThat(mapper.enrollment(f.enrollment).occurrence()).isEqualTo(2);
                    return null;
                });
    }

    @Test
    void contactedPendingTaskIsProtectedAndCommandNotConsumed() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    bind(f, r, today.plusDays(7), false);
                    long task = task(f, 1, today.plusDays(20), "PENDING");
                    jdbc.update(
                            "INSERT INTO fu_contact_log(patient_id,task_id,method,result,contact_time,created_by) VALUES (?,?,'PHONE','REACHED',CURRENT_TIMESTAMP,1)",
                            f.patient,
                            task);
                    assertThatThrownBy(() -> apply(f)).isInstanceOf(ApiException.class);
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNotNull();
                    jdbc.update(
                            "UPDATE fu_record SET visit_date=? WHERE id=?", today.minusDays(20), r);
                    service.refresh(f.patient);
                    assertThat(mapper.tasks(f.enrollment).getFirst().planDate())
                            .isEqualTo(today.plusDays(20));
                    return null;
                });
    }

    @Test
    void invalidEqualDatesRejectAndPastHistoricalInstructionIsOnlyArchived() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    assertThatThrownBy(
                                    () ->
                                            service.register(
                                                    f.patient,
                                                    r,
                                                    today.minusDays(10),
                                                    Map.of(),
                                                    true))
                            .isInstanceOf(ApiException.class);
                    for (String date :
                            List.of(
                                    "invalid",
                                    today.minusDays(10).toString(),
                                    today.minusDays(11).toString()))
                        assertThatThrownBy(
                                        () ->
                                                service.register(
                                                        f.patient,
                                                        r,
                                                        today.minusDays(10),
                                                        Map.of("f333", date),
                                                        false))
                                .isInstanceOf(ApiException.class);
                    assertThat(
                                    service.register(
                                            f.patient,
                                            r,
                                            today.minusDays(10),
                                            Map.of("f333", today.minusDays(1).toString()),
                                            false))
                            .isFalse();
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNull();
                    jdbc.update("UPDATE fu_record SET source='TASK' WHERE id=?", r);
                    assertThatThrownBy(
                                    () ->
                                            service.register(
                                                    f.patient,
                                                    r,
                                                    today.minusDays(10),
                                                    Map.of("f333", today.minusDays(1).toString()),
                                                    false))
                            .isInstanceOf(ApiException.class);
                    return null;
                });
    }

    @Test
    void latestCorrectionWithdrawsFutureUntouchedTaskAndProtectsHistory() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(5));
                    service.refresh(f.patient);
                    long future = task(f, 2, today.plusMonths(12), "PENDING");
                    long done = task(f, 1, today.minusMonths(1), "COMPLETED");
                    jdbc.update(
                            "UPDATE fu_record SET visit_date=? WHERE id=?",
                            today.minusMonths(2),
                            r);
                    service.refresh(f.patient);
                    assertThat(id("SELECT COUNT(*) FROM fu_task WHERE id=?", future)).isZero();
                    assertThat(
                                    id(
                                            "SELECT COUNT(*) FROM fu_task WHERE id=? AND status='COMPLETED'",
                                            done))
                            .isEqualTo(1);
                    return null;
                });
    }

    @Test
    void modeAndDeviceChangesAndLegacyUnboundDateConflictWithoutErasingIt() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    bind(f, r, today.plusDays(7), false);
                    jdbc.update(
                            "UPDATE fu_record SET visit_date=? WHERE id=?",
                            f.start.minusDays(1),
                            r);
                    assertThatThrownBy(() -> apply(f)).isInstanceOf(ApiException.class);
                    jdbc.update(
                            "UPDATE fu_record SET visit_date=? WHERE id=?", today.minusDays(10), r);
                    jdbc.update("UPDATE fu_enrollment SET mode='ERI' WHERE id=?", f.enrollment);
                    assertThatThrownBy(() -> apply(f)).isInstanceOf(ApiException.class);
                    jdbc.update(
                            "UPDATE fu_enrollment SET mode='NORMAL',doctor_next_node_id=NULL WHERE id=?",
                            f.enrollment);
                    assertThatThrownBy(() -> apply(f)).isInstanceOf(ApiException.class);
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNotNull();
                    return null;
                });
    }

    @Test
    void deletingSourceRevokesOnlyItsUnconsumedCommandWithoutReplayingOlderData() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long a = record(f, today.minusDays(30)), b = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    bind(f, b, today.plusDays(7), false);
                    switches.onRecordDeleted(f.patient, a);
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNotNull();
                    jdbc.update("UPDATE fu_record SET deleted=1 WHERE id=?", b);
                    switches.onRecordDeleted(f.patient, b);
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNull();
                    assertThat(last(f)).isEqualTo(today.minusDays(30));
                    return null;
                });
    }

    @Test
    void unchangedDoctorDateOnEditDoesNotReissueConsumedCommand() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    var data = Map.<String, Object>of("f333", today.plusDays(7).toString());
                    bind(f, r, today.plusDays(7), false);
                    creation.initializeForResumedEnrollment(
                            f.enrollment, f.patient, f.plan, "NORMAL", today.minusDays(1));
                    switches.onRecordUpdated(f.patient, r, today.minusDays(10), data, data, false);
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate()).isNull();
                    jdbc.update(
                            "UPDATE fu_record SET visit_date=? WHERE id=?",
                            today.minusMonths(2),
                            r);
                    service.refresh(f.patient);
                    assertThat(mapper.tasks(f.enrollment)).hasSize(1);
                    assertThat(mapper.tasks(f.enrollment).getFirst().planDate())
                            .isEqualTo(today.plusDays(7));
                    assertThat(mapper.tasks(f.enrollment).getFirst().doctorOverride()).isTrue();
                    return null;
                });
    }

    @Test
    void pausedEnrollmentUpdatesDerivedFactWithoutMovingItsTasks() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    long t = task(f, 1, today.plusDays(7), "PENDING");
                    jdbc.update(
                            "UPDATE fu_enrollment SET status='PAUSED' WHERE id=?", f.enrollment);
                    jdbc.update(
                            "UPDATE fu_record SET visit_date=? WHERE id=?", today.minusDays(30), r);
                    service.refresh(f.patient);
                    assertThat(last(f)).isEqualTo(today.minusDays(30));
                    assertThat(mapper.tasks(f.enrollment).getFirst().id()).isEqualTo(t);
                    assertThat(mapper.tasks(f.enrollment).getFirst().planDate())
                            .isEqualTo(today.plusDays(7));
                    return null;
                });
    }

    @Test
    void failingAfterTaskCreationRollsBackTaskAndCommandConsumptionTogether() {
        tenant(
                1,
                () -> {
                    var f = fixture();
                    long r = record(f, today.minusDays(10));
                    service.refresh(f.patient);
                    bind(f, r, today.plusDays(7), false);
                    var nested = new TransactionTemplate(transactions);
                    nested.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_NESTED);
                    assertThatThrownBy(
                                    () ->
                                            nested.execute(
                                                    tx -> {
                                                        creation.initializeForResumedEnrollment(
                                                                f.enrollment,
                                                                f.patient,
                                                                f.plan,
                                                                "NORMAL",
                                                                today.minusDays(1));
                                                        throw new IllegalStateException(
                                                                "后续流程失败（示例）");
                                                    }))
                            .isInstanceOf(IllegalStateException.class);
                    assertThat(mapper.tasks(f.enrollment)).isEmpty();
                    assertThat(mapper.enrollment(f.enrollment).doctorNextDate())
                            .isEqualTo(today.plusDays(7));
                    return null;
                });
    }

    @Test
    void concurrentContactWinsBeforeSchedulerLockAndCommandRemainsForReselection()
            throws Exception {
        Fixture f =
                TenantContext.callAsTenant(
                        1L,
                        "demoa",
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(
                                                tx -> {
                                                    var fixture = fixture();
                                                    long r = record(fixture, today.minusDays(10));
                                                    service.refresh(fixture.patient);
                                                    bind(fixture, r, today.plusDays(7), false);
                                                    task(fixture, 1, today.plusDays(7), "PENDING");
                                                    return fixture;
                                                }));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        var locked = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var contact =
                    executor.submit(
                            () ->
                                    TenantContext.callAsTenant(
                                            1L,
                                            "demoa",
                                            () ->
                                                    new TransactionTemplate(transactions)
                                                            .execute(
                                                                    tx -> {
                                                                        service.lockPatient(
                                                                                f.patient);
                                                                        jdbc.update(
                                                                                "UPDATE fu_task SET status='IN_PROGRESS' WHERE enrollment_id=?",
                                                                                f.enrollment);
                                                                        locked.countDown();
                                                                        try {
                                                                            assertThat(
                                                                                            release
                                                                                                    .await(
                                                                                                            5,
                                                                                                            java
                                                                                                                    .util
                                                                                                                    .concurrent
                                                                                                                    .TimeUnit
                                                                                                                    .SECONDS))
                                                                                    .isTrue();
                                                                        } catch (
                                                                                InterruptedException
                                                                                        interrupted) {
                                                                            Thread.currentThread()
                                                                                    .interrupt();
                                                                            throw new IllegalStateException(
                                                                                    interrupted);
                                                                        }
                                                                        return null;
                                                                    })));
            assertThat(locked.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var scheduler =
                    executor.submit(
                            () ->
                                    TenantContext.callAsTenant(
                                            1L,
                                            "demoa",
                                            () -> {
                                                try {
                                                    creation.initializeForResumedEnrollment(
                                                            f.enrollment,
                                                            f.patient,
                                                            f.plan,
                                                            "NORMAL",
                                                            today.minusDays(1));
                                                    return false;
                                                } catch (ApiException conflict) {
                                                    return true;
                                                }
                                            }));
            release.countDown();
            contact.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(scheduler.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            TenantContext.runAsTenant(
                    1L,
                    "demoa",
                    () -> {
                        assertThat(mapper.enrollment(f.enrollment).doctorNextDate())
                                .isEqualTo(today.plusDays(7));
                        assertThat(mapper.tasks(f.enrollment).getFirst().status())
                                .isEqualTo("IN_PROGRESS");
                    });
        } finally {
            release.countDown();
            executor.shutdownNow();
            TenantContext.runAsTenant(
                    1L,
                    "demoa",
                    () -> {
                        jdbc.update("DELETE FROM fu_record WHERE patient_id=?", f.patient);
                        jdbc.update("DELETE FROM fu_task WHERE enrollment_id=?", f.enrollment);
                        jdbc.update("DELETE FROM fu_enrollment WHERE id=?", f.enrollment);
                        jdbc.update("DELETE FROM pt_implant WHERE patient_id=?", f.patient);
                        jdbc.update("DELETE FROM pt_patient WHERE id=?", f.patient);
                        jdbc.update("DELETE FROM fu_plan_node WHERE plan_id=?", f.plan);
                        jdbc.update("DELETE FROM fu_plan WHERE id=?", f.plan);
                    });
        }
    }
}
