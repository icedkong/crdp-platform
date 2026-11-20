package com.crdp.followup.stats.app;

import com.crdp.followup.dashboard.infra.mapper.DashboardFeatureMapper;
import com.crdp.followup.stats.infra.mapper.*;
import com.crdp.framework.audit.AuditLog;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.*;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一次统计单位一行，年度按患者去重；每次请求重新校验权限 / 范围，不缓存患者集合。 */
@Service
public class StatsDrillService {
    private final StatsDrillMapper mapper;
    private final MonthlyDueService monthly;
    private final NodeOccurrenceMapper occurrences;
    private final DashboardFeatureMapper features;
    private final CompletenessStatsService completeness;
    private final CryptoService crypto;
    private final TenantViewFieldGuard viewGuard;

    public StatsDrillService(
            StatsDrillMapper mapper,
            MonthlyDueService monthly,
            NodeOccurrenceMapper occurrences,
            DashboardFeatureMapper features,
            CompletenessStatsService completeness,
            CryptoService crypto,
            TenantViewFieldGuard viewGuard) {
        this.mapper = mapper;
        this.monthly = monthly;
        this.occurrences = occurrences;
        this.features = features;
        this.completeness = completeness;
        this.crypto = crypto;
        this.viewGuard = viewGuard;
    }

    public record Query(
            String kind,
            String metric,
            Long wardId,
            Long planId,
            Long nodeId,
            Integer occurrence,
            Integer year,
            Integer cohortYear,
            String patientType,
            String fieldKey,
            LocalDate from,
            LocalDate to,
            String timeField,
            Integer pageNo,
            Integer pageSize,
            Long expectedCount,
            Long doctorId) {}

    public record Context(
            String enrollmentId,
            String planId,
            String nodeId,
            Integer occurrence,
            Integer year,
            LocalDate planDate,
            String taskId,
            String recordId,
            boolean virtual,
            String planName,
            String nodeName) {}

    public record Item(String key, String patientId, String name, List<Context> contexts) {}

    public record Page(
            List<Item> items,
            long total,
            int pageNo,
            int pageSize,
            String unit,
            String timeBasis,
            String refreshedAt,
            boolean changed) {}

    private record Raw(String key, long patientId, Context context) {}

