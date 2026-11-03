package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.form.record.app.FormService;
import com.crdp.form.rule.app.FieldIssue;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E11-T02 端到端 / A-NFR-002、C-NFR-002（完整应用 + 真实 MySQL，HTTP 直连 + 服务层直调）。验收原文： 表单按版本读写，旧版本可读。
 *
 * <p>覆盖：FormSchema 不传 version 取当前发布版；停用字段后发布新版本，新版本不含停用字段而<b>旧版本快照仍可读</b>； 记录（fe_record）创建时固化
 * templateVersion，发布新版后旧记录仍按旧版本保存与校验；保存带 baseVersion（过期 40900 附更新人与不同字段、已提交 40901、未知键 40001）；发布无权限
 * 40300、模块关闭 40301、表单 / 版本不存在 40400；发布写 FORM_PUBLISH 审计；双租户版本与记录隔离。 E11-T04：超硬范围
 * 42200（fieldErrors）、软范围放行、跨字段规则提交校验与 FormSchema.rules 下发。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormEngineIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String SCHEMA = "/api/hospital/form/forms/A_BASELINE";
    private static final String FU_SCHEMA = "/api/hospital/form/forms/A_ANNUAL_FU";
    private static final String PUBLISH = "/api/hospital/form/forms/A_BASELINE/publish";
    private static final String FIELDS = "/api/hospital/form/fields";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private FormService formService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        SecurityContextHolder.clearContext();
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 字段与选项恢复真实字典形态（E23-T01 / V38），口径同 FieldDictIT
                        jdbc.update(
                                "UPDATE fe_field SET label = '吸烟史',"
                                        + " help_text = '原表列名「吸烟史_否_0_是_1」',"
                                        + " required = 0, status = 'ENABLED', updated_by = NULL"
                                        + " WHERE form_code = 'A_BASELINE' AND field_key = 'f019'");
                        jdbc.update(
                                "UPDATE fe_field SET label = '身高cm',"
                                        + " help_text = '原表列名「身高_cm」',"
                                        + " soft_range = '{\"min\":130,\"max\":210}',"
                                        + " updated_by = NULL"
                                        + " WHERE form_code = 'A_BASELINE' AND field_key = 'f013'");
                        jdbc.update(
                                "DELETE FROM fe_option WHERE set_code = 'a_yesno' AND option_code > 1");
                        jdbc.update(
                                "UPDATE fe_option SET label = CASE option_code WHEN 0 THEN '否'"
                                        + " WHEN 1 THEN '是' END, sort_no = option_code * 10 + 10,"
                                        + " status = 'ENABLED', updated_by = NULL"
                                        + " WHERE set_code = 'a_yesno'");
                        // 回到 v1：清掉用例发布的新版本与记录，current_version 复位
                        jdbc.update(
                                "DELETE fvf FROM fe_form_version_field fvf"
                                        + " JOIN fe_form_version fv ON fv.id = fvf.version_id"
                                        + " WHERE fv.form_code = 'A_BASELINE' AND fv.version > 1");
                        jdbc.update(
                                "DELETE FROM fe_form_version WHERE form_code = 'A_BASELINE'"
                                        + " AND version > 1");
                        jdbc.update(
                                "UPDATE fe_form SET current_version = 1 WHERE code = 'A_BASELINE'");
                        jdbc.update("DELETE FROM fe_record WHERE form_code = 'A_BASELINE'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE action = 'FORM_PUBLISH'");
                    });
        }
    }

    // ---------------------------------------------------------------- 验收：模板按版本读，旧版本可读

    /** 当前版读取 → 停用字段并发布 → 新版本不含停用字段、旧版本 v1 仍完整可读。 */
    @Test
    void servesSchemaByVersionAndKeepsOldVersionsReadable() {
        String token = hospitalToken("demoa");

        Map<?, ?> v1 = data(get(SCHEMA, token));
        assertEquals(1, ((Number) v1.get("version")).intValue(), "不传 version 取当前发布版");
        assertEquals("A_BASELINE", v1.get("code"));
        List<Map<?, ?>> categories = (List<Map<?, ?>>) v1.get("categories");
        assertEquals(
                List.of(
                        "basic",
                        "case",
                        "lab",
                        "echo",
                        "angio",
                        "surgery",
                        "periop",
                        "discharge_med"),
                categories.stream().map(category -> category.get("key")).toList(),
                "基线 8 个分类按 sortNo 排序，锚点键稳定（A-FLD-007）");
        assertEquals(213, fieldKeys(categories).size(), "附录 A 分类 1–8 共 212 字段 + 出生日期");
        assertTrue(fieldKeys(categories).contains("f019"), "v1 含吸烟史（真实字典）");
        Map<?, ?> height =
                ((List<Map<?, ?>>) categories.get(1).get("fields"))
                        .stream()
                                .filter(field -> "f013".equals(field.get("key")))
                                .findFirst()
                                .orElseThrow();
        assertEquals("height_cm", height.get("code"), "字段编码（ASCII，导入导出名）");
        Map<?, ?> hardRange = (Map<?, ?>) height.get("hardRange");
        assertEquals(100.0, ((Number) hardRange.get("min")).doubleValue(), 1e-9);
        assertEquals(220.0, ((Number) hardRange.get("max")).doubleValue(), 1e-9);
        assertEquals("json", height.get("storage"));

        // 停用吸烟史并发布：新版本不含停用字段（E11-T01「停用不进模板下发」），旧版本保留
        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f019?formCode=A_BASELINE",
                                token,
                                Map.of("label", "吸烟史", "status", "DISABLED"))));
        Map<?, ?> published = data(post(PUBLISH, token, null));
        assertEquals(2, ((Number) published.get("version")).intValue());
        assertEquals(212, ((Number) published.get("fieldCount")).intValue(), "固化启用字段数（213 − 停用 1）");

        Map<?, ?> v2 = data(get(SCHEMA, token));
        assertEquals(2, ((Number) v2.get("version")).intValue());
        assertFalse(
                fieldKeys((List<Map<?, ?>>) v2.get("categories")).contains("f019"), "新版本不含已停用字段");

        Map<?, ?> oldVersion = data(get(SCHEMA + "?version=1", token));
        assertEquals(1, ((Number) oldVersion.get("version")).intValue(), "旧版本可读");
        assertTrue(
                fieldKeys((List<Map<?, ?>>) oldVersion.get("categories")).contains("f019"),
                "旧版本快照仍含后停用的字段");

        assertEquals(40400, code(get(SCHEMA + "?version=99", token)), "版本不存在 40400");
        assertEquals(40400, code(get("/api/hospital/form/forms/NOPE", token)), "表单不存在 40400");

        runInTenant(
                "demoa",
                () ->
                        assertTrue(
                                jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                        + " 'FORM_PUBLISH' AND object_id ="
                                                        + " 'A_BASELINE'",
                                                Integer.class)
                                        >= 1,
                                "发布写 FORM_PUBLISH 审计"));
    }

    // ---------------------------------------------------------------- 记录：按版本读写（fe_record +
    // FormService）

    /** 记录创建固化版本；发布新版后旧记录仍按旧版本保存；乐观锁 40900；值闸门 40001。 */
    @Test
    void recordsReadAndWritePinnedToTheirVersion() {
        loginServicePrincipal();
        Long recordId =
                callInTenant(
                        "demoa",
                        () ->
                                formService
                                        .createRecord(
                                                "A_BASELINE",
                                                Map.of("f019", 1, "f013", 170.5),
                                                Map.of("note", "示例备注"))
                                        .id());
        FormService.RecordView created = callInTenant("demoa", () -> formService.record(recordId));
        assertEquals(1, created.templateVersion(), "创建时固化 = 当时 current_version");
        assertEquals("DRAFT", created.status());
        assertEquals(0, created.version());
        assertEquals(1, ((Number) created.data().get("f019")).intValue());
        assertEquals(170.5, ((Number) created.data().get("f013")).doubleValue());
        assertEquals("示例备注", created.dataMeta().get("note"));

        // 发布 v2：记录不升级，仍按 v1 读写（f019 在 v2 已停用，但 v1 口径仍可写）
        String token = hospitalToken("demoa");
        put(
                FIELDS + "/f019?formCode=A_BASELINE",
                token,
                Map.of("label", "吸烟史", "status", "DISABLED"));
        post(PUBLISH, token, null);

        FormService.RecordView saved =
                callInTenant(
                        "demoa",
                        () ->
                                formService.saveRecord(
                                        recordId,
                                        new FormService.SaveRecordCommand(
                                                0, Map.of("f019", 0, "f013", 171), null)));
        assertEquals(1, saved.version(), "保存成功 version + 1");
        assertEquals(1, saved.templateVersion(), "记录版本不随发布升级");
        assertEquals(0, ((Number) saved.data().get("f019")).intValue());

        // 过期 baseVersion → 40900，附更新人与两边不同字段（README 错误码表）
        ApiException conflict =
                callInTenant(
                        "demoa",
                        () -> {
                            try {
                                formService.saveRecord(
                                        recordId,
                                        new FormService.SaveRecordCommand(
                                                0, Map.of("f013", 172), null));
                                return null;
                            } catch (ApiException e) {
                                return e;
                            }
                        });
        assertNotNull(conflict, "过期 baseVersion 必须 40900");
        assertEquals(40900, conflict.code());
        FormService.VersionConflictView detail = (FormService.VersionConflictView) conflict.data();
        assertEquals(1, detail.serverVersion());
        assertTrue(detail.changedFields().contains("f013"), "附两边不同字段");

        // 写入闸门：旧记录按 v1 校验，未知键拒绝
        ApiException unknown =
                callInTenant(
                        "demoa",
                        () -> {
                            try {
                                formService.saveRecord(
                                        recordId,
                                        new FormService.SaveRecordCommand(
                                                1, Map.of("f500", 1), null));
                                return null;
                            } catch (ApiException e) {
                                return e;
                            }
                        });
        assertEquals(40001, unknown.code());

        runInTenant(
                "demoa",
                () -> {
                    String stored =
                            jdbc.queryForObject(
                                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(data, '$.f013'))"
                                            + " FROM fe_record WHERE id = "
                                            + recordId,
                                    String.class);
                    assertTrue("171".equals(stored), "JSON 落库：" + stored);
                });
    }

    // ---------------------------------------------------------------- E11-T04：范围与跨字段校验（A-FLD-003
    // / A-FLD-006 / C-FLD-007）

    /**
     * E11-T04 验收「前后端校验一致，超范围拦截」：超硬范围 42200（fieldErrors 指到字段）、软范围放行、跨字段规则
     * 提交校验拦截与软命中返回警告、FormSchema.rules 下发 fe_rule（V30 种子演示规则）。
     */
    @Test
    void rangeAndCrossFieldRulesGateWritesAndSubmits() {
        loginServicePrincipal();

        // 写入闸门：超硬范围（身高 300 > 250）不可保存（A-FLD-003），42200 附 fieldErrors
        ApiException hard =
                callInTenant(
                        "demoa",
                        () -> {
                            try {
                                formService.createRecord("A_BASELINE", Map.of("f013", 300), null);
                                return null;
                            } catch (ApiException e) {
                                return e;
                            }
                        });
        assertNotNull(hard, "超硬范围必须拦截");
        assertEquals(42200, hard.code());
        List<?> fieldErrors = (List<?>) ((Map<?, ?>) hard.data()).get("fieldErrors");
        assertEquals(1, fieldErrors.size());
        FieldIssue issue = (FieldIssue) fieldErrors.get(0);
        assertEquals("f013", issue.key());
        assertEquals("hardRange", issue.rule());

        // 软范围外（身高 120 < 软下限 130，硬范围内）可保存——A-FLD-003「提示但可保存」
        FormService.RecordView soft =
                callInTenant(
                        "demoa",
                        () -> formService.createRecord("A_BASELINE", Map.of("f013", 120), null));
        assertEquals(120, ((Number) soft.data().get("f013")).intValue());

        // 跨字段（V38 规则链：手术开始 < 手术结束）：违反 → 提交校验 42200；通过且软范围命中 → 警告返回不拦截
        ApiException cross =
                callInTenant(
                        "demoa",
                        () -> {
                            try {
                                formService.validateForSubmit(
                                        "A_BASELINE",
                                        1,
                                        Map.of(
                                                "f138", "2024-05-06T10:00",
                                                "f139", "2024-05-01T08:00"));
                                return null;
                            } catch (ApiException e) {
                                return e;
                            }
                        });
        assertNotNull(cross, "跨字段硬规则违反必须拦截提交");
        assertEquals(42200, cross.code());
        assertEquals(
                "surgery_start_lt_end",
                ((FieldIssue) ((List<?>) ((Map<?, ?>) cross.data()).get("fieldErrors")).get(0))
                        .rule());

        List<FieldIssue> warnings =
                callInTenant(
                        "demoa",
                        () ->
                                formService.validateForSubmit(
                                        "A_BASELINE",
                                        publishedVersionWithSoftRange(),
                                        Map.of(
                                                "f138",
                                                "2024-05-01T08:00",
                                                "f139",
                                                "2024-05-06T10:00",
                                                "f013",
                                                120)));
        assertTrue(
                warnings.stream()
                        .anyMatch(
                                warning ->
                                        "softRange".equals(warning.rule())
                                                && "f013".equals(warning.key())),
                "软范围命中作为警告返回（C-FLD-007 黄色提示语义）");

        // FormSchema.rules 下发 fe_rule 当前启用规则（实时读取）
        Map<?, ?> schema = data(get(SCHEMA, hospitalToken("demoa")));
        List<Map<?, ?>> rules = (List<Map<?, ?>>) schema.get("rules");
        assertTrue(
                rules.stream()
                        .anyMatch(
                                rule ->
                                        "surgery_start_lt_end".equals(rule.get("id"))
                                                && "HARD".equals(rule.get("severity"))),
                "schema.rules 含 A-FLD-006 规则链（V38）");
    }

    // ---------------------------------------------------------------- E11-T03：条件显示与条件必填（A-FLD-004）

    /**
     * E11-T03 验收「前后端同一套用例结果一致」（本用例走服务层端到端，双端用例一致性由共享 JSON 锁定；条件字段为 E23-T01 录入的年度随访 表单）： FormSchema
     * 下发 visibleWhen / requiredWhen（是否死亡=是 → 死亡日期 / 死亡原因显示并必填）； 是否死亡=是且死亡日期缺失 →
     * 42200（rule=required）；是否死亡=否 → 死亡日期隐藏，残留值不拦截（掩蔽）且 stripHiddenFields 清空残留值（tech-plan §6）。
     */
    @Test
    void conditionalRequiredGatesSubmitAndHiddenValuesAreStripped() {
        loginServicePrincipal();

        // schema 下发条件树（f216 死亡日期：f215 是否死亡=1 才显示并必填，A-FLD-004 验收原文）
        Map<?, ?> schema = data(get(FU_SCHEMA, hospitalToken("demoa")));
        Map<?, ?> deathDate =
                fieldKeysWithDef((List<Map<?, ?>>) schema.get("categories")).get("f216");
        assertNotNull(deathDate.get("visibleWhen"), "schema 下发 visibleWhen 条件树");
        assertNotNull(deathDate.get("requiredWhen"), "schema 下发 requiredWhen 条件树");

        // 是否死亡=是、死亡日期缺失：提交校验 42200，fieldErrors 命中 required（文案与前端「必填」同词）
        ApiException missing =
                callInTenant(
                        "demoa",
                        () -> {
                            try {
                                formService.validateForSubmit(
                                        "A_ANNUAL_FU", 1, Map.of("f214", "2026-06-01", "f215", 1));
                                return null;
                            } catch (ApiException e) {
                                return e;
                            }
                        });
        assertNotNull(missing, "条件必填缺失必须拦截提交");
        assertEquals(42200, missing.code());
        FieldIssue issue =
                (FieldIssue) ((List<?>) ((Map<?, ?>) missing.data()).get("fieldErrors")).get(0);
        assertEquals("f216", issue.key());
        assertEquals("required", issue.rule());
        assertEquals("必填", issue.message());

        // 是否死亡=否：f216 隐藏 → 残留死亡日期不拦截；提交前清空残留值并返回被清空键
        callInTenant(
                "demoa",
                () ->
                        formService.validateForSubmit(
                                "A_ANNUAL_FU",
                                1,
                                Map.of("f214", "2026-06-01", "f215", 0, "f216", "2026-01-01")));
        FormService.StrippedData stripped =
                callInTenant(
                        "demoa",
                        () ->
                                formService.stripHiddenFields(
                                        "A_ANNUAL_FU",
                                        1,
                                        new java.util.LinkedHashMap<>(
                                                Map.of(
                                                        "f214",
                                                        "2026-06-01",
                                                        "f215",
                                                        0,
                                                        "f216",
                                                        "2026-01-01",
                                                        "f219",
                                                        1)),
                                        Map.of()));
        assertEquals(List.of("f216"), stripped.clearedKeys(), "被隐藏字段的残留值被清空");
        assertFalse(stripped.data().containsKey("f216"), "清空后的 data 不含隐藏字段值");
        assertEquals(1, ((Number) stripped.data().get("f219")).intValue(), "显示中字段保留");
    }

    // ----------------------------------------------------------------
    // E11-T05：派生重算（A-FLD-005、C-FLD-006）

    /**
     * E11-T05 验收「BMI、年龄等派生字段两端一致」（本用例走服务层端到端，两端一致性由共享 JSON form-derived-cases.json 锁定；字段为 E23-T01
     * 录入的真实字典）：身高 175 体重 70 → BMI 落库 22.9（后端结果为准，tech-plan §6）； 手术开始 08:00、结束 12:45 → 手术时长 285
     * 分钟；结束早于开始 → 带手工值 42200（rule=derived，结果无效不允许手工录入）； schema 下发 derived 定义（前端用同一函数库实时重算）。
     */
    @Test
    void derivedFieldsAreRecalculatedOnWrite() {
        loginServicePrincipal();

        // schema 下发派生定义（f015 BMI：体重 f014、身高 f013；附录 #15）
        Map<?, ?> schema = data(get(SCHEMA, hospitalToken("demoa")));
        Map<?, ?> bmi = fieldKeysWithDef((List<Map<?, ?>>) schema.get("categories")).get("f015");
        assertNotNull(bmi, "v1 快照含 BMI 派生字段");
        Map<?, ?> derived = (Map<?, ?>) bmi.get("derived");
        assertEquals("BMI", derived.get("fn"), "schema 下发派生定义（前端同一函数库重算）");
        assertEquals(List.of("f014", "f013"), derived.get("args"));

        // 身高 175 + 体重 70：BMI 落库 22.9（提交旧值 30 也以计算值为准，A-FLD-005）
        FormService.RecordView created =
                callInTenant(
                        "demoa",
                        () ->
                                formService.createRecord(
                                        "A_BASELINE",
                                        new java.util.LinkedHashMap<>(
                                                Map.of("f013", 175, "f014", 70, "f015", 30)),
                                        null));
        assertEquals(22.9, ((Number) created.data().get("f015")).doubleValue(), 1e-9, "BMI 以计算值落库");

        // 手术开始 2026-03-01T08:00、结束 2026-03-01T12:45 → 手术时长 285 分钟（向下取整，验收原文）
        FormService.RecordView duration =
                callInTenant(
                        "demoa",
                        () ->
                                formService.createRecord(
                                        "A_BASELINE",
                                        Map.of(
                                                "f138",
                                                "2026-03-01T08:00",
                                                "f139",
                                                "2026-03-01T12:45"),
                                        null));
        assertEquals(285, ((Number) duration.data().get("f140")).intValue(), "手术时长 = 结束 − 开始（分钟）");

        // 结束早于开始且带手工值：42200 rule=derived（结果无效不允许手工录入，应先修正来源字段）
        ApiException rejected =
                callInTenant(
                        "demoa",
                        () -> {
                            try {
                                formService.createRecord(
                                        "A_BASELINE",
                                        new java.util.LinkedHashMap<>(
                                                Map.of(
                                                        "f138",
                                                        "2026-03-01T12:45",
                                                        "f139",
                                                        "2026-03-01T08:00",
                                                        "f140",
                                                        3)),
                                        null);
                                return null;
                            } catch (ApiException e) {
                                return e;
                            }
                        });
        assertNotNull(rejected, "结果无效的手工值必须拦截");
        assertEquals(42200, rejected.code());
        FieldIssue issue =
                (FieldIssue) ((List<?>) ((Map<?, ?>) rejected.data()).get("fieldErrors")).get(0);
        assertEquals("f140", issue.key());
        assertEquals("derived", issue.rule());
        assertEquals("手术结束时间早于手术开始时间", issue.message());
    }

    // ---------------------------------------------------------------- 权限、模块开关、隔离

    /** 无 followup:field:edit 的医生：发布 40300；模板读取登录即可（渲染与录入都要用）。 */
    @Test
    void publishRequiresPermissionButSchemaOnlyLogin() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(post(PUBLISH, doctor, null)));
        assertEquals(0, code(get(SCHEMA, doctor)), "模板读取登录即可");
    }

    /** 模块关闭（40301）：followup 关闭后模板读取 40301，恢复即用（PLT-MOD-006）。 */
    @Test
    void closedFollowupModuleReturns40301() {
        String token = hospitalToken("demoa");
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, code(get(SCHEMA, token)));
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        assertEquals(0, code(get(SCHEMA, token)));
    }

    /** 双租户隔离：demoa 发布 v2 不影响 demob（各院各库各快照），记录互不可见。 */
    @Test
    void publishAndRecordsStayIsolatedPerTenant() {
        String tenantA = hospitalToken("demoa");
        assertEquals(0, code(post(PUBLISH, tenantA, null)));
        assertEquals(2, ((Number) data(get(SCHEMA, tenantA)).get("version")).intValue());

        String tenantB = hospitalToken("demob");
        assertEquals(
                1,
                ((Number) data(get(SCHEMA, tenantB)).get("version")).intValue(),
                "B 院不受 A 院发布影响");

        loginServicePrincipal();
        Long idA =
                callInTenant(
                        "demoa",
                        () -> formService.createRecord("A_BASELINE", Map.of("f019", 1), null).id());
        callInTenant(
                "demob", () -> formService.createRecord("A_BASELINE", Map.of("f019", 0), null));
        callInTenant(
                "demob",
                () -> {
                    try {
                        formService.record(idA);
                        return "seen";
                    } catch (ApiException e) {
                        return e.code();
                    }
                },
                40400,
                "A 院记录在 B 院不可见");
    }

    // ---------------------------------------------------------------- 辅助

    /**
     * 经字典维护 + 发布把身高软范围固化为 130–210 并返回新版本号（A-FLD-008 软范围租户可调 + E11-T02 快照）。 不依赖 v1
     * 快照里的软范围（本地演示库的历史快照可能没有），保证提交校验口径确定。
     */
    private int publishedVersionWithSoftRange() {
        String token = hospitalToken("demoa");
        assertEquals(
                0,
                code(
                        put(
                                FIELDS + "/f013?formCode=A_BASELINE",
                                token,
                                Map.of(
                                        "label",
                                        "身高",
                                        "softRange",
                                        Map.of("min", 130, "max", 210),
                                        "required",
                                        false,
                                        "status",
                                        "ENABLED"))));
        return ((Number) data(post(PUBLISH, token, null)).get("version")).intValue();
    }

    /** 全部字段的定义索引（key → field 定义），供断言单字段下发口径（如 visibleWhen / requiredWhen）。 */
    private static Map<String, Map<?, ?>> fieldKeysWithDef(List<Map<?, ?>> categories) {
        Map<String, Map<?, ?>> index = new java.util.LinkedHashMap<>();
        for (Map<?, ?> category : categories) {
            for (Map<?, ?> field : (List<Map<?, ?>>) category.get("fields")) {
                index.put((String) field.get("key"), field);
            }
        }
        return index;
    }

    private static List<String> fieldKeys(List<Map<?, ?>> categories) {
        return categories.stream()
                .flatMap(category -> ((List<Map<?, ?>>) category.get("fields")).stream())
                .map(field -> (String) field.get("key"))
                .toList();
    }

    private String hospitalToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private void loginServicePrincipal() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                servicePrincipal(), null, List.of()));
    }

    /** 服务层直调的医院会话（IT 线程无 HTTP 过滤器链，手动装配；E15 业务接口落地后走 HTTP）。 */
    private static SessionPrincipal servicePrincipal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例）",
                7L,
                "nurse.demo",
                "张医生（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:field:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                null);
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
        return (Map<?, ?>)
                rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T callInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    /** 期望值版：动作返回的实际值（或捕获的异常码）应等于 expected。 */
    private void callInTenant(
            String tenant, Supplier<Object> action, Object expected, String message) {
        Object actual = TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
        assertEquals(expected, actual, message);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
