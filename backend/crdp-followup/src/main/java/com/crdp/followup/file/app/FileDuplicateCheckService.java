package com.crdp.followup.file.app;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.SensitiveValueMasker;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 重复文件提示（E21-T18 / C-SCAN-017，P1；docs/api/report.md §1 GET /files/check-duplicate）。
 *
 * <p>上传接口（POST /files）返回 {@code sha256}，前端拿它调本口：命中时 {@code duplicate = true} 并返回已上传过该文件的患者（脱敏），
 * 前端提示「该文件已上传过（所属患者：…），确定继续？」——<b>提示不阻止</b>，确认后照常创建报告单。
 *
 * <p>口径（C-SCAN-017 + AGENTS §5.2 / §5.3）：
 *
 * <ul>
 *   <li><b>只按已挂报告单的文件命中</b>：患者归属经 {@code rpt_report_file}（报告单 NORMAL、sys_file 未删除）。刚上传、 尚未挂任何记录的
 *       PENDING 文件（含本次正在检查的这份）没有患者归属、不命中——查询键只有 sha256，把 PENDING 算进来会让每次自查命中自己；
 *       同一对话框里重复选了同一文件的情形由前端比对两次上传返回的 sha256 自行提示；
 *   <li><b>仅在可见范围内提示</b>：查询经 Mapper 语句上的 {@code @DataScope(resource = "patient")} 出行，
 *       范围外患者不出行（不泄露）、范围为空返回空清单；
 *   <li><b>患者只输出脱敏值</b>：姓名 / 病案号解密后 {@link SensitiveValueMasker}（姓名首尾、病案号前 2 后 2）， 再经视角执行点（{@link
 *       TenantViewFieldGuard}）输出——列表不提供逐行明文，明文走专用接口；
 *   <li><b>纯提示查询不写审计</b>（与患者病案号查重、随访日期查重同口径，OQ-71④ 的提示类查询先例）。
 * </ul>
 *
 * <p>无功能开关：与 E21-T11 重复记录提示同口径——后端口子全租户可用，A / B 前端不接入即无感（差异在消费侧， 不新增数据概念，AGENTS §5.7
 * 的开关口径不适用于纯查询提示）。
 */
@Service
public class FileDuplicateCheckService {

    /** 提示清单上限：同一哈希命中超过 20 名患者属病态数据，截断够用（与患者查重可能重复清单的限制同思路）。 */
    static final int PATIENT_LIMIT = 20;

    private static final int SHA256_HEX_LENGTH = 64;

    private final FileDuplicateCheckPort lookup;
    private final CryptoService crypto;
    private final TenantViewFieldGuard viewGuard;

    public FileDuplicateCheckService(
            FileDuplicateCheckPort lookup, CryptoService crypto, TenantViewFieldGuard viewGuard) {
        this.lookup = lookup;
        this.crypto = crypto;
        this.viewGuard = viewGuard;
    }

    // ---------------------------------------------------------------- DTO

    /** 命中患者（只含脱敏值；visitNo 随访编号与病区名非敏感原样返回）。 */
    public record DuplicatePatientView(
            String patientId,
            String nameMasked,
            String mrnMasked,
            String visitNo,
            String wardName) {}

    /** 查重结果：duplicate = false 时 patients 为空（不重复，直接继续）。 */
    public record DuplicateCheckView(boolean duplicate, List<DuplicatePatientView> patients) {}

    // ---------------------------------------------------------------- 查询

    /**
     * 按文件 SHA-256 查已上传过该文件的患者（可见范围内、脱敏）。
     *
     * @param sha256 64 位十六进制（大小写均可，归一为小写——上传侧产出小写）
     */
    public DuplicateCheckView check(String sha256) {
        String hash = requireSha256(sha256);
        List<DuplicatePatientView> patients =
                lookup.findPatientsByFileSha256(hash, PATIENT_LIMIT).stream()
                        .map(this::toView)
                        .toList();
        return new DuplicateCheckView(!patients.isEmpty(), patients);
    }

    // ---------------------------------------------------------------- 内部

    private DuplicatePatientView toView(FileDuplicateCheckPort.DuplicateFilePatientRow row) {
        return new DuplicatePatientView(
                String.valueOf(row.patientId()),
                mask("name", row.nameCipher()),
                mask("medicalRecordNo", row.mrnCipher()),
                row.visitNo(),
                row.wardName());
    }

    /** 姓名脱敏（解密失败按未知处理输出空串，不抛错——与全院报告单列表同口径）。 */
    private String mask(String field, String cipher) {
        String plain;
        if (cipher == null) {
            plain = null;
        } else {
            try {
                plain = crypto.decrypt(cipher);
            } catch (RuntimeException undecryptable) {
                plain = null;
            }
        }
        return viewGuard.apply(
                "patient", field, plain == null ? "" : SensitiveValueMasker.mask(field, plain));
    }

    /** sha256 必填、64 位十六进制（大小写归一小写）；不合法 40001。 */
    private static String requireSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            throw bad("sha256 必填（64 位十六进制，取上传接口返回值）");
        }
        String hash = sha256.trim().toLowerCase();
        if (hash.length() != SHA256_HEX_LENGTH || !hash.matches("[0-9a-f]+")) {
            throw bad("sha256 格式不正确（应为 64 位十六进制）");
        }
        return hash;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