    @AuditLog(
            module = "followup",
            action = "STATS_DRILL",
            objectType = "STATS",
            description = "按统计口径读取脱敏明细（不记录请求载荷或患者值）")
    @Transactional(readOnly = true)
    public Page search(Query q) {
        var session = CurrentSession.requireHospital();
        if (!session.permissionCodes().contains("followup:patient:view"))
            throw new ApiException(403, 40300, "没有患者查看权限");
        if (q == null
                || !Set.of(
                                "MONTH",
                                "YEAR",
                                "NODE",
                                "BASELINE_MISSING",
                                "FOLLOWUP_MISSING",
                                "BASELINE_ALL",
                                "FOLLOWUP_ALL")
                        .contains(String.valueOf(q.kind()))) throw bad("kind 不合法");
        positive(q.wardId(), "wardId");
        positive(q.planId(), "planId");
        positive(q.nodeId(), "nodeId");
        positive(q.doctorId(), "doctorId");
        if (q.cohortYear() != null && (q.cohortYear() < 1900 || q.cohortYear() > 2999))
            throw bad("cohortYear 不合法");
        int page = q.pageNo() == null ? 1 : q.pageNo(),
                size = q.pageSize() == null ? 20 : q.pageSize();
        if (page < 1 || size < 1 || size > 100) throw bad("分页参数不合法");
        List<Raw> raw = new ArrayList<>();
        String unit;
        String basis = "LIVE";
        String refreshed = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
        switch (q.kind()) {
            case "MONTH" -> {
                unit = "入组×节点×次数";
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                LocalDate from = q.from() == null ? today.withDayOfMonth(1) : q.from();
                LocalDate to = q.to() == null ? from.withDayOfMonth(from.lengthOfMonth()) : q.to();
                for (var u :
                        monthly.units(from, to, q.wardId()).stream()
                                .filter(
                                        u ->
                                                q.doctorId() == null
                                                        || Objects.equals(
                                                                q.doctorId(), u.doctorId()))
                                .toList())
                    raw.add(
                            new Raw(
                                    u.enrollmentId() + ":" + u.nodeId() + ":" + u.occurrence(),
                                    u.patientId(),
                                    new Context(
                                            id(u.enrollmentId()),
                                            id(u.planId()),
                                            id(u.nodeId()),
                                            u.occurrence(),
                                            null,
                                            u.planDate(),
                                            id(u.taskId()),
                                            id(u.recordId()),
                                            u.taskId() == null,
                                            u.planName(),
                                            u.nodeName())));
            }
            case "YEAR", "NODE" -> {
                boolean yearly = "YEAR".equals(q.kind());
                unit = yearly ? "患者" : "入组×节点×次数";
                basis = "DAILY";
                if (yearly && (q.year() == null || q.year() < 1)) throw bad("year 必填且须为正整数");
                if (!yearly
                        && (q.planId() == null
                                || q.nodeId() == null
                                || q.occurrence() == null
                                || q.occurrence() < 1)) throw bad("节点钻取须指定计划、节点和次数");
                if (!yearly && !Boolean.TRUE.equals(features.findEnabled("node_follow_rate")))
                    throw new ApiException(403, 40301, "节点随访率未开通");
                String metric = q.metric() == null ? "DUE" : q.metric();
                if (!Set.of("DUE", "FOLLOWED", "IN_WINDOW", "LOST", "DEATH").contains(metric))
                    throw bad("metric 不合法");
                for (var f :
                        mapper.facts(
                                q.wardId(),
                                q.cohortYear(),
                                yearly ? null : q.planId(),
                                yearly ? null : q.nodeId(),
                                yearly ? null : q.occurrence(),
                                yearly ? q.year() : null)) {
                    if (!matches(f, metric)) continue;
                    raw.add(
                            new Raw(
                                    yearly
                                            ? id(f.patientId())
                                            : f.enrollmentId()
                                                    + ":"
                                                    + f.nodeId()
                                                    + ":"
                                                    + f.occurrence(),
                                    f.patientId(),
                                    new Context(
                                            id(f.enrollmentId()),
                                            id(f.planId()),
                                            id(f.nodeId()),
                                            f.occurrence(),
                                            f.followupYear(),
                                            f.planDate(),
                                            id(f.taskId()),
                                            id(f.recordId()),
                                            false,
                                            f.planName(),
                                            f.nodeName())));
                }
                var state = occurrences.lastRefresh();
                refreshed =
                        state == null
                                ? null
                                : state.refreshedAt().atOffset(ZoneOffset.ofHours(8)).toString();
            }
            case "BASELINE_MISSING", "BASELINE_ALL" -> {
                unit = "患者";
                for (long patient :
                        ("BASELINE_ALL".equals(q.kind())
                                ? completeness.baselinePatientIds(completenessQuery(q))
                                : completeness.patientsMissingBaselineField(
                                        q.fieldKey(), completenessQuery(q))))
                    raw.add(new Raw(id(patient), patient, null));
            }
            case "FOLLOWUP_MISSING", "FOLLOWUP_ALL" -> {
                unit = "年度记录";
                for (var r :
                        ("FOLLOWUP_ALL".equals(q.kind())
                                ? completeness.followupReferences(completenessQuery(q))
                                : completeness.recordsMissingFollowupField(
                                        q.fieldKey(), completenessQuery(q))))
                    raw.add(
                            new Raw(
                                    id(r.recordId()),
                                    r.patientId(),
                                    new Context(
                                            null,
                                            null,
                                            null,
                                            null,
                                            r.year(),
                                            null,
                                            null,
                                            id(r.recordId()),
                                            false,
                                            null,
                                            null)));
            }
            default -> throw bad("kind 不合法");
        }
        Map<String, List<Raw>> grouped = new LinkedHashMap<>();
        raw.forEach(r -> grouped.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r));
        long total = grouped.size();
        long offset = (long) (page - 1) * size;
        var groups = grouped.values().stream().skip(offset).limit(size).toList();
        var ids = groups.stream().map(r -> r.getFirst().patientId()).distinct().toList();
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty())
            for (var p : mapper.patients(ids)) names.put(p.id(), maskedName(p.nameCipher()));
        List<Item> items = new ArrayList<>();
        for (var group : groups) {
            var first = group.getFirst();
            items.add(
                    new Item(
                            first.key(),
                            id(first.patientId()),
                            names.get(first.patientId()),
                            group.stream().map(Raw::context).filter(Objects::nonNull).toList()));
        }
        return new Page(
                items,
                total,
                page,
                size,
                unit,
                basis,
                refreshed,
                q.expectedCount() != null && q.expectedCount() != total);
    }

    private String maskedName(String cipher) {
        var policies = CurrentSession.requireHospital().fieldPolicies();
        if ("HIDDEN"
                .equals(
                        (policies == null ? FieldPolicySnapshot.defaults() : policies)
                                .decide("patient", "name"))) return null;
        return viewGuard.apply(
                "patient",
                "name",
                cipher == null ? null : SensitiveValueMasker.mask("name", crypto.decrypt(cipher)));
    }

    private static boolean matches(StatsDrillMapper.Fact f, String metric) {
        return switch (metric) {
            case "FOLLOWED" -> f.followed();
            case "IN_WINDOW" -> f.inWindow();
            case "LOST" -> f.lost();
            case "DEATH" -> f.death();
            default -> true;
        };
    }

    private static CompletenessStatsService.Query completenessQuery(Query q) {
        return new CompletenessStatsService.Query(
                q.wardId(), q.patientType(), q.from(), q.to(), q.timeField(), q.year());
    }

    private static void positive(Long value, String name) {
        if (value != null && value <= 0) throw bad(name + " 不合法");
    }

    private static String id(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
