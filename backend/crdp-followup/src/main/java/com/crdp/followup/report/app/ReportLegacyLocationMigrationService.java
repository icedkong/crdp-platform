package com.crdp.followup.report.app;

import com.crdp.followup.implant.app.ImplantService;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.LegacyScanRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkRow;
import com.crdp.followup.report.infra.mapper.ReportMapper.YearRecordRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 历史报告单位置迁移（E18-T08 / A-ATT-002，2026-10-31 位置必填变更；tech-plan §10「历史数据迁移」）。
 *
 * <p>验收原文：迁移脚本可重入；按年关联的落到该年随访记录整条，原关联基线的进入未指定位置，迁移报告列出数量。
 *
 * <ul>
 *   <li>输入形态（followup V75）：旧系统 / 前期试运行按「可选单一关联」保存的报告单装载进 {@code rpt_report.legacy_link_type} （YEAR
 *       按年关联 / BASELINE 关联基线）+ {@code legacy_link_year}（年度 N）；正常上传接口不写这两列。 迁移由实施在历史数据装载后经 {@code
 *       POST /api/hospital/followup/reports/legacy-location-migration} 触发（权限 {@code
 *       followup:patient:import}，与导入向导同一操作人画像）。
 *   <li>口径：按年关联 → 该年随访记录<b>整条</b>（fu_record.followup_year = N，E18-T05
 *       历史导入生成的年度记录；锚点整条、模板版本取该记录固化版本，与 E16-T02 创建口同口径）；原关联基线 →
 *       不落位置行、进入「未指定位置」组（A-ATT-020「不集中补指定」），经修改位置 add 由有上传权限的用户在使用中补指定；医院 C 的植入记录关联随 E21-T32
 *       同机制扩展（C 规格 §508）。
 *   <li>可重入：{@code legacy_migrated_at} 为判据（NULL 待迁移）；已迁移行不再处理，重复执行零写入；按年关联但该年无随访记录（窗口未结束 /
 *       未导入该年）的<b>保持待迁移</b>——下次执行可补落，期间在「未指定位置」组可见。 该报告单已有同记录整条位置（人工先行指定）时不重复落行（V63 uk 兜底），只标记迁移。
 *   <li>审计：每个完成迁移的报告单写一条 {@code RPT_LEGACY_LOCATION_MIGRATE}（module=followup， objectType=REPORT，谁 /
 *       何时由审计框架带出，before / after 留位置四元组快照，与 RPT_LOCATION_CHANGE 同形）； 基线关联无位置行变更、不推进乐观锁版本
 *       （位置状态前后均为空），按年关联落位置行时 version + 1（与 E16-T13 同口径）。 legacy_* 两列迁移后保留作原关联溯源。
 *   <li>扇出：单报告单独立事务，一条坏行 WARN 跳过不拖垮整批（与 E16-T15 作业同口径）；巡检走 @IgnoreScope
 *       （租户级收尾批处理，无逐患者数据范围——操作人持导入权限，审计记操作）。
 * </ul>
 */
@Service
public class ReportLegacyLocationMigrationService {

