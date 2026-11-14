package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E21-T16 端到端 / C-SCAN-009（完整应用 + 真实 MySQL，HTTP 直连）。验收原文：未指定位置的扫描件集中分拣并指定位置
 * （规格：先扫描后标注——未选患者的文件进入待归档区，录入人员逐个指定患者、类型与位置（至少一个）后归档）。
 *
 * <p>覆盖：进区 / 检索 / 归档全链路（生成报告单、复用创建校验与 RPT_CREATE 审计、行置 ARCHIVED 保留溯源、附件转 ATTACHED）；进区校验（空清单 / 超 50
 * 个 / 重复进区 / 已挂他处）；归档校验（缺患者 / 空位置 / 越范围患者 40400 / 类型名称必填 / 行不存在 40400）；按上传人所在科室病区隔离（病区无交集不可见、归档
 * 40400 写 DENIED 审计、科室交集可见、 ALL 全见、上传人本人可见）；开关 report_inbox 关闭 40301；无权限 40300、模块关闭 40301。全部虚构数据，门控
 * CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportInboxIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String BASE = "/api/hospital/followup";
    private static final String UPLOAD = "/api/hospital/files";
    private static final String LEGACY = "IT-E21T16";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;

    /** 建范围用户的手机号序号（本院唯一约束）。 */
    private static final AtomicInteger phoneSeq = new AtomicInteger(600);

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    // 开关回关（A 租户形态；followup V138 默认 0）
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                    + " 'report_inbox'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE object_type = 'REPORT' AND object_id IN"
                                    + " (SELECT id FROM rpt_report WHERE patient_id IN"
                                    + "  (SELECT id FROM pt_patient WHERE legacy_no LIKE ?))",
                            LEGACY + "-%");
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE action IN ('RPT_INBOX_CREATE')");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action = 'RPT_INBOX_ARCHIVE'"
                                    + " AND result = 'DENIED'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_data_access_log WHERE object_type ="
                                    + " 'REPORT_INBOX'");
                    jdbc.update(
                            "DELETE FROM rpt_inbox_file WHERE uploaded_by IN"
                                    + " (SELECT id FROM sys_user WHERE username LIKE 'IT-T16-%')"
                                    + " OR file_id IN (SELECT id FROM sys_file WHERE original_name"
                                    + " LIKE 'IT-E21T16-%')");
                    jdbc.update(
                            "DELETE f FROM rpt_report_file f"
                                    + " JOIN rpt_report r ON r.id = f.report_id"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE l FROM rpt_report_link l"
                                    + " JOIN pt_patient p ON p.id = l.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE r FROM rpt_report r"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM sys_file WHERE original_name LIKE 'IT-E21T16-%'");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE ds FROM sys_user_dept_scope ds"
                                    + " JOIN sys_user u ON u.id = ds.user_id"
                                    + " WHERE u.username LIKE 'IT-T16-%'");
                    jdbc.update(
                            "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                    + " WHERE u.username LIKE 'IT-T16-%'");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                    + " WHERE u.username LIKE 'IT-T16-%'");
                    jdbc.update(
                            "DELETE ph FROM sys_password_history ph"
                                    + " JOIN sys_user u ON u.id = ph.user_id"
                                    + " WHERE u.username LIKE 'IT-T16-%'");
                    jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-T16-%'");
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code LIKE 'IT-T16-%'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code LIKE 'IT-T16-%'");
                    jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-T16-%'");
                    jdbc.update("DELETE FROM ward WHERE code LIKE 'W-IT-T16-%'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 全链路（验收：集中分拣并指定位置）

    /** 先扫描（进区）→ 集中分拣（检索）→ 指定患者 / 类型 / 位置归档（生成报告单，行置 ARCHIVED 留溯源）。 */
    @Test
    void intakeSearchArchiveRoundTrip() {
        enableFeature();
        String admin = hospitalToken("demoa");
        long f1 = uploadPng(admin, "IT-E21T16-扫描件一（示例）.png");
        long f2 = uploadPng(admin, "IT-E21T16-扫描件二（示例）.png");
        long f3 = uploadPng(admin, "IT-E21T16-扫描件三（示例）.png");

        // 先扫描：一批扫描件放入待归档区（未选患者，C-SCAN-009）
        Map<?, ?> intake =
                post(
                        BASE + "/report-inbox/files",
                        admin,
                        Map.of("fileIds", List.of(str(f1), str(f2), str(f3))));
        assertEquals(0, code(intake), "进区失败：" + intake);
        assertEquals(3, ((Number) data(intake).get("fileCount")).intValue());

        // 集中分拣：检索只出待分拣行，行带文件台账信息
        Map<?, ?> page = post(BASE + "/report-inbox/search", admin, Map.of());
        assertEquals(0, code(page));
        assertEquals(3L, ((Number) data(page).get("total")).longValue());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) data(page).get("items")).get(0);
        String inboxId = str(row.get("id"));
        assertEquals(str(f3), str(row.get("fileId")), "按进区时间倒序（最新批次在前）");
        assertEquals("png", row.get("format"));
        assertTrue(((Number) row.get("sizeBytes")).longValue() > 0, "文件大小来自台账");
        assertNotNull(row.get("uploaderName"));
        assertFalse(data(page).get("uploaders").toString().isEmpty(), "上传人候选随页返回");

        // 上传日期 / 上传人过滤
        long adminId = userIdOf("demoa", "nurse.demo");
        assertEquals(
                3L,
                ((Number)
                                data(post(
                                                BASE + "/report-inbox/search",
                                                admin,
                                                Map.of("creatorId", adminId)))
                                        .get("total"))
                        .longValue(),
                "按上传人过滤");
        assertEquals(
                0L,
                ((Number)
                                data(post(
                                                BASE + "/report-inbox/search",
                                                admin,
                                                Map.of(
                                                        "uploadedFrom",
                                                        LocalDate.now().plusDays(1).toString())))
                                        .get("total"))
                        .longValue(),
                "上传日期起点在明天 → 空");
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/report-inbox/search",
                                admin,
                                Map.of("uploadedFrom", "2026-12-31", "uploadedTo", "2026-01-01"))),
                "起止日期颠倒 40001");
        assertEquals(
                40001,
                code(post(BASE + "/report-inbox/search", admin, Map.of("pageNo", 0))),
                "pageNo 从 1 开始");

        // 后标注：指定患者、类型与位置（至少一个）归档 → 生成报告单
        long patientId = registeredPatient("demoa", admin, defaultWardId("demoa"), "何待归（示例）");
        Map<?, ?> name = labNameWithItems(admin);
        String categoryKey = baselineCategoryKey(admin, patientId);
        Map<?, ?> archived =
                post(
                        BASE + "/report-inbox/" + inboxId + "/archive",
                        admin,
                        archiveBody(patientId, name, categoryKey, "2026-10-20"));
        assertEquals(0, code(archived), "归档失败：" + archived);
        String reportId = str(data(archived).get("id"));
        assertNotNull(reportId);
        assertEquals("检验", data(archived).get("typeName"));
        assertEquals(1, ((Number) data(archived).get("locationCount")).intValue());
        assertEquals(1, ((Number) data(archived).get("fileCount")).intValue(), "附件即待归档文件");

        // 报告单进入患者内清单；附件台账转 ATTACHED；待归档行置 ARCHIVED 并记 report_id（溯源）
        Map<?, ?> reportsData =
                (Map<?, ?>) get(BASE + "/patients/" + patientId + "/reports", admin).get("data");
        List<?> reports = (List<?>) reportsData.get("items");
        assertEquals(
                1,
                reports.stream()
                        .filter(item -> reportId.equals(str(((Map<?, ?>) item).get("id"))))
                        .count(),
                "归档生成的报告单出现在患者内清单");
        runInTenant(
                "demoa",
                () -> {
                    assertEquals(
                            "ATTACHED",
                            jdbc.queryForObject(
                                    "SELECT status FROM sys_file WHERE id = ?", String.class, f3),
                            "归档后附件转 ATTACHED");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM rpt_inbox_file WHERE id = "
                                            + inboxId
                                            + " AND status = 'ARCHIVED' AND archived_report_id = "
                                            + reportId,
                                    Long.class),
                            "待归档行置 ARCHIVED 并记 report_id（行保留作溯源）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_INBOX_CREATE' AND result = 'SUCCESS'",
                                            Long.class)
                                    >= 1,
                            "进区写 RPT_INBOX_CREATE 审计");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_INBOX_ARCHIVE' AND result ="
                                                    + " 'SUCCESS' AND object_id = '"
                                                    + inboxId
                                                    + "'",
                                            Long.class)
                                    >= 1,
                            "归档写 RPT_INBOX_ARCHIVE 审计");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                    + " 'RPT_CREATE' AND object_type = 'REPORT'"
                                                    + " AND object_id = '"
                                                    + reportId
                                                    + "'",
                                            Long.class)
                                    >= 1,
                            "生成报告单写 RPT_CREATE 审计（复用创建链路）");
                    assertTrue(
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_data_access_log WHERE"
                                                    + " action = 'BATCH_QUERY' AND object_type ="
                                                    + " 'REPORT_INBOX'",
                                            Long.class)
                                    >= 1,
                            "批量查询写 BATCH_QUERY 数据访问审计");
                });

        // 归档后退出待分拣清单；重复归档 40400（行已非 PENDING → 不可见，不暴露状态）
        assertEquals(
                2L,
                ((Number) data(post(BASE + "/report-inbox/search", admin, Map.of())).get("total"))
                        .longValue(),
                "归档后待分拣数 -1");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/" + inboxId + "/archive",
                                admin,
                                archiveBody(patientId, name, categoryKey, null))),
                "重复归档 40400");
    }

    // ---------------------------------------------------------------- 进区校验（C-SCAN-009 先扫描）

    /** 空清单 / 超上限 / 重复进区 / 已挂他处的文件均 40001，且不落行。 */
    @Test
    void intakeValidations() {
        enableFeature();
        String admin = hospitalToken("demoa");
        assertEquals(
                40001,
                code(post(BASE + "/report-inbox/files", admin, Map.of("fileIds", List.of()))),
                "空清单 40001");

        List<String> tooMany = new ArrayList<>();
        for (long id = 900_000L; id < 900_051L; id++) {
            tooMany.add(str(id));
        }
        assertEquals(
                40001,
                code(post(BASE + "/report-inbox/files", admin, Map.of("fileIds", tooMany))),
                "一次最多 50 个文件");

        long fileId = uploadPng(admin, "IT-E21T16-校验（示例）.png");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                admin,
                                Map.of("fileIds", List.of(str(fileId))))));
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                admin,
                                Map.of("fileIds", List.of(str(fileId))))),
                "同一文件重复进区 40001");

        // 已挂报告单的文件（ATTACHED）不能进区
        long attached = uploadPng(admin, "IT-E21T16-已挂（示例）.png");
        long patientId = registeredPatient("demoa", admin, defaultWardId("demoa"), "柳待归（示例）");
        Map<?, ?> name = labNameWithItems(admin);
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/reports",
                                admin,
                                reportBody(
                                        patientId,
                                        "LAB",
                                        str(name.get("id")),
                                        baselineCategoryKey(admin, patientId),
                                        null,
                                        null,
                                        attached))));
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                admin,
                                Map.of("fileIds", List.of(str(attached))))),
                "已挂报告单的文件 40001");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                admin,
                                Map.of("fileIds", List.of("not-a-number")))),
                "非法 fileId 40400");
    }

    // ---------------------------------------------------------------- 归档校验（C-SCAN-009 后标注）

    /** 缺患者 / 空位置 / 类型名称必填 / 越范围患者 / 行不存在。 */
    @Test
    void archiveValidations() {
        enableFeature();
        String admin = hospitalToken("demoa");
        long patientId = registeredPatient("demoa", admin, defaultWardId("demoa"), "茅待归（示例）");
        Map<?, ?> name = labNameWithItems(admin);
        String categoryKey = baselineCategoryKey(admin, patientId);
        long fileId = uploadPng(admin, "IT-E21T16-归档校验（示例）.png");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                admin,
                                Map.of("fileIds", List.of(str(fileId))))));
        long inboxId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM rpt_inbox_file WHERE file_id = ?",
                                        Long.class,
                                        fileId));

        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/report-inbox/" + inboxId + "/archive",
                                admin,
                                Map.of("typeCode", "LAB", "locations", List.of()))),
                "缺患者 40001");
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/report-inbox/" + inboxId + "/archive",
                                admin,
                                Map.of(
                                        "patientId",
                                        patientId,
                                        "typeCode",
                                        "LAB",
                                        "locations",
                                        List.of()))),
                "位置为空 40001（至少一个）");
        assertEquals(
                40001,
                code(
                        post(
                                BASE + "/report-inbox/" + inboxId + "/archive",
                                admin,
                                Map.of(
                                        "patientId",
                                        patientId,
                                        "typeCode",
                                        "LAB",
                                        "locations",
                                        List.of(baselineLocation(categoryKey))))),
                "类型必填名称（LAB nameRequired）40001");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/" + inboxId + "/archive",
                                admin,
                                archiveBody(999_000_000L, name, categoryKey, null))),
                "患者不存在 40400");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/" + inboxId + "/archive",
                                admin,
                                Map.of(
                                        "patientId",
                                        patientId,
                                        "typeCode",
                                        "NO_SUCH_TYPE",
                                        "nameId",
                                        name.get("id"),
                                        "locations",
                                        List.of(baselineLocation(categoryKey))))),
                "类型不存在 40400");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/999000000/archive",
                                admin,
                                archiveBody(patientId, name, categoryKey, null))),
                "待归档行不存在 40400");

        // 越范围患者：病区范围用户归档自己的待归档文件到其他病区患者 → 行可见但患者越范围 40400
        // （requirePatient 数据范围校验，不暴露存在性，AGENTS §5.2）
        long ownWard = createWard("W-IT-T16-VA", "示例 IT 归档病区（E21-T16）");
        long patientWard = createWard("W-IT-T16-VB", "示例 IT 患者病区（E21-T16）");
        long outOfScopePatient = registeredPatient("demoa", admin, patientWard, "茅他区（示例）");
        String wardUser = scopedToken("IT-T16-WD", ownWard, null, "IT-T16-RW");
        long wardUserFile = uploadPng(wardUser, "IT-E21T16-越范围（示例）.png");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                wardUser,
                                Map.of("fileIds", List.of(str(wardUserFile))))),
                "病区用户进区自己的文件");
        long wardUserInboxId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM rpt_inbox_file WHERE file_id = ?",
                                        Long.class,
                                        wardUserFile));
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/" + wardUserInboxId + "/archive",
                                wardUser,
                                archiveBody(outOfScopePatient, name, categoryKey, null))),
                "患者不在归档者数据范围 40400");
    }

    // ---------------------------------------------------------------- 按上传人科室病区隔离（C-WARD-005 验收）

    /**
     * 待归档区按上传人所在科室 / 病区隔离（docs/api/report.md §4 暂行口径）：病区无交集不可见（归档 40400 写 DENIED 审计）、科室交集可见、ALL
     * 全见、上传人本人恒可见。
     */
    @Test
    void inboxIsIsolatedByUploaderWardAndDept() {
        enableFeature();
        String admin = hospitalToken("demoa");
        long ownWard = createWard("W-IT-T16-OW", "示例 IT 同病区（E21-T16）");
        long otherWard = createWard("W-IT-T16-TW", "示例 IT 他病区（E21-T16）");
        long deptId = deptIdByCode("demoa", "DEPT-CARDIO");

        // 上传人：本病区 + 负责科室（隔离锚点 = 上传人的负责科室 / 病区）
        String uploader = scopedToken("IT-T16-UP", ownWard, deptId, "IT-T16-RW");
        // 同病区查看者（病区分支命中）
        String sameWardViewer = scopedToken("IT-T16-SV", ownWard, null, "IT-T16-RW");
        // 他病区查看者（病区、科室都无交集 → 只见自己上传的）
        String otherWardViewer = scopedToken("IT-T16-TV", otherWard, null, "IT-T16-RW");
        // 科室查看者（无病区，负责科室与上传人交集命中）
        String deptViewer = scopedToken("IT-T16-DV", null, deptId, "IT-T16-RD");

        long uploaderFile = uploadPng(uploader, "IT-E21T16-隔离上传（示例）.png");
        assertEquals(
                0,
                code(
                        post(
                                BASE + "/report-inbox/files",
                                uploader,
                                Map.of("fileIds", List.of(str(uploaderFile))))),
                "上传人（有 231）可进区");
        long uploaderInboxId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM rpt_inbox_file WHERE file_id = ?",
                                        Long.class,
                                        uploaderFile));

        // 他病区：不可见（检索不含、归档 40400 + DENIED 审计）
        List<?> otherItems =
                (List<?>)
                        data(post(BASE + "/report-inbox/search", otherWardViewer, Map.of()))
                                .get("items");
        assertTrue(
                otherItems.stream()
                        .noneMatch(
                                item ->
                                        str(((Map<?, ?>) item).get("id"))
                                                .equals(str(uploaderInboxId))),
                "他病区查看者看不到上传人的待归档文件");
        assertEquals(
                40400,
                code(
                        post(
                                BASE + "/report-inbox/" + uploaderInboxId + "/archive",
                                otherWardViewer,
                                Map.of("patientId", 1, "typeCode", "LAB", "locations", List.of()))),
                "他病区归档 40400");
        runInTenant(
                "demoa",
                () ->
                        assertTrue(
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'RPT_INBOX_ARCHIVE' AND result = 'DENIED'"
                                                        + " AND object_id = '"
                                                        + uploaderInboxId
                                                        + "'",
                                                Long.class)
                                        >= 1,
                                "越范围归档写 DENIED 审计"));

        // 同病区与科室交集：可见
        assertTrue(
                ((List<?>)
                                data(post(BASE + "/report-inbox/search", sameWardViewer, Map.of()))
                                        .get("items"))
                        .stream()
                                .anyMatch(
                                        item ->
                                                str(((Map<?, ?>) item).get("id"))
                                                        .equals(str(uploaderInboxId))),
                "同病区查看者可见（病区交集）");
        assertTrue(
                ((List<?>)
                                data(post(BASE + "/report-inbox/search", deptViewer, Map.of()))
                                        .get("items"))
                        .stream()
                                .anyMatch(
                                        item ->
                                                str(((Map<?, ?>) item).get("id"))
                                                        .equals(str(uploaderInboxId))),
                "科室交集查看者可见（上传人负责科室命中）");

        // 上传人本人恒可见（「仅本人负责的患者」范围不滞留）；ALL 管理员全见
        assertTrue(
                ((List<?>)
                                data(post(BASE + "/report-inbox/search", uploader, Map.of()))
                                        .get("items"))
                        .stream()
                                .anyMatch(
                                        item ->
                                                str(((Map<?, ?>) item).get("id"))
                                                        .equals(str(uploaderInboxId))),
                "上传人本人可见");
        assertTrue(
                ((List<?>) data(post(BASE + "/report-inbox/search", admin, Map.of())).get("items"))
                        .stream()
                                .anyMatch(
                                        item ->
                                                str(((Map<?, ?>) item).get("id"))
                                                        .equals(str(uploaderInboxId))),
                "ALL 范围管理员可见");
    }

    // ---------------------------------------------------------------- 开关 / 权限 / 模块

    /** 开关 report_inbox 关闭（A / B 默认，含无行）三个口一律 40301；无权限 40300；模块关闭 40301。 */
    @Test
    void featurePermissionAndModuleGuards() {
        String admin = hospitalToken("demoa");
        String doctor = hospitalToken("demoa", "doctor.demo");
        // 开关关闭（cleanState 已回关；本用例不再打开）
        assertEquals(
                40301,
                code(post(BASE + "/report-inbox/files", admin, Map.of("fileIds", List.of("1")))),
                "进区：开关关闭 40301");
        assertEquals(
                40301, code(post(BASE + "/report-inbox/search", admin, Map.of())), "检索：开关关闭 40301");
        assertEquals(
                40301,
                code(
                        post(
                                BASE + "/report-inbox/1/archive",
                                admin,
                                Map.of("patientId", 1, "typeCode", "LAB", "locations", List.of()))),
                "归档：开关关闭 40301");

        enableFeature();
        // 无权限：FOLLOWUP_DOCTOR 未授菜单 23 / 354（followup:report:view）与按钮 231（followup:scan:upload）
        assertEquals(
                40300, code(post(BASE + "/report-inbox/search", doctor, Map.of())), "检索无权限 40300");
        assertEquals(
                40300,
                code(post(BASE + "/report-inbox/files", doctor, Map.of("fileIds", List.of("1")))),
                "进区无权限 40300");
        assertEquals(
                40300,
                code(
                        post(
                                BASE + "/report-inbox/1/archive",
                                doctor,
                                Map.of("patientId", 1, "typeCode", "LAB", "locations", List.of()))),
                "归档无权限 40300");

        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(post(BASE + "/report-inbox/search", admin, Map.of())),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(post(BASE + "/report-inbox/search", admin, Map.of())), "模块恢复后可用");
    }

    // ---------------------------------------------------------------- 造数与工具（与 ReportSearchIT 同口径）

    private void enableFeature() {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                        + " 'report_inbox'"));
    }

    private long registeredPatient(String tenant, String token, long wardId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + (System.nanoTime() % 100000));
        body.put("name", name);
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", wardId);
        body.put("admissionDate", LocalDate.now().minusDays(23).toString());
        body.put("surgeryDate", LocalDate.now().minusDays(22).toString());
        body.put("dischargeDate", LocalDate.now().minusDays(20).toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", body.get("medicalRecordNo"));
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong(str(data(created).get("id")));
    }

    /** LAB 首个绑定模板的名称（血常规，A 预置 V62）。 */
    private Map<?, ?> labNameWithItems(String token) {
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        return (Map<?, ?>) names.get(0);
    }

    /** 归档请求体（患者 + 类型 + 名称 + 位置 = 基线分类；reportDate 可空 = 缺省当天）。 */
    private Map<String, Object> archiveBody(
            long patientId, Map<?, ?> name, String categoryKey, String reportDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "LAB");
        body.put("nameId", name.get("id"));
        if (reportDate != null) {
            body.put("reportDate", reportDate);
        }
        body.put("locations", List.of(baselineLocation(categoryKey)));
        return body;
    }

    /** 基线分类位置（linkType=baseline + anchorType=category）。 */
    private Map<String, Object> baselineLocation(String categoryKey) {
        Map<String, Object> location = new HashMap<>();
        location.put("linkType", "baseline");
        location.put("anchorType", "category");
        location.put("anchorKey", categoryKey);
        return location;
    }

    /** 位置树基线根第一个分类的锚点（种子 V38 分类 1–8，与 ReportSearchIT 同口径）。 */
    private String baselineCategoryKey(String token, long patientId) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baselineRoot =
                (Map<?, ?>)
                        roots.stream()
                                .filter(
                                        node ->
                                                "baseline"
                                                        .equals(str(((Map<?, ?>) node).get("key"))))
                                .findFirst()
                                .orElseThrow();
        return str(
                ((Map<?, ?>)
                                ((Map<?, ?>) ((List<?>) baselineRoot.get("children")).get(0))
                                        .get("value"))
                        .get("anchorKey"));
    }

    /** 单份报告单创建体（intakeValidations 造「已挂他处」的文件用）。 */
    private Map<String, Object> reportBody(
            long patientId,
            String typeCode,
            String nameId,
            String categoryKey,
            Long recordId,
            String sectionKey,
            Long fileId) {
        List<Map<String, Object>> locations = new ArrayList<>();
        locations.add(baselineLocation(categoryKey));
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", typeCode);
        body.put("nameId", nameId);
        body.put("reportDate", "2026-10-20");
        body.put("locations", locations);
        body.put("fileIds", fileId == null ? List.of() : List.of(str(fileId)));
        return body;
    }

    /**
     * 范围用户：IT 专用角色（IT-T16-RW 病区 / IT-T16-RD 负责科室，均含菜单 20 / 23 / 354 与按钮 231） + 指定 病区 /
     * 科室，首登改密后返回令牌。角色建一次幂等复用；角色与用户随 cleanState 清理，不污染预置角色。
     */
    private String scopedToken(String username, Long wardId, Long deptId, String roleCode) {
        String admin = hospitalToken("demoa");
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            List<Long> found =
                                    jdbc.queryForList(
                                            "SELECT id FROM sys_role WHERE code = ?",
                                            Long.class,
                                            roleCode);
                            if (!found.isEmpty()) {
                                return found.get(0);
                            }
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status, role_type,"
                                            + " module_code, description) VALUES (?, 'IT 待归档范围角色（示例）',"
                                            + " 0, 'ENABLED', 'CUSTOM', 'followup', 'E21-T16 IT 专用')",
                                    roleCode);
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = ?",
                                            Long.class,
                                            roleCode);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, ?)",
                                    id,
                                    "IT-T16-RW".equals(roleCode) ? "WARD" : "RESPONSIBLE_DEPT");
                            for (long menuId : new long[] {20, 23, 231, 354}) {
                                jdbc.update(
                                        "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                        id,
                                        menuId);
                            }
                            return id;
                        });
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("displayName", "IT 范围用户（示例）");
        body.put("phone", "1390000" + String.format("%04d", phoneSeq.incrementAndGet() % 10000));
        body.put("mainDeptId", deptIdByCode("demoa", "DEPT-CARDIO"));
        body.put("roleIds", List.of(roleId));
        if (wardId != null) {
            body.put("wardIds", List.of(wardId));
        }
        if (deptId != null) {
            body.put("deptScopeIds", List.of(deptId));
        }
        Map<?, ?> created = post("/api/hospital/system/users", admin, body);
        assertEquals(0, code(created), "建范围用户失败：" + created + "（" + username + "）");
        String temporary = str(data(created).get("temporaryPassword"));
        String newPassword = "It#E21t16-Pass";
        String token = hospitalToken("demoa", username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", newPassword))),
                "首登改密失败");
        return hospitalToken("demoa", username, newPassword);
    }

    /** 上传一个 PNG（须有 followup:scan:upload），返回 fileId。 */
    private long uploadPng(String token, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(png()) {
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
        return Long.parseLong(str(data(response).get("fileId")));
    }

    /** 合法 1×1 PNG（与 ReportSearchIT 同一张）。 */
    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    private long createWard(String code, String name) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO ward (code, name, status) VALUES (?, ?, 'ENABLED')",
                            code,
                            name);
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = ?", Long.class, code);
                });
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private long deptIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }

    private long userIdOf(String tenant, String username) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo", PASSWORD);
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
        assertEquals(0, code(response));
        return (String) data(response).get("token");
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
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
