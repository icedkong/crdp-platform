package com.crdp.followup.report.app;

import com.crdp.followup.file.app.ReportFileScopePort;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.framework.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * 报告单附件的患者数据范围校验（E16-T09 / OQ-62① 的报告单部分，实现 file 子域声明的 {@link ReportFileScopePort}）。
 *
 * <p>文件挂在报告单上（rpt_report_file，且报告单在册）时按患者可见性校验：越范围一律 40400，不暴露文件存在 （AGENTS §5.2）。预览口登录即可 + followup
 * 模块（E10-T04 口径）、下载口另有 followup:scan:download 敏感权限，这里补的是患者数据范围这一层。未挂报告单（含挂沟通记录的录音、PENDING 待关联）返回
 * null， 维持文件服务既有口径；归属查询本身不能按范围过滤（越范围文件会被误判为未挂而放行），见 {@link ReportMapper#reportFileContext}。
 */
@Component
public class ReportFileScopeGuard implements ReportFileScopePort {

    private final ReportMapper mapper;

    private final ReportPatientGateway patientScope;

    public ReportFileScopeGuard(ReportMapper mapper, ReportPatientGateway patientScope) {
        this.mapper = mapper;
        this.patientScope = patientScope;
    }

    @Override
    public ReportFileView requireReportFileReadable(long fileId) {
        ReportMapper.ReportFileContextRow row = mapper.reportFileContext(fileId);
        if (row == null) {
            return null; // 未挂报告单：维持文件服务既有口径（沟通录音范围由 ContactAudioScopeGuard 把守）
        }
        if (patientScope.patientIdInScope(row.patientId()) == null) {
            throw new ApiException(404, 40400, "文件不存在或无权访问");
        }
        return new ReportFileView(
                row.reportId(), row.typeCode(), row.typeName(), row.nameSnapshot());
    }
}