    private static final Logger log =
            LoggerFactory.getLogger(ReportLegacyLocationMigrationService.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 旧关联形态（V75 legacy_link_type 取值）。 */
    static final String LEGACY_YEAR = "YEAR";

    static final String LEGACY_BASELINE = "BASELINE";

    /**
     * 医院 C 的植入记录关联（OQ-105⑤，随 E21-T32）：旧系统原关联某条植入记录 → 该植入记录整条。 id 载体 = {@code legacy_link_year}
     * 列（INT，复用既有列不扩表）；植入记录未装载（分批导入未到）保持待迁移，与 YEAR 无记录同口径。
     */
    static final String LEGACY_IMPLANT = "IMPLANT";

    private final ReportMapper mapper;
    private final AuditLogger audit;
    private final TransactionTemplate transactions;
    private final FormService formService;

    public ReportLegacyLocationMigrationService(
            ReportMapper mapper,
            AuditLogger audit,
            PlatformTransactionManager transactionManager,
            FormService formService) {
        this.mapper = mapper;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
        this.formService = formService;
    }

    /** 迁移报告（tasks.csv 验收「迁移报告列出数量」）：本次完成的两类迁移数、保持待迁移的两类数量 与重入时已迁移的总数。 */
    public record MigrationReport(
            String tenantCode,
            int yearLinked,
            int baselineUnassigned,
            int yearWithoutRecord,
            int invalidSkipped,
            int implantLinked,
            int implantWithoutRecord,
            long alreadyMigrated) {}

    /** 迁移当前租户全部待迁移报告单（须在租户上下文内；逐报告单独立事务，失败行 WARN 继续）。 */
    public MigrationReport migrateCurrentTenant() {
        TenantContext.State tenant = TenantContext.require();
        long operator = CurrentSession.requireHospital().userId();
        List<LegacyScanRow> pending = mapper.scanPendingLegacyReports();
        int yearLinked = 0;
        int baselineUnassigned = 0;
        int yearWithoutRecord = 0;
        int invalidSkipped = 0;
        int implantLinked = 0;
        int implantWithoutRecord = 0;
        for (LegacyScanRow row : pending) {
            try {
                Outcome outcome = migrateReport(row, operator);
                switch (outcome) {
                    case YEAR_LINKED -> yearLinked++;
                    case BASELINE_UNASSIGNED -> baselineUnassigned++;
                    case YEAR_WITHOUT_RECORD -> yearWithoutRecord++;
                    case INVALID -> invalidSkipped++;
                    case IMPLANT_LINKED -> implantLinked++;
                    case IMPLANT_WITHOUT_RECORD -> implantWithoutRecord++;
                }
            } catch (RuntimeException ex) {
                log.warn("历史报告单位置迁移失败，跳过该报告单继续 reportId={} 原因={}", row.reportId(), ex.getMessage());
            }
        }
        long alreadyMigrated = mapper.countMigratedLegacyReports();
        MigrationReport report =
                new MigrationReport(
                        tenant.tenantCode(),
                        yearLinked,
                        baselineUnassigned,
                        yearWithoutRecord,
                        invalidSkipped,
                        implantLinked,
                        implantWithoutRecord,
                        alreadyMigrated);
        log.info(
                "历史报告单位置迁移完成（E18-T08）：tenant={} 按年关联落记录={} 基线进未指定={} 该年无记录={} 形态非法={} 植入关联落记录={} 植入未装载={} 已迁移累计={}",
                report.tenantCode(),
                report.yearLinked(),
                report.baselineUnassigned(),
                report.yearWithoutRecord(),
                report.invalidSkipped(),
                report.implantLinked(),
                report.implantWithoutRecord(),
                report.alreadyMigrated());
        return report;
    }

    /** 单报告单迁移结果。 */
    private enum Outcome {
        /** 按年关联已落到该年随访记录整条（含已在册同位置不重复落）。 */
        YEAR_LINKED,

        /** 原关联基线，进入未指定位置。 */
        BASELINE_UNASSIGNED,

        /** 按年关联但该年无随访记录：保持待迁移，本次不动。 */
        YEAR_WITHOUT_RECORD,

        /** 形态非法（不认识的关联形态 / YEAR 缺年度）：跳过，不做无依据的落位。 */
        INVALID,

        /** 植入记录关联已落到该植入记录整条（E21-T32 / OQ-105⑤，含已在册同位置不重复落）。 */
        IMPLANT_LINKED,

        /** 植入记录关联但该植入记录未装载（分批导入未到）：保持待迁移，本次不动。 */
        IMPLANT_WITHOUT_RECORD
    }

    /** 判定并迁移一个报告单（只读判定在事务外、写动作在事务内：变更 + 标记 + 日志同生共死）。 */
    private Outcome migrateReport(LegacyScanRow row, long operator) {
        if (LEGACY_BASELINE.equals(row.legacyLinkType())) {
            List<LinkRow> links = mapper.linksOfReportForMigration(row.reportId());
            transactions.executeWithoutResult(
                    status -> {
                        mapper.markLegacyMigrated(row.reportId());
                        audit.append(entryOf(row, "原关联基线 → 未指定位置", links, links));
                    });
            log.info("历史报告单位置迁移（E18-T08）：reportId={} 基线关联 → 未指定位置", row.reportId());
            return Outcome.BASELINE_UNASSIGNED;
        }
        if (LEGACY_IMPLANT.equals(row.legacyLinkType())) {
            return migrateImplant(row, operator);
        }
        if (!LEGACY_YEAR.equals(row.legacyLinkType()) || row.legacyLinkYear() == null) {
            log.warn(
                    "历史报告单关联形态非法，跳过（不做无依据的落位）reportId={} type={}",
                    row.reportId(),
                    row.legacyLinkType());
            return Outcome.INVALID;
        }
        int year = row.legacyLinkYear();
        YearRecordRow record = mapper.findYearRecord(row.patientId(), year);
        if (record == null) {
            // 该年无随访记录（窗口未结束 / 未导入该年）：留在未指定位置、保持待迁移，下次执行可补
            log.info("历史报告单按年关联但该年无随访记录，保持待迁移 reportId={} year={}", row.reportId(), year);
            return Outcome.YEAR_WITHOUT_RECORD;
        }
        List<LinkRow> before = mapper.linksOfReportForMigration(row.reportId());
        boolean alreadyPlaced =
                before.stream()
                        .anyMatch(
                                link ->
                                        "followup_record".equals(link.linkType())
                                                && Long.valueOf(record.id())
                                                        .equals(link.linkRecordId())
                                                && link.anchorType() == null);
        if (alreadyPlaced) {
            transactions.executeWithoutResult(status -> mapper.markLegacyMigrated(row.reportId()));
            log.info("历史报告单按年关联的位置已在册（人工先行指定），只标记迁移 reportId={} year={}", row.reportId(), year);
            return Outcome.YEAR_LINKED;
        }
        LinkInsert insert =
                new LinkInsert(
                        row.reportId(),
                        row.patientId(),
                        "followup_record",
                        record.id(),
                        null,
                        null,
                        record.templateVersion());
        List<LinkRow> after = new ArrayList<>(before);
        after.add(
                new LinkRow(
                        null,
                        row.reportId(),
                        insert.linkType(),
                        insert.linkRecordId(),
                        insert.anchorType(),
                        insert.anchorKey(),
                        insert.templateVersion(),
                        0));
        transactions.executeWithoutResult(
                status -> {
                    mapper.insertLink(insert);
                    mapper.bumpVersion(row.reportId(), operator);
                    mapper.markLegacyMigrated(row.reportId());
                    audit.append(entryOf(row, "按年关联 → 第" + year + "年随访记录整条", before, after));
                });
        log.info(
                "历史报告单位置迁移（E18-T08）：reportId={} 第{}年随访关联 → 该年随访记录整条 recordId={}",
                row.reportId(),
                year,
                record.id());
        return Outcome.YEAR_LINKED;
    }

    /**
     * 植入记录关联迁移（E21-T32 / OQ-105⑤、C 规格 §7）：落到该植入记录整条（anchor 空、模板版本取 C_IMPLANT
     * 当前发布版——植入记录无按记录固化版本，与创建口同口径）。植入记录未装载 → 保持待迁移； 与报告单患者不一致（防御：装载错库）→ 形态非法跳过。
     */
    private Outcome migrateImplant(LegacyScanRow row, long operator) {
        if (row.legacyLinkYear() == null) {
            log.warn("历史报告单植入关联缺记录标识，跳过 reportId={}", row.reportId());
            return Outcome.INVALID;
        }
        long implantId = row.legacyLinkYear();
        ReportMapper.ImplantRefRow implant = mapper.findImplantById(implantId);
        if (implant == null) {
            log.info(
                    "历史报告单植入关联但该植入记录未装载，保持待迁移 reportId={} implantId={}", row.reportId(), implantId);
            return Outcome.IMPLANT_WITHOUT_RECORD;
        }
        if (implant.patientId() == null || implant.patientId() != row.patientId()) {
            log.warn(
                    "历史报告单植入关联与患者不一致，跳过（不做无依据的落位）reportId={} implantId={}",
                    row.reportId(),
                    implantId);
            return Outcome.INVALID;
        }
        List<LinkRow> before = mapper.linksOfReportForMigration(row.reportId());
        boolean alreadyPlaced =
                before.stream()
                        .anyMatch(
                                link ->
                                        "implant_record".equals(link.linkType())
                                                && Long.valueOf(implantId)
                                                        .equals(link.linkRecordId())
                                                && link.anchorType() == null);
        if (alreadyPlaced) {
            transactions.executeWithoutResult(status -> mapper.markLegacyMigrated(row.reportId()));
            log.info(
                    "历史报告单植入关联的位置已在册（人工先行指定），只标记迁移 reportId={} implantId={}",
                    row.reportId(),
                    implantId);
            return Outcome.IMPLANT_LINKED;
        }
        int templateVersion;
        try {
            templateVersion = formService.publishedVersion(ImplantService.IMPLANT_FORM_CODE);
        } catch (RuntimeException ex) {
            // C_IMPLANT 模板读不出：本条 WARN 跳过（外层 catch），不做无依据的落位
            throw new IllegalStateException("C_IMPLANT 模板读取失败：" + ex.getMessage(), ex);
        }
        LinkInsert insert =
                new LinkInsert(
                        row.reportId(),
                        row.patientId(),
                        "implant_record",
                        implantId,
                        null,
                        null,
                        templateVersion);
        List<LinkRow> after = new ArrayList<>(before);
        after.add(
                new LinkRow(
                        null,
                        row.reportId(),
                        insert.linkType(),
                        insert.linkRecordId(),
                        insert.anchorType(),
                        insert.anchorKey(),
                        insert.templateVersion(),
                        0));
        transactions.executeWithoutResult(
                status -> {
                    mapper.insertLink(insert);
                    mapper.bumpVersion(row.reportId(), operator);
                    mapper.markLegacyMigrated(row.reportId());
                    audit.append(entryOf(row, "植入关联 → 植入记录 " + implantId + " 整条", before, after));
                });
        log.info(
                "历史报告单位置迁移（E21-T32）：reportId={} 植入关联 → 该植入记录整条 implantId={}",
                row.reportId(),
                implantId);
        return Outcome.IMPLANT_LINKED;
    }

    /** 操作日志条目（与 RPT_LOCATION_CHANGE / RPT_LOCATION_FALLBACK 同形：before / after 留位置四元组快照）。 */
    private AuditEntry entryOf(
            LegacyScanRow row, String summary, List<LinkRow> before, List<LinkRow> after) {
        return AuditEntry.operation("followup", "RPT_LEGACY_LOCATION_MIGRATE")
                .objectType("REPORT")
                .objectId(String.valueOf(row.reportId()))
                .description(
                        "历史报告单位置迁移（E18-T08）："
                                + summary
                                + "；报告单："
                                + row.typeName()
                                + (row.nameSnapshot() == null ? "" : " · " + row.nameSnapshot()))
                .beforeJson(writeJson(refsOf(before)))
                .afterJson(writeJson(refsOf(after)))
                .result(AuditResult.SUCCESS)
                .build();
    }

    /** 位置四元组快照（与 RPT_LOCATION_CHANGE 的 before / after 同形）。 */
    private static List<Map<String, Object>> refsOf(List<LinkRow> rows) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (LinkRow link : rows) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("linkType", link.linkType());
            ref.put("linkRecordId", link.linkRecordId());
            ref.put("anchorType", link.anchorType());
            ref.put("anchorKey", link.anchorKey());
            ref.put("templateVersion", link.templateVersion());
            ref.put("adjusted", link.adjusted() != null && link.adjusted() > 0 ? 1 : 0);
            refs.add(ref);
        }
        return refs;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("迁移日志 JSON 序列化失败", e);
        }
    }
}
