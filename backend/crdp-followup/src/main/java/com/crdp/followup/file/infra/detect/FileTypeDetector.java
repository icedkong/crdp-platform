package com.crdp.followup.file.infra.detect;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 真实文件类型检测（E10-T01 / A-ATT-008、C-SCAN-018）：Tika 按内容魔数识别 MIME，不传文件名—— 扩展名伪装在这一步失效（技术方案
 * §3「文件识别与安全」：Apache Tika 识别真实类型并按白名单放行）。 只引 tika-core（检测），不带解析器全家桶，避免不必要的攻击面与依赖体积。
 *
 * <p>tika-core 对 OOXML 包只能给到 {@code application/x-tika-ooxml}（docx / xlsx 的判别在解析器 模块里）：这里按 {@code
 * [Content_Types].xml} 的主部件 Content Type 自行判别 docx / xlsx， 其余 OOXML（pptx 等，不在白名单）维持 x-tika-ooxml
 * 原样，由白名单拒绝。
 */
@Component
public class FileTypeDetector {

    private static final Logger log = LoggerFactory.getLogger(FileTypeDetector.class);

    /** Tika 实例线程安全（内部 mime 注册表只读），静态复用避免每次上传重建注册表。 */
    private static final Tika TIKA = new Tika();

    static final String OOXML_GENERIC = "application/x-tika-ooxml";

    static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 按内容识别 MIME（小写）；识别失败或空内容返回 application/octet-stream 或空串，由白名单兜底拒绝。 */
    public String detect(byte[] body) {
        String mime = TIKA.detect(body);
        if (mime == null) {
            return "";
        }
        mime = mime.toLowerCase(Locale.ROOT);
        return OOXML_GENERIC.equals(mime) ? refineOoxml(body, mime) : mime;
    }

    /** OOXML 包判别 docx / xlsx（tika-core 缺解析器时的补充）；其余 OOXML 原样返回。 */
    private static String refineOoxml(byte[] body, String generic) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(body))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    String contentTypes = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    if (contentTypes.contains("wordprocessingml.document.main+xml")) {
                        return DOCX_MIME;
                    }
                    if (contentTypes.contains("spreadsheetml.sheet.main+xml")) {
                        return XLSX_MIME;
                    }
                    return generic;
                }
            }
        } catch (IOException ex) {
            // 损坏的包按原识别结果走白名单（x-tika-ooxml / zip 都会被拒），不因检测实现放行
            log.warn("OOXML 内容类型读取失败，按原始识别结果 {} 处理", generic);
        }
        return generic;
    }
}
