package com.crdp.followup.report.app;

import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.app.FileUploadService;
import com.crdp.followup.report.app.MobileUploadTokenStore.Payload;
import com.crdp.followup.report.app.ReportService.CreatedView;
import com.crdp.followup.report.app.ReportService.LocationInput;
import com.crdp.followup.report.app.ReportService.LocationNodeView;
import com.crdp.followup.report.app.ReportService.LocationValueView;
import com.crdp.followup.report.infra.mapper.MobileUploadMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 手机扫码拍照上传（E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006；docs/api/report.md §5）。
 *
 * <p>电脑端登录会话签发**一次性二维码**（15 分钟、绑定当前用户与患者，令牌在 Redis）：手机扫码进入 H5（apps/mobile-upload），选类型 与位置（下拉、至少 1
 * 个，从分类 / 分组内生成的二维码预选该位置）→ 拍照 / 选图（前端压缩后上传）→ 提交直接生成该患者的报告单，令牌 随即作废。H5 请求的会话上下文由 H5UploadTokenFilter
 * 按令牌装载（签发人实时会话：权限、数据范围、租户全部现场判定），服务层数据范围、 附件归属、每份上限、位置模板版本校验与电脑端创建同一套（复用 {@link
 * ReportService#create}），写 RPT_CREATE 审计 + 本服务另记 RPT_MOBILE_SUBMIT / RPT_UPLOAD_TOKEN_ISSUE。
 *
 * <p>令牌过期或已用（含签发人会话已结束）：统一 40101「二维码已过期，请在电脑上重新生成」（OQ-25 文案）。C 要求手机端登录本院账号 （C-NFR-006）：租户
 * cfg_feature {@code h5_upload_login} 打开后由过滤器强制（A 默认关闭，无行即不要求）。
 */
@Service
public class MobileUploadService {

    private static final Logger log = LoggerFactory.getLogger(MobileUploadService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String IP_LIMIT_PREFIX = "crdp:h5-upload:ip:";

    private final MobileUploadTokenStore tokens;
    private final MobileUploadProperties properties;
    private final MobileUploadMapper mapper;
    private final ReportPatientGateway patientScope;
    private final ReportService reports;
    private final ReportConfigService catalog;
    private final FileUploadService uploads;
    private final FilePolicyService filePolicy;
    private final CryptoService crypto;
    private final AuditLogger audit;
    private final StringRedisTemplate redis;

    public MobileUploadService(
            MobileUploadTokenStore tokens,
            MobileUploadProperties properties,
            MobileUploadMapper mapper,
            ReportPatientGateway patientScope,
            ReportService reports,
            ReportConfigService catalog,
            FileUploadService uploads,
            FilePolicyService filePolicy,
            CryptoService crypto,
            AuditLogger audit,
            StringRedisTemplate redis) {
        this.tokens = tokens;
        this.properties = properties;
        this.mapper = mapper;
        this.patientScope = patientScope;
        this.reports = reports;
        this.catalog = catalog;
        this.uploads = uploads;
        this.filePolicy = filePolicy;
        this.crypto = crypto;
        this.audit = audit;
        this.redis = redis;
    }

    // ---------------------------------------------------------------- DTO

    /** 签发结果（docs/api/report.md §5：qrUrl 令牌只放 URL 片段）。 */
    public record IssueView(String token, String qrUrl, long expiresAtEpochSecond) {}

    /** H5 引导信息（无需登录即可取：登录表单租户预填与过期提示用；不含患者数据）。 */
    public record ContextView(
            String tenantCode,
            String tenantName,
            boolean requireLogin,
            long expiresAtEpochSecond) {}

    /** H5 页面信息（患者脱敏 + 类型 / 名称 + 位置下拉；选项与电脑端同源）。 */
    public record TypeInfoView(
            String code, String name, boolean nameRequired, List<NameView> names) {}

    public record NameView(String id, String name) {}

    public record LocationOptionView(String key, String label, LocationValueView value) {}

    public record InfoView(
            String patientName,
            String medicalRecordNo,
            String wardName,
            LocalDate today,
            List<TypeInfoView> types,
            List<LocationOptionView> locationOptions,
            String presetKey,
            boolean requireLogin,
            long expiresAtEpochSecond) {}

    /** 提交命令（fileIds 取令牌内手机已上传清单，不由手机端回传——防拼接他人文件）。 */
    public record SubmitCommand(
            String typeCode, String nameId, LocalDate reportDate, List<LocationInput> locations) {}

    /** 电脑端轮询状态（PENDING / SUBMITTED / EXPIRED）。 */
    public record StatusView(String status, String reportId, long expiresAtEpochSecond) {}

    // ---------------------------------------------------------------- 电脑端（登录会话）

    /**
     * 签发一次性上传令牌（followup:scan:upload 在控制器校验）：患者须在当前用户数据范围内（越范围 40400）； 写 RPT_UPLOAD_TOKEN_ISSUE
     * 操作日志。
     */
    public IssueView issue(long patientId, LocationInput presetLocation) {
        SessionPrincipal issuer = CurrentSession.requireHospital();
        if (patientId <= 0) {
            throw bad("缺少患者");
        }
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在");
        }
        MobileUploadTokenStore.Issued issued =
                tokens.issue(
                        issuer.token(),
                        issuer.tenantId(),
                        issuer.tenantCode(),
                        patientId,
                        presetLocation);
        String qrUrl = properties.getMobileBaseUrl() + "/#/u/" + issued.token();
        audit.append(
                AuditEntry.operation("followup", "RPT_UPLOAD_TOKEN_ISSUE")
                        .objectType("PATIENT")
                        .objectId(String.valueOf(patientId))
                        .description("生成手机上传二维码（15 分钟有效，绑定本人与患者）：" + maskedPatientLabel(patientId))
                        .build());
        log.info(
                "手机上传二维码签发（E16-T10）：patientId={} userId={} expiresAt={}",
                patientId,
                issuer.userId(),
                issued.expiresAtEpochSecond());
        return new IssueView(issued.token(), qrUrl, issued.expiresAtEpochSecond());
    }

    /** 电脑端轮询（打开二维码弹窗期间每几秒一次）：手机提交后带回 reportId，过期报 EXPIRED。 */
    public StatusView status(String token) {
        CurrentSession.requireHospital();
        Payload payload = tokens.find(token);
        if (payload == null) {
            return new StatusView("EXPIRED", null, 0);
        }
        return new StatusView(
                payload.status(), payload.reportId(), now() + tokens.remainingSeconds(token));
    }

    // ---------------------------------------------------------------- 手机端（一次性令牌）

    /** H5 引导信息（无患者数据；令牌不存在 / 已用统一 40101）。 */
    public ContextView context(String token) {
        Payload payload = requireToken(token);
        boolean requireLogin = loginRequired();
        return new ContextView(
                payload.tenantCode(),
                CurrentSession.current()
                        .map(SessionPrincipal::tenantName)
                        .orElse(payload.tenantCode()),
                requireLogin,
                now() + tokens.remainingSeconds(token));
    }

    /** H5 页面信息：患者脱敏（姓名张*、病案号前 2 后 2）、类型 + 名称、位置下拉（含预选 key）。 */
    public InfoView info(String token) {
        Payload payload = requirePending(token);
        MobileUploadMapper.PatientBriefRow brief = mapper.findPatientBrief(payload.patientId());
        if (brief == null) {
            // 签发后患者被删除 / 数据范围变化：按二维码过期口径提示重新生成
            throw expired();
        }
        List<LocationNodeView> tree = reports.reportLocations(payload.patientId());
        List<LocationOptionView> options = flatten(tree);
        String presetKey =
                payload.presetLocation() == null
                        ? null
                        : optionKeyOf(options, payload.presetLocation());
        List<TypeInfoView> types = new ArrayList<>();
        for (ReportConfigService.CatalogTypeView type : catalog.catalogTypes()) {
            List<NameView> names = new ArrayList<>();
            for (ReportConfigService.CatalogNameView name : catalog.catalogNames(type.code())) {
                names.add(new NameView(name.id(), name.name()));
            }
            types.add(new TypeInfoView(type.code(), type.name(), type.nameRequired(), names));
        }
        return new InfoView(
                maskName(brief.nameCipher()),
                maskMrn(brief.mrnCipher()),
                brief.wardName(),
                LocalDate.now(ZONE),
                types,
                options,
                presetKey,
                loginRequired(),
                now() + tokens.remainingSeconds(token));
    }

    /** 手机端上传一张图片（前端已压缩、去 EXIF；后端格式白名单与真实类型校验同电脑端）。 */
    public FileUploadService.FileUploadView upload(String token, String filename, byte[] body) {
        Payload payload = requirePending(token);
        int cap = filePolicy.policy().maxFilesPerReport();
        if (payload.fileIds().size() >= cap) {
            throw bad("附件数量超过上限（每份报告单最多 " + cap + " 个）");
        }
        FileUploadService.FileUploadView view = uploads.upload(filename, body);
        tokens.appendFile(token, payload, view.fileId());
        log.info(
                "手机端上传图片（E16-T10）：tokenFileCount={} fileId={} patientId={}",
                payload.fileIds().size() + 1,
                view.fileId(),
                payload.patientId());
        return view;
    }

    /** 手机端提交：以签发人身份创建报告单（位置至少 1 个、模板版本校验、附件上限同电脑端），令牌随即作废。 */
    public CreatedView submit(String token, SubmitCommand command) {
        Payload payload = requirePending(token);
        if (command == null) {
            throw bad("请求体不能为空");
        }
        if (payload.fileIds().isEmpty()) {
            throw bad("请至少上传一张照片");
        }
        CreatedView view =
                reports.create(
                        payload.patientId(),
                        new ReportService.CreateRequest(
                                command.typeCode(),
                                command.nameId(),
                                command.reportDate(),
                                null,
                                null,
                                null,
                                null,
                                command.locations(),
                                payload.fileIds(),
                                null,
                                null));
        tokens.markSubmitted(token, payload, view.id());
        audit.append(
                AuditEntry.operation("followup", "RPT_MOBILE_SUBMIT")
                        .objectType("REPORT")
                        .objectId(view.id())
                        .description(
                                "手机扫码提交报告单（A-ATT-015 / C-SCAN-008）：报告单 #"
                                        + view.id()
                                        + "，附件 "
                                        + view.fileCount()
                                        + " 个、位置 "
                                        + view.locationCount()
                                        + " 个，操作人 = 二维码签发人")
                        .afterJson(
                                "{\"reportId\":\""
                                        + view.id()
                                        + "\",\"fileCount\":"
                                        + view.fileCount()
                                        + "}")
                        .build());
        log.info(
                "手机端提交报告单（E16-T10）：reportId={} patientId={} files={}",
                view.id(),
                payload.patientId(),
                view.fileCount());
        return view;
    }

    /** 公开口按 IP 每小时限流（AGENTS.md 第 6 节；控制器调用）。 */
    public void rateLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        String key = IP_LIMIT_PREFIX + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > properties.getIpHourlyLimit()) {
            throw new ApiException(429, 42900, "尝试次数过多，请稍后再试");
        }
    }

    // ---------------------------------------------------------------- 私有

    /** 令牌存在且未消费（不校验会话——/context 引导口无需登录）；否则统一 40101（过期 / 已用同一句，防探测）。 */
    private Payload requireToken(String token) {
        Payload payload = tokens.find(token);
        if (payload == null || !MobileUploadTokenStore.STATUS_PENDING.equals(payload.status())) {
            throw expired();
        }
        return payload;
    }

    /** 令牌存在、未消费且签发人会话仍有效（H5UploadTokenFilter 已装载 SecurityContext；会话已结束 / 租户不符 都按二维码过期口径提示）。 */
    private Payload requirePending(String token) {
        Payload payload = requireToken(token);
        SessionPrincipal principal =
                CurrentSession.current().orElseThrow(MobileUploadService::expired);
        if (!principal.hospital()
                || principal.tenantId() == null
                || principal.tenantId() != payload.tenantId()) {
            throw expired();
        }
        return payload;
    }

    private boolean loginRequired() {
        return Boolean.TRUE.equals(mapper.findLoginRequired());
    }

    private static ApiException expired() {
        return new ApiException(401, 40101, "二维码已过期，请在电脑上重新生成");
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private String maskedPatientLabel(long patientId) {
        MobileUploadMapper.PatientBriefRow brief = mapper.findPatientBrief(patientId);
        return brief == null ? "#" + patientId : maskName(brief.nameCipher());
    }

    private String maskName(String cipher) {
        String name = cipher == null ? null : crypto.decrypt(cipher);
        return SensitiveValueMasker.mask("name", name == null ? "" : name);
    }

    private String maskMrn(String cipher) {
        String mrn = cipher == null ? null : crypto.decrypt(cipher);
        return SensitiveValueMasker.mask("medicalRecordNo", mrn == null ? "" : mrn);
    }

    /** 位置树拍平为下拉选项（label = 祖先标题拼接「 · 」；只保留可选叶子）。 */
    private static List<LocationOptionView> flatten(List<LocationNodeView> tree) {
        List<LocationOptionView> options = new ArrayList<>();
        append(options, tree, "");
        return options;
    }

    private static void append(
            List<LocationOptionView> options, List<LocationNodeView> nodes, String prefix) {
        for (LocationNodeView node : nodes) {
            String label = prefix.isEmpty() ? node.title() : prefix + " · " + node.title();
            if (node.selectable() && node.value() != null) {
                options.add(new LocationOptionView(node.key(), label, node.value()));
            }
            if (node.children() != null && !node.children().isEmpty()) {
                append(options, node.children(), label);
            }
        }
    }

    /** 预选位置匹配下拉项（五元组相等；锚点模板版本一致才预选，模板已调整时不预选由用户重选）。 */
    private static String optionKeyOf(List<LocationOptionView> options, LocationInput preset) {
        for (LocationOptionView option : options) {
            LocationValueView value = option.value();
            String recordId =
                    preset.linkRecordId() == null ? null : String.valueOf(preset.linkRecordId());
            Integer presetVersion = preset.anchorTemplateVersion();
            if (equalsNullable(value.linkType(), preset.linkType())
                    && equalsNullable(value.linkRecordId(), recordId)
                    && equalsNullable(value.anchorType(), preset.anchorType())
                    && equalsNullable(value.anchorKey(), preset.anchorKey())
                    && presetVersion != null
                    && value.anchorTemplateVersion() == presetVersion) {
                return option.key();
            }
        }
        return null;
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }
}
