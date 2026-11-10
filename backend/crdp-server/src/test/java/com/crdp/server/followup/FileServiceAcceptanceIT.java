package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E10-T06 / A-ATT-008、C-SCAN-018、A-FU-025：文件服务端到端验收（完整应用 + 真实 MySQL / Redis，HTTP 直连）。
 *
 * <p>单测（E10-T01/T04 的 FileUploadServiceTest 等）已锁校验逻辑；这里补**全链路 HTTP** 验收： 伪装文件在 multipart
 * 上传口被拒（任务验收①：改扩展名的可执行文件 40001，台账与 FILE_UPLOAD 审计无痕）； MP3 从上传 → preview-url（inlinePlayable）→
 * 公开取流口（无登录令牌，原生 audio 的真实姿势）整链可播（验收②）； 上传口 40300 / 模块关闭 40301；A 院文件在 B 院 preview-url 40400（租户隔离）。
 * 前端「水印不可关」（验收②水印口径）由 frontend/packages/viewer 的 e10-t06 对抗用例覆盖（visibility / 背景抹除 / 容器清空 / 假关闭
 * prop）。
 *
 * <p>全部合成样例（PE / ELF / ID3 最小魔数，不可执行、不含真实代码），文件名带「示例」； 门控 CRDP_IT_MYSQL_PORT，自清理台账、桶对象与审计（维护开关口径同其他
 * IT）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileServiceAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String UPLOAD = "/api/hospital/files";

    /** 本类上传文件的文件名前缀（自清理按它定位，不碰别的用例数据）。 */
    private static final String PREFIX = "E10T06-";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private ObjectStorage storage;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 先删桶对象（对象键带 t/{租户}/ 前缀，删相对键即可），再清台账与审计
                        List<Map<String, Object>> rows =
                                jdbc.queryForList(
                                        "SELECT object_key, thumbnail_key FROM sys_file"
                                                + " WHERE original_name LIKE ?",
                                        PREFIX + "%");
                        for (Map<String, Object> row : rows) {
                            deleteObject(tenant, (String) row.get("object_key"));
                            deleteObject(tenant, (String) row.get("thumbnail_key"));
                        }
                        jdbc.update(
                                "DELETE FROM sys_file WHERE original_name LIKE ?", PREFIX + "%");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type = 'FILE_UPLOAD'"
                                        + " AND detail LIKE ?",
                                "上传文件：" + PREFIX + "%");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE action = 'FILE_PREVIEW'");
                    });
        }
        // 公开取流口按 IP 限流计数复位（其余 IT 共用本机 IP，避免累积影响重复运行）
        Set<String> limited = redis.keys("crdp:file-preview:ip:*");
        if (limited != null && !limited.isEmpty()) {
            redis.delete(limited);
        }
    }

    // ---------------------------------------------------------------- 验收①：伪装文件被拒（HTTP 全链路）

    /** PE 伪装 .pdf（任务验收）、ELF 伪装 .png、脚本伪装 .txt、真 MP3 伪装 .pdf（白名单内容也须扩展名一致）全部 40001。 */
    @Test
    void disguisedFilesAreRejectedOverHttp() {
        String token = hospitalToken("demoa");

        Map<?, ?> exeAsPdf = uploadRaw(token, PREFIX + "出院小结（示例）.pdf", peExe());
        assertEquals(40001, code(exeAsPdf), "改扩展名的可执行文件必须被拒");
        assertTrue(message(exeAsPdf).contains("真实类型"), "提示按真实类型拒绝：" + message(exeAsPdf));

        assertEquals(
                40001,
                code(uploadRaw(token, PREFIX + "超声（示例）.png", elf())),
                "ELF 按内容拒绝，不信任 .png 扩展名");
        assertEquals(
                40001,
                code(uploadRaw(token, PREFIX + "说明（示例）.txt", script())),
                "脚本内容拒绝（txt 也不在白名单）");

        Map<?, ?> mp3AsPdf = uploadRaw(token, PREFIX + "录音（示例）.pdf", mp3(128));
        assertEquals(40001, code(mp3AsPdf), "白名单内容伪装别的扩展名同样拒绝");
        assertTrue(message(mp3AsPdf).contains("不符"), "提示扩展名与真实类型不符");

        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM sys_file WHERE original_name" + " LIKE ?",
                                    Long.class,
                                    PREFIX + "%"),
                            "被拒文件不落台账");
                    assertEquals(
                            0L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                            + " 'FILE_UPLOAD' AND detail LIKE ?",
                                    Long.class,
                                    "上传文件：" + PREFIX + "%"),
                            "被拒上传不写 FILE_UPLOAD 审计（拒绝路径不产生业务对象）");
                });
    }

    // ---------------------------------------------------------------- 验收②：MP3 上传 → 签发 → 原生播放取流

    /** MP3 全链路：上传成 mp3 台账 → preview-url 可内联播放 → 公开取流口（无登录令牌）按 audio/mpeg inline 回流原字节。 */
    @Test
    void mp3UploadsAndPlaysThroughPreviewProxy() {
        String token = hospitalToken("demoa");
        byte[] body = mp3(256);

        Map<?, ?> uploadResponse = uploadRaw(token, PREFIX + "通话录音（示例）.mp3", body);
        assertEquals(0, code(uploadResponse), "合法 MP3 上传应成功：" + uploadResponse);
        Map<?, ?> uploaded = data(uploadResponse);
        long fileId = idOf(uploaded.get("fileId"));
        assertEquals("mp3", uploaded.get("format"), "真实类型按内容识别为 mp3");
        assertEquals("audio/mpeg", uploaded.get("mime"));
        assertEquals(body.length, ((Number) uploaded.get("size")).intValue());
        assertEquals(sha256Hex(body), uploaded.get("sha256"));

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> row =
                            jdbc.queryForMap(
                                    "SELECT object_key, status, format_code FROM sys_file WHERE id"
                                            + " = ?",
                                    fileId);
                    assertEquals("PENDING", row.get("status"), "上传即 PENDING，关联报告单随 E16");
                    assertEquals("mp3", row.get("format_code"));
                    assertTrue(
                            String.valueOf(row.get("object_key")).startsWith("t/demoa/files/"),
                            "对象键带租户前缀入桶（E10-T02 隔离）");
                });

        Map<?, ?> grant = data(get("/api/hospital/files/" + fileId + "/preview-url", token));
        assertEquals("mp3", grant.get("format"));
        assertEquals("audio/mpeg", grant.get("mime"));
        assertEquals(Boolean.TRUE, grant.get("inlinePlayable"), "MP3 浏览器原生可播");
        String url = (String) grant.get("url");
        assertTrue(url.startsWith("/api/hospital/files/preview/"), "短时链接指向公开取流口");
        assertTrue(
                ((Number) grant.get("expiresAtEpochSecond")).longValue()
                        > System.currentTimeMillis() / 1000,
                "链接未过期");

        // 原生 <audio> 是浏览器子资源请求，带不了登录令牌——取流口必须匿名可达
        ResponseEntity<byte[]> streamed = rest.getForEntity(url, byte[].class);
        assertEquals(200, streamed.getStatusCode().value());
        assertEquals(
                MediaType.parseMediaType("audio/mpeg"), streamed.getHeaders().getContentType());
        assertTrue(
                streamed.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("inline"),
                "inline 展示而非附件下载");
        assertTrue(
                streamed.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_DISPOSITION)
                        .contains("filename*"),
                "中文文件名走 filename*（RFC 5987）");
        assertTrue(
                streamed.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL).contains("no-store"),
                "预览不缓存");
        assertEquals(
                "nosniff", streamed.getHeaders().getFirst("X-Content-Type-Options"), "MIME 不可嗅探");
        assertArrayEquals(body, streamed.getBody(), "取流字节与上传一致（不转码、不另存副本）");

        // 同一令牌窗口内可多次取流（播放器 seek / 重试会重复请求）
        assertEquals(200, rest.getForEntity(url, byte[].class).getStatusCode().value());

        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                + " 'FILE_PREVIEW' AND object_id = ?",
                                        Long.class,
                                        String.valueOf(fileId)),
                                "每次预览写 FILE_PREVIEW 审计"));

        // 对照：AMR 只下载不可在线播放（A-FU-025）
        Map<?, ?> amr = data(uploadRaw(token, PREFIX + "通话录音（示例）.amr", amr()));
        Map<?, ?> amrGrant =
                data(get("/api/hospital/files/" + idOf(amr.get("fileId")) + "/preview-url", token));
        assertEquals(Boolean.FALSE, amrGrant.get("inlinePlayable"), "AMR 浏览器不能直接播放");
    }

    // ---------------------------------------------------------------- 权限与模块开关（AGENTS.md 第 6 节）

    /** 无 followup:scan:upload 40300；followup 模块关闭 40301（上传口与策略口），恢复即用。 */
    @Test
    void uploadGatedByPermissionAndOpenModule() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(
                40300, code(uploadRaw(doctor, PREFIX + "出院小结（示例）.pdf", peExe())), "随访医生没有上传权限码");

        String nurse = hospitalToken("demoa");
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("E10-T06 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(uploadRaw(nurse, PREFIX + "出院小结（示例）.pdf", peExe())),
                    "模块关闭后上传 40301");
            assertEquals(40301, code(get("/api/hospital/files/policy", nurse)), "策略口同样挂在模块开关下");
        } finally {
            moduleService.open(
                    1L, "followup", new TenantModuleService.OpenRequest("E10-T06 用例恢复（示例）"));
        }
        assertEquals(0, code(get("/api/hospital/files/policy", nurse)), "恢复后即可用");
    }

    // ---------------------------------------------------------------- 租户隔离

    /** A 院上传的文件在 B 院 preview-url 40400（各院各库，不暴露存在性）。 */
    @Test
    void previewGrantStaysInsideUploadingTenant() {
        String tenantA = hospitalToken("demoa");
        Map<?, ?> uploaded = data(uploadRaw(tenantA, PREFIX + "通话录音（示例）.mp3", mp3(64)));
        long fileId = idOf(uploaded.get("fileId"));

        String tenantB = hospitalToken("demob");
        assertEquals(
                40400,
                code(get("/api/hospital/files/" + fileId + "/preview-url", tenantB)),
                "B 院看不到 A 院台账");
        runInTenant(
                "demob",
                () ->
                        assertEquals(
                                0L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_file WHERE id = ?",
                                        Long.class,
                                        fileId),
                                "B 库本就没有这一行"));
    }

    // ---------------------------------------------------------------- S1（M3 审查）：未关联文件只允许上传者本人

    /**
     * S1：PENDING 未挂沟通记录 / 报告单的文件，同院另一名登录用户（有预览权限）按自增文件号取 preview-url 40400；上传者本人不受影响。 下载口同口径的服务级用例见
     * FileDownloadServiceTest（S1 节，下载还需敏感权限，HTTP 层不再重复搭权限）。
     */
    @Test
    void unlinkedFileOnlyReachableByUploader() {
        String uploader = hospitalToken("demoa");
        Map<?, ?> uploaded = data(uploadRaw(uploader, PREFIX + "出院小结（示例）.mp3", mp3(64)));
        long fileId = idOf(uploaded.get("fileId"));

        String colleague = hospitalToken("demoa", "doctor.demo");
        assertEquals(
                40400,
                code(get("/api/hospital/files/" + fileId + "/preview-url", colleague)),
                "未关联文件非上传者预览 40400（S1 收紧）");

        assertEquals(
                0,
                code(get("/api/hospital/files/" + fileId + "/preview-url", uploader)),
                "上传者本人仍可预览自己未关联的文件");
    }

    // ---------------------------------------------------------------- 合成样例（不可执行、不含真实代码）

    /** PE 最小样例：MZ 头 + DOS stub + PE 签名占位（与 FileUploadServiceTest 同款合成法）。 */
    private static byte[] peExe() {
        byte[] exe = new byte[256];
        exe[0] = 'M';
        exe[1] = 'Z';
        for (int i = 2; i < 0x40; i++) {
            exe[i] = (byte) 0x90;
        }
        exe[0x3C] = 0x40;
        exe[0x40] = 'P';
        exe[0x41] = 'E';
        exe[0x42] = 0;
        exe[0x43] = 0;
        return exe;
    }

    private static byte[] elf() {
        byte[] elf = new byte[64];
        elf[0] = 0x7F;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        elf[4] = 2;
        return elf;
    }

    private static byte[] script() {
        return "#!/bin/sh\necho 示例（虚构）\n".getBytes(StandardCharsets.UTF_8);
    }

    /** ID3v2 头 + 帧填充（Tika 按 ID3 魔数识别 audio/mpeg）。 */
    private static byte[] mp3(int padding) {
        byte[] header = new byte[] {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] payload = new byte[header.length + padding];
        System.arraycopy(header, 0, payload, 0, header.length);
        for (int i = header.length; i < payload.length; i++) {
            payload[i] = 0x55;
        }
        return payload;
    }

    private static byte[] amr() {
        return "#!AMR\n".getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------------------------------- HTTP 与租户辅助（口径同 FormEngineIT）

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    /** multipart 上传原始响应（不断言，调用方按 code 判断）。 */
    private Map<?, ?> uploadRaw(String token, String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(body) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(UPLOAD, new HttpEntity<>(parts, headers), Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        return response;
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    /** 超 2^53 的 ID 序列化为字符串（AGENTS.md 第 6 节），两种形态都取 long。 */
    private static long idOf(Object value) {
        return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value));
    }

    private static String message(Map<?, ?> response) {
        return String.valueOf(response.get("message"));
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private static long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }

    /** 删桶对象：台账键带 t/{租户}/ 前缀，存储端口按当前上下文重新拼前缀，故剥出相对键。 */
    private void deleteObject(String tenant, String objectKey) {
        if (objectKey == null) {
            return;
        }
        String prefix = "t/" + tenant + "/";
        String relative =
                objectKey.startsWith(prefix) ? objectKey.substring(prefix.length()) : objectKey;
        storage.delete(relative);
    }
}
