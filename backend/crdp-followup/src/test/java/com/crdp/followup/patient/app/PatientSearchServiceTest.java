package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import com.crdp.followup.patient.infra.mapper.PatientSearchMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.fieldpolicy.TenantViewFieldGuard;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009 单元测试（验收「按病区隔离，按姓名精确检索」）。
 *
 * <p>锁定口径：姓名 / 病案号走盲索引精确匹配（keyword 同时命中两个盲索引，OR）；分页与排序 白名单（默认下次随访升序）；随访状态枚举白名单；日期区间先后校验；行内姓名与病案号只输出
 * 脱敏值（列表不提供逐行明文）；批量查询写 BATCH_QUERY 审计（queryDigest 不含检索词明文）； 病区隔离由 Mapper 语句上的 @DataScope
 * 改写承担（E07-T02），服务层不拼范围条件。全部虚构数据。
 */
class PatientSearchServiceTest {

    private static final long WARD_ID = 3L;
    private static final long OPERATOR_ID = 9L;
    private static final String NAME = "张小明（示例）";
    private static final String MRN = "IT-MRN-1001";

    private final PatientSearchMapper mapper = mock(PatientSearchMapper.class);
    private final PatientSearchGateway gateway = mock(PatientSearchGateway.class);
    private final PatientMapper patientMapper = mock(PatientMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final PatientSearchFieldService searchFields = mock(PatientSearchFieldService.class);

    private final PatientSearchService service =
            new PatientSearchService(
                    gateway,
                    patientMapper,
                    crypto,
                    audit,
                    searchFields,
                    TenantViewFieldGuard.passthrough());

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(invocation -> "BLIND:" + invocation.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0).toString().replace("CIPHER:", ""));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 列表行（A-PAT-001）

    @Test
    void searchMasksNameAndMedicalRecordNoInRows() {
        when(gateway.count(any())).thenReturn(1L);
        when(gateway.search(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                new PatientSearchMapper.PatientRowRaw(
                                        101L,
                                        "CIPHER:" + NAME,
                                        "CIPHER:" + MRN,
                                        "OLD-A-1",
                                        "M",
                                        71,
                                        "示例二病区",
                                        "王医生（示例）",
                                        LocalDate.of(2026, 3, 1),
                                        LocalDate.of(2026, 3, 8),
                                        "ACTIVE",
                                        LocalDate.of(2026, 11, 5),
                                        92,
                                        new BigDecimal("78.00"))));

        PatientSearchService.PatientPage page = service.search(request(null, null));

        assertEquals(1, page.items().size());
        PatientSearchService.PatientRowView row = page.items().get(0);
        assertEquals("101", row.id());
        assertEquals("张*）", row.name(), "姓名只输出脱敏值（列表不提供逐行明文）");
        assertEquals("IT*******01", row.patientNo(), "病案号脱敏（前 2 后 2）");
        assertEquals("OLD-A-1", row.legacyNo());
        assertEquals("M", row.gender());
        assertEquals(71, row.age());
        assertEquals("示例二病区", row.wardName());
        assertEquals("王医生（示例）", row.doctorName());
        assertEquals(LocalDate.of(2026, 3, 1), row.surgeryDate());
        assertEquals(LocalDate.of(2026, 3, 8), row.dischargeDate());
        assertEquals("ACTIVE", row.status());
        assertEquals(LocalDate.of(2026, 11, 5), row.nextVisitDate());
        assertEquals(92, row.baselineCompleteness());
        assertEquals(new BigDecimal("78.00"), row.followupCompleteness());
        assertEquals(1L, page.total());
        assertEquals(1, page.pageNo());
        assertEquals(20, page.pageSize());
    }

