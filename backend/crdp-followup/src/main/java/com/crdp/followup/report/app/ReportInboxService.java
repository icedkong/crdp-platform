package com.crdp.followup.report.app;

import com.crdp.followup.file.infra.mapper.SysFileMapper;
import com.crdp.followup.file.infra.mapper.SysFileRow;
import com.crdp.followup.report.infra.mapper.ReportFeatureMapper;
import com.crdp.followup.report.infra.mapper.ReportInboxMapper;
import com.crdp.followup.report.infra.mapper.ReportInboxMapper.Criteria;
import com.crdp.followup.report.infra.mapper.ReportInboxMapper.InboxInsert;
import com.crdp.followup.report.infra.mapper.ReportInboxMapper.InboxRowRaw;
import com.crdp.followup.report.infra.mapper.ReportInboxMapper.Scope;
import com.crdp.followup.report.infra.mapper.ReportInboxMapper.UploaderOptionRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待归档区（E21-T16 / C-SCAN-009，P1；docs/api/report.md §4）：先扫描后标注——未选患者的文件进入待归档区，
 * 录入人员逐个指定患者、类型与位置（至少一个）后归档。
 *
 * <ul>
 *   <li><b>进区</b> {@link #intake}：已上传（sys_file PENDING、未挂任何台账）的文件放入 rpt_inbox_file， 一行一个文件；文件已挂他处 /
 *       已在待归档区 40001。开关 {@code report_inbox} 关闭（含无行）40301 （AGENTS §5.7，A / B 默认不启用，tech-plan §10「A
 *       与 C 的配置差异」）。
 *   <li><b>检索</b> {@link #search}：只出 PENDING 行，按上传日期 / 上传人过滤；同样按科室病区隔离 （C-WARD-005）——暂无患者可作 EXISTS
 *       锚点，按 docs/api/report.md §4 暂行口径「按上传人所在科室 / 病区」：
 *       上传人负责科室（sys_user_dept_scope）或负责病区（sys_user_ward）与操作者范围有交集即可见，
 *       上传人本人恒可见（「仅本人负责的患者」范围用户上传后自己仍能分拣，不滞留）；ALL 范围全见。批量查询写 BATCH_QUERY 数据访问审计（AGENTS §5.4）。
 *   <li><b>归档</b> {@link #archive}：逐个指定患者、类型与位置（至少一个，40001）后生成报告单——复用 {@link ReportService#create}
 *       的全套校验（患者数据范围 40400、类型 / 名称、位置锚点按当前模板版本、 附件上限与归属）与 RPT_CREATE 审计；成功后本表行置 ARCHIVED 并记
 *       report_id 作溯源（行保留）。 越范围 / 不存在 / 已归档的待归档行 40400（写 DENIED 审计，不暴露存在性，AGENTS §5.2）；并发归档
 *       （行已被别人归档）40901，事务回滚不重复生成报告单。
 * </ul>
 */
@Service
public class ReportInboxService {

    /** 待归档区功能开关（C-SCAN-009；components.md §1.3，followup V138 默认关闭）。 */
    static final String FEATURE = "report_inbox";

    /** 一次进区的文件数上限（与 E16-T03 批量上传同口径：一次 ≤ 50 个文件）。 */
    static final int MAX_INTAKE_FILES = 50;

    private static final int PAGE_SIZE_DEFAULT = 20;

    private static final int PAGE_SIZE_MAX = 100;

    private static final Logger log = LoggerFactory.getLogger(ReportInboxService.class);

    private final ReportInboxMapper mapper;
    private final ReportFeatureMapper features;
    private final SysFileMapper files;
    private final ReportService reports;
    private final AuditLogger audit;

    public ReportInboxService(
            ReportInboxMapper mapper,
            ReportFeatureMapper features,
            SysFileMapper files,
            ReportService reports,
            AuditLogger audit) {
        this.mapper = mapper;
        this.features = features;
        this.files = files;
        this.reports = reports;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 进区请求（report.md §4：上传后未选患者的文件清单）。 */
    public record IntakeRequest(List<String> fileIds) {}

    /** 进区结果（文件数；行明细经检索刷新，前端无需行 id）。 */
    public record IntakeView(int fileCount) {}

    /** 检索请求（字段全部可空 = 只按可见性）。 */
    public record SearchRequest(
            Integer pageNo,
            Integer pageSize,
            LocalDate uploadedFrom,
            LocalDate uploadedTo,
            Long creatorId) {}

    /** 列表行（文件台账信息；fileId 供前端换预览短时链接，无患者面数据）。 */
    public record RowView(
            String id,
            String fileId,
            String fileName,
            String format,
            long sizeBytes,
            Integer pages,
            String uploaderName,
            String createdAt) {}

    /** 上传人候选（当前可见范围内出现过的上传人）。 */
    public record UploaderView(String id, String name) {}

    /** 分页结果（统一分页口径 + 上传人候选）。 */
    public record PageView(
            List<RowView> items,
            long total,
            int pageNo,
            int pageSize,
            List<UploaderView> uploaders) {}

    /**
     * 归档请求（report.md §4）：患者、类型必填，位置至少 1 个（创建链路 40001 兜底）； nameId 按类型 nameRequired 校验；reportDate
     * 缺省归档当天（先扫描后标注的纸质资料常无报告日期，取分拣当天）。
     */
    public record ArchiveRequest(
            Long patientId,
            String typeCode,
            String nameId,
            LocalDate reportDate,
            List<ReportService.LocationInput> locations) {}

    // ---------------------------------------------------------------- 进区（先扫描）

    /** 放入待归档区：文件须在册、未删除且仍为 PENDING（未挂任何记录）；一次 1–50 个，整批一个事务。 */
    @Transactional
    public IntakeView intake(IntakeRequest request) {
        requireFeature();
        if (request == null) {
            throw bad("请求体不能为空");
        }
        List<Long> fileIds = normalizeIntakeFileIds(request.fileIds());
        long operator = CurrentSession.requireHospital().userId();
        List<String> names = new ArrayList<>();
        for (Long fileId : fileIds) {
            SysFileRow file = files.find(fileId);
            if (file == null || "DELETED".equals(file.getStatus())) {
                throw new ApiException(404, 40400, "附件文件不存在或已删除");
            }
            if (!"PENDING".equals(file.getStatus())) {
                // ATTACHED = 已挂沟通录音 / 报告单等台账，不能再次进区（sys_file 状态机，V25）
                throw bad("文件已关联其他记录：" + file.getOriginalName());
            }
            if (mapper.findIdByFileId(fileId) != null) {
                throw bad("文件已在待归档区：" + file.getOriginalName());
            }
            try {
                mapper.insert(new InboxInsert(fileId, operator));
            } catch (DuplicateKeyException e) {
                // uk_rpt_inbox_file 兜底（并发同文件进区）：整批回滚，提示后重试
                throw bad("文件已在待归档区：" + file.getOriginalName());
            }
            names.add(file.getOriginalName());
        }
        IntakeView view = new IntakeView(fileIds.size());
        audit.append(
                AuditEntry.operation("followup", "RPT_INBOX_CREATE")
                        .objectType("REPORT_INBOX")
                        .description(
                                "放入待归档区（先扫描后标注，C-SCAN-009）：文件 "
                                        + fileIds.size()
                                        + " 个（"
                                        + String.join("、", abbreviate(names))
                                        + "）")
                        .afterJson(
                                "{\"fileCount\":"
                                        + fileIds.size()
                                        + ",\"fileIds\":"
                                        + fileIds
                                        + "}")
                        .build());
        log.info("待归档区进区（E21-T16）：文件 {} 个，操作人 {}", fileIds.size(), operator);
        return view;
    }

    // ---------------------------------------------------------------- 检索（后标注前的集中分拣）

    /** 条件检索一页（可见性由 {@link Scope} 承担；批量查询写 BATCH_QUERY 审计）。 */
    public PageView search(SearchRequest request) {
        requireFeature();
        SearchRequest safe = request == null ? emptyRequest() : request;
        int pageNo = safe.pageNo() == null ? 1 : safe.pageNo();
        int pageSize = safe.pageSize() == null ? PAGE_SIZE_DEFAULT : safe.pageSize();
        if (pageNo < 1) {
            throw bad("pageNo（从 1 开始）");
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAX) {
            throw bad("pageSize（1–" + PAGE_SIZE_MAX + "）");
        }
        if (safe.uploadedFrom() != null
                && safe.uploadedTo() != null
                && safe.uploadedFrom().isAfter(safe.uploadedTo())) {
            throw bad("uploadedFrom / uploadedTo（起止日期颠倒）");
        }
        Long creatorId = safe.creatorId();
        if (creatorId != null && creatorId <= 0) {
            throw bad("creatorId（须为正整数）");
        }
        Criteria criteria =
                new Criteria(scopeOf(), safe.uploadedFrom(), safe.uploadedTo(), creatorId);
        Scope scope = criteria.scope();

        List<RowView> items = new ArrayList<>();
        for (InboxRowRaw row : mapper.search(criteria, scope, (pageNo - 1) * pageSize, pageSize)) {
            items.add(
                    new RowView(
                            String.valueOf(row.id()),
                            String.valueOf(row.fileId()),
                            row.originalName(),
                            row.formatCode(),
                            row.sizeBytes(),
                            row.pages(),
                            row.uploaderName(),
                            row.createdAt() == null ? null : row.createdAt().toString()));
        }
        long total = mapper.count(criteria, scope);
        List<UploaderView> uploaders = new ArrayList<>();
        for (UploaderOptionRow option : mapper.uploaderOptions(scope)) {
            uploaders.add(new UploaderView(String.valueOf(option.id()), option.name()));
        }

        audit.append(
                AuditEntry.dataAccess("followup", "BATCH_QUERY")
                        .objectType("REPORT_INBOX")
                        .description("检索待归档区列表")
                        .recordCount(total)
                        .queryDigest(digest(safe))
                        .build());
        return new PageView(items, total, pageNo, pageSize, uploaders);
    }

    // ---------------------------------------------------------------- 归档（后标注）

    /**
     * 归档：生成报告单（复用 {@link ReportService#create} 的校验与审计）并把待归档行置 ARCHIVED。 待归档行不可见（按上传人科室 / 病区口径）时
     * 40400 + DENIED 审计；并发归档 40901 整体回滚。
     */
    @Transactional
    public ReportService.CreatedView archive(long inboxId, ArchiveRequest request) {
        requireFeature();
        if (request == null) {
            throw bad("请求体不能为空");
        }
        if (request.patientId() == null || request.patientId() <= 0) {
            throw bad("请选择患者");
        }
        InboxRowRaw row = mapper.findVisible(inboxId, scopeOf());
        if (row == null) {
            audit.append(
                    AuditEntry.operation("followup", "RPT_INBOX_ARCHIVE")
                            .objectType("REPORT_INBOX")
                            .objectId(String.valueOf(inboxId))
                            .description("待归档区归档被拒绝：文件不在操作者数据范围内或已归档")
                            .result(AuditResult.DENIED)
                            .errorCode(40400)
                            .reason("OUT_OF_SCOPE")
                            .build());
            throw new ApiException(404, 40400, "待归档文件不存在或不在您的数据范围内");
        }
        // 位置至少 1 个由创建链路 40001 兜底（「请至少选择 1 个位置」，C-SCAN-009「至少一个」）
        LocalDate reportDate =
                request.reportDate() == null ? LocalDate.now() : request.reportDate();
        ReportService.CreatedView created =
                reports.create(
                        request.patientId(),
                        new ReportService.CreateRequest(
                                request.typeCode(),
                                request.nameId(),
                                reportDate,
                                Map.of(),
                                List.of(),
                                null,
                                null,
                                request.locations() == null ? List.of() : request.locations(),
                                List.of(String.valueOf(row.fileId())),
                                null,
                                null));
        long operator = CurrentSession.requireHospital().userId();
        if (mapper.markArchived(inboxId, Long.parseLong(created.id()), operator) == 0) {
            // 并发归档：行已被别人归档 → 40901，事务回滚（刚生成的报告单一并撤销，不重复）
            throw new ApiException(409, 40901, "该文件已被归档，请刷新列表");
        }
        audit.append(
                AuditEntry.operation("followup", "RPT_INBOX_ARCHIVE")
                        .objectType("REPORT_INBOX")
                        .objectId(String.valueOf(inboxId))
                        .description(
                                "待归档区归档："
                                        + row.originalName()
                                        + " → 报告单 #"
                                        + created.id()
                                        + "（"
                                        + created.typeName()
                                        + (created.name() == null ? "" : " · " + created.name())
                                        + "，患者 "
                                        + request.patientId()
                                        + "，位置 "
                                        + created.locationCount()
                                        + " 个）")
                        .afterJson(
                                "{\"reportId\":"
                                        + created.id()
                                        + ",\"patientId\":"
                                        + request.patientId()
                                        + ",\"fileId\":"
                                        + row.fileId()
                                        + "}")
                        .build());
        log.info(
                "待归档区归档（E21-T16）：inboxId={} → reportId={} patientId={} 操作人 {}",
                inboxId,
                created.id(),
                request.patientId(),
                operator);
        return created;
    }

    // ---------------------------------------------------------------- 内部

    private void requireFeature() {
        Boolean enabled = features.findEnabled(FEATURE);
        if (enabled == null || !enabled) {
            throw new ApiException(403, 40301, "待归档区未开通（功能开关 report_inbox 关闭）");
        }
    }

    /** 进区文件清单：与批量上传同口径解析去重；空清单 / 超 {@link #MAX_INTAKE_FILES} 40001。 */
    private static List<Long> normalizeIntakeFileIds(List<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw bad("请至少选择 1 个文件");
        }
        LinkedHashSet<Long> distinct = new LinkedHashSet<>();
        for (String raw : fileIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                distinct.add(Long.parseLong(raw.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(404, 40400, "附件文件不存在或已删除");
            }
        }
        if (distinct.isEmpty()) {
            throw bad("请至少选择 1 个文件");
        }
        if (distinct.size() > MAX_INTAKE_FILES) {
            throw bad("一次最多放入 " + MAX_INTAKE_FILES + " 个文件");
        }
        return List.copyOf(distinct);
    }

    /** 可见性冻结（{@link Scope}）：ALL 全见；无快照按最受限处理（仅本人上传，fail closed）。 */
    private static Scope scopeOf() {
        long userId = CurrentSession.requireHospital().userId();
        return CurrentSession.current()
                .map(
                        session -> {
                            var snapshot = session.scope();
                            if (snapshot == null || snapshot.all()) {
                                return new Scope(true, userId, List.of(), List.of());
                            }
                            return new Scope(false, userId, snapshot.wardIds(), snapshot.deptIds());
                        })
                .orElseGet(() -> new Scope(false, userId, List.of(), List.of()));
    }

    private static SearchRequest emptyRequest() {
        return new SearchRequest(null, null, null, null, null);
    }

    /** 审计文件名摘要：最多列 3 个，超出折叠为「等 N 个」（完整清单在 afterJson 的 fileIds）。 */
    private static List<String> abbreviate(List<String> names) {
        if (names.size() <= 3) {
            return names;
        }
        List<String> head = new ArrayList<>(names.subList(0, 3));
        head.add("等 " + names.size() + " 个");
        return head;
    }

    /** 检索摘要（审计 queryDigest：只记条件形态，不含明文，AGENTS §5.4）。 */
    private static String digest(SearchRequest request) {
        StringBuilder digest = new StringBuilder("待归档区");
        if (request.uploadedFrom() != null || request.uploadedTo() != null) {
            digest.append("·上传日期");
        }
        if (request.creatorId() != null) {
            digest.append("·上传人");
        }
        return digest.toString();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
