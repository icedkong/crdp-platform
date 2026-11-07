package com.crdp.followup.report.app;

import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.NameRow;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper.TypeRow;
import com.crdp.followup.report.infra.mapper.ReportInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * E16-T08 / A-ATT-017：通话记录报告单与沟通记录录音为同一数据（docs/api/report.md §2「通话记录」）。
 *
 * <p>沟通记录创建带录音（E15-T04 audioFileIds 非空）时，{@link com.crdp.followup.contact.app.ContactLogService}
 * 在<b>同一事务</b>内调 {@link #createForContact} 自动生成一份「通话记录」报告单（typeCode=CALL，V62 预置）：
 *
 * <ul>
 *   <li>同一批 sys_file 同时挂 fu_contact_audio 与 rpt_report_file——同一份文件、不重复存储，
 *       沟通记录页签（AudioPlayer）与报告单页签两处看到同一份数据；
 *   <li>位置预选为该沟通关联任务对应的随访记录（整条，fu_task.record_id），没有对应记录时不落位置行、 进入「未指定位置」组（A-ATT-020 / E16-T05
 *       既有口径）；
 *   <li>关联单独存放在 rpt_report.contact_log_id（followup V72），不算位置（tech-plan §10）。
 * </ul>
 *
 * <p>删除联动（「任一处删除即两处同时删除」，pages.md §4.8）：删沟通记录经 {@link #softDeleteForContact} 连带软删其 CALL 报告单（录音
 * sys_file 已由沟通侧软删）；删 CALL 报告单 / 其中单个录音（E16-T09）软删 sys_file 后，沟通记录页签的录音同时消失（audiosOf 只出非 DELETED
 * 台账）。 都写审计（RPT_CREATE / RPT_DELETE）。
 *
 * <p>租户停用 CALL 类型或 RPT_CALL 表单不可读时自动生成<b>跳过</b>（WARN 留痕，不阻断沟通记录创建——沟通记录是随访业务主流程，
 * 报告单联动是展示增强；OQ-103①）。
 */
@Service
public class CallReportService {

    /** 「通话记录」类型编码（rpt_type.code，V62 预置，A-ATT-003）。 */
    static final String CALL_TYPE_CODE = "CALL";

    /** CALL 类型无启用名称时的名称快照兜底（OQ-103①：展示不受字典配置缺位影响）。 */
    static final String CALL_NAME_FALLBACK = "随访电话录音";

    /** RPT_CALL 表单「通话时间」字段稳定键（V62 预置 f001，datetime 必填）。 */
    private static final String FIELD_CALL_TIME = "call_time";

    /** RPT_CALL 表单「通话说明」字段稳定键（V62 预置 f002，textarea 选填）。 */
    private static final String FIELD_CALL_NOTE = "call_note";

    /** datetime 值格式（FormDataValidator DATETIME 正则：yyyy-MM-dd'T'HH:mm(:ss)）。 */
    private static final DateTimeFormatter CALL_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 连带软删的删除原因（rpt_report.delete_reason，E16-T09 列；沟通记录删除本身不收原因）。 */
    private static final String CASCADE_DELETE_REASON = "沟通记录删除，通话记录报告单连带删除（A-ATT-017）";

    private static final Logger log = LoggerFactory.getLogger(CallReportService.class);

    private final ReportMapper mapper;
    private final ReportConfigMapper config;
    private final FormService formService;
    private final AuditLogger audit;

    public CallReportService(
            ReportMapper mapper,
            ReportConfigMapper config,
            FormService formService,
            AuditLogger audit) {
        this.mapper = mapper;
        this.config = config;
        this.formService = formService;
        this.audit = audit;
    }

    /**
     * 沟通记录带录音 → 同事务生成 CALL 报告单（A-ATT-017「自动显示为一份通话记录报告单」）。
     *
     * @return 报告单 id；无录音或 CALL 类型 / 表单不可用（跳过生成，OQ-103①）时 null
     */
    public Long createForContact(
            long patientId,
            long contactLogId,
            Long taskId,
            LocalDateTime contactTime,
            String note,
            List<Long> audioFileIds,
            long operator) {
        if (audioFileIds == null || audioFileIds.isEmpty()) {
            return null; // 无录音不生成（录音才是报告单的载体）
        }
        TypeRow type = enabledCallType();
        if (type == null) {
            log.warn("通话记录报告单未生成：CALL 类型不存在或已停用（跳过，OQ-103①）contactLogId={}", contactLogId);
            return null;
        }
        NameRow name = defaultCallName();
        ReportInsert row = new ReportInsert();
        row.setPatientId(patientId);
        row.setTypeCode(type.code());
        row.setTypeName(type.name());
        row.setNameId(name == null ? null : name.id());
        row.setNameSnapshot(name == null ? CALL_NAME_FALLBACK : name.name());
        row.setReportDate(contactTime.toLocalDate());
        row.setFieldValues(callFieldValues(contactTime, note, type));
        row.setDigitizeStatus(type.defaultDigitizeStatus());
        row.setCreatedBy(operator);
        row.setContactLogId(contactLogId);
        mapper.insertReport(row);
        long reportId = row.getId();

        int locationCount = 0;
        RecordRow record = taskRecord(patientId, taskId);
        if (record != null) {
            // 位置预选 = 该任务对应的随访记录（整条，A-ATT-017）；模板版本取记录固化版本（与手动创建同口径）
            mapper.insertLink(
                    new LinkInsert(
                            reportId,
                            patientId,
                            ReportService.LINK_FOLLOWUP_RECORD,
                            record.id(),
                            null,
                            null,
                            record.templateVersion()));
            locationCount = 1;
        }
        int sortNo = 0;
        for (Long fileId : audioFileIds) {
            // 同一批 sys_file（沟通侧 attachAudios 已置 ATTACHED），此处只挂关联行，不再改台账状态
            mapper.insertFile(new ReportMapper.FileInsert(reportId, patientId, fileId, sortNo++));
        }

        audit.append(
                AuditEntry.operation("followup", "RPT_CREATE")
                        .objectType("REPORT")
                        .objectId(String.valueOf(reportId))
                        .description(
                                "通话记录报告单自动生成（关联沟通记录 "
                                        + contactLogId
                                        + "，A-ATT-017 两处同一数据）："
                                        + type.name()
                                        + "，报告时间 "
                                        + row.getReportDate()
                                        + "，位置 "
                                        + (locationCount == 0 ? "未指定" : "任务对应随访记录（整条）")
                                        + "，附件（录音）"
                                        + audioFileIds.size()
                                        + " 个")
                        .afterJson(
                                "{\"contactLogId\":"
                                        + contactLogId
                                        + ",\"typeCode\":\"CALL\",\"fileCount\":"
                                        + audioFileIds.size()
                                        + ",\"locationCount\":"
                                        + locationCount
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "通话记录报告单已生成（E16-T08）：reportId={} contactLogId={} patientId={} audio={} locations={}",
                reportId,
                contactLogId,
                patientId,
                audioFileIds.size(),
                locationCount);
        return reportId;
    }

    /**
     * 删沟通记录 → 连带软删其 CALL 报告单（「任一处删除即两处同时删除」）；录音 sys_file 已由沟通侧软删， 这里只落报告单状态与审计。幂等：报告单已被删（0
     * 行更新）时跳过该份。
     */
    public void softDeleteForContact(long contactLogId, long operator) {
        for (ReportMapper.ReportHeadRow report : mapper.callReportsOfContact(contactLogId)) {
            if (mapper.softDeleteReport(report.id(), CASCADE_DELETE_REASON, operator) == 0) {
                continue; // 并发已被他人删除
            }
            audit.append(
                    AuditEntry.operation("followup", "RPT_DELETE")
                            .objectType("REPORT")
                            .objectId(String.valueOf(report.id()))
                            .description(
                                    "删除报告单："
                                            + report.typeName()
                                            + (report.nameSnapshot() == null
                                                    ? ""
                                                    : " · " + report.nameSnapshot())
                                            + "（沟通记录删除，连带删除，A-ATT-017）")
                            .beforeJson("{\"status\":\"NORMAL\"}")
                            .afterJson(
                                    "{\"status\":\"DELETED\",\"reason\":\""
                                            + CASCADE_DELETE_REASON
                                            + "\",\"contactLogId\":"
                                            + contactLogId
                                            + "}")
                            .result(AuditResult.SUCCESS)
                            .build());
            log.info(
                    "通话记录报告单连带删除（E16-T08）：reportId={} contactLogId={} operator={}",
                    report.id(),
                    contactLogId,
                    operator);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** CALL 类型行（不存在 / 停用 → null，调用方跳过生成）。 */
    private TypeRow enabledCallType() {
        Long id = config.findTypeIdByCode(CALL_TYPE_CODE);
        if (id == null || id <= 0) {
            return null;
        }
        TypeRow type = config.findType(id);
        if (type == null || !"ENABLED".equals(type.status())) {
            return null;
        }
        return type;
    }

    /** CALL 类型的首个启用名称（sort_no 最小；无启用名称 → null，快照用兜底文案）。 */
    private NameRow defaultCallName() {
        List<NameRow> names = config.findNames(CALL_TYPE_CODE, true);
        return names.isEmpty() ? null : names.get(0);
    }

    /**
     * 类型专属字段值（通话时间 / 通话说明，RPT_CALL 表单稳定键）经表单引擎写入闸门校验； 闸门不通过（表单被改配置等，OQ-103②）时不落
     * field_values、报告单照常生成——展示联动优先于字段快照。
     */
    private String callFieldValues(LocalDateTime contactTime, String note, TypeRow type) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_CALL_TIME, CALL_TIME_FORMAT.format(contactTime));
        if (note != null) {
            fields.put(FIELD_CALL_NOTE, note);
        }
        try {
            int version = formService.publishedVersion(type.formCode());
            return JSON.writeValueAsString(
                    formService.writeGateData(type.formCode(), version, fields));
        } catch (ApiException e) {
            log.warn("通话记录报告单字段值未落（表单闸门未通过，报告单照常生成，OQ-103②）：{}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("通话记录报告单字段值序列化失败，报告单照常生成", e);
            return null;
        }
    }

    /** 该任务对应的随访记录（fu_task.record_id → fu_record；无任务 / 未开始录入 → null）。 */
    private RecordRow taskRecord(long patientId, Long taskId) {
        if (taskId == null) {
            return null;
        }
        for (RecordRow record : mapper.recordsOfPatient(patientId)) {
            if (taskId.equals(record.taskId())) {
                return record;
            }
        }
        return null;
    }
}
