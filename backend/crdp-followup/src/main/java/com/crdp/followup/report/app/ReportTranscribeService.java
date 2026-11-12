package com.crdp.followup.report.app;

import com.crdp.followup.report.infra.mapper.ReportFeatureMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 转录到结构化记录的完成口径（E21-T17 / C-SCAN-012、C-FU-017；OQ-131②「选择或新建补录记录」发生在转录流程）： 随访记录在转录态（表单页 {@code
 * ?compare=<报告单 id>&transcribe=1}）提交 / 保存修改时，由 {@code RecordEntryService} 在<b>同一事务</b>内调用 {@link
 * #completeTranscription}——
 *
 * <ul>
 *   <li>保存后该记录自动加入扫描件的位置（C-FU-017）：报告单位置中没有该随访记录（整条或分组都不算）时插入 「整条记录」位置行（模板版本取记录固化版本，与创建报告单同口径），写
 *       RPT_LOCATION_CHANGE 操作日志（注明转录自动加入）；
 *   <li>转录后电子化状态自动更新（C-SCAN-011 / C-SCAN-012 验收位）：状态不为 DONE 时置 DONE 并推进 version + 1， 写
 *       RPT_TRANSCRIBE 操作日志（谁、何时、前后值、对应随访记录）；已是 DONE 则幂等跳过（无状态变化不留痕，位置照常确保）；
 *   <li>功能开关 {@code scan_progress} 关闭（含无行）→ 40301（医院 A / B 不启用电子化状态，A-ATT-018； 前端转录入口按开关隐藏，本口兜底）；
 *   <li>越范围 / 不存在 / 已删报告单 40400（写 DENIED 审计，不暴露对象存在）；报告单与记录不属于同一患者 40001。
 * </ul>
 *
 * <p>权限沿用提交 / 保存修改自身的闸门（{@code followup:record:edit} / edit-submitted）， 不另要求 {@code
 * followup:scan:upload}——转录是录入动作，不是报告单维护（C-SCAN-012 未挂上传权限）。
 */
@Service
public class ReportTranscribeService {

    /** 电子化状态功能开关（C-SCAN-011 / C-DASH-007；关闭 → 40301，AGENTS §5.7）。 */
    static final String SCAN_PROGRESS_FEATURE = "scan_progress";

    /** 转录终态（C-SCAN-011「DONE 只由转录或手工产生」）。 */
    static final String STATUS_DONE = "DONE";

    private static final Logger log = LoggerFactory.getLogger(ReportTranscribeService.class);

    private final ReportMapper mapper;
    private final ReportFeatureMapper features;
    private final AuditLogger audit;

    public ReportTranscribeService(
            ReportMapper mapper, ReportFeatureMapper features, AuditLogger audit) {
        this.mapper = mapper;
        this.features = features;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 转录完成结果：报告单终态与推进后的乐观锁版本（无变化时为当前版本）。 */
    public record TranscribedView(String id, String digitizeStatus, int version) {}

    // ---------------------------------------------------------------- 完成转录

    /**
     * 完成转录（与记录提交 / 保存修改同一事务）：确保位置 + 自动置已电子化。
     *
     * @param reportId 转录目标报告单
     * @param recordId 本次提交 / 保存的随访记录
     * @param patientId 记录归属患者（提交链路已过记录侧范围校验）
     * @param recordTemplateVersion 记录固化模板版本（新位置行的 template_version）
     */
    @Transactional
    public TranscribedView completeTranscription(
            long reportId, long recordId, long patientId, int recordTemplateVersion) {
        if (!Boolean.TRUE.equals(features.findEnabled(SCAN_PROGRESS_FEATURE))) {
            throw new ApiException(403, 40301, "电子化状态未开通（功能开关 scan_progress 关闭）");
        }
        ReportMapper.DigitizeRow report = mapper.findDigitizeRow(reportId);
        if (report == null) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_TRANSCRIBE")
                            .objectType("REPORT")
                            .objectId(String.valueOf(reportId))
                            .description("转录到结构化记录被拒绝：报告单不在操作者数据范围内或已删除")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "报告单不存在或不在您的数据范围内");
        }
        if (report.patientId() != patientId) {
            throw new ApiException(400, 40001, "转录目标报告单与随访记录不属于同一患者");
        }
        long operator = CurrentSession.requireHospital().userId();
        boolean locationAdded =
                ensureRecordLocation(
                        reportId, patientId, recordId, recordTemplateVersion, operator);
        if (STATUS_DONE.equals(report.digitizeStatus())) {
            return new TranscribedView(String.valueOf(reportId), STATUS_DONE, report.version());
        }
        mapper.updateDigitizeStatus(reportId, STATUS_DONE, operator);
        String beforeLabel =
                report.digitizeStatus() == null
                        ? "未设置"
                        : ReportService.digitizeStatusLabel(report.digitizeStatus());
        Map<String, String> before = new LinkedHashMap<>();
        before.put("digitizeStatus", report.digitizeStatus());
        audit.append(
                AuditEntry.operation("followup", "RPT_TRANSCRIBE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "转录到结构化记录："
                                        + beforeLabel
                                        + " → 已电子化（随访记录 "
                                        + recordId
                                        + "；报告单："
                                        + report.typeName()
                                        + (report.nameSnapshot() == null
                                                ? ""
                                                : " · " + report.nameSnapshot())
                                        + (locationAdded ? "；记录已自动加入位置" : "")
                                        + "）")
                        .beforeJson(writeJson(before))
                        .afterJson(writeJson(Map.of("digitizeStatus", STATUS_DONE)))
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "转录到结构化记录完成（E21-T17）：reportId={} recordId={} patientId={} {} → DONE operator={}",
                reportId,
                recordId,
                patientId,
                report.digitizeStatus(),
                operator);
        return new TranscribedView(String.valueOf(reportId), STATUS_DONE, report.version() + 1);
    }

    /**
     * 确保该随访记录在报告单位置中（C-FU-017「保存后该记录自动加入扫描件的位置」）： 整条或分组位置指向同一记录即视为已加入； 否则插入整条记录位置并写
     * RPT_LOCATION_CHANGE 操作日志（A-ATT-020「增删写操作日志」的转录联动口径）。 返回是否实际插入。
     */
    private boolean ensureRecordLocation(
            long reportId,
            long patientId,
            long recordId,
            int recordTemplateVersion,
            long operator) {
        boolean alreadyLinked =
                mapper.linksOfPatient(patientId).stream()
                        .anyMatch(
                                link ->
                                        link.reportId() == reportId
                                                && ReportService.LINK_FOLLOWUP_RECORD.equals(
                                                        link.linkType())
                                                && link.linkRecordId() != null
                                                && link.linkRecordId() == recordId);
        if (alreadyLinked) {
            return false;
        }
        mapper.insertLink(
                new ReportMapper.LinkInsert(
                        reportId,
                        patientId,
                        ReportService.LINK_FOLLOWUP_RECORD,
                        recordId,
                        null,
                        null,
                        recordTemplateVersion));
        audit.append(
                AuditEntry.operation("followup", "RPT_LOCATION_CHANGE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description("转录到结构化记录：随访记录 " + recordId + " 自动加入报告单位置（整条记录）")
                        .afterJson(
                                writeJson(
                                        Map.of(
                                                "linkType",
                                                ReportService.LINK_FOLLOWUP_RECORD,
                                                "linkRecordId",
                                                String.valueOf(recordId),
                                                "anchorType",
                                                "",
                                                "source",
                                                "TRANSCRIBE")))
                        .result(AuditResult.SUCCESS)
                        .build());
        return true;
    }

    private static String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("报告单转录日志 JSON 序列化失败", invalid);
        }
    }
}
