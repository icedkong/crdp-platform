package com.crdp.followup.record.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史补录（E15-T07 / A-FU-014、C-FU-013；docs/api/followup.md §3 {@code POST /patients/{id}/records}）。
 *
 * <p>验收口径（A-FU-014「为过去年度补录随访记录，填写实际随访日期，标记为补录」；C-FU-013「不需要任务、不受窗口限制」）： 选择患者后直接新建过去日期的随访记录（source =
 * BACKFILL、visit_date 必填），创建 DRAFT 草稿后统一用 recordId 进入随访整页表单（pages.md §5）填写并提交——提交流程与任务录入同一条（{@link
 * RecordEntryService#submit}，无任务 不联动完成）。表单缺省取患者在组计划的 FORM 节点表单（首个；显式传 formCode 时以请求为准）。
 *
 * <p>计划外随访（UNPLANNED，A-FU-013）随 E15-T06、远程监测（REMOTE，C-FU-025）随 E21 开放，本入口先 40001。
 * 补录建议随访时间点（C-FU-014）与重复记录提示（C-FU-024）随 C 医院接入（E21 / E23）。
 */
@Service
public class RecordBackfillService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** fu_record.source 之外的来源（本入口暂不开放）。 */
    private static final String SOURCE_UNPLANNED = "UNPLANNED";

    private static final String SOURCE_REMOTE = "REMOTE";

    private final PatientContactGateway patientScope;
    private final FollowupRecordService recordService;
    private final FollowupRecordMapper recordMapper;
    private final com.crdp.form.record.app.FormService formService;
    private final AuditLogger audit;

    public RecordBackfillService(
            PatientContactGateway patientScope,
            FollowupRecordService recordService,
            FollowupRecordMapper recordMapper,
            com.crdp.form.record.app.FormService formService,
            AuditLogger audit) {
        this.patientScope = patientScope;
        this.recordService = recordService;
        this.recordMapper = recordMapper;
        this.formService = formService;
        this.audit = audit;
    }

    /** 补录请求（followup.md §3：source 目前只收 BACKFILL）。 */
    public record BackfillCommand(String source, LocalDate visitDate, String formCode) {}

    /** 补录创建结果：recordId 即随访表单路由（pages.md §5 统一用 recordId）。 */
    public record BackfillView(
            String recordId, String patientId, LocalDate visitDate, String formCode) {}

    /** 新建历史补录草稿（一次事务：范围校验 → 表单解析 → fu_record DRAFT → 审计）。 */
    @Transactional
    public BackfillView create(long patientId, BackfillCommand command) {
        // 数据范围前置校验（与任务开始录入同口径）：越范围患者 40400，不暴露存在
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        String source = command == null ? null : command.source();
        if (SOURCE_UNPLANNED.equals(source)) {
            throw new ApiException(400, 40001, "计划外随访随 E15-T06 开放，暂不支持该来源");
        }
        if (SOURCE_REMOTE.equals(source)) {
            throw new ApiException(400, 40001, "远程监测记录随 E21 开放，暂不支持该来源");
        }
        if (source != null && !FollowupRecordService.SOURCE_BACKFILL.equals(source)) {
            throw new ApiException(400, 40001, "source 只支持 BACKFILL（历史补录）");
        }
        if (command == null || command.visitDate() == null) {
            throw new ApiException(400, 40001, "补录必须填写实际随访日期（A-FU-014）");
        }
        // 「为过去年度补录」：随访日期须早于今天（北京时间），当天录入走任务 / 工作台
        if (!command.visitDate().isBefore(LocalDate.now(ZONE))) {
            throw new ApiException(400, 40001, "补录随访日期必须是过去的日期");
        }
        String formCode = resolveFormCode(patientId, command.formCode());
        FollowupRecordService.RecordView created =
                recordService.createDraft(
                        new FollowupRecordService.CreateCommand(
                                patientId,
                                null,
                                FollowupRecordService.SOURCE_BACKFILL,
                                command.visitDate(),
                                formCode,
                                java.util.Map.of(),
                                java.util.Map.of("source", "BACKFILL")));
        audit.append(
                AuditEntry.operation("followup", "RECORD_BACKFILL_CREATE")
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(created.id()))
                        .description(
                                "历史补录：患者 "
                                        + patientId
                                        + "，随访日期 "
                                        + command.visitDate()
                                        + "，表单 "
                                        + formCode
                                        + "（A-FU-014 / C-FU-013）")
                        .afterJson(
                                "{\"recordId\":"
                                        + created.id()
                                        + ",\"patientId\":"
                                        + patientId
                                        + ",\"visitDate\":\""
                                        + command.visitDate()
                                        + "\",\"formCode\":\""
                                        + formCode
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new BackfillView(
                String.valueOf(created.id()),
                String.valueOf(patientId),
                command.visitDate(),
                formCode);
    }

    /** 表单解析：显式传 formCode 时校验存在（40400）；缺省取患者在组计划首个 FORM 节点表单，无可解析 40001。 */
    private String resolveFormCode(long patientId, String requested) {
        if (requested != null && !requested.isBlank()) {
            formService.publishedVersion(requested); // 表单不存在 / 未发布 → 40400
            return requested;
        }
        String derived = recordMapper.findEnrollmentFormCode(patientId);
        if (derived == null) {
            throw new ApiException(400, 40001, "患者在组计划没有可用的随访表单，请显式指定 formCode 或先加入随访计划");
        }
        return derived;
    }
}
