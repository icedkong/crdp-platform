package com.crdp.followup.file.app;

import java.util.List;

/**
 * 文件哈希 → 所属患者查询端口（E21-T18 / C-SCAN-017「按文件哈希提示同一文件已上传过及其所属患者」）。
 *
 * <p>文件有没有「所属患者」取决于挂没挂报告单（{@code rpt_report_file}），归属与可见性都在 report 子域； 放在 file 子域的 app 层接口，避免 file
 * → report 的反向编译依赖（依赖方向 report → file），实现由 report 子域提供（与 {@link ReportFileScopePort} 同模式，E16-T09）。
 *
 * <p>实现口径：只查<strong>已挂报告单</strong>（报告单 NORMAL）且未删除（sys_file ≠ DELETED）的文件—— 刚上传、还没挂任何记录的 PENDING
 * 文件没有患者归属，不参与命中（否则每次上传后自查都会命中自己）。语句必须 {@code @DataScope(resource = "patient")}： 范围外患者不出行（AGENTS
 * §5.2「重复提示按当前用户范围计算」），范围为空返回空清单。
 */
public interface FileDuplicateCheckPort {

    /**
     * 按文件 SHA-256 找已挂报告单的患者（同一患者多份报告单只出一行；按患者 id 升序、最多 {@code limit} 行）。
     *
     * @param sha256 64 位小写十六进制（服务层已归一）
     * @param limit 提示清单上限（超出部分截断，提示场景足够）
     */
    List<DuplicateFilePatientRow> findPatientsByFileSha256(String sha256, int limit);

    /** 命中行：姓名 / 病案号为密文，由 file 子域服务层解密后脱敏输出（AGENTS §5.3 列表不提供明文）。 */
    record DuplicateFilePatientRow(
            long patientId, String nameCipher, String mrnCipher, String visitNo, String wardName) {}
}
