package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.infra.mapper.NameInsert;
import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.TplInsert;
import com.crdp.followup.report.infra.mapper.TypeInsert;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T01 单测锁定（全部虚构数据）：资料类型 / 名称字典 / 指标模板的维护口径。
 *
 * <p>覆盖：类型编码唯一（40902）与字符集校验（40001）、字段集必须是 REPORT 表单（40001，非 REPORT 用途
 * 拒绝）、名称必填默认值（C-SCAN-003：可显式放开）、电子化状态取值（PENDING / NO_ENTRY，其余 40001）、 删除守卫（类型下有名称 40901、模板被绑定
 * 40901，被拒写 DENIED 审计）、同名名称同类型 40902、 模板项目代码重复 40001、空项目 40001、编码不可改（更新请求不携带编码）、运行时目录只吐启用类型与
 * 停用模板不带出项目、增删改写审计。
 */
@ExtendWith(MockitoExtension.class)
class ReportConfigServiceTest {

    private static final long OPERATOR = 7L;

    private final ReportConfigMapper mapper = mock(ReportConfigMapper.class);
    private final FormFieldMapper forms = mock(FormFieldMapper.class);
    private final ReportMapper reports = mock(ReportMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ReportConfigService service =
            new ReportConfigService(mapper, forms, reports, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 类型（A-ATT-003）

    @Test
    void createTypeInsertsRowWithDefaultsAndAudits() {
        when(mapper.findTypeIdByCode("ECHO2")).thenReturn(null);
        when(forms.findForm("RPT_ECHO"))
                .thenReturn(new FormRow(9L, "RPT_ECHO", "心脏超声报告单", "REPORT", 1, "[]", "ENABLED"));
        when(mapper.insertType(any(TypeInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, TypeInsert.class).setId(31L);
                            return 1;
                        });
        when(mapper.findType(31L)).thenReturn(typeRow(31L, "ECHO2", "心脏超声（新）", "RPT_ECHO"));

        ReportConfigService.TypeView view =
                service.createType(
                        new ReportConfigService.TypeCreateRequest(
                                "ECHO2", "心脏超声（新）", "RPT_ECHO", null, null, 15, "本院新增"));

        assertEquals("ECHO2", view.code());
        assertTrue(view.nameRequired(), "nameRequired 缺省为必填（A 医院口径，C-SCAN-003 可显式放开）");
        assertNull(view.defaultDigitizeStatus(), "A 医院不启用电子化状态");
        ArgumentCaptor<TypeInsert> captor = ArgumentCaptor.forClass(TypeInsert.class);
        verify(mapper).insertType(captor.capture());
        assertEquals(OPERATOR, captor.getValue().getUpdatedBy());
        verify(audit).append(any(AuditEntry.class)); // RPT_TYPE_CREATE 至少一条（校验通过路径不写 DENIED）
    }

    @Test
    void createTypeRejectsDuplicateCodeWith40902AndDeniedAudit() {
        when(mapper.findTypeIdByCode("ECHO")).thenReturn(1L);
        when(forms.findForm("RPT_ECHO"))
                .thenReturn(new FormRow(9L, "RPT_ECHO", "心脏超声报告单", "REPORT", 1, "[]", "ENABLED"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createType(
                                        new ReportConfigService.TypeCreateRequest(
                                                "ECHO",
                                                "心脏超声",
                                                "RPT_ECHO",
                                                null,
                                                null,
                                                null,
                                                null)));

        assertEquals(40902, error.code());
        verify(mapper, never()).insertType(any(TypeInsert.class));
        verify(audit).append(any(AuditEntry.class));
    }

    @Test
    void createTypeRejectsNonReportForm() {
        when(forms.findForm("A_BASELINE"))
                .thenReturn(new FormRow(1L, "A_BASELINE", "基线表单", "BASELINE", 1, "[]", "ENABLED"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createType(
                                        new ReportConfigService.TypeCreateRequest(
                                                "X1",
                                                "新类型",
                                                "A_BASELINE",
                                                null,
                                                null,
                                                null,
                                                null)));

        assertEquals(40001, error.code());
        assertTrue(error.getMessage().contains("REPORT"));
    }

    @Test
    void createTypeRejectsBlankCodeAndName() {
        ApiException code =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createType(
                                        new ReportConfigService.TypeCreateRequest(
                                                " ", "名称", "RPT_ECHO", null, null, null, null)));
        assertEquals(40001, code.code());
        ApiException chinese =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createType(
                                        new ReportConfigService.TypeCreateRequest(
                                                "心脏超声", "名称", "RPT_ECHO", null, null, null, null)));
        assertTrue(chinese.getMessage().contains("字母"));
    }

    @Test
    void updateTypeKeepsCodeAndAuditsBeforeAfter() {
        when(mapper.findType(11L))
                .thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"))
                .thenReturn(
                        new ReportConfigMapper.TypeRow(
                                11L,
                                "LAB",
                                "检验",
                                "RPT_LAB",
                                "报告单",
                                false,
                                "PENDING",
                                20,
                                "DISABLED",
                                null,
                                0L));
        when(forms.findForm("RPT_LAB"))
                .thenReturn(new FormRow(5L, "RPT_LAB", "检验报告单", "REPORT", 1, "[]", "ENABLED"));

        ReportConfigService.TypeView view =
                service.updateType(
                        11L,
                        new ReportConfigService.TypeUpdateRequest(
                                "检验（改）", "RPT_LAB", false, "PENDING", 20, "DISABLED", null));

        assertEquals("LAB", view.code(), "编码不可改（更新语句不含编码列）");
        assertEquals("DISABLED", view.status());
        verify(mapper)
                .updateType(
                        eq(11L),
                        eq("检验（改）"),
                        eq("RPT_LAB"),
                        eq(false),
                        eq("PENDING"),
                        eq(20),
                        eq("DISABLED"),
                        any(),
                        eq(OPERATOR));
    }

    @Test
    void updateTypeRejectsBadDigitizeStatus() {
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(forms.findForm("RPT_LAB"))
                .thenReturn(new FormRow(5L, "RPT_LAB", "检验报告单", "REPORT", 1, "[]", "ENABLED"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateType(
                                        11L,
                                        new ReportConfigService.TypeUpdateRequest(
                                                "检验", "RPT_LAB", null, "DONE", null, "ENABLED",
                                                null)));

        assertEquals(40001, error.code(), "DONE 只由转录产生，不能配成默认值（C-SCAN-011）");
    }

    @Test
    void updateTypeMissingRowIs40400() {
        when(mapper.findType(999L)).thenReturn(null);
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateType(
                                        999L,
                                        new ReportConfigService.TypeUpdateRequest(
                                                "检验", "RPT_LAB", null, null, null, "ENABLED",
                                                null)));
        assertEquals(40400, error.code());
    }

    @Test
    void deleteTypeBlockedWhileNamesExist() {
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(mapper.countNamesByTypeCode("LAB")).thenReturn(6L);

        ApiException error = assertThrows(ApiException.class, () -> service.deleteType(11L));

        assertEquals(40901, error.code());
        verify(mapper, never()).deleteType(anyLong());
    }

    @Test
    void deleteTypeRemovesRowWhenNoNames() {
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(mapper.countNamesByTypeCode("LAB")).thenReturn(0L);

        service.deleteType(11L);

        verify(mapper).deleteType(11L);
    }

    @Test
    void deleteTypeBlockedWhileReportsExist() {
        // OQ-95②（随 E16-T02 落地）：类型被报告单引用 → 40901，可停用不可删
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(reports.countReportsOfType("LAB")).thenReturn(3L);

        ApiException error = assertThrows(ApiException.class, () -> service.deleteType(11L));

        assertEquals(40901, error.code());
        assertTrue(error.getMessage().contains("3 份报告单"), error.getMessage());
        verify(mapper, never()).deleteType(anyLong());
        verify(audit).append(any(AuditEntry.class)); // DENIED 审计
    }

    @Test
    void deleteNameBlockedWhileReportsExist() {
        when(mapper.findName(51L)).thenReturn(nameRow(51L, "LAB", "检验", "凝血四项（示例）", 77L, "凝血四项"));
        when(reports.countReportsOfName(51L)).thenReturn(2L);

        ApiException error = assertThrows(ApiException.class, () -> service.deleteName(51L));

        assertEquals(40901, error.code());
        verify(mapper, never()).deleteName(anyLong());
    }

    // ---------------------------------------------------------------- 名称（A-ATT-011）

    @Test
    void createNameBindsTemplateAndAudits() {
        when(mapper.findTypeIdByCode("LAB")).thenReturn(11L);
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(mapper.findNameId("LAB", "凝血四项")).thenReturn(null);
        when(mapper.findTpl(77L)).thenReturn(tplRow(77L, "凝血四项", "ENABLED", 0L));
        when(mapper.insertName(any(NameInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, NameInsert.class).setId(51L);
                            return 1;
                        });
        when(mapper.findName(51L)).thenReturn(nameRow(51L, "LAB", "检验", "凝血四项", 77L, "凝血四项"));

        ReportConfigService.NameView view =
                service.createName(
                        new ReportConfigService.NameCreateRequest("LAB", "凝血四项", "77", null, 10));

        assertEquals("凝血四项", view.name());
        assertEquals("77", view.indicatorTplId());
        ArgumentCaptor<NameInsert> captor = ArgumentCaptor.forClass(NameInsert.class);
        verify(mapper).insertName(captor.capture());
        assertEquals(77L, captor.getValue().getIndicatorTplId());
    }

    @Test
    void createNameRejectsDuplicateInSameType() {
        when(mapper.findTypeIdByCode("LAB")).thenReturn(11L);
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(mapper.findNameId("LAB", "血常规")).thenReturn(50L);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createName(
                                        new ReportConfigService.NameCreateRequest(
                                                "LAB", "血常规", null, null, null)));

        assertEquals(40902, error.code());
        verify(mapper, never()).insertName(any(NameInsert.class));
    }

    @Test
    void createNameRejectsUnknownTypeAndTemplate() {
        when(mapper.findTypeIdByCode("NOPE")).thenReturn(null);
        ApiException type =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createName(
                                        new ReportConfigService.NameCreateRequest(
                                                "NOPE", "名称", null, null, null)));
        assertEquals(40400, type.code());

        when(mapper.findTypeIdByCode("LAB")).thenReturn(11L);
        when(mapper.findType(11L)).thenReturn(typeRow(11L, "LAB", "检验", "RPT_LAB"));
        when(mapper.findTpl(404L)).thenReturn(null);
        ApiException tpl =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createName(
                                        new ReportConfigService.NameCreateRequest(
                                                "LAB", "名称", "404", null, null)));
        assertEquals(40001, tpl.code());
    }

    @Test
    void updateNameRejectsClashWithSibling() {
        when(mapper.findName(50L)).thenReturn(nameRow(50L, "LAB", "检验", "血常规", 77L, "血常规"));
        when(mapper.findNameId("LAB", "肝肾功能")).thenReturn(51L);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateName(
                                        50L,
                                        new ReportConfigService.NameUpdateRequest(
                                                "肝肾功能", null, null, null, "ENABLED")));

        assertEquals(40902, error.code());
        verify(mapper, never())
                .updateName(anyLong(), anyString(), any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void updateNameAllowsKeepingOwnName() {
        when(mapper.findName(50L)).thenReturn(nameRow(50L, "LAB", "检验", "血常规", 77L, "血常规"));
        when(mapper.findNameId("LAB", "血常规")).thenReturn(50L);

        service.updateName(
                50L, new ReportConfigService.NameUpdateRequest("血常规", "", null, 30, "DISABLED"));

        verify(mapper)
                .updateName(
                        eq(50L),
                        eq("血常规"),
                        eq(null),
                        eq(null),
                        eq(30),
                        eq("DISABLED"),
                        eq(OPERATOR));
    }

    // ---------------------------------------------------------------- 指标模板（A-ATT-012 / C-SCAN-004）

    @Test
    void createTemplateInsertsItemsInOrder() {
        when(mapper.findTplIdByName("凝血四项")).thenReturn(null);
        when(mapper.insertTpl(any(TplInsert.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, TplInsert.class).setId(88L);
                            return 1;
                        });
        when(mapper.findTpl(88L)).thenReturn(tplRow(88L, "凝血四项", "ENABLED", 0L));
        when(mapper.findItems(88L)).thenReturn(List.of(itemRow(88L, "凝血酶原时间", "PT", "s", 10)));

        service.createTemplate(
                new ReportConfigService.TplCreateRequest(
                        "凝血四项",
                        null,
                        List.of(
                                new ReportConfigService.IndicatorItemRequest(
                                        "凝血酶原时间", "PT", "s", "11–13", null, null, 10),
                                new ReportConfigService.IndicatorItemRequest(
                                        "国际标准化比值", "INR", "—", "0.8–1.2", null, null, 20))));

        verify(mapper)
                .insertItem(
                        eq(88L),
                        eq("凝血酶原时间"),
                        eq("PT"),
                        eq("s"),
                        eq("11–13"),
                        any(),
                        any(),
                        eq(10));
        verify(mapper)
                .insertItem(
                        eq(88L),
                        eq("国际标准化比值"),
                        eq("INR"),
                        eq("—"),
                        eq("0.8–1.2"),
                        any(),
                        any(),
                        eq(20));
    }

    @Test
    void createTemplateRejectsDuplicateItemCodeAndEmptyItems() {
        when(mapper.findTplIdByName("模板")).thenReturn(null);

        ApiException dup =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTemplate(
                                        new ReportConfigService.TplCreateRequest(
                                                "模板",
                                                null,
                                                List.of(
                                                        item("白细胞", "WBC"),
                                                        item("白细胞计数", "WBC")))));
        assertEquals(40001, dup.code());

        ApiException empty =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTemplate(
                                        new ReportConfigService.TplCreateRequest(
                                                "模板", null, List.of())));
        assertEquals(40001, empty.code());
        verify(mapper, never()).insertTpl(any(TplInsert.class));
    }

    @Test
    void createTemplateRejectsDuplicateName() {
        when(mapper.findTplIdByName("血常规")).thenReturn(77L);
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.createTemplate(
                                        new ReportConfigService.TplCreateRequest(
                                                "血常规", null, List.of(item("白细胞", "WBC")))));
        assertEquals(40902, error.code());
    }

    @Test
    void updateTemplateReplacesItemsWholesale() {
        when(mapper.findTpl(77L)).thenReturn(tplRow(77L, "血常规", "ENABLED", 2L));
        when(mapper.findTplIdByName("血常规（新）")).thenReturn(null);
        when(mapper.findItems(77L)).thenReturn(List.of());

        service.updateTemplate(
                77L,
                new ReportConfigService.TplUpdateRequest(
                        "血常规（新）", null, "DISABLED", List.of(item("白细胞", "WBC"))));

        verify(mapper).updateTpl(eq(77L), eq("血常规（新）"), eq("DISABLED"), any(), eq(OPERATOR));
        verify(mapper).deleteItems(77L);
        verify(mapper)
                .insertItem(eq(77L), anyString(), eq("WBC"), any(), any(), any(), any(), anyInt());
    }

    @Test
    void deleteTemplateBlockedWhileBoundByNames() {
        when(mapper.findTpl(77L)).thenReturn(tplRow(77L, "血常规", "ENABLED", 1L));

        ApiException error = assertThrows(ApiException.class, () -> service.deleteTemplate(77L));

        assertEquals(40901, error.code());
        verify(mapper, never()).deleteTpl(anyLong());
    }

    // ---------------------------------------------------------------- 运行时目录（report.md §2）

    @Test
    void catalogTypesOnlyReturnsEnabledWithReportFields() {
        ReportConfigMapper.TypeRow enabled = typeRow(11L, "LAB", "检验", "RPT_LAB");
        ReportConfigMapper.TypeRow disabled =
                new ReportConfigMapper.TypeRow(
                        12L,
                        "OLD",
                        "旧类型",
                        "RPT_OTHER",
                        "其他资料",
                        true,
                        null,
                        90,
                        "DISABLED",
                        null,
                        0L);
        when(mapper.findTypes()).thenReturn(List.of(enabled, disabled));
        when(forms.findFields("RPT_LAB", null))
                .thenReturn(
                        List.of(
                                new com.crdp.form.field.infra.mapper.FieldRow(
                                        1L,
                                        "RPT_LAB",
                                        "f001",
                                        "report_date",
                                        "报告时间",
                                        "main",
                                        "date",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        true,
                                        null,
                                        null,
                                        null,
                                        false,
                                        "json",
                                        null,
                                        10,
                                        "ENABLED",
                                        null,
                                        null)));

        List<ReportConfigService.CatalogTypeView> views = service.catalogTypes();

        assertEquals(1, views.size(), "停用类型不进运行时目录（A-ATT-003 停用不再可选）");
        assertEquals("LAB", views.get(0).code());
        assertEquals(1, views.get(0).fields().size());
        assertEquals("report_date", views.get(0).fields().get(0).code());
        assertTrue(views.get(0).fields().get(0).required());
    }

    @Test
    void catalogNamesSkipsDisabledTemplateItems() {
        when(mapper.findTypeIdByCode("LAB")).thenReturn(11L);
        ReportConfigMapper.NameRow bound = nameRow(50L, "LAB", "检验", "血常规", 77L, "血常规");
        ReportConfigMapper.NameRow unbound = nameRow(51L, "LAB", "检验", "血糖与糖化血红蛋白", null, null);
        when(mapper.findNames("LAB", true)).thenReturn(List.of(bound, unbound));
        when(mapper.findTpl(77L)).thenReturn(tplRow(77L, "血常规", "DISABLED", 1L));

        List<ReportConfigService.CatalogNameView> views = service.catalogNames("LAB");

        assertEquals(2, views.size());
        assertEquals(0, views.get(0).items().size(), "模板停用后不再带出项目（新报告单）");
        assertEquals(0, views.get(1).items().size());
    }

    @Test
    void catalogNamesUnknownTypeIs40400() {
        when(mapper.findTypeIdByCode("NOPE")).thenReturn(null);
        ApiException error = assertThrows(ApiException.class, () -> service.catalogNames("NOPE"));
        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 字段映射读取（E23-T03 / A-ATT-013）

    /** mappings()：行联查展示名、项目按顺序带出，附带写入 JSON 解析为视图（A 心脏超声预置的读取口径）。 */
    @Test
    void mappingsBringItemsAndParseExtraWrites() {
        when(mapper.findMappings())
                .thenReturn(
                        List.of(
                                new ReportConfigMapper.MappingRow(
                                        5L,
                                        "ECHO_FOLLOWUP",
                                        "心脏超声 → 随访记录（复查超声）",
                                        "ECHO",
                                        "心脏超声",
                                        "FOLLOWUP_RECORD",
                                        "A_ANNUAL_FU",
                                        "年度随访（示例）",
                                        null,
                                        77L,
                                        "心脏超声指标",
                                        "[{\"fieldKey\":\"f236\",\"value\":\"1\"}]",
                                        30,
                                        "ENABLED",
                                        "医院 A 预置（示例）")));
        when(mapper.findMappingItems(5L))
                .thenReturn(
                        List.of(
                                new ReportConfigMapper.MappingItemRow(
                                        1L, 5L, "REPORT_DATE", "f237", "超声检查时间", null, 10),
                                new ReportConfigMapper.MappingItemRow(
                                        2L, 5L, "EF", "f248", "EF 射血分数", null, 20),
                                new ReportConfigMapper.MappingItemRow(
                                        3L,
                                        5L,
                                        "MR_DEGREE",
                                        "f239",
                                        "二尖瓣返流程度",
                                        "[{\"fieldKey\":\"f238\",\"value\":\"1\"}]",
                                        150)));

        List<ReportConfigService.MappingView> views = service.mappings();

        assertEquals(1, views.size());
        ReportConfigService.MappingView view = views.get(0);
        assertEquals("ECHO_FOLLOWUP", view.code());
        assertEquals("ECHO", view.typeCode(), "来源类型 = 心脏超声（rpt_type 联查）");
        assertEquals("FOLLOWUP_RECORD", view.targetType(), "目标 = 随访记录");
        assertNull(view.phase(), "随访目标无时相");
        assertEquals("77", view.indicatorTplId(), "绑定的指标模板（一一对应，A-ATT-013）");
        assertEquals("心脏超声指标", view.indicatorTplName());
        assertEquals(
                List.of(new ReportConfigService.ExtraWriteView("f236", "1")),
                view.extraWrites(),
                "映射级附带写入：复查超声置为是");

        assertEquals(3, view.items().size());
        assertEquals("REPORT_DATE", view.items().get(0).itemCode(), "报告时间伪项目（超声检查时间 = 报告时间）");
        assertEquals("f237", view.items().get(0).targetFieldKey());
        assertEquals("超声检查时间", view.items().get(0).targetFieldLabel(), "目标字段显示名（字段字典联查）");
        assertEquals(0, view.items().get(1).extraWrites().size(), "无附带写入返回空清单");
        assertEquals(
                List.of(new ReportConfigService.ExtraWriteView("f238", "1")),
                view.items().get(2).extraWrites(),
                "项目级附带写入：二尖瓣返流程度写入时同时填有无二尖瓣返流");
    }

    /** 附带写入 JSON 缺省或不可解析时按空清单返回（读取口径容错，不阻断配置核对页）。 */
    @Test
    void mappingsTolerateBlankOrBrokenExtraWrites() {
        when(mapper.findMappings())
                .thenReturn(
                        List.of(
                                new ReportConfigMapper.MappingRow(
                                        6L,
                                        "ECHO_PREOP_LAST",
                                        "心脏超声 → 基线（术前最后一次）",
                                        "ECHO",
                                        "心脏超声",
                                        "BASELINE",
                                        "A_BASELINE",
                                        "基线（示例）",
                                        "PREOP_LAST",
                                        77L,
                                        "心脏超声指标",
                                        null,
                                        10,
                                        "ENABLED",
                                        null),
                                new ReportConfigMapper.MappingRow(
                                        7L,
                                        "BROKEN",
                                        "坏行（示例）",
                                        "ECHO",
                                        "心脏超声",
                                        "BASELINE",
                                        "A_BASELINE",
                                        "基线（示例）",
                                        null,
                                        null,
                                        null,
                                        "不是 JSON",
                                        20,
                                        "ENABLED",
                                        null)));
        when(mapper.findMappingItems(6L)).thenReturn(List.of());
        when(mapper.findMappingItems(7L)).thenReturn(List.of());

        List<ReportConfigService.MappingView> views = service.mappings();

        assertEquals(2, views.size());
        assertEquals(0, views.get(0).extraWrites().size(), "缺省按空清单");
        assertEquals(0, views.get(1).extraWrites().size(), "不可解析按空清单（不抛异常）");
    }

    // ---------------------------------------------------------------- 构造

    private static ReportConfigService.IndicatorItemRequest item(String name, String code) {
        return new ReportConfigService.IndicatorItemRequest(name, code, null, null, null, null, 10);
    }

    private static ReportConfigMapper.TypeRow typeRow(
            long id, String code, String name, String formCode) {
        return new ReportConfigMapper.TypeRow(
                id, code, name, formCode, "报告单", true, null, 10, "ENABLED", null, 0L);
    }

    private static ReportConfigMapper.NameRow nameRow(
            long id, String typeCode, String typeName, String name, Long tplId, String tplName) {
        return new ReportConfigMapper.NameRow(
                id, typeCode, typeName, name, tplId, tplName, null, 10, "ENABLED");
    }

    private static ReportConfigMapper.TplRow tplRow(
            long id, String name, String status, long bound) {
        return new ReportConfigMapper.TplRow(id, name, status, null, 0L, bound);
    }

    private static ReportConfigMapper.ItemRow itemRow(
            long tplId, String name, String code, String unit, int sort) {
        return new ReportConfigMapper.ItemRow(1L, tplId, name, code, unit, null, null, null, sort);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                OPERATOR,
                "admin.demo",
                "管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