    @Test
    void searchOmitsNullCompletenessForDisplay() {
        when(gateway.count(any())).thenReturn(1L);
        when(gateway.search(any(), anyInt(), anyInt()))
                .thenReturn(
                        List.of(
                                new PatientSearchMapper.PatientRowRaw(
                                        102L,
                                        "CIPHER:" + NAME,
                                        "CIPHER:" + MRN,
                                        null,
                                        "F",
                                        null,
                                        "示例一病区",
                                        null,
                                        LocalDate.of(2026, 1, 5),
                                        LocalDate.of(2026, 1, 12),
                                        "ACTIVE",
                                        null,
                                        null,
                                        null)));

        PatientSearchService.PatientPage page = service.search(request(null, null));

        PatientSearchService.PatientRowView row = page.items().get(0);
        assertNull(row.age(), "无出生日期 / 手术日期时年龄为空");
        assertNull(row.baselineCompleteness(), "完整度未计算（E13-T02 / E15 写入）时不出现");
        assertNull(row.followupCompleteness(), "无已完成随访记录时为空，前端显示—");
    }

    // ---------------------------------------------------------------- 精确检索（A-PAT-002）

    @Test
    void searchComputesBlindIndexesForKeyword() {
        when(gateway.count(any())).thenReturn(0L);
        when(gateway.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.search(request("  " + NAME + "  ", null));

        ArgumentCaptor<PatientSearchMapper.SearchCriteria> captor =
                ArgumentCaptor.forClass(PatientSearchMapper.SearchCriteria.class);
        verify(gateway).search(captor.capture(), eq(0), eq(20));
        PatientSearchMapper.SearchCriteria criteria = captor.getValue();
        assertEquals("BLIND:" + NAME, criteria.nameBlind(), "姓名精确检索走姓名盲索引");
        assertEquals("BLIND:" + NAME, criteria.mrnBlind(), "同一检索词同时按病案号盲索引匹配");
        verify(crypto).blindIndex(PatientRegistrationService.PURPOSE_NAME, NAME);
        verify(crypto).blindIndex(PatientRegistrationService.PURPOSE_MRN, NAME);
    }

    @Test
    void searchRejectsOverlongKeyword() {
        ApiException error =
                assertThrows(
                        ApiException.class, () -> service.search(request("非".repeat(65), null)));
        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("检索词"), "提示应说明检索词过长，实际：" + error.getMessage());
        verify(gateway, never()).search(any(), anyInt(), anyInt());
    }

    // ---------------------------------------------------------------- 分页与排序

    @Test
    void searchValidatesPaging() {
        assertInvalid(requestWithPaging(0, 20), "pageNo（从 1 开始）");
        assertInvalid(requestWithPaging(1, 0), "pageSize（1–200）");
        assertInvalid(requestWithPaging(1, 201), "pageSize（1–200）");
    }

