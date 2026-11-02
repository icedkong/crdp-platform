package com.crdp.form.field.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.crdp.form.field.infra.mapper.FieldRow;
import com.crdp.form.field.infra.mapper.FormFieldMapper;
import com.crdp.form.field.infra.mapper.FormRow;
import com.crdp.form.field.infra.mapper.OptionRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E11-T09 / A-FLD-013、C-FLD-008：字段字典 Excel 批量导入单元测试。
 *
 * <p>验收原文：产品可用 Excel 批量导入字段定义。锁定：模板表头与示例（虚构数据）回读一致；新增字段全列落库（选项集缺省时自动建集并落选项 编码 / 显示名 / 序数 / 互斥 /
 * 需补充说明 / 别名）；已有字段按稳定键更新且键 / 编码 / 类型 / 存储方式 / 敏感标记不可改（A-FLD-008）； 单行失败不影响其他行； 派生公式与条件 DSL 解析为
 * fe_field 的 JSON 定义（派生参数可前向引用同文件字段）；每次导入写 FIELD_DICT_IMPORT 作业审计与 fe_field_dict_log
 * 变更记录（A-FLD-009）；文件级错误（非 xlsx / 表头不符 / 无数据行 / 超行数上限）40001、无医院会话 40100。 全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class FieldImportServiceTest {

    private final FormFieldMapper mapper = mock(FormFieldMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final FieldImportService service = new FieldImportService(mapper, audit);

    private static final String CATEGORIES =
            "[{\"key\":\"basic_info\",\"label\":\"病例基本信息\",\"sortNo\":10},"
                    + "{\"key\":\"surgery\",\"label\":\"手术信息\",\"sortNo\":20}]";

    private static final FormRow BASELINE =
            new FormRow(1L, "A_BASELINE", "基线档案（示例）", "BASELINE", 1, CATEGORIES, "ENABLED");

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
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

    // ---------------------------------------------------------------- 库内快照形态

    /** fe_field 种子形态：出生日期（f003）、手术日期（f020）与吸烟史（f010，选项集 yesno 0=否 / 1=是）。 */
    private static FieldRow field(
            String key, String code, String label, String category, String type) {
        return new FieldRow(
                (long) key.hashCode(),
                "A_BASELINE",
                key,
                code,
                label,
                category,
                type,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                false,
                "json",
                null,
                10,
                "ENABLED",
                null,
                LocalDateTime.of(2027, 2, 15, 9, 0));
    }

    private static FieldRow smokingRow() {
        return new FieldRow(
                10L,
                "A_BASELINE",
                "f010",
                "smoking_history",
                "吸烟史",
                "basic_info",
                "radio",
                null,
                null,
                null,
                null,
                "yesno",
                true,
                null,
                null,
                null,
                false,
                "json",
                "示例帮助",
                40,
                "ENABLED",
                null,
                LocalDateTime.of(2027, 2, 15, 9, 0));
    }

    private static OptionRow option(int code, String label) {
        return new OptionRow(
                (long) code,
                "yesno",
                code,
                label,
                null,
                false,
                false,
                null,
                code * 10 + 10,
                "ENABLED",
                null,
                LocalDateTime.of(2027, 2, 15, 9, 0));
    }

    /** 空租户：只有示例基线表单，没有字段与选项。 */
    private void snapshotEmptyTenant() {
        lenient().when(mapper.findForms()).thenReturn(List.of(BASELINE));
        lenient().when(mapper.findFields("A_BASELINE", null)).thenReturn(List.of());
        lenient().when(mapper.findOptionSet(anyString())).thenReturn(null);
        lenient().when(mapper.findOptions(anyString())).thenReturn(List.of());
    }

    /** 库内已有出生日期（f003）、身高（f011）、手术日期（f020）与吸烟史（f010，选项集 yesno）。 */
    private void snapshotWithSeeds() {
        lenient().when(mapper.findForms()).thenReturn(List.of(BASELINE));
        lenient()
                .when(mapper.findFields("A_BASELINE", null))
                .thenReturn(
                        List.of(
                                field("f003", "birth_date", "出生日期", "basic_info", "date"),
                                field("f011", "height_cm", "身高", "basic_info", "number"),
                                field("f020", "surgery_date", "手术日期", "surgery", "date"),
                                smokingRow()));
        lenient()
                .when(mapper.findOptionSet("yesno"))
                .thenReturn(new FormFieldMapper.OptionSetRow(5L, "yesno", "是否（示例）"));
        lenient()
                .when(mapper.findOptions("yesno"))
                .thenReturn(List.of(option(0, "否"), option(1, "是")));
        lenient()
                .when(mapper.findOptionSet(argThat(code -> code != null && !"yesno".equals(code))))
                .thenReturn(null);
        lenient()
                .when(mapper.findOptions(argThat(code -> code != null && !"yesno".equals(code))))
                .thenReturn(List.of());
    }

    // ---------------------------------------------------------------- 构造导入文件

    /** 按「表头名 → 值」拼一行，未提到的列留空。 */
    private static List<String> row(String... pairs) {
        List<String> cells =
                new ArrayList<>(Collections.nCopies(FieldImportService.HEADERS.size(), ""));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            int index = FieldImportService.HEADERS.indexOf(pairs[i]);
            if (index < 0) {
                throw new IllegalArgumentException("测试写了不存在的表头：" + pairs[i]);
            }
            cells.set(index, pairs[i + 1]);
        }
        return cells;
    }

    private static InputStream xlsx(List<List<String>> rows) {
        return new ByteArrayInputStream(SimpleXlsx.write(FieldImportService.HEADERS, rows));
    }

    private static ApiException assertApiEx(ThrowingSupplier supplier) {
        try {
            supplier.get();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("应抛 ApiException");
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get();
    }

    // ---------------------------------------------------------------- 模板

    /** 模板可下载可回读：表头逐列一致，示例行全部为虚构数据（带「示例」标记）。 */
    @Test
    void templateCarriesHeadersAndFictionalExamples() {
        byte[] body = service.template();

        List<List<String>> grid = SimpleXlsx.read(new ByteArrayInputStream(body));
        assertEquals(FieldImportService.HEADERS, grid.get(0), "表头与导入逐列精确匹配");
        assertTrue(grid.size() > 1, "模板带示例行");
        for (int i = 1; i < grid.size(); i++) {
            assertTrue(
                    grid.get(i).stream().anyMatch(cell -> cell.contains("示例")),
                    "示例行 " + (i + 1) + " 全部为虚构数据");
        }
    }

    // ---------------------------------------------------------------- 新增

    /** 新增字段：文本、数值（单位 / 小数位 / 范围）、单选（引用已有选项集并补选项）、派生（A-FLD-013 手术年龄）。 */
    @Test
    void importsNewFieldsWithFullMetadata() {
        snapshotWithSeeds();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f101",
                                                "字段编码", "death_cause",
                                                "显示名", "死亡原因（示例）",
                                                "分类键", "basic_info",
                                                "类型", "textarea",
                                                "帮助说明", "示例帮助",
                                                "排序", "70"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f102",
                                                "字段编码", "example_ef",
                                                "显示名", "EF（示例）",
                                                "分类键", "basic_info",
                                                "类型", "number",
                                                "单位", "%",
                                                "小数位", "1",
                                                "硬范围下限", "0",
                                                "硬范围上限", "100",
                                                "提示范围下限", "20",
                                                "提示范围上限", "70",
                                                "必填", "是",
                                                "排序", "80"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f110",
                                                "字段编码", "example_select",
                                                "显示名", "示例单选（示例）",
                                                "分类键", "surgery",
                                                "类型", "select",
                                                "选项集编码", "yesno",
                                                "选项", "2=既往（示例）",
                                                "排序", "90"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f032",
                                                "字段编码", "surgery_age",
                                                "显示名", "手术年龄（示例）",
                                                "分类键", "surgery",
                                                "类型", "derived",
                                                "派生公式", "AGE_YEARS(f003,f020)",
                                                "帮助说明", "手术日期 − 出生日期，取周岁（A-FLD-013）",
                                                "排序", "12"))));

        assertEquals(0, report.failed(), "全部成功：" + report.rows());
        assertEquals(4, report.created());
        assertEquals(0, report.updated());
        assertTrue(
                report.rows().stream().allMatch(item -> "CREATED".equals(item.action())),
                "逐行 CREATED：" + report.rows());

        verify(mapper)
                .insertField(
                        eq("A_BASELINE"),
                        eq("f101"),
                        eq("death_cause"),
                        eq("死亡原因（示例）"),
                        eq("basic_info"),
                        eq("textarea"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        eq("json"),
                        eq("示例帮助"),
                        eq(70),
                        eq("ENABLED"),
                        eq(7L));
        verify(mapper)
                .insertField(
                        eq("A_BASELINE"),
                        eq("f102"),
                        eq("example_ef"),
                        eq("EF（示例）"),
                        eq("basic_info"),
                        eq("number"),
                        eq("%"),
                        eq(1),
                        argThat(
                                json ->
                                        json != null
                                                && json.contains("\"min\"")
                                                && json.contains("max")),
                        argThat(json -> json != null && json.contains("20")),
                        isNull(),
                        eq(true),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        eq("json"),
                        any(),
                        eq(80),
                        eq("ENABLED"),
                        eq(7L));
        verify(mapper)
                .insertField(
                        eq("A_BASELINE"),
                        eq("f032"),
                        eq("surgery_age"),
                        eq("手术年龄（示例）"),
                        eq("surgery"),
                        eq("derived"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        isNull(),
                        isNull(),
                        argThat(json -> json != null && json.contains("\"AGE_YEARS\"")),
                        eq(false),
                        eq("json"),
                        any(),
                        eq(12),
                        eq("ENABLED"),
                        eq(7L));
        // 已有选项集只补新选项（编码 2，排序接在集内最大排序号之后），不动 0 / 1
        verify(mapper)
                .insertOptionFull(
                        eq("yesno"),
                        eq(2),
                        eq("既往（示例）"),
                        isNull(),
                        eq(false),
                        eq(false),
                        isNull(),
                        eq(30),
                        eq("ENABLED"),
                        eq(7L));
        verify(mapper, never())
                .updateOptionForImport(
                        anyString(),
                        anyInt(),
                        anyString(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        any(),
                        anyLong());
    }

    /** 新选项集自动创建：选项带序数 / 互斥 / 需补充说明 / 别名（C-FLD-008 囊袋检查形态）；第二个字段复用不重建。 */
    @Test
    void createsMissingOptionSetWithOptionsAndFlags() {
        snapshotEmptyTenant();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f201",
                                                "字段编码", "pocket_check",
                                                "显示名", "囊袋/伤口检查（示例）",
                                                "分类键", "surgery",
                                                "类型", "multiselect",
                                                "选项集编码", "pocket_status",
                                                "选项", "0=愈合良好（示例）,1=红肿（示例）,2=渗液（示例）,6=其他（示例）",
                                                "需补充说明选项编码", "6",
                                                "选项别名", "0=愈合;切口愈合",
                                                "排序", "30"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f202",
                                                "字段编码", "pocket_check_2",
                                                "显示名", "囊袋复查（示例）",
                                                "分类键", "surgery",
                                                "类型", "multiselect",
                                                "选项集编码", "pocket_status",
                                                "排序", "40"))));

        assertEquals(0, report.failed(), "全部成功：" + report.rows());
        verify(mapper, times(1)).insertOptionSet(eq("pocket_status"), anyString());
        verify(mapper)
                .insertOptionFull(
                        eq("pocket_status"),
                        eq(0),
                        eq("愈合良好（示例）"),
                        isNull(),
                        eq(false),
                        eq(false),
                        eq("愈合;切口愈合"),
                        eq(10),
                        eq("ENABLED"),
                        eq(7L));
        verify(mapper)
                .insertOptionFull(
                        eq("pocket_status"),
                        eq(6),
                        eq("其他（示例）"),
                        isNull(),
                        eq(false),
                        eq(true),
                        isNull(),
                        eq(40),
                        eq("ENABLED"),
                        eq(7L));
        // 复用本批创建的选项集且未再给选项：不重复建集、不重复插选项（共 4 个选项）
        verify(mapper, times(4))
                .insertOptionFull(
                        anyString(),
                        anyInt(),
                        anyString(),
                        any(),
                        anyBoolean(),
                        anyBoolean(),
                        any(),
                        anyInt(),
                        anyString(),
                        anyLong());
    }

    /** 选项序数（「返流加重 ≥ 2 级」按序数比较）与互斥（「无」）同样可录入。 */
    @Test
    void importsOptionOrdinalsAndExclusiveFlags() {
        snapshotEmptyTenant();

        service.importFields(
                xlsx(
                        List.of(
                                row(
                                        "表单编码", "A_BASELINE",
                                        "稳定键", "f203",
                                        "字段编码", "mr_grade",
                                        "显示名", "二尖瓣返流加重（示例）",
                                        "分类键", "surgery",
                                        "类型", "select",
                                        "选项集编码", "mr_change",
                                        "选项", "0=无加重（示例）,1=加重 1 级（示例）,2=加重 2 级（示例）",
                                        "选项序数", "1=1,2=2",
                                        "互斥选项编码", "0"))));

        verify(mapper)
                .insertOptionFull(
                        eq("mr_change"),
                        eq(1),
                        eq("加重 1 级（示例）"),
                        eq(1),
                        eq(false),
                        eq(false),
                        isNull(),
                        eq(20),
                        eq("ENABLED"),
                        eq(7L));
        verify(mapper)
                .insertOptionFull(
                        eq("mr_change"),
                        eq(0),
                        eq("无加重（示例）"),
                        isNull(),
                        eq(true),
                        eq(false),
                        isNull(),
                        eq(10),
                        eq("ENABLED"),
                        eq(7L));
    }

    // ---------------------------------------------------------------- 更新

    /** 更新：按表单 + 稳定键命中即全量覆盖可维护与实施列；已有选项只改显示名（不删、不改编码）。 */
    @Test
    void updatesExistingFieldOverwritingMaintainableColumns() {
        snapshotWithSeeds();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f010",
                                                "字段编码", "smoking_history",
                                                "显示名", "吸烟史（修订）",
                                                "分类键", "basic_info",
                                                "类型", "radio",
                                                "必填", "否",
                                                "选项集编码", "yesno",
                                                "选项", "0=从不（示例）,1=吸烟（示例）",
                                                "条件必填", "f020!=2020-01-01",
                                                "帮助说明", "修订后的帮助（示例）",
                                                "排序", "45",
                                                "状态", "DISABLED"))));

        assertEquals(1, report.updated());
        assertEquals(0, report.failed());
        verify(mapper)
                .updateFieldForImport(
                        eq("A_BASELINE"),
                        eq("f010"),
                        eq("吸烟史（修订）"),
                        eq("basic_info"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq("yesno"),
                        eq(false),
                        isNull(),
                        argThat(
                                json ->
                                        json != null
                                                && json.contains("\"f020\"")
                                                && json.contains("\"ne\"")),
                        isNull(),
                        eq("修订后的帮助（示例）"),
                        eq(45),
                        eq("DISABLED"),
                        eq(7L));
        // 已有选项只改显示名（0 → 从不（示例）），不删、不改编码
        verify(mapper)
                .updateOptionForImport(
                        eq("yesno"),
                        eq(0),
                        eq("从不（示例）"),
                        isNull(),
                        eq(false),
                        eq(false),
                        isNull(),
                        eq(7L));
        verify(mapper)
                .updateOptionForImport(
                        eq("yesno"),
                        eq(1),
                        eq("吸烟（示例）"),
                        isNull(),
                        eq(false),
                        eq(false),
                        isNull(),
                        eq(7L));
        verify(mapper, never())
                .insertField(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        any(),
                        anyInt(),
                        anyString(),
                        anyLong());
    }

    /** 稳定键命中但字段编码 / 类型 / 敏感标记 / 存储方式与库内不一致 → 该行失败（A-FLD-008 不可改），其余行照常。 */
    @Test
    void rejectsImmutableColumnChangesOnUpdate() {
        snapshotWithSeeds();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f003",
                                                "字段编码", "birth_date_2",
                                                "显示名", "出生日期",
                                                "分类键", "basic_info",
                                                "类型", "date"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f010",
                                                "字段编码", "smoking_history",
                                                "显示名", "吸烟史",
                                                "分类键", "basic_info",
                                                "类型", "select",
                                                "选项集编码", "yesno"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f020",
                                                "字段编码", "surgery_date",
                                                "显示名", "手术日期",
                                                "分类键", "surgery",
                                                "类型", "date",
                                                "敏感", "是"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f011",
                                                "字段编码", "height_cm",
                                                "显示名", "身高",
                                                "分类键", "basic_info",
                                                "类型", "number",
                                                "存储方式", "encrypted"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f300",
                                                "字段编码", "ok_field",
                                                "显示名", "正常行（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text"))));

        assertEquals(4, report.failed(), "四行不可改列冲突：" + report.rows());
        assertEquals(1, report.created(), "无关的好行照常导入");
        assertTrue(
                report.rows().stream()
                        .filter(item -> "FAILED".equals(item.action()))
                        .allMatch(item -> item.message() != null && item.message().contains("不可改")),
                "失败原因点明不可改：" + report.rows());
        verify(mapper, never())
                .updateFieldForImport(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyInt(),
                        anyString(),
                        anyLong());
    }

    // ---------------------------------------------------------------- 条件与派生 DSL

    /** 派生参数可引用同文件后面才定义的字段；未知函数 / 参数个数不符 / 引用不存在的字段 → 行失败。 */
    @Test
    void validatesDerivedFormulaAndForwardReferences() {
        snapshotEmptyTenant();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        // 前向引用：f401 的公式引用后面才定义的 f402 / f403
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f401",
                                                "字段编码", "bridge_total",
                                                "显示名", "桥血管数量（示例）",
                                                "分类键", "surgery",
                                                "类型", "derived",
                                                "派生公式", "SUM(f402,f403)"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f402",
                                                "字段编码", "arterial_grafts",
                                                "显示名", "动脉桥数量（示例）",
                                                "分类键", "surgery",
                                                "类型", "number"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f403",
                                                "字段编码", "venous_grafts",
                                                "显示名", "静脉桥数量（示例）",
                                                "分类键", "surgery",
                                                "类型", "number"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f404",
                                                "字段编码", "bad_fn",
                                                "显示名", "坏函数（示例）",
                                                "分类键", "surgery",
                                                "类型", "derived",
                                                "派生公式", "UNKNOWN_FN(f402)"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f405",
                                                "字段编码", "bad_arity",
                                                "显示名", "坏参数（示例）",
                                                "分类键", "surgery",
                                                "类型", "derived",
                                                "派生公式", "BMI(f402)"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f406",
                                                "字段编码", "bad_ref",
                                                "显示名", "坏引用（示例）",
                                                "分类键", "surgery",
                                                "类型", "derived",
                                                "派生公式", "AGE_YEARS(f999)"))));

        assertEquals(3, report.failed(), "未知函数 / 参数个数 / 引用不存在各一行失败：" + report.rows());
        assertEquals(3, report.created(), "前向引用的两个来源与派生本身成功");
        verify(mapper)
                .insertField(
                        eq("A_BASELINE"),
                        eq("f401"),
                        eq("bridge_total"),
                        eq("桥血管数量（示例）"),
                        eq("surgery"),
                        eq("derived"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        isNull(),
                        isNull(),
                        argThat(
                                json ->
                                        json != null
                                                && json.contains("\"SUM\"")
                                                && json.contains("\"f402\"")
                                                && json.contains("\"f403\"")),
                        eq(false),
                        eq("json"),
                        any(),
                        anyInt(),
                        eq("ENABLED"),
                        eq(7L));
    }

    /** 条件 DSL：`字段键=值`（多个用逗号分隔 = 全部成立），比较符 = != > >= < <=；引用不存在的字段 → 行失败。 */
    @Test
    void parsesConditionDslAndRejectsUnknownFieldReference() {
        snapshotWithSeeds();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f410",
                                                "字段编码", "death_date",
                                                "显示名", "死亡日期（示例）",
                                                "分类键", "basic_info",
                                                "类型", "date",
                                                "条件显示", "f010=1",
                                                "条件必填", "f020>=2020-01-01"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f411",
                                                "字段编码", "bad_condition",
                                                "显示名", "坏条件（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text",
                                                "条件显示", "f999=1"))));

        assertEquals(1, report.failed());
        assertTrue(
                report.rows().stream()
                        .anyMatch(
                                item ->
                                        "FAILED".equals(item.action())
                                                && item.message().contains("f999")),
                "失败原因点明未知字段：" + report.rows());
        verify(mapper)
                .insertField(
                        eq("A_BASELINE"),
                        eq("f410"),
                        eq("death_date"),
                        eq("死亡日期（示例）"),
                        eq("basic_info"),
                        eq("date"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        argThat(
                                json ->
                                        json != null
                                                && json.contains("\"all\"")
                                                && json.contains("\"eq\"")
                                                && json.contains("\"f010\"")),
                        argThat(json -> json != null && json.contains("\"gte\"")),
                        isNull(),
                        eq(false),
                        eq("json"),
                        any(),
                        anyInt(),
                        eq("ENABLED"),
                        eq(7L));
    }

    // ---------------------------------------------------------------- 逐行失败与文件级错误

    /** 单行失败不影响其他行：表单不存在 / 分类不在表单 / 类型非法 / 选项型缺选项集 / 文件内稳定键重复 / 字段编码被占用。 */
    @Test
    void rowFailuresDoNotBlockOtherRows() {
        snapshotWithSeeds();

        FieldImportService.ImportReport report =
                service.importFields(
                        xlsx(
                                List.of(
                                        row(
                                                "表单编码", "NOPE",
                                                "稳定键", "f501",
                                                "字段编码", "a",
                                                "显示名", "表单不存在（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f502",
                                                "字段编码", "b",
                                                "显示名", "分类不存在（示例）",
                                                "分类键", "no_such_category",
                                                "类型", "text"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f503",
                                                "字段编码", "c",
                                                "显示名", "类型非法（示例）",
                                                "分类键", "basic_info",
                                                "类型", "int"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f504",
                                                "字段编码", "d",
                                                "显示名", "缺选项集（示例）",
                                                "分类键", "basic_info",
                                                "类型", "select"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f505",
                                                "字段编码", "e",
                                                "显示名", "重复键一（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f505",
                                                "字段编码", "e2",
                                                "显示名", "重复键二（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f506",
                                                "字段编码", "smoking_history",
                                                "显示名", "编码被占用（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text"),
                                        row(
                                                "表单编码", "A_BASELINE",
                                                "稳定键", "f507",
                                                "字段编码", "good_field",
                                                "显示名", "正常行（示例）",
                                                "分类键", "basic_info",
                                                "类型", "text"))));

        assertEquals(7, report.failed(), "七行各失败一次：" + report.rows());
        assertEquals(1, report.created());
        assertEquals(8, report.total());
        verify(mapper)
                .insertField(
                        eq("A_BASELINE"),
                        eq("f507"),
                        eq("good_field"),
                        eq("正常行（示例）"),
                        eq("basic_info"),
                        eq("text"),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(false),
                        eq("json"),
                        any(),
                        anyInt(),
                        eq("ENABLED"),
                        eq(7L));
    }

    /** 文件级错误 40001：非 xlsx、表头不符、无数据行、超过行数上限；无医院会话 40100。 */
    @Test
    void rejectsBrokenFilesAndMissingSession() {
        snapshotEmptyTenant();

        ApiException notXlsx =
                assertApiEx(
                        () -> service.importFields(new ByteArrayInputStream("不是表格".getBytes())));
        assertEquals(40001, notXlsx.code());

        ApiException badHeader =
                assertApiEx(
                        () ->
                                service.importFields(
                                        new ByteArrayInputStream(
                                                SimpleXlsx.write(
                                                        List.of("错误表头"), List.of(List.of("值"))))));
        assertEquals(40001, badHeader.code());
        assertTrue(badHeader.getMessage().contains("模板"), "提示下载模板：" + badHeader.getMessage());

        ApiException noRows = assertApiEx(() -> service.importFields(xlsx(List.of())));
        assertEquals(40001, noRows.code());

        List<List<String>> tooMany = new ArrayList<>();
        for (int i = 0; i <= FieldImportService.MAX_ROWS; i++) {
            tooMany.add(
                    row(
                            "表单编码",
                            "A_BASELINE",
                            "稳定键",
                            "f" + i,
                            "字段编码",
                            "c" + i,
                            "显示名",
                            "示例",
                            "分类键",
                            "basic_info",
                            "类型",
                            "text"));
        }
        ApiException overLimit = assertApiEx(() -> service.importFields(xlsx(tooMany)));
        assertEquals(40001, overLimit.code());
        assertTrue(overLimit.getMessage().contains(String.valueOf(FieldImportService.MAX_ROWS)));

        SecurityContextHolder.clearContext();
        ApiException noSession =
                assertApiEx(
                        () ->
                                service.importFields(
                                        xlsx(
                                                List.of(
                                                        row(
                                                                "表单编码", "A_BASELINE",
                                                                "稳定键", "f601",
                                                                "字段编码", "x",
                                                                "显示名", "示例",
                                                                "分类键", "basic_info",
                                                                "类型", "text")))));
        assertEquals(40100, noSession.code());
    }

    // ---------------------------------------------------------------- 审计与变更记录（A-FLD-009）

    /** 导入写一条 FIELD_DICT_IMPORT 作业级审计；每个新建 / 更新的字段各写一条 fe_field_dict_log（前后值 + 操作人）。 */
    @Test
    void writesJobAuditAndFieldDictLogs() {
        snapshotWithSeeds();

        service.importFields(
                xlsx(
                        List.of(
                                row(
                                        "表单编码", "A_BASELINE",
                                        "稳定键", "f600",
                                        "字段编码", "new_field",
                                        "显示名", "新字段（示例）",
                                        "分类键", "basic_info",
                                        "类型", "text"),
                                row(
                                        "表单编码", "A_BASELINE",
                                        "稳定键", "f010",
                                        "字段编码", "smoking_history",
                                        "显示名", "吸烟史（导入修订）",
                                        "分类键", "basic_info",
                                        "类型", "radio",
                                        "选项集编码", "yesno",
                                        "状态", "ENABLED"))));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("FIELD_DICT_IMPORT", entry.action());
        assertEquals("FE_FIELD", entry.objectType());
        assertNotNull(entry.objectId());
        assertTrue(entry.description().contains("共 2 行"), "作业级摘要：" + entry.description());
        assertTrue(entry.description().contains("新增 1"));
        assertTrue(entry.description().contains("更新 1"));

        verify(mapper)
                .insertFieldDictLog(
                        eq("A_BASELINE"),
                        eq("f600"),
                        eq("FIELD_CREATE"),
                        isNull(),
                        argThat(after -> after != null && after.contains("新字段（示例）")),
                        eq(7L));
        verify(mapper)
                .insertFieldDictLog(
                        eq("A_BASELINE"),
                        eq("f010"),
                        eq("FIELD_UPDATE"),
                        argThat(before -> before != null && before.contains("吸烟史")),
                        argThat(after -> after != null && after.contains("吸烟史（导入修订）")),
                        eq(7L));
    }
}
