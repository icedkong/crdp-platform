package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E15-T04 端到端 / A-FU-006、A-FU-025、C-FU-019（完整应用 + 真实 MySQL，HTTP 直连 + 服务层造数）。
 *
 * <p>覆盖：联系结果字典完整（六值逐一可记，tasks.csv 验收①）；MP3 可播放（上传 → 记沟通挂录音 → 列表 inlinePlayable → preview-url →
 * 公开取流口按 audio/mpeg 回流，验收②）；AMR 只保存与下载（inlinePlayable=false）； 第一次联系任务进入进行中 +
 * 任务列表「最近联系」列；「告知已故」触发死亡终止（患者 DEAD、未完成任务 TERMINATED，A-FU-007 / OQ-79②）； 删除沟通记录连带软删录音（A-ATT-017
 * 任一处删除两处同删）；录音预览按患者数据范围校验（越范围 40400）； 无权限 40300 / 模块关闭 40301 / 校验失败 40001；demoa / demob 双租户隔离。门控
 * CRDP_IT_MYSQL_PORT。全部虚构数据（legacy IT-E15T04-*）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContactLogIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String LEGACY = "IT-E15T04";

    private static final String PLAN_CODE = "PLAN-IT-E15T04";

    private static final String UPLOAD = "/api/hospital/files";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private com.crdp.framework.storage.ObjectStorage storage;

    @BeforeEach
    @AfterEach
    void cleanState() {
        SecurityContextHolderClear.clear();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('CONTACT_CREATE', 'CONTACT_UPDATE', 'CONTACT_DELETE')");
                        // 先删录音对象再清台账（对象键带 t/{租户}/ 前缀；无缩略图——录音不生成）
                        List<Map<String, Object>> objects =
                                jdbc.queryForList(
                                        "SELECT object_key FROM sys_file WHERE original_name LIKE ?",
                                        "IT-E15T04-%");
                        for (Map<String, Object> object : objects) {
                            deleteObject(tenant, (String) object.get("object_key"));
                        }
                        jdbc.update(
                                "DELETE ca FROM fu_contact_audio ca"
                                        + " JOIN fu_contact_log c ON c.id = ca.contact_log_id"
                                        + " JOIN pt_patient p ON p.id = c.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM sys_file WHERE original_name LIKE 'IT-E15T04-%'");
                        jdbc.update(
                                "DELETE c FROM fu_contact_log c JOIN pt_patient p"
                                        + " ON p.id = c.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                        + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                        + " WHERE p.code LIKE ?",
                                PLAN_CODE + "%");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update("DELETE FROM ward WHERE code = 'W-IT-E15T04'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u"
                                        + " ON u.id = ur.user_id WHERE u.username LIKE 'IT-U-E15T04-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON ph.user_id = u.id WHERE u.username LIKE 'IT-U-E15T04-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E15T04-%'");
                        for (String roleCode : List.of("IT-R-E15T04-VIEW", "IT-R-E15T04-WARD")) {
                            jdbc.update(
                                    "DELETE rm FROM sys_role_menu rm JOIN sys_role r"
                                            + " ON r.id = rm.role_id WHERE r.code = ?",
                                    roleCode);
                            jdbc.update(
                                    "DELETE rs FROM sys_role_scope rs JOIN sys_role r"
                                            + " ON r.id = rs.role_id WHERE r.code = ?",
                                    roleCode);
                            jdbc.update("DELETE FROM sys_role WHERE code = ?", roleCode);
                        }
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收①：联系结果字典完整

    /** A-FU-006 六值逐一可记（含「告知已故」），列表回读一致；平台字典 contact_result 六值预置。 */
    @Test
    void contactResultDictionaryIsComplete() {
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient("demoa", admin, "R1");
        List<String> results =
                List.of("REACHED", "NOT_REACHED", "WRONG_NUMBER", "REFUSED", "FAMILY", "DECEASED");
        for (String result : results) {
            Map<?, ?> created =
                    post(
                            contactPath(patientId),
                            admin,
                            Map.of("method", "PHONE", "result", result, "note", "示例沟通"));
            assertEquals(0, code(created), "联系结果 " + result + " 应可记录：" + created);
        }
        Map<?, ?> page = data(get(contactPath(patientId) + "?pageSize=50", admin));
        assertEquals(6, ((Number) page.get("total")).intValue(), "六条沟通记录全部落库");
        List<?> items = (List<?>) page.get("items");
        assertEquals("DECEASED", ((Map<?, ?>) items.get(0)).get("result"), "倒序：最后一条（告知已故）在最前");
        // 联系结果字典完整（平台库 V45 预置，租户经字典接口继承可见）：六值全量、文字与 A-FU-006 一致
        List<?> dictItems =
                (List<?>) get("/api/hospital/system/dicts/contact_result/items", admin).get("data");
        List<?> itemCodes =
                dictItems.stream().map(item -> ((Map<?, ?>) item).get("itemCode")).toList();
        assertEquals(
                List.of("REACHED", "NOT_REACHED", "WRONG_NUMBER", "REFUSED", "FAMILY", "DECEASED"),
                itemCodes,
                "联系结果字典完整：平台预置六值（平台库 V45）");
        assertEquals("告知已故", ((Map<?, ?>) dictItems.get(5)).get("label"));
        assertEquals(
                "DEAD",
                jdbcQuery(
                        "demoa",
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "「告知已故」触发死亡终止（A-FU-007）");
    }

    // ---------------------------------------------------------------- 验收②：MP3 可播放

    /** 上传 MP3 → 记沟通挂录音 → 列表 inlinePlayable=true → preview-url 短时链接可回流（原生 audio 姿势）。 */
    @Test
    void mp3AttachesToContactAndPlaysInline() {
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient("demoa", admin, "M1");
        long fileId = uploadAudio(admin, "IT-E15T04-通话录音（示例）.mp3", mp3());

        Map<?, ?> created =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of(
                                "method",
                                "PHONE",
                                "result",
                                "REACHED",
                                "nextContactTime",
                                "2027-01-05T09:00:00",
                                "note",
                                "患者一般情况可（示例）",
                                "audioFileIds",
                                List.of(fileId)));
        assertEquals(0, code(created), "记沟通失败：" + created);
        List<?> audios = (List<?>) logOf(created).get("audios");
        assertEquals(1, audios.size());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) audios.get(0)).get("inlinePlayable"), "MP3 可播");

        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                "ATTACHED",
                                jdbc.queryForObject(
                                        "SELECT status FROM sys_file WHERE id = ?",
                                        String.class,
                                        fileId),
                                "录音挂上沟通记录后置 ATTACHED"));

        Map<?, ?> grant = data(get("/api/hospital/files/" + fileId + "/preview-url", admin));
        assertEquals("mp3", grant.get("format"));
        assertEquals(
                200,
                rest.getForEntity((String) grant.get("url"), byte[].class).getStatusCode().value(),
                "公开取流口按短时链接回流（原生 audio 不带登录令牌）");

        // AMR 只保存与下载：挂上后 inlinePlayable=false
        long amrId = uploadAudio(admin, "IT-E15T04-通话录音（示例）.amr", amr());
        Map<?, ?> amrContact =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of(
                                "method",
                                "PHONE",
                                "result",
                                "FAMILY",
                                "audioFileIds",
                                List.of(amrId)));
        assertEquals(0, code(amrContact), "AMR 也接受上传与保存：" + amrContact);
        List<?> amrAudios = (List<?>) logOf(amrContact).get("audios");
        assertEquals(
                Boolean.FALSE,
                ((Map<?, ?>) amrAudios.get(0)).get("inlinePlayable"),
                "AMR 浏览器不能直接播放，只保存与下载（A-FU-025）");
    }

    // ---------------------------------------------------------------- 任务联动：进行中 + 最近联系

    /** 第一次联系任务进入进行中；任务列表「最近联系」显示该患者最新联系结果。 */
    @Test
    void firstContactMovesTaskToProgressAndFeedsLastContactColumn() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo");
        String admin = hospitalToken("demoa", "nurse.demo");
        long planId = publishedWindowPlan(admin);
        long patientId = registerPatient("demoa", doctor, "P1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        assertEquals(
                "PENDING",
                jdbcQuery(
                        "demoa", "SELECT status FROM fu_task WHERE id = ?", String.class, taskId));

        Map<?, ?> created =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of("taskId", taskId, "method", "PHONE", "result", "NOT_REACHED"));
        assertEquals(0, code(created), "记沟通失败：" + created);
        assertEquals(
                "IN_PROGRESS",
                jdbcQuery("demoa", "SELECT status FROM fu_task WHERE id = ?", String.class, taskId),
                "第一次联系 → 任务进入进行中（followup.md §4）");

        // 再记一条「已联系」：最近联系随之更新（每患者最新一条）
        assertEquals(
                0,
                code(
                        post(
                                contactPath(patientId),
                                admin,
                                Map.of("method", "PHONE", "result", "REACHED"))));
        Map<?, ?> search =
                post("/api/hospital/followup/tasks/search", doctor, Map.of("scope", "ALL"));
        List<?> rows = (List<?>) data(search).get("items");
        String latest = null;
        for (Object row : rows) {
            if (String.valueOf(((Map<?, ?>) row).get("id")).equals(String.valueOf(taskId))) {
                latest = (String) ((Map<?, ?>) row).get("lastContactResult");
            }
        }
        assertEquals("REACHED", latest, "任务列表「最近联系」= 该患者最新一条沟通结果（E15-T04 接入）");
    }

    // ---------------------------------------------------------------- 删除：两处同时删除（A-ATT-017）

    /** 删除沟通记录连带软删录音：sys_file 置 DELETED，preview-url 随之 40400。 */
    @Test
    void deletingContactSoftDeletesAudioEverywhere() {
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient("demoa", admin, "D1");
        long fileId = uploadAudio(admin, "IT-E15T04-通话录音（示例）.mp3", mp3());
        Map<?, ?> created =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of(
                                "method",
                                "PHONE",
                                "result",
                                "REACHED",
                                "audioFileIds",
                                List.of(fileId)));
        long contactId = idOf(logOf(created).get("id"));

        Map<?, ?> deleted =
                exchange(
                        HttpMethod.DELETE,
                        "/api/hospital/followup/contacts/" + contactId,
                        admin,
                        null);
        assertEquals(0, code(deleted), "删除失败：" + deleted);
        assertEquals(
                "DELETED",
                jdbcQuery(
                        "demoa", "SELECT status FROM sys_file WHERE id = ?", String.class, fileId),
                "录音随之软删（A-ATT-017 两处同时删除）");
        assertEquals(
                40400,
                code(get("/api/hospital/files/" + fileId + "/preview-url", admin)),
                "软删后预览 40400");
        assertEquals(
                0L,
                jdbcQuery(
                        "demoa",
                        "SELECT COUNT(*) FROM fu_contact_log WHERE patient_id = ?",
                        Long.class,
                        patientId),
                "沟通记录已删");
    }

    // ---------------------------------------------------------------- 修改：乐观锁与死亡终止联动

    /** 修改带 baseVersion；结果改为「告知已故」触发死亡终止；过期 baseVersion 40900。 */
    @Test
    void updateHonorsOptimisticLockAndDeceasedTriggersDeath() {
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient("demoa", admin, "U1");
        Map<?, ?> created =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of("method", "PHONE", "result", "NOT_REACHED", "note", "无人接听（示例）"));
        long contactId = idOf(logOf(created).get("id"));
        int version = ((Number) logOf(created).get("version")).intValue();

        // 过期版本：40900
        assertEquals(
                40900,
                code(
                        put(
                                "/api/hospital/followup/contacts/" + contactId,
                                admin,
                                Map.of(
                                        "method",
                                        "PHONE",
                                        "result",
                                        "REACHED",
                                        "note",
                                        "次日接通（示例）",
                                        "baseVersion",
                                        version - 1))),
                "乐观锁冲突 40900");

        Map<?, ?> updated =
                put(
                        "/api/hospital/followup/contacts/" + contactId,
                        admin,
                        Map.of(
                                "method",
                                "PHONE",
                                "result",
                                "DECEASED",
                                "note",
                                "家属后续告知已故（示例）",
                                "baseVersion",
                                version));
        assertEquals(0, code(updated), "修改失败：" + updated);
        assertEquals("DECEASED", data(updated).get("result"), "PUT 直接返回记录视图");
        assertEquals(
                "DEAD",
                jdbcQuery(
                        "demoa",
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "结果改为「告知已故」同样触发死亡终止（幂等入口）");
    }

    // ---------------------------------------------------------------- 权限 / 范围 / 开关 / 校验

    /** 只读（patient:view 无 record:edit）可看列表、写 40300；模块关闭 40301。 */
    @Test
    void permissionAndModuleGatesAreEnforced() {
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient("demoa", admin, "G1");

        long viewRoleId = roleWithMenus("IT-R-E15T04-VIEW", List.of(21L), "ALL");
        String viewToken = newUser("demoa", "IT-U-E15T04-VIEW", viewRoleId);
        assertEquals(
                0, code(get(contactPath(patientId), viewToken)), "列表只要求 followup:patient:view");
        assertEquals(
                40300,
                code(
                        post(
                                contactPath(patientId),
                                viewToken,
                                Map.of("method", "PHONE", "result", "REACHED"))),
                "无 followup:record:edit 写 40300");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(
                            post(
                                    contactPath(patientId),
                                    admin,
                                    Map.of("method", "PHONE", "result", "REACHED"))),
                    "模块关闭 40301");
            assertEquals(40301, code(get(contactPath(patientId), admin)), "列表同样挂在模块开关下");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    /** 越范围患者：列表 / 写入 / 录音预览一律 40400（不暴露存在）。 */
    @Test
    void outOfScopePatientAndAudioAreHidden() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo");
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId =
                registerPatientInWard("demoa", admin, "O1", today.minusDays(20), ensureOtherWard());
        long fileId = uploadAudio(admin, "IT-E15T04-通话录音（示例）.mp3", mp3());
        post(
                contactPath(patientId),
                admin,
                Map.of("method", "PHONE", "result", "REACHED", "audioFileIds", List.of(fileId)));

        assertEquals(40400, code(get(contactPath(patientId), doctor)), "越范围患者列表 40400");
        assertEquals(
                40400,
                code(
                        post(
                                contactPath(patientId),
                                doctor,
                                Map.of("method", "PHONE", "result", "REACHED"))),
                "越范围患者写入 40400");
        assertEquals(
                40400,
                code(get("/api/hospital/files/" + fileId + "/preview-url", doctor)),
                "越范围患者的录音预览 40400（E15-T04 收紧）");
        assertEquals(40400, code(get(contactPath(999999999L), doctor)), "不存在同 40400");
    }

    /** 校验失败：未知结果 40001；非录音文件 40001；重复挂同一录音 40001；下次联系时间早于本次 40001。 */
    @Test
    void invalidBodiesAreRejected() {
        String admin = hospitalToken("demoa", "nurse.demo");
        long patientId = registeredPatient("demoa", admin, "V1");

        Map<?, ?> badResult =
                post(contactPath(patientId), admin, Map.of("method", "PHONE", "result", "GAVE_UP"));
        assertEquals(40001, code(badResult), "未知联系结果 40001");
        assertTrue(
                String.valueOf(badResult.get("message")).contains("告知已故"),
                "错误提示含完整六值：" + badResult.get("message"));

        long pngId = uploadAudio(admin, "IT-E15T04-说明（示例）.png", png());
        // PNG 挂沟通记录：不是录音 40001
        Map<?, ?> withPng =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of(
                                "method",
                                "PHONE",
                                "result",
                                "REACHED",
                                "audioFileIds",
                                List.of(pngId)));
        assertEquals(40001, code(withPng), "只接受录音文件");
        // 重复挂同一 MP3：40001
        long mp3Id = uploadAudio(admin, "IT-E15T04-通话录音（示例）.mp3", mp3());
        assertEquals(
                0,
                code(
                        post(
                                contactPath(patientId),
                                admin,
                                Map.of(
                                        "method",
                                        "PHONE",
                                        "result",
                                        "REACHED",
                                        "audioFileIds",
                                        List.of(mp3Id)))));
        Map<?, ?> again =
                post(
                        contactPath(patientId),
                        admin,
                        Map.of(
                                "method",
                                "PHONE",
                                "result",
                                "REACHED",
                                "audioFileIds",
                                List.of(mp3Id)));
        assertEquals(40001, code(again), "同一录音不能挂第二条沟通记录");
        // 下次联系时间不晚于本次
        assertEquals(
                40001,
                code(
                        post(
                                contactPath(patientId),
                                admin,
                                Map.of(
                                        "method",
                                        "PHONE",
                                        "result",
                                        "REACHED",
                                        "contactTime",
                                        "2026-11-05T09:00:00",
                                        "nextContactTime",
                                        "2026-11-05T09:00:00"))),
                "下次联系时间必须晚于本次沟通时间");
    }

    // ---------------------------------------------------------------- 双租户隔离

    /** demoa 的患者与沟通记录在 demob 不可见（40400）；demob 内自成闭环。 */
    @Test
    void contactLogsAreIsolatedPerTenant() {
        String adminA = hospitalToken("demoa", "nurse.demo");
        String adminB = hospitalToken("demob", "nurse.demo");
        long patientA = registeredPatient("demoa", adminA, "T1");

        assertEquals(40400, code(get(contactPath(patientA), adminB)), "跨租户患者 40400（独立租户库）");
        assertEquals(
                40400,
                code(
                        post(
                                contactPath(patientA),
                                adminB,
                                Map.of("method", "PHONE", "result", "REACHED"))),
                "跨租户写入 40400");

        long patientB = registeredPatient("demob", adminB, "T2");
        assertEquals(
                0,
                code(
                        post(
                                contactPath(patientB),
                                adminB,
                                Map.of("method", "WECHAT", "result", "REACHED"))),
                "demob 内记沟通正常");
        Map<?, ?> pageB = data(get(contactPath(patientB), adminB));
        assertEquals(1, ((Number) pageB.get("total")).intValue(), "demob 只看到自己的记录");
    }

    // ---------------------------------------------------------------- 造数与工具（与 TaskAssignmentIT
    // 同口径）

    private static String contactPath(long patientId) {
        return "/api/hospital/followup/patients/" + patientId + "/contacts";
    }

    /** 登记患者（默认病区 = 医生病区，范围内），不带任务（联系记录不依赖任务）。 */
    private long registeredPatient(String tenant, String token, String suffix) {
        return registerPatient(tenant, token, suffix, today().minusDays(20));
    }

    /** 发布计划：单节点电话随访（偏移 20 天、窗口 ±3 天）——起算 T−20 时窗口覆盖今天。 */
    private long publishedWindowPlan(String token) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 沟通计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        Map<?, ?> created = post("/api/hospital/followup/plans", token, body);
        assertEquals(0, code(created), "新建计划失败：" + created);
        long planId = Long.parseLong((String) data(created).get("id"));
        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "循环随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 365);
        subplan.put("cycleUnit", "DAY");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 3);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 3);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 3);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "A_ANNUAL_FU");
        node.put("sortNo", 20);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                code(post("/api/hospital/followup/plans/" + planId + "/publish", token, Map.of())),
                "发布失败");
        return planId;
    }

    private long registerPatient(String tenant, String token, String suffix, LocalDate discharge) {
        return register(tenant, token, LEGACY + "-" + suffix, defaultWardId(tenant), discharge);
    }

    private long registerPatientInWard(
            String tenant, String token, String suffix, LocalDate discharge, long wardId) {
        return register(tenant, token, LEGACY + "-" + suffix, wardId, discharge);
    }

    private long register(
            String tenant, String token, String legacyNo, long wardId, LocalDate discharge) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周沟通（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(3).toString());
        body.put("surgeryDate", discharge.minusDays(2).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 入组（E14-T05 入组即建当期任务：起算 T−20 + 偏移 20 → 计划日期 = 今天、窗口 ±3 已开启）。 */
    private long enrollCurrentTask(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    return jdbc.queryForObject(
                            "SELECT id FROM fu_task WHERE enrollment_id = ? ORDER BY id DESC LIMIT 1",
                            Long.class,
                            enrollmentId);
                });
    }

    /** 上传一个录音 / 图片（nurse.demo = TENANT_ADMIN，有 followup:scan:upload），返回 fileId。 */
    private long uploadAudio(String token, String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new ByteArrayResource(body) {
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
        assertEquals(0, code(response), "上传失败：" + response);
        return idOf(data(response).get("fileId"));
    }

    /** 建角色并授权菜单与数据范围（scopeType = ALL / WARD）。 */
    private long roleWithMenus(String roleCode, List<Long> menuIds, String scopeType) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status)"
                                    + " VALUES (?, 'IT 角色（示例）', 0, 'ENABLED')",
                            roleCode);
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
                    for (Long menuId : menuIds) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                roleId,
                                menuId);
                    }
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)",
                            roleId,
                            scopeType);
                    return roleId;
                });
    }

    /** 建用户（走系统接口，首密改口令后登录）并返回 token。 */
    private String newUser(String tenant, String username, long roleId) {
        long deptId =
                runInTenant(
                        tenant,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalToken(tenant, "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 用户（示例）",
                                "phone",
                                "1390000144" + Math.abs(username.hashCode() % 10),
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken(tenant, username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken(tenant, username, PASSWORD);
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    /** 越范围病区（医生数据范围外；存在即复用，cleanState 删除）。 */
    private long ensureOtherWard() {
        return runInTenant(
                "demoa",
                () -> {
                    Long existing =
                            jdbc.query(
                                    "SELECT id FROM ward WHERE code = 'W-IT-E15T04'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES ('W-IT-E15T04', 'IT 越范围病区（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-E15T04'", Long.class);
                });
    }

    private <T> T jdbcQuery(String tenant, String sql, Class<T> type, Object... args) {
        return runInTenant(tenant, () -> jdbc.queryForObject(sql, type, args));
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

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }

    private String hospitalToken(String tenant, String username) {
        return hospitalToken(tenant, username, PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
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
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class)
                                .getBody();
        return response == null ? Map.of() : response;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    /** 创建结果（CreateResult）里的沟通记录视图。 */
    private static Map<?, ?> logOf(Map<?, ?> response) {
        return (Map<?, ?>) data(response).get("log");
    }

    /** 超 2^53 的 ID 序列化为字符串（AGENTS.md 第 6 节），两种形态都取 long。 */
    private static long idOf(Object value) {
        return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value));
    }

    /** ID3v2 头 + 帧填充（Tika 按 ID3 魔数识别 audio/mpeg，与 FileServiceAcceptanceIT 同款）。 */
    private static byte[] mp3() {
        byte[] header = new byte[] {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] payload = new byte[header.length + 128];
        System.arraycopy(header, 0, payload, 0, header.length);
        for (int i = header.length; i < payload.length; i++) {
            payload[i] = 0x55;
        }
        return payload;
    }

    private static byte[] amr() {
        return "#!AMR\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** 合法 1×1 PNG（能通过上传白名单与 Tika 解码；挂沟通记录时按「只接受录音」被拒）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    /** 测试内会话清理（服务层造数可能装配认证上下文）。 */
    static final class SecurityContextHolderClear {
        private SecurityContextHolderClear() {}

        static void clear() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