    @Test
    void searchDefaultsSortToNextVisitAscending() {
        when(gateway.count(any())).thenReturn(0L);
        when(gateway.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.search(request(null, null));

        ArgumentCaptor<PatientSearchMapper.SearchCriteria> captor =
                ArgumentCaptor.forClass(PatientSearchMapper.SearchCriteria.class);
        verify(gateway).search(captor.capture(), anyInt(), anyInt());
        assertTrue(
                captor.getValue().orderClause().contains("next_plan_date"),
                "默认按下次随访日期升序（patient.md §1）");
        assertTrue(captor.getValue().orderClause().contains("p.id"));
    }

    @Test
    void searchAppliesWhitelistedSortOnly() {
        when(gateway.count(any())).thenReturn(0L);
        when(gateway.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.search(requestWithSort("dischargeDate", "desc"));

        ArgumentCaptor<PatientSearchMapper.SearchCriteria> captor =
                ArgumentCaptor.forClass(PatientSearchMapper.SearchCriteria.class);
        verify(gateway).search(captor.capture(), anyInt(), anyInt());
        assertTrue(captor.getValue().orderClause().contains("discharge_date DESC"));
    }

    @Test
    void searchRejectsSortOutsideWhitelist() {
        assertInvalid(requestWithSort("mrnCipher; DROP TABLE pt_patient", "asc"), "sort.field");
        assertInvalid(requestWithSort("unknownField", "asc"), "sort.field");
        assertInvalid(requestWithSort("dischargeDate", "random"), "sort.order");
    }

    // ---------------------------------------------------------------- 高级检索（E13-T06 /
    // A-PAT-014、A-NFR-009）

    private static final com.fasterxml.jackson.databind.ObjectMapper ADV_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 条件树经字段目录编译后以参数化片段进入检索条件（取值全部在参数位）。 */
    @Test
    void searchCompilesAdvancedConditionIntoParameterizedCriteria() throws Exception {
        when(gateway.count(any())).thenReturn(0L);
        when(gateway.search(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(searchFields.specs())
                .thenReturn(
                        java.util.Map.of(
                                "discharge_date",
                                new com.crdp.followup.patient.domain.AdvancedSearchCatalog
                                        .SearchFieldSpec(
                                        "discharge_date",
                                        "出院日期",
                                        com.crdp.followup.patient.domain.AdvancedSearchCatalog
                                                .ValueType.DATE,
                                        "p.discharge_date",
                                        List.of())));

        service.search(
                new PatientSearchService.SearchRequest(
                        null,
                        null,
                        null,
                        null,
                        emptyFilters(),
                        ADV_JSON.readTree(
                                """
                                {"logic":"OR","items":[
                                  {"field":"discharge_date","op":"BETWEEN","value":["2026-01-01","2026-06-30"]},
                                  {"field":"discharge_date","op":"IS_NULL"}]}
                                """)));

        ArgumentCaptor<PatientSearchMapper.SearchCriteria> captor =
                ArgumentCaptor.forClass(PatientSearchMapper.SearchCriteria.class);
        verify(gateway).search(captor.capture(), anyInt(), anyInt());
        verify(gateway).count(captor.capture());
        PatientSearchMapper.SearchCriteria criteria = captor.getAllValues().get(0);
        PatientSearchMapper.SearchCriteria countCriteria = captor.getAllValues().get(1);
        assertEquals(
                "(p.discharge_date BETWEEN #{c.advancedBinds[0]} AND #{c.advancedBinds[1]}"
                        + " OR p.discharge_date IS NULL)",
                criteria.advancedWhere(),
                "条件树编译为白名单列 + 参数位的 SQL 片段");
        assertEquals(
                List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30)),
                criteria.advancedBinds());
        assertEquals(
                criteria.advancedWhere(), countCriteria.advancedWhere(), "count 与 search 同一条件");

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(auditCaptor.capture());
        assertTrue(
                auditCaptor.getValue().queryDigest().contains("\"advancedRows\":2"),
                "批量查询审计摘要记录高级检索条件行数（不含取值明文）：" + auditCaptor.getValue().queryDigest());
    }

    @Test
    void searchRejectsConditionFieldOutsideCatalog() throws Exception {
        when(searchFields.specs()).thenReturn(java.util.Map.of());
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.search(
                                        new PatientSearchService.SearchRequest(
                                                null,
                                                null,
                                                null,
                                                null,
                                                emptyFilters(),
                                                ADV_JSON.readTree(
                                                        """
                                                        {"items":[{"field":"no_such_field","op":"EQ","value":"x"}]}
                                                        """))));
        assertEquals(40001, error.code(), "目录外字段（字典字段才可引用）40001");
        verify(gateway, never()).search(any(), anyInt(), anyInt());
    }

    // ---------------------------------------------------------------- 筛选（A-PAT-002）

    @Test
    void searchValidatesStatusEnumWhitelist() {
        assertInvalid(requestWithStatus(List.of("ACTIVE", "BOGUS")), "status");
    }

    @Test
    void searchValidatesDateRangeOrder() {
        LocalDate from = LocalDate.of(2026, 5, 1);
        LocalDate to = LocalDate.of(2026, 4, 1);
        assertInvalid(requestWithDischarge(from, to), "dischargeFrom / dischargeTo");
        assertInvalid(requestWithSurgery(from, to), "surgeryFrom / surgeryTo");
        assertInvalid(requestWithNextVisit(from, to), "nextVisitFrom / nextVisitTo");
    }

    @Test
    void searchValidatesFilterValues() {
        assertInvalid(requestWithWard(List.of(0L)), "wardIds");
        assertInvalid(requestWithDoctor(-1L), "doctorId");
        assertInvalid(requestWithDischargeStatus(9), "dischargeStatus");
    }

    @Test
    void searchPassesFiltersToMapper() {
        when(gateway.count(any())).thenReturn(0L);
        when(gateway.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        PatientSearchService.SearchRequest request =
                new PatientSearchService.SearchRequest(
                        2,
                        50,
                        null,
                        null,
                        new PatientSearchService.Filters(
                                List.of(WARD_ID),
                                null,
                                List.of("ACTIVE"),
                                7L,
                                1,
                                "TY_A",
                                LocalDate.of(2026, 1, 1),
                                LocalDate.of(2026, 6, 1),
                                LocalDate.of(2026, 2, 1),
                                LocalDate.of(2026, 3, 1),
                                LocalDate.of(2026, 11, 1),
                                LocalDate.of(2026, 12, 1)),
                        null);
        service.search(request);

        ArgumentCaptor<PatientSearchMapper.SearchCriteria> captor =
                ArgumentCaptor.forClass(PatientSearchMapper.SearchCriteria.class);
        verify(gateway).search(captor.capture(), eq(50), eq(50));
        PatientSearchMapper.SearchCriteria criteria = captor.getValue();
        assertEquals(List.of(WARD_ID), criteria.wardIds());
        assertEquals(List.of("ACTIVE"), criteria.status());
        assertEquals(7L, criteria.doctorId());
        assertEquals(1, criteria.dischargeStatus());
        assertEquals("TY_A", criteria.patientType());
        assertEquals(LocalDate.of(2026, 2, 1), criteria.dischargeFrom());
        assertEquals(LocalDate.of(2026, 12, 1), criteria.nextVisitTo());
    }

    // ---------------------------------------------------------------- 审计（批量查询）

    @Test
    void searchWritesBatchQueryAuditWithoutKeywordPlaintext() {
        when(gateway.count(any())).thenReturn(42L);
        when(gateway.search(any(), anyInt(), anyInt())).thenReturn(List.of());

        service.search(request(NAME, null));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("BATCH_QUERY", entry.accessMethod());
        assertEquals("PATIENT", entry.objectType());
        assertEquals(42L, entry.recordCount());
        assertFalse(entry.queryDigest().contains(NAME), "审计不得出现检索词明文");
        assertTrue(entry.queryDigest().contains("\"keyword\":true"), "摘要记录是否用了检索词");
    }

    // ---------------------------------------------------------------- 筛选候选

    @Test
    void filterOptionsListsEnabledWards() {
        when(patientMapper.findWards())
                .thenReturn(
                        List.of(
                                new PatientMapper.OptionRow(WARD_ID, "示例二病区"),
                                new PatientMapper.OptionRow(4L, "示例三病区")));

        PatientSearchService.FilterOptionsView options = service.filterOptions();

        assertEquals(2, options.wards().size());
        assertEquals(WARD_ID, options.wards().get(0).id());
        assertEquals("示例二病区", options.wards().get(0).name());
    }

    // ---------------------------------------------------------------- 辅助

    private void assertInvalid(PatientSearchService.SearchRequest request, String messagePart) {
        ApiException error = assertThrows(ApiException.class, () -> service.search(request));
        assertEquals(40001, error.code());
        assertTrue(
                error.getMessage().contains(messagePart),
                "提示应包含 " + messagePart + "，实际：" + error.getMessage());
        verify(gateway, never()).search(any(), anyInt(), anyInt());
    }

    private static PatientSearchService.SearchRequest request(String keyword, Object ignored) {
        return new PatientSearchService.SearchRequest(
                null, null, null, keyword, emptyFilters(), null);
    }

    private static PatientSearchService.SearchRequest requestWithPaging(int pageNo, int pageSize) {
        return new PatientSearchService.SearchRequest(
                pageNo, pageSize, null, null, emptyFilters(), null);
    }

    private static PatientSearchService.SearchRequest requestWithSort(String field, String order) {
        return new PatientSearchService.SearchRequest(
                null,
                null,
                List.of(new PatientSearchService.SortSpec(field, order)),
                null,
                emptyFilters(),
                null);
    }

    private static PatientSearchService.SearchRequest requestWithStatus(List<String> status) {
        return new PatientSearchService.SearchRequest(
                null, null, null, null, filtersWith(filters -> filters.status = status), null);
    }

    private static PatientSearchService.SearchRequest requestWithWard(List<Long> wardIds) {
        return new PatientSearchService.SearchRequest(
                null, null, null, null, filtersWith(filters -> filters.wardIds = wardIds), null);
    }

    private static PatientSearchService.SearchRequest requestWithDoctor(long doctorId) {
        return new PatientSearchService.SearchRequest(
                null, null, null, null, filtersWith(filters -> filters.doctorId = doctorId), null);
    }

    private static PatientSearchService.SearchRequest requestWithDischargeStatus(int status) {
        return new PatientSearchService.SearchRequest(
                null,
                null,
                null,
                null,
                filtersWith(filters -> filters.dischargeStatus = status),
                null);
    }

    private static PatientSearchService.SearchRequest requestWithDischarge(
            LocalDate from, LocalDate to) {
        return new PatientSearchService.SearchRequest(
                null,
                null,
                null,
                null,
                filtersWith(
                        filters -> {
                            filters.dischargeFrom = from;
                            filters.dischargeTo = to;
                        }),
                null);
    }

    private static PatientSearchService.SearchRequest requestWithSurgery(
            LocalDate from, LocalDate to) {
        return new PatientSearchService.SearchRequest(
                null,
                null,
                null,
                null,
                filtersWith(
                        filters -> {
                            filters.surgeryFrom = from;
                            filters.surgeryTo = to;
                        }),
                null);
    }

    private static PatientSearchService.SearchRequest requestWithNextVisit(
            LocalDate from, LocalDate to) {
        return new PatientSearchService.SearchRequest(
                null,
                null,
                null,
                null,
                filtersWith(
                        filters -> {
                            filters.nextVisitFrom = from;
                            filters.nextVisitTo = to;
                        }),
                null);
    }

    private static PatientSearchService.Filters emptyFilters() {
        return new PatientSearchService.Filters(
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 可变构造辅助（测试内一次成形）。 */
    private static PatientSearchService.Filters filtersWith(
            java.util.function.Consumer<MutableFilters> customize) {
        MutableFilters filters = new MutableFilters();
        customize.accept(filters);
        return new PatientSearchService.Filters(
                filters.wardIds,
                filters.deptIds,
                filters.status,
                filters.doctorId,
                filters.dischargeStatus,
                filters.patientType,
                filters.surgeryFrom,
                filters.surgeryTo,
                filters.dischargeFrom,
                filters.dischargeTo,
                filters.nextVisitFrom,
                filters.nextVisitTo);
    }

    private static final class MutableFilters {
        List<Long> wardIds;
        List<Long> deptIds;
        List<String> status;
        Long doctorId;
        Integer dischargeStatus;
        String patientType;
        LocalDate surgeryFrom;
        LocalDate surgeryTo;
        LocalDate dischargeFrom;
        LocalDate dischargeTo;
        LocalDate nextVisitFrom;
        LocalDate nextVisitTo;
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:patient:view"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                0L, // 绝对时长用部署配置的全局值,
                null);
    }
}
