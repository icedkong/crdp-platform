package com.crdp.followup.compare.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.compare.infra.mapper.CompareFeatureMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleMapper;
import com.crdp.followup.compare.infra.mapper.CompareRuleRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E17-T01 / A-FU-027：对比规则维护单元测试。
 *
 * <p>锁定：清单解析 params 并带显示名、修改全量覆盖阈值与开关并写 COMPARE_RULE_UPDATE 审计（含前后值）、 功能开关 compare_highlight 关闭 →
 * 40301、无行视为开、规则不存在 40400、键集校验失败 40001 不落库。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class CompareRuleServiceTest {

    private final CompareRuleMapper rules = mock(CompareRuleMapper.class);
    private final CompareFeatureMapper features = mock(CompareFeatureMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final CompareRuleService service = new CompareRuleService(rules, features, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient().when(features.findEnabled("compare_highlight")).thenReturn(null);
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
                "医院 A（示例）",
                7L,
                "nurse.demo",
                "李管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:abnormal:config"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }

    /** 种子规则行（迁移 V67：EF 下降 ≥ 10 个百分点，回落基线 f102）。 */
    private static CompareRuleRow efDropRow(boolean enabled) {
        return new CompareRuleRow(
                11L,
                "A_ANNUAL_FU",
                "年度随访记录",
                "ef_drop",
                "射血分数下降",
                "fu_echo",
                "f248",
                "EF 射血分数",
                "%",
                "DIFF",
                "{\"direction\":\"DROP\",\"threshold\":10}",
                "A_BASELINE",
                "f102",
                "出院前最后一次EF",
                "EF 比上次（或出院前）下降 ≥ 10 个百分点",
                10,
                enabled ? "ENABLED" : "DISABLED",
                7L,
                LocalDateTime.of(2027, 5, 17, 10, 0));
    }

    /** GET 清单：params 解析为对象、显示名与类型标签带出、空 formCode 透传为 null（全部表单）。 */
    @Test
    void listParsesParamsAndCarriesLabels() {
        when(rules.findAll(null)).thenReturn(List.of(efDropRow(true)));

        List<CompareRuleService.RuleView> items = service.list(null);

        assertEquals(1, items.size());
        CompareRuleService.RuleView view = items.get(0);
        assertEquals("ef_drop", view.ruleCode());
        assertEquals("EF 射血分数", view.fieldLabel());
        assertEquals("%", view.fieldUnit());
        assertEquals("差值下降 / 上升", view.ruleTypeLabel());
        assertEquals(Map.of("direction", "DROP", "threshold", 10), view.params());
        assertEquals("出院前最后一次EF", view.baselineFieldLabel());
        assertTrue(view.enabled());
    }

    /** formCode 过滤原样透传（按表单取规则，E17-T03 求值同入口）。 */
    @Test
    void listFiltersByFormCode() {
        when(rules.findAll("A_ANNUAL_FU")).thenReturn(List.of());
        assertEquals(0, service.list("A_ANNUAL_FU").size());
        verify(rules).findAll("A_ANNUAL_FU");
    }

    /** 正常修改：停用并调阈值 → 落库、审计带前后值、返回最新值。 */
    @Test
    void updatePersistsThresholdAndSwitchAndAudits() {
        when(rules.find(11L))
                .thenReturn(Optional.of(efDropRow(true)))
                .thenReturn(
                        Optional.of(
                                new CompareRuleRow(
                                        11L,
                                        "A_ANNUAL_FU",
                                        "年度随访记录",
                                        "ef_drop",
                                        "射血分数下降",
                                        "fu_echo",
                                        "f248",
                                        "EF 射血分数",
                                        "%",
                                        "DIFF",
                                        "{\"direction\":\"DROP\",\"threshold\":15}",
                                        "A_BASELINE",
                                        "f102",
                                        "出院前最后一次EF",
                                        "EF 比上次（或出院前）下降 ≥ 10 个百分点",
                                        10,
                                        "DISABLED",
                                        7L,
                                        LocalDateTime.of(2027, 5, 18, 9, 0))));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("direction", "DROP");
        params.put("threshold", 15);

        CompareRuleService.RuleView updated =
                service.update(11L, new CompareRuleService.RuleUpdate(false, params));

        assertFalse(updated.enabled());
        assertEquals(15, ((Number) updated.params().get("threshold")).intValue());
        ArgumentCaptor<String> paramsJson = ArgumentCaptor.forClass(String.class);
        verify(rules).update(anyLong(), anyString(), paramsJson.capture(), anyLong());
        assertTrue(paramsJson.getValue().contains("\"threshold\":15"));

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("COMPARE_RULE_UPDATE", entry.getValue().action());
        assertEquals("FU_COMPARE_RULE", entry.getValue().objectType());
        assertEquals("ef_drop", entry.getValue().objectId());
        assertTrue(entry.getValue().beforeJson().contains("\"threshold\":10"));
        assertTrue(entry.getValue().afterJson().contains("\"threshold\":15"));
        assertTrue(entry.getValue().afterJson().contains("\"enabled\":false"));
    }

    /** 规则不存在 → 40400（不暴露存在性口径与其他租户行）。 */
    @Test
    void updateRejectsUnknownRule() {
        when(rules.find(99L)).thenReturn(Optional.empty());
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        99L, new CompareRuleService.RuleUpdate(true, Map.of())));
        assertEquals(40400, error.code());
    }

    /** enabled 缺失 / params 缺必填键 → 40001 不落库。 */
    @Test
    void updateRejectsMissingEnabledOrBadParams() {
        when(rules.find(11L)).thenReturn(Optional.of(efDropRow(true)));
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        11L, new CompareRuleService.RuleUpdate(null, Map.of())));
        assertEquals(40001, missing.code());
        ApiException badParams =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        11L,
                                        new CompareRuleService.RuleUpdate(
                                                true, Map.of("direction", "DROP"))));
        assertEquals(40001, badParams.code());
        verify(rules, never()).update(anyLong(), anyString(), anyString(), anyLong());
    }

    /** 功能开关关闭 → 40301（GET / PUT 同口径，AGENTS.md 规则 7）；无行视为默认开。 */
    @Test
    void featureOffBlocksBothEndpointsButMissingRowDefaultsOn() {
        when(features.findEnabled("compare_highlight")).thenReturn(false);
        ApiException listBlocked = assertThrows(ApiException.class, () -> service.list(null));
        assertEquals(40301, listBlocked.code());
        ApiException updateBlocked =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        11L, new CompareRuleService.RuleUpdate(true, Map.of())));
        assertEquals(40301, updateBlocked.code());

        when(features.findEnabled("compare_highlight")).thenReturn(null);
        when(rules.findAll(null)).thenReturn(List.of(efDropRow(true)));
        assertEquals(1, service.list(null).size());
    }
}
