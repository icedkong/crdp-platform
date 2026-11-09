package com.crdp.followup.report.api;

import com.crdp.followup.file.app.FileUploadService;
import com.crdp.followup.report.app.MobileUploadService;
import com.crdp.followup.report.app.ReportService.CreatedView;
import com.crdp.followup.report.app.ReportService.LocationInput;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiException;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 手机扫码拍照上传（E16-T10 / A-ATT-015、C-SCAN-008、C-NFR-006；docs/api/report.md §5）。
 *
 * <p>电脑端两条（登录会话 + followup:scan:upload）：签发一次性二维码、轮询状态（手机提交后刷新患者报告单清单）。 手机端四条公开口
 * （/api/h5/upload/**，SecurityConfig permitAll + 按 IP 每小时限流；会话上下文由 H5UploadTokenFilter
 * 按令牌装载签发人实时会话）： 引导信息（登录表单租户预填）、页面信息（患者脱敏 + 类型 / 名称 + 位置下拉）、上传图片、提交生成报告单。
 *
 * <p>令牌过期或已用：40101「二维码已过期，请在电脑上重新生成」（OQ-25）；C 租户打开 cfg_feature h5_upload_login 后手机端须先登录 本院账号（40100
 * 引导登录，C-NFR-006）。
 */
@Tag(name = "手机扫码拍照上传（H5）")
@RestController
public class MobileUploadController {

    private final MobileUploadService service;

    public MobileUploadController(MobileUploadService service) {
        this.service = service;
    }

    // ---------------------------------------------------------------- 电脑端

    @Operation(
            summary =
                    "签发一次性上传二维码（A-ATT-015 / C-SCAN-008）：{ patientId, presetLocation? } → "
                            + "{ token, qrUrl, expiresAt }；15 分钟、一次性、绑定当前用户与患者；"
                            + "从分类 / 分组内生成时带 presetLocation（手机端下拉预选该位置）；写 RPT_UPLOAD_TOKEN_ISSUE 审计")
    @PostMapping("/api/hospital/followup/upload-tokens")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<MobileUploadService.IssueView> issue(
            @RequestBody(required = false) IssueBody body) {
        if (body == null || body.patientId() == null) {
            throw new ApiException(400, 40001, "缺少患者");
        }
        return ApiResponse.ok(service.issue(body.patientId(), toLocation(body.presetLocation())));
    }

    @Operation(
            summary =
                    "轮询二维码状态（电脑端弹窗）：PENDING 等待手机操作 / SUBMITTED 已生成报告单（带 reportId，"
                            + "前端随即刷新患者内清单）/ EXPIRED 过期（提示重新生成）")
    @GetMapping("/api/hospital/followup/upload-tokens/{token}")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<MobileUploadService.StatusView> status(@PathVariable String token) {
        return ApiResponse.ok(service.status(token));
    }

    // ---------------------------------------------------------------- 手机端（公开口）

    @Operation(
            summary =
                    "H5 引导信息（无需登录）：{ tenantCode, tenantName, requireLogin, expiresAt }；"
                            + "登录表单租户预填与过期前置提示用，不含患者数据")
    @PublicApi
    @GetMapping("/api/h5/upload/{token}/context")
    public ApiResponse<MobileUploadService.ContextView> context(
            @PathVariable String token, HttpServletRequest request) {
        service.rateLimit(clientIp(request));
        return ApiResponse.ok(service.context(token));
    }

    @Operation(
            summary =
                    "H5 页面信息：患者脱敏（张*明 / 病案号前 2 后 2）、类型与名称（名称必填标记）、"
                            + "位置下拉选项（与电脑端同源，含二维码预选 key presetKey）、今天日期（报告时间缺省）")
    @PublicApi
    @GetMapping("/api/h5/upload/{token}")
    public ApiResponse<MobileUploadService.InfoView> info(
            @PathVariable String token, HttpServletRequest request) {
        service.rateLimit(clientIp(request));
        return ApiResponse.ok(service.info(token));
    }

    @Operation(
            summary =
                    "H5 上传一张图片（multipart file；前端已压缩长边 ≤ 2560、转 JPEG、去 EXIF），"
                            + "服务端格式白名单与真实类型校验同电脑端；返回 { fileId, sha256, ... }；"
                            + "超过每份报告单附件上限整批 40001")
    @PublicApi
    @PostMapping("/api/h5/upload/{token}/files")
    public ApiResponse<FileUploadService.FileUploadView> upload(
            @PathVariable String token,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request)
            throws IOException {
        service.rateLimit(clientIp(request));
        return ApiResponse.ok(service.upload(token, file.getOriginalFilename(), file.getBytes()));
    }

    @Operation(
            summary =
                    "H5 提交：{ typeCode, nameId?, reportDate, locations[≥1] } 直接生成该患者的报告单"
                            + "（创建人 = 二维码签发人，位置 / 附件 / 模板版本校验与电脑端同一套）；"
                            + "附件取令牌内手机已上传清单（不收手机端回传 fileId）；提交成功令牌作废（一次性）")
    @PublicApi
    @PostMapping("/api/h5/upload/{token}/submit")
    public ApiResponse<CreatedView> submit(
            @PathVariable String token,
            @RequestBody(required = false) SubmitBody body,
            HttpServletRequest request) {
        service.rateLimit(clientIp(request));
        return ApiResponse.ok(
                service.submit(
                        token,
                        new MobileUploadService.SubmitCommand(
                                body == null ? null : body.typeCode(),
                                body == null ? null : body.nameId(),
                                body == null ? null : body.reportDate(),
                                body == null || body.locations() == null
                                        ? List.of()
                                        : body.locations().stream()
                                                .map(MobileUploadController::toLocation)
                                                .toList())));
    }

    // ---------------------------------------------------------------- 私有

    /** 反代后取真实 IP：优先 X-Forwarded-For 首段，兜底 remoteAddr（与 FileDownloadController 同口径）。 */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static LocationInput toLocation(LocationBody loc) {
        if (loc == null) {
            return null;
        }
        Long recordId = null;
        if (loc.linkRecordId() != null && !loc.linkRecordId().isBlank()) {
            try {
                recordId = Long.parseLong(loc.linkRecordId().trim());
            } catch (NumberFormatException e) {
                throw new ApiException(404, 40400, "随访记录不存在");
            }
        }
        return new LocationInput(
                loc.linkType(),
                recordId,
                loc.anchorType(),
                loc.anchorKey(),
                loc.anchorTemplateVersion());
    }

    /** 签发请求体：presetLocation 为从分类 / 分组内生成时的预选位置（可空）。 */
    record IssueBody(Long patientId, LocationBody presetLocation) {}

    /** 提交请求体（ID 序列化为字符串，超 2^53 安全）。 */
    record SubmitBody(
            String typeCode,
            String nameId,
            java.time.LocalDate reportDate,
            List<LocationBody> locations) {}

    record LocationBody(
            String linkType,
            String linkRecordId,
            String anchorType,
            String anchorKey,
            Integer anchorTemplateVersion) {}
}
