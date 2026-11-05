package com.crdp.followup.contact.app;

import com.crdp.followup.contact.domain.ContactMethod;
import com.crdp.followup.contact.domain.ContactResult;
import com.crdp.followup.contact.infra.mapper.ContactLogMapper;
import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.domain.FileCategory;
import com.crdp.followup.file.domain.FileFormat;
import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.plan.app.FollowupTerminationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 沟通记录（E15-T04 / A-FU-006、A-FU-025、C-FU-019；docs/api/followup.md §6，pages.md §4.8）。
 *
 * <p>每次联系记一条：方式、结果（六值字典完整）、时间（默认当前）、所用联系方式（引用 pt_contact 版本， 不落号码明文）、情况说明、下次联系时间、录音附件。数据范围：患者面读取由
 * Mapper 语句上的 {@code @DataScope} 出行，单行写入口先经 {@link PatientContactGateway} 校验（越范围 40400，不暴露存在）。
 *
 * <p>业务联动（同一事务）：
 *
 * <ul>
 *   <li>第一次联系 → 任务进入「进行中」（{@link FollowupTaskStatusService#markInProgress}，只从待随访进、
 *       逾期不退回，OQ-82①；followup.md §4 预留的接入点）；
 *   <li>结果 = 告知已故 → {@link FollowupTerminationService#terminateOnDeath}（患者 → 已死亡、未完成任务
 *       全部终止、之后不再创建任务，A-FU-007；OQ-79② 的 HTTP 触发接入，幂等）；
 *   <li>录音（MP3 / M4A / WAV / AMR，A-FU-025）：先经文件服务上传（真实类型与 50 MB 上限在 E10-T01 校验）， 这里校验类别为 AUDIO、状态
 *       PENDING 且未挂他处，关联后置 ATTACHED；删除沟通记录连带软删录音 （A-ATT-017「任一处删除即两处同时删除」；录音同时显示为「通话记录」报告单随
 *       E16-T08）。
 * </ul>
 *
 * <p>失访判定（A-FU-012）与未联系到自动跟进（A-FU-024）按本表计数，随 E15-T05。写操作写审计（AGENTS §5.4）。
 */
@Service
public class ContactLogService {

    private static final Logger log = LoggerFactory.getLogger(ContactLogService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final int NOTE_MAX = 2000;

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 100;

    private final ContactLogMapper mapper;
    private final SysFileMapper files;
    private final PatientContactGateway patientScope;
    private final FollowupTaskMapper taskMapper;
    private final FollowupTaskStatusService taskStatus;
    private final FollowupTerminationService termination;
    private final FilePolicyService filePolicy;
    private final AuditLogger audit;

    public ContactLogService(
            ContactLogMapper mapper,
            SysFileMapper files,
            PatientContactGateway patientScope,
            FollowupTaskMapper taskMapper,
            FollowupTaskStatusService taskStatus,
            FollowupTerminationService termination,
            FilePolicyService filePolicy,
            AuditLogger audit) {
        this.mapper = mapper;
        this.files = files;
        this.patientScope = patientScope;
        this.taskMapper = taskMapper;
        this.taskStatus = taskStatus;
        this.termination = termination;
        this.filePolicy = filePolicy;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 录音展示行（AMR 的 inlinePlayable=false：只保存与下载，A-FU-025）。 */
    public record AudioView(
            String fileId,
            String format,
            String originalName,
            String mimeType,
            long sizeBytes,
            boolean inlinePlayable) {}

    /** 沟通记录视图（result / method 为枚举编码，中文映射在前端 packages/api，components.md §2）。 */
    public record ContactLogView(
            String id,
            String patientId,
            String taskId,
            String method,
            String result,
            LocalDateTime contactTime,
            String contactVersionId,
            LocalDateTime nextContactTime,
            String note,
            int version,
            String creatorName,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<AudioView> audios) {}

    /** 创建结果（terminatedTasks = 结果「告知已故」时本次终止的未完成任务数，其余 null）。 */
    public record CreateResult(ContactLogView log, Integer terminatedTasks) {}

    /** 分页结果（统一分页口径）。 */
    public record ContactLogPage(
            List<ContactLogView> items, long total, int pageNo, int pageSize) {}

    /** 创建 / 修改请求体（docs/api/followup.md §6；audioFileIds 仅创建时生效）。 */
    public record SaveBody(
            Long taskId,
            String method,
            String result,
            LocalDateTime contactTime,
            Long contactVersionId,
            LocalDateTime nextContactTime,
            String note,
            List<Long> audioFileIds) {}

    /** 修改请求体（baseVersion 乐观锁；contactTime 缺省保持原值，可空字段传 null 即清空）。 */
    public record UpdateBody(
            String method,
            String result,
            LocalDateTime contactTime,
            Long contactVersionId,
            LocalDateTime nextContactTime,
            String note,
            Integer baseVersion) {}

    // ---------------------------------------------------------------- 读取（pages.md §4.8 沟通记录页签）

    /** 患者沟通记录分页（倒序，最近在前；越范围患者 40400，不暴露存在）。 */
    public ContactLogPage list(long patientId, Integer pageNo, Integer pageSize) {
        requirePatientInScope(patientId);
        int page = pageNo == null ? 1 : pageNo;
        int size = pageSize == null ? PAGE_SIZE_DEFAULT : pageSize;
        if (page < 1) {
            throw bad("pageNo（从 1 开始）");
        }
        if (size < 1 || size > PAGE_SIZE_MAX) {
            throw bad("pageSize（1–" + PAGE_SIZE_MAX + "）");
        }
        List<ContactLogMapper.ContactLogRow> rows =
                mapper.pageOf(patientId, (page - 1) * size, size);
        long total = mapper.countOf(patientId);
        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("CONTACT_LOG")
                        .description("查询患者沟通记录列表（A-FU-006）")
                        .recordCount(total)
                        .build());
        return new ContactLogPage(viewsOf(rows), total, page, size);
    }

    /** 任务列表「最近联系」：每患者最新一条联系结果（patientIds 已在数据范围内由 Mapper 语句过滤）。 */
    public Map<Long, String> latestResultByPatients(List<Long> patientIds) {
        if (patientIds == null || patientIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, ContactLogMapper.LatestResultRow> latest = new LinkedHashMap<>();
        for (ContactLogMapper.LatestResultRow row : mapper.resultsOfPatients(patientIds)) {
            latest.merge(
                    row.patientId(),
                    row,
                    (oldRow, newRow) -> newRow.id() > oldRow.id() ? newRow : oldRow);
        }
        Map<Long, String> results = new LinkedHashMap<>();
        latest.forEach((patientId, row) -> results.put(patientId, row.result()));
        return results;
    }

    // ---------------------------------------------------------------- 写入

    /** 记一次联系（A-FU-006）；结果「告知已故」同事务触发死亡终止（A-FU-007）。 */
    @Transactional
    public CreateResult create(long patientId, SaveBody body) {
        if (body == null) {
            throw bad("请求体不能为空");
        }
        requirePatientInScope(patientId);
        ContactMethod method = requireMethod(body.method());
        ContactResult result = requireResult(body.result());
        String note = noteOf(body.note());
        LocalDateTime contactTime =
                body.contactTime() == null ? LocalDateTime.now(ZONE) : body.contactTime();
        LocalDateTime nextContactTime = body.nextContactTime();
        if (nextContactTime != null && !nextContactTime.isAfter(contactTime)) {
            throw bad("下次联系时间必须晚于本次沟通时间");
        }
        Long contactVersionId = requireContactVersion(patientId, body.contactVersionId());
        Long taskId = requireTaskOfPatient(patientId, body.taskId());
        List<Long> audioFileIds = distinctAudioIds(body.audioFileIds());

        ContactLogMapper.ContactLogInsert row = new ContactLogMapper.ContactLogInsert();
        row.setPatientId(patientId);
        row.setTaskId(taskId);
        row.setMethod(method.code());
        row.setResult(result.code());
        row.setContactTime(contactTime);
        row.setContactVersionId(contactVersionId);
        row.setNextContactTime(nextContactTime);
        row.setNote(note);
        row.setCreatedBy(CurrentSession.requireHospital().userId());
        mapper.insert(row);

        attachAudios(row.getId(), audioFileIds);

        if (taskId != null) {
            // 第一次联系 → 进行中（只从待随访进，幂等不写审计；followup.md §4 预留接入点）
            taskStatus.markInProgress(taskId, row.getCreatedBy(), "记录沟通");
        }
        Integer terminatedTasks = null;
        if (result.triggersDeathTermination()) {
            terminatedTasks =
                    termination
                            .terminateOnDeath(patientId, "沟通记录「告知已故」", row.getCreatedBy())
                            .terminatedTasks();
        }
        audit.append(
                AuditEntry.operation("followup", "CONTACT_CREATE")
                        .objectType("CONTACT_LOG")
                        .objectId(String.valueOf(row.getId()))
                        .description(
                                "记录沟通："
                                        + method.label()
                                        + " · "
                                        + result.label()
                                        + (audioFileIds.isEmpty()
                                                ? ""
                                                : "，附录音 " + audioFileIds.size() + " 个")
                                        + (result.triggersDeathTermination()
                                                ? "；患者标记死亡，未完成任务终止 " + terminatedTasks + " 条"
                                                : ""))
                        .afterJson(
                                "{\"method\":\""
                                        + method.code()
                                        + "\",\"result\":\""
                                        + result.code()
                                        + "\",\"taskId\":"
                                        + (taskId == null ? "null" : taskId)
                                        + ",\"audioCount\":"
                                        + audioFileIds.size()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info(
                "沟通记录已创建 id={} patient={} result={} audio={} deathTerminated={}",
                row.getId(),
                patientId,
                result.code(),
                audioFileIds.size(),
                terminatedTasks);
        return new CreateResult(viewsOf(List.of(requireRow(row.getId()))).get(0), terminatedTasks);
    }

    /** 修改（乐观锁 baseVersion，冲突 409；结果改为「告知已故」同样触发死亡终止，幂等）。 */
    @Transactional
    public ContactLogView update(long contactId, UpdateBody body) {
        if (body == null) {
            throw bad("请求体不能为空");
        }
        ContactLogMapper.ContactLogRow existing = requireContactInScope(contactId);
        ContactMethod method = requireMethod(body.method());
        ContactResult result = requireResult(body.result());
        String note = noteOf(body.note());
        LocalDateTime contactTime =
                body.contactTime() == null ? existing.contactTime() : body.contactTime();
        LocalDateTime nextContactTime = body.nextContactTime();
        if (nextContactTime != null && !nextContactTime.isAfter(contactTime)) {
            throw bad("下次联系时间必须晚于本次沟通时间");
        }
        Long contactVersionId =
                requireContactVersion(existing.patientId(), body.contactVersionId());
        int baseVersion = body.baseVersion() == null ? existing.lockVersion() : body.baseVersion();
        if (mapper.update(
                        contactId,
                        baseVersion,
                        method.code(),
                        result.code(),
                        contactTime,
                        contactVersionId,
                        nextContactTime,
                        note)
                == 0) {
            throw new ApiException(409, 40900, "沟通记录已被他人修改，请刷新后重试");
        }
        if (result.triggersDeathTermination()
                && !ContactResult.of(existing.result())
                        .map(ContactResult::triggersDeathTermination)
                        .orElse(false)) {
            termination.terminateOnDeath(
                    existing.patientId(), "沟通记录「告知已故」", CurrentSession.requireHospital().userId());
        }
        audit.append(
                AuditEntry.operation("followup", "CONTACT_UPDATE")
                        .objectType("CONTACT_LOG")
                        .objectId(String.valueOf(contactId))
                        .description("修改沟通记录：" + existing.result() + " → " + result.code())
                        .beforeJson(
                                "{\"method\":\""
                                        + existing.method()
                                        + "\",\"result\":\""
                                        + existing.result()
                                        + "\"}")
                        .afterJson(
                                "{\"method\":\""
                                        + method.code()
                                        + "\",\"result\":\""
                                        + result.code()
                                        + "\"}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("沟通记录已修改 id={} result={}", contactId, result.code());
        return viewsOf(List.of(requireRow(contactId))).get(0);
    }

    /** 删除（连带软删录音，A-ATT-017「任一处删除即两处同时删除」；任务与失访计数不回滚）。 */
    @Transactional
    public void delete(long contactId) {
        ContactLogMapper.ContactLogRow existing = requireContactInScope(contactId);
        List<ContactLogMapper.AudioRow> audios = mapper.audiosOf(List.of(contactId));
        mapper.delete(contactId);
        mapper.deleteAudiosOf(contactId);
        for (ContactLogMapper.AudioRow audio : audios) {
            files.markDeleted(audio.fileId());
        }
        audit.append(
                AuditEntry.operation("followup", "CONTACT_DELETE")
                        .objectType("CONTACT_LOG")
                        .objectId(String.valueOf(contactId))
                        .description(
                                "删除沟通记录（结果 "
                                        + existing.result()
                                        + "，连带删除录音 "
                                        + audios.size()
                                        + " 个，两处同时删除 A-ATT-017）")
                        .beforeJson(
                                "{\"method\":\""
                                        + existing.method()
                                        + "\",\"result\":\""
                                        + existing.result()
                                        + "\",\"audioCount\":"
                                        + audios.size()
                                        + "}")
                        .result(AuditResult.SUCCESS)
                        .build());
        log.info("沟通记录已删除 id={} audio={}", contactId, audios.size());
    }

    // ---------------------------------------------------------------- 内部

    /** 患者数据范围校验（越范围 / 不存在 40400，不暴露存在）。 */
    private void requirePatientInScope(long patientId) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或无权访问");
        }
    }

    /** 单行读取 + 范围校验（Mapper 单行读为 @IgnoreScope：范围由这里承担）。 */
    private ContactLogMapper.ContactLogRow requireContactInScope(long contactId) {
        ContactLogMapper.ContactLogRow row = mapper.find(contactId);
        if (row == null) {
            throw new ApiException(404, 40400, "沟通记录不存在");
        }
        requirePatientInScope(row.patientId());
        return row;
    }

    private ContactLogMapper.ContactLogRow requireRow(long contactId) {
        ContactLogMapper.ContactLogRow row = mapper.find(contactId);
        if (row == null) {
            throw new ApiException(404, 40400, "沟通记录不存在");
        }
        return row;
    }

    /** 沟通方式枚举校验（40001，提示可用值）。 */
    private static ContactMethod requireMethod(String code) {
        return ContactMethod.of(code == null ? null : code.trim())
                .orElseThrow(
                        () ->
                                bad(
                                        "沟通方式必须是："
                                                + ContactMethod.ALL.stream()
                                                        .map(ContactMethod::label)
                                                        .reduce((a, b) -> a + " / " + b)
                                                        .orElse("")));
    }

    /** 联系结果枚举校验（40001，提示六值——联系结果字典完整）。 */
    private static ContactResult requireResult(String code) {
        return ContactResult.of(code == null ? null : code.trim())
                .orElseThrow(
                        () ->
                                bad(
                                        "联系结果必须是："
                                                + ContactResult.ALL.stream()
                                                        .map(ContactResult::label)
                                                        .reduce((a, b) -> a + " / " + b)
                                                        .orElse("")));
    }

    /** 情况说明长度校验（≤2000，列宽）。 */
    private static String noteOf(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > NOTE_MAX) {
            throw bad("情况说明不能超过 " + NOTE_MAX + " 字");
        }
        return trimmed;
    }

    /** 所用联系方式：须为该患者的联系方式版本行（引用版本，不落号码明文）。 */
    private Long requireContactVersion(long patientId, Long contactVersionId) {
        if (contactVersionId == null) {
            return null;
        }
        boolean owned =
                patientScope.versionsOf(patientId).stream()
                        .anyMatch(version -> contactVersionId.equals(version.id()));
        if (!owned) {
            throw bad("所用联系方式不属于该患者");
        }
        return contactVersionId;
    }

    /** 关联任务：存在（40400）且属于该患者（40001）；NULL = 不挂任务。 */
    private Long requireTaskOfPatient(long patientId, Long taskId) {
        if (taskId == null) {
            return null;
        }
        FollowupTaskMapper.TaskRow task = taskMapper.findTaskById(taskId);
        if (task == null) {
            throw new ApiException(404, 40400, "任务不存在");
        }
        if (task.patientId() != patientId) {
            throw bad("任务与患者不一致，不能挂到该沟通记录");
        }
        return taskId;
    }

    /** 录音清单去重与数量上限（每条沟通记录 ≤ maxFilesPerReport，同报告单口径，E10 平台参数）。 */
    private List<Long> distinctAudioIds(List<Long> audioFileIds) {
        if (audioFileIds == null || audioFileIds.isEmpty()) {
            return List.of();
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long id : audioFileIds) {
            if (id != null) {
                distinct.add(id);
            }
        }
        List<Long> ids = List.copyOf(distinct);
        if (!filePolicy.policy().withinFileCount(ids.size())) {
            throw bad("录音数量超过上限（每条沟通记录最多 " + filePolicy.policy().maxFilesPerReport() + " 个）");
        }
        return ids;
    }

    /** 校验并关联录音：类别 AUDIO、状态 PENDING、未挂他处；关联后置 ATTACHED。 */
    private void attachAudios(long contactLogId, List<Long> audioFileIds) {
        int sortNo = 0;
        for (Long fileId : audioFileIds) {
            SysFileRow file = files.find(fileId);
            if (file == null || "DELETED".equals(file.getStatus())) {
                throw new ApiException(404, 40400, "录音文件不存在或已删除");
            }
            FileFormat format = FileFormat.ofExtension(file.getFormatCode()).orElse(null);
            if (format == null || format.category() != FileCategory.AUDIO) {
                throw bad("只能附加录音文件（MP3 / M4A / WAV / AMR），" + file.getOriginalName() + " 不是录音");
            }
            if (files.markAttached(fileId) == 0) {
                throw bad("录音已关联其他记录：" + file.getOriginalName());
            }
            mapper.insertAudio(contactLogId, fileId, sortNo++);
        }
    }

    /** 行 → 视图（含录音展示信息）。 */
    private List<ContactLogView> viewsOf(List<ContactLogMapper.ContactLogRow> rows) {
        List<Long> ids = rows.stream().map(ContactLogMapper.ContactLogRow::id).toList();
        Map<Long, List<AudioView>> audios = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            for (ContactLogMapper.AudioRow audio : mapper.audiosOf(ids)) {
                audios.computeIfAbsent(audio.contactLogId(), key -> new ArrayList<>())
                        .add(
                                new AudioView(
                                        String.valueOf(audio.fileId()),
                                        audio.format(),
                                        audio.originalName(),
                                        audio.mimeType(),
                                        audio.sizeBytes(),
                                        inlinePlayable(audio.format())));
            }
        }
        List<ContactLogView> views = new ArrayList<>(rows.size());
        for (ContactLogMapper.ContactLogRow row : rows) {
            views.add(
                    new ContactLogView(
                            String.valueOf(row.id()),
                            String.valueOf(row.patientId()),
                            row.taskId() == null ? null : String.valueOf(row.taskId()),
                            row.method(),
                            row.result(),
                            row.contactTime(),
                            row.contactVersionId() == null
                                    ? null
                                    : String.valueOf(row.contactVersionId()),
                            row.nextContactTime(),
                            row.note(),
                            row.lockVersion(),
                            row.creatorName(),
                            row.createdAt(),
                            row.updatedAt(),
                            audios.getOrDefault(row.id(), List.of())));
        }
        return views;
    }

    /** 浏览器原生可播：MP3 / M4A / WAV 可、AMR 只保存与下载（A-FU-025，与 FilePreviewService 同口径）。 */
    private static boolean inlinePlayable(String formatCode) {
        return FileFormat.ofExtension(formatCode)
                .filter(format -> format != FileFormat.AMR && format != FileFormat.XLSX)
                .isPresent();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
