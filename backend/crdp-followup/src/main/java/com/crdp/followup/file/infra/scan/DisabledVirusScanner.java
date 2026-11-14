package com.crdp.followup.file.infra.scan;

/**
 * 禁用态替身（{@code crdp.file.virus-scan.mode=off}，本机 / CI / 未部署 clamd 的环境）。
 *
 * <p>不扫描、不拦截——与首期口径一致（类型白名单 + 大小限制仍在上传链路生效）；此时台账 {@code virus_scan_status} 落 UNSCANNED，供补扫作业在扫描启用后按
 * SHA-256 补扫存量（tech-plan §10）。
 */
public final class DisabledVirusScanner implements VirusScanner {

    public static final VirusScanner INSTANCE = new DisabledVirusScanner();

    private DisabledVirusScanner() {}

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public ScanResult scan(byte[] body) {
        // 禁用态不会被调用（FileUploadService 只在 enabled() 时扫描）；返回干净结论仅作防御
        return ScanResult.clean();
    }
}
