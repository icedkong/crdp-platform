package com.crdp.followup.file.infra.scan;

/**
 * 上传文件病毒扫描端口（E22-T15 / 等保自查 D-01「恶意代码防范」整改，tech-plan §10 后续方案）。
 *
 * <p>方案取舍（tech-plan §10 三方案对比定稿）：**自建 ClamAV（clamd）同步扫描**——上传时应用把文件流经 INSTREAM 发给
 * clamd，同步返回结果，通过后再写对象存储；**不用云沙箱**（文件会离开平台，恶意样本保留 6 个月， 涉及患者资料外传）；HSS 病毒查杀作为主机层补充（云上由运维配置，见整改手册
 * §5）。
 *
 * <p>语义（fail closed，验收锚点）：{@link #enabled()} 为 true 时，{@link #scan(byte[])} 必须给出明确结论——
 * 干净放行、检出病毒拒绝（40001）；扫描器不可达 / 超时 / 应答异常一律抛 {@code 50300}「稍后重试」， **不放行**（tech-plan
 * §10：扫描不可用时上传返回稍后重试）。{@link #enabled()} 为 false（本机 / CI / 未部署 clamd 的环境）不扫描， 台账 {@code
 * sys_file.virus_scan_status} 留 UNSCANNED，由补扫作业（{@code VirusScanRetroService}）在启用后补扫。
 */
public interface VirusScanner {

    /** 是否启用扫描（crdp.file.virus-scan.mode != off）。 */
    boolean enabled();

    /**
     * 同步扫描一段文件内容。
     *
     * @throws com.crdp.framework.web.ApiException 50300——扫描服务不可用（fail closed，调用方不得放行）
     */
    ScanResult scan(byte[] body);

    /** 扫描结论：infected=false 干净放行；infected=true 时 virusName 为检出名称（写入拒绝文案与审计）。 */
    record ScanResult(boolean infected, String virusName) {

        public static ScanResult clean() {
            return new ScanResult(false, null);
        }

        public static ScanResult infected(String virusName) {
            return new ScanResult(true, virusName);
        }
    }
}
