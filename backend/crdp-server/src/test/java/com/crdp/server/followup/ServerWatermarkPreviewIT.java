package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crdp.followup.file.app.PreviewRenderer;
import com.crdp.followup.file.infra.mapper.*;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.*;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** HTTP + MySQL + Redis 验证授权边界；真实隔离转换另由 PreviewSandboxIT 验证。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ServerWatermarkPreviewIT {
    @Autowired TestRestTemplate rest;
    @Autowired SessionStore sessions;
    @Autowired ObjectMapper json;
    @Autowired SysFileMapper files;
    @Autowired ObjectStorage storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired CryptoService crypto;
    @Autowired StringRedisTemplate redis;
    @MockitoBean PreviewRenderer renderer;
    private final Map<Long, Fixture> fixtures = new HashMap<>();
    private final List<String> tokens = new ArrayList<>(), grants = new ArrayList<>();
    private final long ward = 988888886L;
    private static final byte[] BODY = "audio-original-example".getBytes();
    private String owner, secondSession, otherUser, otherTenant;

    record Fixture(long file, String key, Long patient, Long contact) {}

    private <T> T tenant(long id, Supplier<T> work) {
        return TenantContext.callAsTenant(id, id == 1 ? "demoa" : "demob", work::get);
    }

    private SessionPrincipal principal(
            String token,
            long user,
            long tenant,
            ScopeSnapshot scope,
            List<String> permissions,
            List<String> modules) {
        return new SessionPrincipal(
                token,
                "HOSPITAL",
                tenant,
                tenant == 1 ? "demoa" : "demob",
                "医院（示例）",
                user,
                "doctor.example",
                "医生（示例）",
                List.of(),
                List.of(),
                permissions,
                modules,
                Long.MAX_VALUE,
                scope,
                false,
                null);
    }

    private String login(long user, long tenant) {
        String token = UUID.randomUUID().toString();
        sessions.save(
                principal(
                        token,
                        user,
                        tenant,
                        ScopeSnapshot.allowAll(),
                        List.of(
                                "followup:patient:view",
                                "followup:scan:upload",
                                "followup:scan:download"),
                        List.of("followup")));
        tokens.add(token);
        return token;
    }

    @BeforeEach
    void setup() {
        for (long id : List.of(1L, 2L))
            tenant(
                    id,
                    () -> {
                        String key = "files/oqr07-" + UUID.randomUUID() + ".mp3";
                        String full = storage.put(key, BODY, "audio/mpeg");
                        var row = new SysFileRow();
                        row.setObjectKey(full);
                        row.setOriginalName("录音（示例）.mp3");
                        row.setFormatCode("mp3");
                        row.setMimeType("audio/mpeg");
                        row.setSizeBytes(BODY.length);
                        row.setSha256("0".repeat(64));
                        row.setStatus("PENDING");
                        row.setVirusScanStatus("CLEAN");
                        row.setUploaderId(501L);
                        files.insert(row);
                        fixtures.put(id, new Fixture(row.getId(), key, null, null));
                        return null;
                    });
        owner = login(501, 1);
        secondSession = login(501, 1);
        otherUser = login(502, 1);
        otherTenant = login(501, 2);
        when(renderer.render(any(), anyString(), anyString()))
                .thenReturn(
                        new PreviewRenderer.Rendered(
                                "%PDF-watermarked-example".getBytes(), "application/pdf", "pdf"));
    }

    @AfterEach
    void cleanup() {
        for (var entry : fixtures.entrySet())
            tenant(
                    entry.getKey(),
                    () -> {
                        var f = entry.getValue();
                        jdbc.update("DELETE FROM fu_contact_audio WHERE file_id=?", f.file);
                        if (f.contact != null)
                            jdbc.update("DELETE FROM fu_contact_log WHERE id=?", f.contact);
                        if (f.patient != null)
                            jdbc.update("DELETE FROM pt_patient WHERE id=?", f.patient);
                        jdbc.update("DELETE FROM sys_file WHERE id=?", f.file);
                        storage.delete(f.key);
                        return null;
                    });
        tokens.forEach(sessions::delete);
        for (String grant : grants)
            redis.delete("crdp:file-preview:" + sha(grant.substring(grant.lastIndexOf('/') + 1)));
    }

    private ResponseEntity<byte[]> get(String path, String token) {
        var headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    private int code(ResponseEntity<byte[]> response) throws Exception {
        return json.readTree(response.getBody()).get("code").asInt();
    }

    private String grant(String token) throws Exception {
        var response = get("/api/hospital/files/" + fixtures.get(1L).file + "/preview-url", token);
        assertThat(code(response)).isZero();
        String url = json.readTree(response.getBody()).path("data").path("url").asText();
        grants.add(url);
        return url;
    }

    private void update(
            String token, ScopeSnapshot scope, List<String> permissions, List<String> modules) {
        sessions.save(principal(token, 501, 1, scope, permissions, modules));
    }

    private void linkAudio() {
        tenant(
                1,
                () -> {
                    var f = fixtures.get(1L);
                    String marker = "OQR07-" + UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO pt_patient(mrn_cipher,mrn_blind,name_cipher,name_blind,gender,ward_id,discharge_date) VALUES (?,?,?,?,'F',?,?)",
                            crypto.encrypt("pt-patient-mrn", marker),
                            crypto.blindIndex("pt-patient-mrn", marker),
                            crypto.encrypt("pt-patient-name", "水印患者（示例）"),
                            crypto.blindIndex("pt-patient-name", "水印患者（示例）"),
                            ward,
                            LocalDate.now().minusDays(10));
                    long patient =
                            jdbc.queryForObject(
                                    "SELECT id FROM pt_patient WHERE mrn_blind=?",
                                    Long.class,
                                    crypto.blindIndex("pt-patient-mrn", marker));
                    jdbc.update(
                            "INSERT INTO fu_contact_log(patient_id,method,result,contact_time,created_by) VALUES (?,'PHONE','REACHED',CURRENT_TIMESTAMP,501)",
                            patient);
                    long contact =
                            jdbc.queryForObject(
                                    "SELECT id FROM fu_contact_log WHERE patient_id=?",
                                    Long.class,
                                    patient);
                    jdbc.update(
                            "INSERT INTO fu_contact_audio(contact_log_id,file_id) VALUES (?,?)",
                            contact,
                            f.file);
                    fixtures.put(1L, new Fixture(f.file, f.key, patient, contact));
                    return null;
                });
    }

    @Test
    void unsafeStateBlocksOldPreviewAndDownloadGrantsButRetainsBothTenantObjects()
            throws Exception {
        for (String state : List.of("INFECTED", "HASH_MISMATCH")) {
            tenant(
                    1,
                    () -> {
                        jdbc.update(
                                "UPDATE sys_file SET virus_scan_status='CLEAN' WHERE id=?",
                                fixtures.get(1L).file);
                        return null;
                    });
            String preview = grant(owner);
            var headers = new HttpHeaders();
            headers.setBearerAuth(owner);
            var response =
                    rest.exchange(
                            "/api/hospital/files/" + fixtures.get(1L).file + "/download-url",
                            HttpMethod.POST,
                            new HttpEntity<>(headers),
                            byte[].class);
            assertThat(code(response)).isZero();
            String download = json.readTree(response.getBody()).path("data").path("url").asText();
            tenant(
                    1,
                    () -> {
                        jdbc.update(
                                "UPDATE sys_file SET virus_scan_status=? WHERE id=?",
                                state,
                                fixtures.get(1L).file);
                        return null;
                    });
            assertThat(code(get(preview, owner))).isEqualTo(40400);
            assertThat(code(get(download, null))).isEqualTo(40400);
            assertThat(
                            code(
                                    get(
                                            "/api/hospital/files/"
                                                    + fixtures.get(1L).file
                                                    + "/preview-url",
                                            owner)))
                    .isEqualTo(40400);
            var blocked =
                    rest.exchange(
                            "/api/hospital/files/" + fixtures.get(1L).file + "/download-url",
                            HttpMethod.POST,
                            new HttpEntity<>(headers),
                            byte[].class);
            assertThat(code(blocked)).isEqualTo(40400);
            tenant(
                    1,
                    () -> {
                        assertThat(storage.get(fixtures.get(1L).key)).isEqualTo(BODY);
                        return null;
                    });
            tenant(
                    2,
                    () -> {
                        assertThat(files.find(fixtures.get(2L).file).getVirusScanStatus())
                                .isEqualTo("CLEAN");
                        assertThat(storage.get(fixtures.get(2L).key)).isEqualTo(BODY);
                        return null;
                    });
        }
    }

    @Test
    void grantCannotBeUsedAnonymouslyAcrossUsersSessionsOrTenants() throws Exception {
        String url = grant(owner);
        assertThat(get(url, null).getStatusCode().value()).isEqualTo(401);
        assertThat(get(url, owner).getBody()).isEqualTo(BODY);
        for (String actor : List.of(secondSession, otherUser, otherTenant))
            assertThat(code(get(url, actor))).isEqualTo(40400);
        verifyNoInteractions(renderer);
    }

    @Test
    void scopeChangeAfterGrantIsEnforcedOnTheSameSession() throws Exception {
        linkAudio();
        String url = grant(owner);
        update(
                owner,
                ScopeSnapshot.restricted(List.of(), List.of(), false),
                List.of("followup:patient:view", "followup:scan:upload", "followup:scan:download"),
                List.of("followup"));
        assertThat(code(get(url, owner))).isEqualTo(40400);
    }

    @Test
    void permissionRevocationIsEnforcedEvenIfScopeStillIncludesPatient() throws Exception {
        linkAudio();
        String url = grant(owner);
        update(
                owner,
                ScopeSnapshot.allowAll(),
                List.of("followup:scan:upload"),
                List.of("followup"));
        assertThat(code(get(url, owner))).isEqualTo(40300);
    }

    @Test
    void revocationDuringVisualRenderingPreventsDelivery() throws Exception {
        tenant(
                1,
                () -> {
                    jdbc.update(
                            "UPDATE sys_file SET format_code='pdf',mime_type='application/pdf' WHERE id=?",
                            fixtures.get(1L).file);
                    return null;
                });
        String url = grant(owner);
        when(renderer.render(any(), anyString(), anyString()))
                .thenAnswer(
                        call -> {
                            update(owner, ScopeSnapshot.allowAll(), List.of(), List.of("followup"));
                            return new PreviewRenderer.Rendered(
                                    "%PDF-watermarked-example".getBytes(),
                                    "application/pdf",
                                    "pdf");
                        });
        var result = get(url, owner);
        assertThat(code(result)).isEqualTo(40300);
        assertThat(new String(result.getBody())).doesNotContain("%PDF-watermarked-example");
    }

    @Test
    void fileMutationAndDeletionInvalidateOldGrant() throws Exception {
        String url = grant(owner);
        tenant(
                1,
                () -> {
                    jdbc.update(
                            "UPDATE sys_file SET sha256=? WHERE id=?",
                            "1".repeat(64),
                            fixtures.get(1L).file);
                    return null;
                });
        assertThat(code(get(url, owner))).isEqualTo(40400);
        tenant(
                1,
                () -> {
                    jdbc.update(
                            "UPDATE sys_file SET status='DELETED' WHERE id=?",
                            fixtures.get(1L).file);
                    return null;
                });
        assertThat(code(get(url, owner))).isEqualTo(40400);
    }

    @Test
    void logoutAndGrantExpirationAreEnforced() throws Exception {
        String url = grant(owner);
        redis.delete("crdp:file-preview:" + sha(url.substring(url.lastIndexOf('/') + 1)));
        assertThat(code(get(url, owner))).isEqualTo(40400);
        String another = grant(owner);
        sessions.delete(owner);
        assertThat(get(another, owner).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void moduleClosureAlsoRejectsOldGrant() throws Exception {
        String url = grant(owner);
        update(owner, ScopeSnapshot.allowAll(), List.of("followup:scan:upload"), List.of());
        assertThat(code(get(url, owner))).isEqualTo(40301);
    }

    private static String sha(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
