package com.crdp.followup.report.app;

import com.crdp.followup.file.app.FileDuplicateCheckPort;
import com.crdp.followup.report.infra.mapper.ReportSearchMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 文件哈希 → 所属患者查询（E21-T18 / C-SCAN-017，实现 file 子域声明的 {@link FileDuplicateCheckPort}）。 依赖方向 report →
 * file（与 {@link ReportFileScopeGuard} 同模式）：归属与可见性都在报告单侧（rpt_report_file 挂患者数据范围下）， file 子域经端口取数。
 *
 * <p>语句 {@code @DataScope(resource = "patient")} 出行（ReportSearchMapper#findPatientsByFileSha256）：
 * 范围外患者不出行、范围为空返回空清单——「仅在可见范围内提示」由数据范围改写保证，不另写查询条件。
 */
@Component
public class ReportFileDuplicateLookup implements FileDuplicateCheckPort {

    private final ReportSearchMapper mapper;

    public ReportFileDuplicateLookup(ReportSearchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DuplicateFilePatientRow> findPatientsByFileSha256(String sha256, int limit) {
        return mapper.findPatientsByFileSha256(sha256, limit);
    }
}
