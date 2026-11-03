package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.domain.AdvancedSearchCatalog.OptionValue;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.SearchFieldSpec;
import com.crdp.followup.patient.domain.AdvancedSearchCatalog.ValueType;
import com.crdp.followup.patient.infra.mapper.SavedFilterMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T06 / A-PAT-015 单元测试（保存个人筛选：可命名、修改、删除；仅本人可见）。
 *
 * <p>锁定口径：payload 只收 {filters?, condition?} 且检索词 keyword 被拒绝（检索词可能是患者姓名， 与「不写 URL /
 * 审计不存明文」同口径不落库）；条件树保存时按字段目录编译校验（字典字段白名单 + 参数化，A-NFR-009）；增改删写 OPERATION 审计；名下数量上限
 * 20；本人的行才可改删。全部虚构数据。
 */
class SavedFilterServiceTest {

    private static final long USER_ID = 9L;

    private final SavedFilterMapper mapper = mock(SavedFilterMapper.class);
    private final PatientSearchFieldService searchFields = mock(PatientSearchFieldService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final SavedFilterService service = new SavedFilterService(mapper, searchFields, audit);

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(searchFields.specs())
                .thenReturn(
                        java.util.Map.of(
                                "discharge_date",
                                new SearchFieldSpec(
                                        "discharge_date",
                                        "出院日期",
                                        ValueType.DATE,
                                        "p.discharge_date",
                                        List.of()),
                                "ward",
                                new SearchFieldSpec(
                                        "ward",
                                        "病区/中心",
                                        ValueType.OPTION,
                                        "p.ward_id",
                                        List.of(new OptionValue(3L, "示例一病区")))));
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                USER_ID,
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
                null);
    }

    private JsonNode payload(String jsonText) {
        try {
            return json.readTree(jsonText);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SavedFilterMapper.SavedFilterRow row(long id, String name, String payloadJson) {
        return new SavedFilterMapper.SavedFilterRow(
                id,
                USER_ID,
                name,
                payloadJson,
                LocalDateTime.of(2026, 11, 1, 9, 0),
                LocalDateTime.of(2026, 11, 2, 9, 0));
    }

    private static String cond() {
        return "{\"condition\":{\"items\":[{\"field\":\"ward\",\"op\":\"EQ\",\"value\":3}]}}";
    }

    // ---------------------------------------------------------------- 保存（可命名）

    @Test
    void createNormalizesPayloadAndWritesAudit() {
        when(mapper.countByUser(USER_ID)).thenReturn(0L);
        String stored =
                "{\"filters\":{\"wardIds\":[3]},\"condition\":{\"items\":[{\"field\":\"discharge_date\","
                        + "\"op\":\"BETWEEN\",\"value\":[\"2026-01-01\",\"2026-06-30\"]}]}}";
        when(mapper.insert(any(SavedFilterMapper.SavedFilterInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation
                                    .getArgument(0, SavedFilterMapper.SavedFilterInsert.class)
                                    .setId(101L);
                            return 1;
                        });
        when(mapper.findOwn(101L, USER_ID)).thenReturn(row(101L, "上半年出院（示例）", stored));

        SavedFilterService.SavedFilterView view =
                service.create(
                        "  上半年出院（示例）  ",
                        payload(
                                """
                                {"filters":{"wardIds":[3]},"condition":{"items":[{"field":"discharge_date","op":"BETWEEN","value":["2026-01-01","2026-06-30"]}]}}
                                """));

        assertEquals("101", view.id());
        assertEquals("上半年出院（示例）", view.name(), "名称去空白保存");
        assertTrue(view.payload().has("filters"), "filters 原样保留");
        assertTrue(view.payload().has("condition"), "condition 原样保留");

        ArgumentCaptor<SavedFilterMapper.SavedFilterInsert> insert =
                ArgumentCaptor.forClass(SavedFilterMapper.SavedFilterInsert.class);
        verify(mapper).insert(insert.capture());
        assertEquals(USER_ID, insert.getValue().getUserId(), "归属当前用户（仅本人可见）");
        assertTrue(insert.getValue().getPayload().startsWith("{\""));
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("SAVED_FILTER_CREATE", entry.getValue().action(), "保存写 OPERATION 审计");
    }

    @Test
    void createRejectsKeywordAndUnknownKeys() {
        ApiException keyword =
                assertThrows(
                        ApiException.class,
                        () -> service.create("示例筛选", payload("{\"keyword\":\"张小明（示例）\"}")));
        assertEquals(40001, keyword.code(), "检索词不进保存的筛选（可能是患者姓名）");

        ApiException unknown =
                assertThrows(
                        ApiException.class,
                        () -> service.create("示例筛选", payload("{\"sort\":[{}]}")));
        assertEquals(40001, unknown.code(), "未知键 40001");

        ApiException unknownFilter =
                assertThrows(
                        ApiException.class,
                        () -> service.create("示例筛选", payload("{\"filters\":{\"evil\":[1]}}")));
        assertEquals(40001, unknownFilter.code(), "filters 白名单外键 40001");

        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () -> service.create("示例筛选", payload("{\"filters\":{}}")));
        assertEquals(40001, empty.code(), "至少包含一个筛选条件");
        verify(mapper, never()).insert(any(SavedFilterMapper.SavedFilterInsert.class));
    }

    @Test
    void createValidatesConditionAgainstCatalog() {
        ApiException unknownField =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.create(
                                        "示例筛选",
                                        payload(
                                                """
                                                {"condition":{"items":[{"field":"mrn_cipher","op":"EQ","value":"IT-1"}]}}
                                                """)));
        assertEquals(40001, unknownField.code(), "条件树保存时即按字段目录校验（字典字段白名单，A-NFR-009）");
        verify(mapper, never()).insert(any(SavedFilterMapper.SavedFilterInsert.class));
    }

    @Test
    void createRejectsBadNameAndLimit() {
        when(mapper.countByUser(USER_ID)).thenReturn(0L);
        assertThrows(ApiException.class, () -> service.create("  ", payload(cond())));
        assertThrows(ApiException.class, () -> service.create("长".repeat(33), payload(cond())));

        when(mapper.countByUser(USER_ID)).thenReturn(20L);
        ApiException limit =
                assertThrows(ApiException.class, () -> service.create("示例筛选", payload(cond())));
        assertEquals(40001, limit.code(), "每人最多 20 条");
    }

    // ---------------------------------------------------------------- 修改与删除

    @Test
    void updateRenamesOrOverwritesOwnFilterOnly() {
        when(mapper.findOwn(101L, USER_ID))
                .thenReturn(row(101L, "旧名（示例）", cond()))
                .thenReturn(row(101L, "新名（示例）", cond()));
        SavedFilterService.SavedFilterView renamed = service.update(101L, "新名（示例）", null);
        assertEquals("新名（示例）", renamed.name());
        verify(mapper).updateOwn(eq(101L), eq(USER_ID), eq("新名（示例）"), eq(cond()));

        when(mapper.findOwn(101L, USER_ID)).thenReturn(row(101L, "新名（示例）", cond()));
        service.update(101L, null, payload("{\"filters\":{\"wardIds\":[3]}}"));
        verify(mapper)
                .updateOwn(
                        eq(101L), eq(USER_ID), eq("新名（示例）"), eq("{\"filters\":{\"wardIds\":[3]}}"));

        when(mapper.findOwn(999L, USER_ID)).thenReturn(null);
        ApiException missing =
                assertThrows(ApiException.class, () -> service.update(999L, "x", null));
        assertEquals(40400, missing.code(), "他人 / 不存在的筛选 40400（不暴露存在性）");
    }

    @Test
    void deleteRemovesOwnFilterAndWritesAudit() {
        when(mapper.findOwn(101L, USER_ID)).thenReturn(row(101L, "待删（示例）", cond()));
        service.delete(101L);
        verify(mapper).deleteOwn(101L, USER_ID);

        when(mapper.findOwn(404L, USER_ID)).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class, () -> service.delete(404L));
        assertEquals(40400, missing.code());

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("SAVED_FILTER_DELETE", entry.getValue().action());
    }

    @Test
    void listReturnsOwnFilters() {
        when(mapper.findByUser(USER_ID)).thenReturn(List.of(row(101L, "A（示例）", cond())));
        SavedFilterService.SavedFilterList list = service.list();
        assertEquals(1, list.items().size());
        assertEquals("A（示例）", list.items().get(0).name());
        assertTrue(list.items().get(0).payload().has("condition"));
        // 只查本人：查询参数带当前用户
        verify(mapper).findByUser(USER_ID);
        verify(mapper, never()).findOwn(anyLong(), anyLong());
    }
}
