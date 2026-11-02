package com.crdp.followup.file.api;

import com.crdp.followup.file.app.FilePolicyService;
import com.crdp.followup.file.app.FileUploadService;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.security.RequireModule;
import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传（E10-T01 / A-ATT-008、C-SCAN-018、PLT-CFG-010；docs/api/report.md §1）。
 *
 * <p>前端直传到应用（不直传对象存储，技术方案 §「上传」），便于统一校验。multipart 上限见 application.yml（spring.servlet.multipart，200
 * MB 兜底）；本院生效的大小 / 白名单在 {@code GET /api/hospital/files/policy}，前端先取策略做前置提示，服务端仍强制校验。
 */
@Tag(name = "医院端文件上传")
@RestController
public class FileController {

    private final FileUploadService upload;
    private final FilePolicyService policy;

    public FileController(FileUploadService upload, FilePolicyService policy) {
        this.upload = upload;
        this.policy = policy;
    }

    @Operation(
            summary =
                    "上传文件（A-ATT-008、C-SCAN-018）：multipart 字段 file，单文件；按内容识别真实类型，"
                            + "改扩展名的可执行文件 / 不支持格式 / 超大小 → 40001 + 原因；"
                            + "返回 { fileId, sha256, size, mime, format }")
    @PostMapping("/api/hospital/files")
    @RequireModule("followup")
    @RequiresPermission("followup:scan:upload")
    public ApiResponse<FileUploadService.FileUploadView> upload(
            @RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(upload.upload(file.getOriginalFilename(), file.getBytes()));
    }

    @Operation(
            summary =
                    "本院上传策略（PLT-CFG-010）：开放的格式与各类别大小上限、每份报告单文件数上限；"
                            + "登录即可（上传对话框前置提示用），变更经租户参数页即时生效")
    @PublicApi
    @GetMapping("/api/hospital/files/policy")
    @RequireModule("followup")
    public ApiResponse<FilePolicyService.UploadPolicy> policy() {
        return ApiResponse.ok(policy.policy());
    }
}
