package com.crdp.followup.file.app;

/**
 * 报告单附件的患者数据范围校验端口（E16-T09 / OQ-62① 的报告单部分）：文件挂 rpt_report_file 时按患者可见性校验， 越范围 / 报告单已删一律
 * 40400，不暴露文件存在（AGENTS §5.2）。放在 file 子域的 app 层接口，避免 file → report 的反向编译依赖（依赖方向 report → file）；实现由
 * report 子域提供（与 {@link FilePreviewService.ContactAudioScopePort} 同模式，E15-T04）。
 *
 * <p>预览口（{@code GET /files/{fileId}/preview-url}）与下载口（{@code POST
 * /files/{fileId}/download-url}）都经本端口收紧；下载审计另用 {@link ReportFileView} 补「哪份报告单的哪个文件」 （A-ATT-006）。
 */
public interface ReportFileScopePort {

    /** 文件未挂任何在册报告单 → 返回 null（维持文件服务既有口径：登录 + 本院台账）； 挂了 → 按患者数据范围校验，越范围 40400 并返回报告单上下文。 */
    ReportFileView requireReportFileReadable(long fileId);

    /** 文件归属报告单的上下文（下载审计描述「哪份报告单的哪个文件」，A-ATT-006）。 */
    record ReportFileView(long reportId, String typeCode, String typeName, String nameSnapshot) {}
}
