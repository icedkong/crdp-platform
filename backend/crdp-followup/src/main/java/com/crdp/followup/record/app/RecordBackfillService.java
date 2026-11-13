package com.crdp.followup.record.app;

import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 历史补录与计划外随访（E15-T07 / A-FU-014、C-FU-013；E15-T06 起 UNPLANNED /
 * A-FU-013、C-FU-022；docs/api/followup.md §3 {@code POST /patients/{id}/records}）。
 *
 * <p><b>历史补录（BACKFILL）</b>验收口径（A-FU-014「为过去年度补录随访记录，填写实际随访日期，标记为补录」；C-FU-013「不需要任务、不受窗口限制」）：
 * 选择患者后直接新建过去日期的随访记录（source = BACKFILL、visit_date 必填且早于今天），创建 DRAFT 草稿后统一用 recordId
 * 进入随访整页表单（pages.md §5）填写并提交——提交流程与任务录入同一条（{@link RecordEntryService#submit}，无任务
 * 不联动完成）。表单缺省取患者在组计划的 FORM 节点表单（首个；显式传 formCode 时以请求为准）。
 *
 * <p><b>建议随访时间点（E21-T14 / C-FU-014，随 C 医院接入）</b>：创建时经 {@link TimepointSuggestionService}
 * 按随访日期距植入天数建议时间点并<b>种进草稿</b>（visit_timepoint 字段，可改选）；随访日期不得早于患者最早植入日期（C 规格 §5「补录一份纸质随访记录」：
 * 「可填过去日期（不早于植入日期、不晚于今天）」，40001）；无植入记录（A / B 租户、未录植入的 C 患者）无下界。 同一创建接口即「继续补录下一次」（C-FU-015）
 * 的后端入口：连续补录只是前端在提交成功后带同患者再调本接口（保留患者、器械按新随访日期重新匹配，测量值天然清空——新记录）。
 *
 * <p><b>计划外随访（UNPLANNED，E15-T06 /
 * C-FU-022「症状就诊、程控调整等直接新建记录，随访时间点选『其他』；不影响后续节点，但填写了下次随访时间时照常覆盖」）</b>： 同一入口、同一条 fu_record（visit_date
 * 必填、允许当天——补录须早于今天，计划外是「现在发生了的就诊」）， 不创建任务、不动入组 next_plan_date 缓存（「不影响后续节点」：时间轴单独成节点、不作对比对象
 * A-FU-027）；须患者在随访中（ACTIVE，pages.md §4.10 已终止置灰）。「随访时间点选『其他』」是表单字段（不做时间点建议，C-FU-022 与补录的 C-FU-014
 * 分开）。
 *
 * <p><b>远程监测记录（REMOTE，E21-T12 / C-FU-025「随访方式为『远程监测』时使用同一表单人工录入；首期不对接厂商远程监测平台」）</b>：
 * 同一入口、同一张程控表单人工录入（厂商对接首期不做，附录 B），source = REMOTE 与门诊程控（任务 / 补录 /
 * 计划外）区分——时间轴单独成「远程监测」节点、记录页头幅与横幅按来源展示； 功能开关 {@code remote_monitor_record}（医院 C，AGENTS §5.7）关闭
 * 40301；不创建任务、不做时间点建议（远程传输不对应术后节点）；visit_date
 * 必填且不能晚于今天（远程传输收到当天即可录，与计划外同口径）；须患者在随访中（ACTIVE）。提交与补录同一条（POST /records/{id}/submit），照常参与 C-FU-024
 * 重复提示、C-FU-018 序号与对比标红（远程参数是真实测量值，作对比对象——计划外才排除，A-FU-027）。
 */
@Service
public class RecordBackfillService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** fu_record.source：计划外随访（E15-T06 / A-FU-013、C-FU-022）。 */
    static final String SOURCE_UNPLANNED = "UNPLANNED";

    /** fu_record.source：远程监测记录（E21-T12 / C-FU-025）。 */
    static final String SOURCE_REMOTE = "REMOTE";

    /** 功能开关：远程监测记录（医院 C，followup V99；无行 = 关闭 = A / B 行为，AGENTS §5.7）。 */
    static final String FEATURE_REMOTE_MONITOR_RECORD = "remote_monitor_record";

    private final PatientContactGateway patientScope;
    private final FollowupRecordService recordService;
    private final FollowupRecordMapper recordMapper;
    private final com.crdp.form.record.app.FormService formService;
    private final TimepointSuggestionService suggestions;
    private final AuditLogger audit;
    private final com.crdp.followup.record.infra.mapper.RecordFeatureMapper features;

    public RecordBackfillService(
            PatientContactGateway patientScope,
            FollowupRecordService recordService,
            FollowupRecordMapper recordMapper,
            com.crdp.form.record.app.FormService formService,
            TimepointSuggestionService suggestions,
            AuditLogger audit,
            com.crdp.followup.record.infra.mapper.RecordFeatureMapper features) {
        this.patientScope = patientScope;
        this.recordService = recordService;
        this.recordMapper = recordMapper;
        this.formService = formService;
        this.suggestions = suggestions;
        this.audit = audit;
        this.features = features;
    }

    /** 补录 / 计划外 / 远程监测请求（followup.md §3：source 收 BACKFILL / UNPLANNED / REMOTE）。 */
    public record BackfillCommand(String source, LocalDate visitDate, String formCode) {}

    /** 补录创建结果：recordId 即随访表单路由（pages.md §5 统一用 recordId）。 */
    public record BackfillView(
            String recordId, String patientId, LocalDate visitDate, String formCode) {}

    /** 新建历史补录 / 计划外随访草稿（一次事务：范围校验 → 来源分支 → 表单解析 → fu_record DRAFT → 审计）。 */
    @Transactional
    public BackfillView create(long patientId, BackfillCommand command) {
        // 数据范围前置校验（与任务开始录入同口径）：越范围患者 40400，不暴露存在
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        String source = command == null ? null : command.source();
        if (SOURCE_REMOTE.equals(source)) {
            return createRemote(patientId, command);
        }
        if (SOURCE_UNPLANNED.equals(source)) {
            return createUnplanned(patientId, command);
        }
        if (source != null && !FollowupRecordService.SOURCE_BACKFILL.equals(source)) {
            throw new ApiException(
                    400, 40001, "source 只支持 BACKFILL（历史补录）、UNPLANNED（计划外随访）或 REMOTE（远程监测）");
        }
        if (command == null || command.visitDate() == null) {
            throw new ApiException(400, 40001, "补录必须填写实际随访日期（A-FU-014）");
        }
        // 「为过去年度补录」：随访日期须早于今天（北京时间），当天录入走任务 / 工作台
        if (!command.visitDate().isBefore(LocalDate.now(ZONE))) {
            throw new ApiException(400, 40001, "补录随访日期必须是过去的日期");
        }
        return createRecord(
                patientId,
                FollowupRecordService.SOURCE_BACKFILL,
                command.visitDate(),
                command.formCode(),
                "RECORD_BACKFILL_CREATE",
                "历史补录（A-FU-014 / C-FU-013）");
    }

    /** 计划外随访分支（E15-T06 / C-FU-022）：当天可录、须患者在随访中、不影响后续节点。 */
    private BackfillView createUnplanned(long patientId, BackfillCommand command) {
        if (command.visitDate() == null) {
            throw new ApiException(400, 40001, "计划外随访必须填写随访日期（C-FU-022）");
        }
        // 症状就诊、程控调整是「现在发生的随访」：允许当天，不允许未来（未来节点由计划排期）
        if (command.visitDate().isAfter(LocalDate.now(ZONE))) {
            throw new ApiException(400, 40001, "计划外随访日期不能晚于今天（未来随访请等待计划任务开启）");
        }
        String status = recordMapper.findPatientFollowupStatus(patientId);
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(409, 40901, "患者不在随访中，不能新建计划外随访（pages.md §4.10）");
        }
        return createRecord(
                patientId,
                SOURCE_UNPLANNED,
                command.visitDate(),
                command.formCode(),
                "RECORD_UNPLANNED_CREATE",
                "计划外随访（C-FU-022：不影响后续节点；下次随访时间覆盖随 E21）");
    }

    /**
     * 远程监测分支（E21-T12 / C-FU-025）：远程传输结果同一表单人工录入，source = REMOTE 与门诊程控记录区分。 功能开关关闭 40301（医院 C
     * 能力，AGENTS §5.7）；visit_date 必填、不能晚于今天（远程传输收到当天即可录，与计划外同口径）；
     * 须患者在随访中（已终止不再接收远程数据）；不做时间点建议（远程传输不对应术后节点，与补录的 C-FU-014 分开）。
     */
    private BackfillView createRemote(long patientId, BackfillCommand command) {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_REMOTE_MONITOR_RECORD))) {
            throw new ApiException(403, 40301, "远程监测记录未开通（C-FU-025，医院 C 功能）");
        }
        if (command == null || command.visitDate() == null) {
            throw new ApiException(400, 40001, "远程监测记录必须填写随访日期（C-FU-025）");
        }
        if (command.visitDate().isAfter(LocalDate.now(ZONE))) {
            throw new ApiException(400, 40001, "远程监测随访日期不能晚于今天");
        }
        String status = recordMapper.findPatientFollowupStatus(patientId);
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(409, 40901, "患者不在随访中，不能录入远程监测记录（C-FU-025）");
        }
        return createRecord(
                patientId,
                SOURCE_REMOTE,
                command.visitDate(),
                command.formCode(),
                "RECORD_REMOTE_CREATE",
                "远程监测记录（C-FU-025：同一表单人工录入，首期不对接厂商远程监测平台）");
    }

    /** 共用落库：表单解析 → DRAFT fu_record → 审计（source 落列与 data_meta 双记，时间轴按 source 分流）。 */
    private BackfillView createRecord(
            long patientId,
            String source,
            LocalDate visitDate,
            String formCode,
            String auditAction,
            String auditLabel) {
        String resolved = resolveFormCode(patientId, formCode);
        // 建议随访时间点（E21-T14 / C-FU-014）：只有补录分支建议（计划外选「其他」、远程传输不对应术后节点，
        // C-FU-022 / C-FU-025 与补录的 C-FU-014 分开）；种进草稿、可改选。
        // 无建议（空档区间 / 无器械 / 表单无 visit_timepoint 字段〔A / B〕）时 initialData 为空 Map，不动表单。
        TimepointSuggestionService.SuggestionView suggestion =
                SOURCE_UNPLANNED.equals(source) || SOURCE_REMOTE.equals(source)
                        ? null
                        : suggestions.suggest(patientId, visitDate, resolved);
        if (suggestion != null
                && suggestion.earliestImplantDate() != null
                && visitDate.isBefore(suggestion.earliestImplantDate())) {
            throw new ApiException(
                    400,
                    40001,
                    "补录随访日期不能早于植入日期（" + suggestion.earliestImplantDate() + "），C-FU-013/014");
        }
        Map<String, Object> initialData =
                suggestion == null ? Map.of() : suggestions.initialData(resolved, suggestion);
        FollowupRecordService.RecordView created =
                recordService.createDraft(
                        new FollowupRecordService.CreateCommand(
                                patientId,
                                null,
                                source,
                                visitDate,
                                resolved,
                                initialData,
                                java.util.Map.of("source", source)));
        audit.append(
                AuditEntry.operation("followup", auditAction)
                        .objectType("FU_RECORD")
                        .objectId(String.valueOf(created.id()))
                        .description(
                                auditLabel
                                        + "：患者 "
                                        + patientId
                                        + "，随访日期 "
                                        + visitDate
                                        + "，表单 "
                                        + resolved)
                        .afterJson(
                                "{\"recordId\":"
                                        + created.id()
                                        + ",\"patientId\":"
                                        + patientId
                                        + ",\"visitDate\":\""
                                        + visitDate
                                        + "\",\"formCode\":\""
                                        + resolved
                                        + "\",\"source\":\""
                                        + source
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        return new BackfillView(
                String.valueOf(created.id()), String.valueOf(patientId), visitDate, resolved);
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
