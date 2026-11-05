package com.crdp.followup.contact.app;

import com.crdp.followup.contact.infra.mapper.ContactLogMapper;
import com.crdp.followup.file.app.FilePreviewService;
import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.framework.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * 沟通录音的患者数据范围校验（E15-T04 / A-FU-025，实现 file 子域声明的 {@link FilePreviewService.ContactAudioScopePort}）。
 *
 * <p>录音挂在沟通记录上（fu_contact_audio）时按患者可见性校验：越范围 / 已删除一律 40400，不暴露文件存在 （AGENTS §5.2；OQ-62① 的沟通录音部分在
 * E15-T04 提前收紧，报告单文件的范围随 E16 rpt_report_file）。 预览口登录即可 + followup 模块（E10-T04 口径）、下载口另有
 * followup:scan:download 敏感权限，这里补的是 患者数据范围这一层。
 */
@Component
public class ContactAudioScopeGuard implements FilePreviewService.ContactAudioScopePort {

    private final ContactLogMapper mapper;
    private final PatientContactGateway patientScope;

    public ContactAudioScopeGuard(ContactLogMapper mapper, PatientContactGateway patientScope) {
        this.mapper = mapper;
        this.patientScope = patientScope;
    }

    @Override
    public void requireAudioPlayable(long fileId) {
        Long patientId = mapper.patientIdOfAudioFile(fileId);
        if (patientId == null) {
            return; // 未挂沟通记录：维持文件服务既有口径（报告单范围随 E16）
        }
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "文件不存在或无权访问");
        }
    }
}
