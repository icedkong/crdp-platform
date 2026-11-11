package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.outbox.OutboxDispatcher;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
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
 * E23-T12 A 上线核对与冒烟测试（tasks.csv 验收「冒烟用例通过」；本行未关联需求编号，覆盖面 =
 * `docs/testing/hospital-a-golive-smoke.md` §3 用例集的机器可判定部分，同 E19-T09 回归收尾口径）。
 *
 * <p>用例登记与手册 §3 一一对应（方法上注释锚点 S-x；S-0 生产入口 / S-1 租户转正属生产人工，不在自动化范围）：
 *
 * <ol>
 *   <li>S-2 功能开关：features 接口按租户返回 {@code wide_import=true} 等 A 配置模板开关（runbook §6 动作 4 机器面；V100
 *       种子口径）；
 *   <li>S-3 各角色登录与菜单：V123 四类角色账号（科主任 / 病区医生 / 随访员 / 数据录入员）登录成功、me 带 医院名称，菜单按 §10
 *       权限矩阵分化（科主任有「随访计划」、随访员无；数据录入员无「我的任务」）（UAT A-1）；
 *   <li>S-5 新建示例患者与查重（先于 S-4 执行为列表与后续用例造数）：病区医生登记「赵冒烟 / 钱跟进（示例）」 出院 = 一年前；同病案号重复登记 40902 拦截（UAT
 *       B-2、A-PAT-007）；另名「孙他区（示例）」登记在心外二 （S-11 越范围对照）；
 *   <li>S-4 患者档案列表与脱敏：随访员列表每页 ≤20、姓名脱敏含 {@code *}、只见本病区（孙他区不可见）（UAT B-1、A-WARD-003、A-PAT-009）；
 *   <li>S-6 自动入组与当期任务：登记事务内自动加入预置计划 A_ANNUAL_FU_PLAN（Q35），第 1 年任务 PENDING、 计划日 =
 *       今天（窗口开启中，A-FU-002~004）；
 *   <li>S-7 任务认领与表单提交：随访员认领 → 开始录入 → 提交 A_ANNUAL_FU → 任务已完成（按时）（UAT E-2 / F-1、A-FU-005）；
 *   <li>S-8 未联系到跟进与站内信：认领第二名患者任务 → 沟通「未联系到 / 电话」→ 任务进行中 + 次日跟进任务 + 责任人站内信（姓名脱敏、TASK 落点）（UAT E-3 /
 *       G-1 / G-2、A-FU-006、A-NTF-001）；
 *   <li>S-9 报告单上传归档：上传 png → 登记报告单（基线位置）→ 患者内报告单清单可见（UAT H-1；水印预览为 生产人工核对项）；
 *   <li>S-10 首页卡片可读：工作台 kpi 卡返回且患者总数 ≥2（本动线两名示例患者）（UAT I-1；口径核对引用 UAT 结论不重算）；
 *   <li>S-11 数据范围与跨端红线：随访员直链他病区示例患者详情 40400；医院令牌调平台接口 40300（UAT K-2 / K-5、AGENTS §5.1~5.2）；
 *   <li>S-12 当日审计留痕：审计中心查到本动线的患者新建 / 任务认领 / 沟通 / 报告单事件（UAT M-1、PLT-AUD）。
 * </ol>
 *
 * <p>与既有 IT 的分工：各域深口径由分项 IT 锁（ReportIT / NoticeInboxIT / Dashboard*IT /
 * TenantIsolationAcceptanceIT……），本套件是<b>上线切换后立即执行的一条完整冒烟动线</b>——四角色真实登录、
 * 两名示例患者从登记到审计的全链路，任何一环断裂即「冒烟不通过」（手册 §6）。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis，CI 同口径）。自建自清（标记 IT-E23T12-
 * 前缀、文件名「IT-E23T12-报告单（示例）.png」；预置计划 auto_enroll 回迁移态，同 M3CoreRegressionIT）。 全部虚构数据（AGENTS.md 规则
 * 5）。后台 outbox 分发 tick 停用（1 小时间隔），站内信在用例内同步 dispatchForCurrentTenant 保证送达断言确定（同
 * TaskContactAcceptanceIT）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "crdp.outbox.dispatch.interval-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoliveSmokeIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    /** 造数与清理标记（全部虚构数据，AGENTS.md 规则 5）。 */
    private static final String LEGACY = "IT-E23T12";

    private static final String UPLOAD_FILE = "IT-E23T12-报告单（示例）.png";

    /** V123 预置账号（E23-T04：A 规格 §10 角色模板，本地 / IT 口径）。 */
    private static final String DIRECTOR = "a.cvs1.dir";

    private static final String WARD_DOCTOR = "a.cvs1.doc01";

    private static final String NURSE = "a.cvs1.nur01";

    private static final String CLERK = "a.fu.clerk01";

    private static final String OTHER_WARD_DOCTOR = "a.cvs2.doc01";

    /** 预置计划编码（V61：出院日期起算、每年一次、窗口 ±7 天、auto_enroll）。 */
    private static final String PRESET_PLAN = "A_ANNUAL_FU_PLAN";

    /** 出院 = 北京时间今天 − 1 年：第 1 年计划日 = 今天，窗口 [今天−7, 今天+7] 开启中（UAT B-2 口径）。 */
    private static final LocalDate DISCHARGE =
            PlanDateCalculator.businessToday(Instant.now()).minusYears(1);

    private static final LocalDate TODAY = PlanDateCalculator.businessToday(Instant.now());

    private static final DateTimeFormatter AUDIT_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'");

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private OutboxDispatcher dispatcher;

    /** 冒烟动线状态：三名示例患者（手册 §3 S-5 / S-8 / S-11）。 */
    private long patientId;

    private long secondPatientId;

    private long otherWardPatientId;

    @BeforeAll
    void recycleLeftoverFromEarlierRun() {
        runInTenant(
                "demoa",
                () -> {
                    // 预置计划回迁移态（V61：auto_enroll=1；其他 IT 按「单计划世界」关掉且不恢复，同 M3 回归）
                    jdbc.update("UPDATE fu_plan SET auto_enroll = 1 WHERE code = ?", PRESET_PLAN);
                    // 预置角色菜单回迁移态（V123）：PlatformTenantScopeMySqlIT 的收回用例会按合同收回
                    // 22/223 且 AfterAll 只还原 TENANT_ADMIN / FOLLOWUP_DOCTOR——A 预置角色的「我的任务」
                    // 授权随之泄漏丢失，套件序在本 IT 之前时四角色冒烟全部 40300。按该 IT 自身「重跑前
                    // 先还原」的前置归一口径补回 V123 随包状态（INSERT IGNORE 幂等）。
                    jdbc.update(
                            "INSERT IGNORE INTO sys_role_menu (role_id, menu_id)"
                                    + " SELECT r.id, m.menu_id FROM sys_role r"
                                    + " JOIN (SELECT 'A_WARD_DOCTOR' AS code, 22 AS menu_id"
                                    + " UNION ALL SELECT 'A_WARD_DOCTOR', 223"
                                    + " UNION ALL SELECT 'A_FOLLOWUP_NURSE', 22"
                                    + " UNION ALL SELECT 'A_FOLLOWUP_NURSE', 223"
                                    + " UNION ALL SELECT 'A_DIRECTOR', 22) m ON m.code = r.code");
                    cleanSmokeRows();
                });
    }

    @AfterAll
    void cleanUp() {
        recycleLeftoverFromEarlierRun();
    }

    // S-2：功能开关与 A 配置模板一致（runbook §6 动作 4 机器面；V100 种子）。
    @Test
    @Order(1)
    void s2_featureSwitchesMatchTenantTemplate() {
        String token = hospitalToken(NURSE);
        Map<?, ?> features = dataOf(get("/api/hospital/auth/features", token));
        assertEquals(Boolean.TRUE, features.get("wide_import"), "wide_import 开（A 宽表导入）");
        assertEquals(Boolean.TRUE, features.get("report"), "report 开（A 报告单）");
        assertEquals(
                Boolean.TRUE, features.get("compare_highlight"), "compare_highlight 开（A 差异标红）");
        assertEquals(Boolean.FALSE, features.get("implant"), "implant 关（A 无植入，仅医院 C）");
    }

    // S-3：四类 V123 同构账号登录成功、me 带医院名称，菜单按 §10 权限矩阵分化（UAT A-1）。
    @Test
    @Order(2)
    void s3_roleLoginsAndRoleScopedMenus() {
        Map<String, List<String>> titlesByRole = new HashMap<>();
        for (String username : List.of(DIRECTOR, WARD_DOCTOR, NURSE, CLERK)) {
            String token = hospitalToken(username);
            Map<?, ?> me = dataOf(get("/api/hospital/auth/me", token));
            assertEquals("虚构示例医院甲", me.get("tenantName"), "登录后显示医院名称（" + username + "）");
            titlesByRole.put(username, menuTitles(token));
        }
        assertTrue(titlesByRole.get(DIRECTOR).contains("随访计划"), "科主任可见随访计划（V123 §10 主要权限矩阵）");
        assertFalse(titlesByRole.get(NURSE).contains("随访计划"), "随访员无随访计划入口");
        assertTrue(titlesByRole.get(NURSE).contains("我的任务"), "随访员有我的任务");
        assertFalse(titlesByRole.get(CLERK).contains("我的任务"), "数据录入员无我的任务（不随访）");
        assertTrue(titlesByRole.get(CLERK).contains("患者档案"), "数据录入员有患者档案（新建 / 编辑）");
    }

    // S-5：病区医生登记两名示例患者 + 心外二对照患者；同病案号重复登记 40902 拦截（UAT B-2、A-PAT-007）。
    @Test
    @Order(3)
    void s5_registerSamplePatientsAndDuplicateBlocked() {
        String doctor = hospitalToken(WARD_DOCTOR);
        String otherDoctor = hospitalToken(OTHER_WARD_DOCTOR);

        patientId = registerPatient(doctor, wardId("W-A-CVS1"), "M1", "赵冒烟（示例）");
        secondPatientId = registerPatient(doctor, wardId("W-A-CVS1"), "M2", "钱跟进（示例）");
        otherWardPatientId = registerPatient(otherDoctor, wardId("W-A-CVS2"), "M3", "孙他区（示例）");

        Map<?, ?> duplicate =
                post(BASE + "/patients", doctor, patientBody(wardId("W-A-CVS1"), "M1", "赵冒烟（示例）"));
        assertEquals(40902, codeOf(duplicate), "重复病案号应 40902 阻止：" + duplicate);
    }

    // S-4：随访员患者列表每页 ≤20、姓名脱敏、只见本病区（UAT B-1、A-WARD-003、A-PAT-009）。
    @Test
    @Order(4)
    void s4_patientListMaskedPagedAndWardScoped() {
        String token = hospitalToken(NURSE);
        Map<?, ?> page = dataOf(post(BASE + "/patients/search", token, searchBody(1)));
        List<?> items = (List<?>) page.get("items");
        assertNotNull(items, "列表响应含 items");
        assertTrue(items.size() <= 20, "每页 ≤20 条（实际 " + items.size() + "）");
        assertEquals(20, ((Number) page.get("pageSize")).intValue(), "每页 20 条（表格默认）");
        long total = ((Number) page.get("total")).longValue();
        assertTrue(total >= 2, "总数含本动线两名示例患者：" + total);
        List<String> visibleLegacyNos = new ArrayList<>();
        for (int pageNo = 1; (pageNo - 1) * 20L < total; pageNo++) {
            List<?> pageItems =
                    (List<?>)
                            dataOf(post(BASE + "/patients/search", token, searchBody(pageNo)))
                                    .get("items");
            if (pageItems == null || pageItems.isEmpty()) {
                break;
            }
            for (Object item : pageItems) {
                Map<?, ?> row = (Map<?, ?>) item;
                String name = str(row.get("name"));
                assertTrue(name.contains("*"), "姓名脱敏（实际 " + name + "）");
                assertFalse(name.equals("赵冒烟（示例）") || name.equals("钱跟进（示例）"), "列表不得出现明文姓名：" + name);
                visibleLegacyNos.add(str(row.get("legacyNo")));
            }
        }
        assertTrue(
                visibleLegacyNos.contains(LEGACY + "-CM1")
                        && visibleLegacyNos.contains(LEGACY + "-CM2"),
                "列表可见本病区两名示例患者（脱敏展示）");
        assertFalse(visibleLegacyNos.contains(LEGACY + "-CM3"), "他病区患者（孙他区）不可见（A-WARD-003 只见本病区）");
    }

    private static Map<String, Object> searchBody(int pageNo) {
        Map<String, Object> body = new HashMap<>();
        body.put("pageNo", pageNo);
        body.put("pageSize", 20);
        body.put("filters", Map.of());
        return body;
    }

    // S-6：登记即自动入组预置计划，第 1 年任务 PENDING、计划日 = 今天（UAT B-3 / E-1、Q35、A-FU-017）。
    @Test
    @Order(5)
    void s6_autoEnrollmentCreatesCurrentYearTask() {
        for (long pid : List.of(patientId, secondPatientId)) {
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM fu_enrollment e JOIN fu_plan p ON p.id = e.plan_id"
                                    + " WHERE e.patient_id = ? AND p.code = ?",
                            pid,
                            PRESET_PLAN),
                    "登记事务内自动加入预置计划（Q35），患者 " + pid);
        }
        assertEquals(
                1,
                count("SELECT COUNT(*) FROM fu_task WHERE patient_id = ?", patientId),
                "第 1 年任务 1 条");
        long taskId = taskIdOf(patientId);
        assertEquals("PENDING", statusOf(taskId), "窗口开启中 → 待随访（A-FU-003）");
        assertEquals(TODAY, planDateOf(taskId), "计划日 = 出院 + 1 年 = 今天");
        assertEquals("NORMAL", createTypeOf(taskId), "当期任务（非补录）");
    }

    // S-7：随访员认领 → 开始录入 → 提交 A_ANNUAL_FU → 任务已完成（按时）（UAT E-2 / F-1、A-FU-005）。
    @Test
    @Order(6)
    void s7_claimAndSubmitCompletesOnTime() {
        String token = hospitalToken(NURSE);
        long taskId = taskIdOf(patientId);
        // 默认随访负责人在册（V123 ⑥）；冒烟动线按 UAT E-2 由随访员认领（置空负责人进可认领态，同
        // TaskContactAcceptanceIT 口径）。认领 = 本人自取负责人，状态仍待随访；首次沟通 / 开始录入转进行中
        jdbcUpdate("UPDATE fu_task SET assignee_id = NULL WHERE id = ?", taskId);
        assertEquals(
                0, codeOf(post(BASE + "/tasks/" + taskId + "/claim", token, Map.of())), "认领失败");
        assertEquals(userId(NURSE), assigneeOf(taskId), "认领后负责人 = 随访员本人");
        assertEquals("PENDING", statusOf(taskId), "认领不动状态（开始录入才转进行中）");

        Map<?, ?> started = post(BASE + "/tasks/" + taskId + "/start", token, Map.of());
        assertEquals(0, codeOf(started), "开始录入失败：" + started);
        assertEquals("IN_PROGRESS", statusOf(taskId), "开始录入后进行中");
        String recordId = str(dataOf(started).get("recordId"));

        Map<String, Object> form = new HashMap<>();
        form.put("baseVersion", 0);
        form.put("data", Map.of("f214", TODAY.toString(), "f215", 0, "f213", 1));
        Map<?, ?> submitted = post(BASE + "/records/" + recordId + "/submit", token, form);
        assertEquals(0, codeOf(submitted), "提交失败：" + submitted);
        assertEquals("COMPLETED", dataOf(submitted).get("taskStatus"), "提交后任务完成");
        assertEquals("ON_TIME", dataOf(submitted).get("completeKind"), "窗口内完成（按时）");
        assertEquals(
                "SUBMITTED",
                query(
                        "SELECT status FROM fu_record WHERE id = ?",
                        String.class,
                        Long.parseLong(recordId)),
                "记录生效");
    }

    // S-8：认领第二名患者任务 → 沟通「未联系到 / 电话」→ 进行中 + 次日跟进任务 + 责任人站内信（姓名脱敏、
    // TASK 落点）（UAT E-3 / G-1 / G-2、A-FU-006、A-NTF-001）。
    @Test
    @Order(7)
    void s8_unreachedContactCreatesFollowupAndNotifies() {
        String token = hospitalToken(NURSE);
        long taskId = taskIdOf(secondPatientId);
        jdbcUpdate("UPDATE fu_task SET assignee_id = NULL WHERE id = ?", taskId);
        assertEquals(
                0, codeOf(post(BASE + "/tasks/" + taskId + "/claim", token, Map.of())), "认领失败");

        Map<String, Object> contact = new HashMap<>();
        contact.put("taskId", taskId);
        contact.put("method", "PHONE");
        contact.put("result", "NOT_REACHED");
        contact.put(
                "contactTime", TODAY.atTime(9, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        Map<?, ?> logged =
                post(BASE + "/patients/" + secondPatientId + "/contacts", token, contact);
        assertEquals(0, codeOf(logged), "沟通记录失败：" + logged);
        assertEquals("IN_PROGRESS", statusOf(taskId), "未联系到 → 进行中（A-FU-006）");
        Object followupTaskId = dataOf(logged).get("followupTaskId");
        assertNotNull(followupTaskId, "同事务生成次日跟进任务");
        assertEquals(
                TODAY.plusDays(1),
                query(
                        "SELECT open_date FROM fu_task WHERE id = ?",
                        LocalDate.class,
                        Long.parseLong(str(followupTaskId))),
                "跟进任务次日开启");

        // 站内信送达（同步分发保证断言确定，同 TaskContactAcceptanceIT）
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        Map<?, ?> unread = dataOf(get("/api/hospital/notify/messages?unread=true", token));
        List<?> messages = (List<?>) unread.get("items");
        Map<?, ?> notice =
                messages.stream()
                        .map(item -> (Map<?, ?>) item)
                        .filter(item -> str(item.get("title")).contains("未联系到"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("未收到未联系到站内信：" + messages));
        String patientName = str(notice.get("patientName"));
        assertFalse(patientName.equals("钱跟进（示例）"), "站内信患者姓名脱敏（实际 " + patientName + "）");
        assertTrue(patientName.contains("*"), "脱敏值含 *（实际 " + patientName + "）");
        assertEquals("TASK", ((Map<?, ?>) notice.get("target")).get("type"), "落点可跳转任务");
    }

    // S-9：上传 png → 登记报告单（基线位置）→ 患者内清单可见（UAT H-1；水印预览为生产人工项）。
    @Test
    @Order(8)
    void s9_reportUploadArchivedInPatientReports() {
        String token = hospitalToken(NURSE);
        long fileId = uploadPng(token);
        List<?> names = (List<?>) get(BASE + "/report-names?typeCode=LAB", token).get("data");
        Map<?, ?> name = (Map<?, ?>) names.get(0);
        Map<?, ?> category = firstBaselineCategory(token);
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("typeCode", "LAB");
        body.put("nameId", str(name.get("id")));
        body.put("reportDate", TODAY.toString());
        body.put("findings", "指标正常（示例）");
        body.put(
                "locations",
                List.of(
                        Map.of(
                                "linkType",
                                "baseline",
                                "anchorType",
                                "category",
                                "anchorKey",
                                str(category.get("anchorKey")))));
        body.put("fileIds", List.of(String.valueOf(fileId)));
        Map<?, ?> created = post(BASE + "/reports", token, body);
        assertEquals(0, codeOf(created), "报告单登记失败：" + created);
        String reportId = str(dataOf(created).get("id"));

        Map<?, ?> page = dataOf(get(BASE + "/patients/" + patientId + "/reports", token));
        List<?> items = (List<?>) page.get("items");
        assertTrue(items.size() >= 1, "患者内报告单清单可见（A-ATT-005 归档展示）");
        assertTrue(
                items.stream()
                        .map(item -> (Map<?, ?>) item)
                        .anyMatch(item -> reportId.equals(str(item.get("id")))),
                "清单含本次登记的报告单：" + items);
    }

    // S-10：工作台 kpi 卡可读、患者总数 ≥2（UAT I-1；口径核对引用 UAT 结论不重算）。
    @Test
    @Order(9)
    void s10_dashboardKpiCardReadable() {
        String token = hospitalToken(NURSE);
        Map<?, ?> kpi = dataOf(get(BASE + "/dashboard/cards/kpi", token));
        List<?> metrics = (List<?>) kpi.get("metrics");
        assertNotNull(metrics, "kpi 卡含指标行");
        Map<String, Object> byCode = new HashMap<>();
        for (Object metric : metrics) {
            Map<?, ?> row = (Map<?, ?>) metric;
            byCode.put(str(row.get("code")), row.get("value"));
        }
        Object total = byCode.get("total");
        assertNotNull(total, "患者总数指标在册：" + byCode.keySet());
        assertTrue(((Number) total).longValue() >= 2, "患者总数 ≥ 本动线两名示例患者（实际 " + total + "）");
    }

    // S-11：越范围直链 40400；医院令牌进不了平台接口（UAT K-2 / K-5、AGENTS §5.1~5.2）。
    @Test
    @Order(10)
    void s11_scopeBoundary40400AndCrossShellDenied() {
        String token = hospitalToken(NURSE);
        assertEquals(
                40400,
                codeOf(get(BASE + "/patients/" + otherWardPatientId + "/summary", token)),
                "他病区患者详情 40400（不暴露存在）");
        assertEquals(40300, codeOf(get("/api/platform/menus", token)), "医院令牌进不了平台接口");
    }

    // S-12：审计中心可查本动线事件（患者新建 / 任务认领 / 沟通 / 报告单），含操作人与时间（UAT M-1）。
    @Test
    @Order(11)
    void s12_auditTrailOfSmokeActions() {
        String token = hospitalToken("nurse.demo");
        Map<String, Object> filter = new HashMap<>();
        filter.put("startTime", TODAY.atStartOfDay().format(AUDIT_TIME));
        filter.put("endTime", TODAY.atTime(23, 59, 59).format(AUDIT_TIME));
        Map<?, ?> page = dataOf(post("/api/hospital/system/audit/operation/search", token, filter));
        String items = String.valueOf(page.get("items"));
        assertTrue(
                ((Number) page.get("total")).longValue() >= 1 && items.contains("PATIENT_CREATE"),
                "患者新建审计在册（含操作人 / 时间）");
        assertTrue(items.contains("TASK_CLAIM"), "任务认领审计在册");
        assertTrue(items.contains("CONTACT_CREATE"), "沟通记录审计在册");
        assertTrue(items.contains("RPT_CREATE"), "报告单登记审计在册");
    }

    // ---------------------------------------------------------------- 造数与清理

    /** 登记示例患者（HTTP 正口；出院 = 一年前 → 第 1 年窗口开启中）。 */
    private long registerPatient(String token, long wardId, String suffix, String name) {
        Map<?, ?> created = post(BASE + "/patients", token, patientBody(wardId, suffix, name));
        assertEquals(0, codeOf(created), "登记失败（" + name + "）：" + created);
        return Long.parseLong(str(dataOf(created).get("id")));
    }

    private static Map<String, Object> patientBody(long wardId, String suffix, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", name);
        body.put("gender", "M");
        body.put("birthDate", "1955-03-08");
        body.put("wardId", wardId);
        body.put("admissionDate", DISCHARGE.minusDays(14).toString());
        body.put("surgeryDate", DISCHARGE.minusDays(7).toString());
        body.put("dischargeDate", DISCHARGE.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-C" + suffix);
        return body;
    }

    private long wardId(String code) {
        return query("SELECT id FROM ward WHERE code = ?", Long.class, code);
    }

    private long taskIdOf(long pid) {
        return query("SELECT MIN(id) FROM fu_task WHERE patient_id = ?", Long.class, pid);
    }

    private String statusOf(long taskId) {
        return query("SELECT status FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private LocalDate planDateOf(long taskId) {
        return query("SELECT plan_date FROM fu_task WHERE id = ?", LocalDate.class, taskId);
    }

    private String createTypeOf(long taskId) {
        return query("SELECT create_type FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private long assigneeOf(long taskId) {
        return query("SELECT assignee_id FROM fu_task WHERE id = ?", Long.class, taskId);
    }

    private long userId(String username) {
        return query("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    /** 上传 1×1 PNG（UAT H-1 的 jpg/png 冒烟物；与 ReportIT 同一张）。 */
    private long uploadPng(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new ByteArrayResource(png()) {
                    @Override
                    public String getFilename() {
                        return UPLOAD_FILE;
                    }
                });
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(
                                        "/api/hospital/files",
                                        new HttpEntity<>(parts, headers),
                                        Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        assertEquals(0, codeOf(response), "上传失败：" + response);
        return Long.parseLong(str(dataOf(response).get("fileId")));
    }

    private static byte[] png() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ"
                                + "AAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    }

    /** 基线第一个分类锚点（报告单位置树；ReportIT 同口径；data 是数组）。 */
    private Map<?, ?> firstBaselineCategory(String token) {
        List<?> roots =
                (List<?>)
                        get(BASE + "/patients/" + patientId + "/report-locations", token)
                                .get("data");
        Map<?, ?> baseline =
                roots.stream()
                        .map(node -> (Map<?, ?>) node)
                        .filter(node -> "baseline".equals(node.get("key")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("位置树缺基线根：" + roots));
        return (Map<?, ?>) ((Map<?, ?>) ((List<?>) baseline.get("children")).get(0)).get("value");
    }

    /** 递归收菜单中文标题（标题在 meta.title，M1FoundationRegressionIT 同口径；data 是数组）。 */
    private List<String> menuTitles(String token) {
        return collectTitles(
                (List<?>) get("/api/hospital/menus", token).get("data"), new ArrayList<>());
    }

    private static List<String> collectTitles(List<?> nodes, List<String> titles) {
        if (nodes == null) {
            return titles;
        }
        for (Object node : nodes) {
            Map<?, ?> item = (Map<?, ?>) node;
            titles.add(str(((Map<?, ?>) item.get("meta")).get("title")));
            collectTitles((List<?>) item.get("children"), titles);
        }
        return titles;
    }

    /** 冒烟动线自清：患者子行、任务 / 沟通 / 记录 / 报告单 / 站内信 / outbox / 审计（标记 IT-E23T12-）。 */
    private void cleanSmokeRows() {
        AuditTableMaintenance.delete(
                jdbc,
                "DELETE FROM aud_log WHERE"
                        + " (event_type IN ('TASK_CREATE', 'TASK_CLAIM', 'TASK_START',"
                        + " 'TASK_PROGRESS', 'TASK_COMPLETE', 'TASK_FOLLOWUP_CREATE')"
                        + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                        + " JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'))"
                        + " OR (event_type = 'PATIENT_CREATE'"
                        + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                        + " WHERE legacy_no LIKE '"
                        + LEGACY
                        + "-%'))"
                        + " OR (event_type = 'RECORD_SUBMIT'"
                        + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM fu_record r"
                        + " JOIN pt_patient p ON p.id = r.patient_id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'))"
                        + " OR (event_type = 'CONTACT_CREATE'"
                        + " AND target IN (SELECT CONCAT('CONTACT_LOG:', c.id) FROM fu_contact_log c"
                        + " JOIN pt_patient p ON p.id = c.patient_id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'))"
                        + " OR (action = 'RPT_CREATE' AND object_type = 'REPORT'"
                        + " AND object_id IN (SELECT CAST(r.id AS CHAR) FROM rpt_report r"
                        + " JOIN pt_patient p ON p.id = r.patient_id"
                        + " WHERE p.legacy_no LIKE '"
                        + LEGACY
                        + "-%'))"
                        + " OR detail LIKE '%"
                        + LEGACY
                        + "%'");
        jdbc.update(
                "DELETE FROM sys_outbox WHERE JSON_EXTRACT(payload, '$.patientId') IN"
                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                LEGACY + "-%");
        jdbc.update("DELETE FROM ntf_message WHERE event_type = 'UNREACHED_FOLLOWUP'");
        jdbc.update("DELETE FROM ntf_send_log WHERE event_type = 'UNREACHED_FOLLOWUP'");
        jdbc.update(
                "DELETE fh FROM fe_field_history fh"
                        + " JOIN fu_record fr ON fr.id = fh.record_id"
                        + " JOIN pt_patient p ON p.id = fr.patient_id"
                        + " WHERE fh.record_type = 'FU_RECORD' AND p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE v FROM rpt_indicator_value v JOIN rpt_report r ON r.id = v.report_id"
                        + " JOIN pt_patient p ON p.id = r.patient_id WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE l FROM rpt_report_link l JOIN pt_patient p ON p.id = l.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE r FROM rpt_report r JOIN pt_patient p ON p.id = r.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update("DELETE FROM sys_file WHERE original_name = ?", UPLOAD_FILE);
        jdbc.update(
                "DELETE fr FROM fu_record fr JOIN pt_patient p ON p.id = fr.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE c FROM fu_contact_log c JOIN pt_patient p ON p.id = c.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE s FROM pt_baseline_section s JOIN pt_patient p ON p.id = s.patient_id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE ps FROM pt_patient_summary ps JOIN pt_patient p ON ps.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update(
                "DELETE sh FROM pt_status_history sh JOIN pt_patient p ON sh.patient_id = p.id"
                        + " WHERE p.legacy_no LIKE ?",
                LEGACY + "-%");
        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
    }

    // ---------------------------------------------------------------- HTTP 与上下文

    private String hospitalToken(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", "demoa", "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response), "医院登录失败（" + username + "）：" + response);
        return (String) dataOf(response).get("token");
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
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
        ResponseEntity<Map> response =
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
        return response.getBody();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static String str(Object value) {
        return String.valueOf(value);
    }

    private int count(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private <T> T query(String sql, Class<T> type, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, type, args));
    }

    private void jdbcUpdate(String sql, Object... args) {
        runInTenant("demoa", () -> jdbc.update(sql, args));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
