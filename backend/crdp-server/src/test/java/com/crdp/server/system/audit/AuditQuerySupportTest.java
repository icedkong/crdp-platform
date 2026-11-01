package com.crdp.server.system.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** E08-T04 纯逻辑单测：查询条件校验（时间窗 ≤ 3 个月、分页边界）、CSV 水印与转义、 类型映射与条件摘要。不依赖数据库。 */
class AuditQuerySupportTest {

    @Test
    void parsesIsoOffsetTimeIntoBeijingLocalDateTime() {
        LocalDateTime time = AuditQuerySupport.parseTime("2027-01-01T00:00:00+00:00", "开始时间");
        assertEquals(LocalDateTime.of(2027, 1, 1, 8, 0), time);
    }

    @Test
    void rejectsBlankOrMalformedTimeWith40001() {
        ApiException blank =
                assertThrows(ApiException.class, () -> AuditQuerySupport.parseTime(" ", "开始时间"));
        assertEquals(40001, blank.code());
        ApiException malformed =
                assertThrows(
                        ApiException.class,
                        () -> AuditQuerySupport.parseTime("2027-01-01", "开始时间"));
        assertEquals(40001, malformed.code());
    }

    @Test
    void rejectsEndBeforeStart() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                AuditQuerySupport.requireSpan(
                                        LocalDateTime.of(2027, 1, 2, 0, 0),
                                        LocalDateTime.of(2027, 1, 1, 0, 0)));
        assertEquals(40001, e.code());
    }

    @Test
    void rejectsSpanOverThreeMonths() {
        ApiException e =
                assertThrows(
                        ApiException.class,
                        () ->
                                AuditQuerySupport.requireSpan(
                                        LocalDateTime.of(2027, 1, 1, 0, 0),
                                        LocalDateTime.of(2027, 4, 15, 0, 0)));
        assertEquals(40001, e.code());
        assertTrue(e.getMessage().contains("3 个月"));
    }

    @Test
    void acceptsExactlyThreeMonths() {
        AuditQuerySupport.requireSpan(
                LocalDateTime.of(2027, 1, 1, 0, 0), LocalDateTime.of(2027, 4, 1, 0, 0));
    }

    @Test
    void pageDefaultsAndBounds() {
        assertEquals(1, AuditQuerySupport.pageNo(null));
        assertEquals(20, AuditQuerySupport.pageSize(null));
        assertEquals(100, AuditQuerySupport.pageSize(100));
        assertThrows(ApiException.class, () -> AuditQuerySupport.pageNo(0));
        assertThrows(ApiException.class, () -> AuditQuerySupport.pageSize(101));
    }

    @Test
    void formatsBeijingTimeWithOffset() {
        String text = AuditQuerySupport.format(LocalDateTime.of(2027, 1, 1, 9, 30));
        assertNotNull(text);
        assertTrue(text.startsWith("2027-01-01T09:30:00+08:00"), text);
    }

    @Test
    void tenantAndPlatformTypeMappingRejectsUnknown() {
        assertEquals(AuditTables.Kind.OPERATION, AuditTables.tenantKind("operation"));
        assertEquals(AuditTables.Kind.LOGIN, AuditTables.tenantKind("login"));
        assertEquals(AuditTables.Kind.DATA_ACCESS, AuditTables.tenantKind("data-access"));
        assertEquals(AuditTables.Kind.OPERATION, AuditTables.platformKind("control-plane"));
        assertEquals(AuditTables.Kind.PLATFORM_ACCESS, AuditTables.platformKind("view-rule"));
        ApiException e =
                assertThrows(ApiException.class, () -> AuditTables.tenantKind("platform-access"));
        assertEquals(40001, e.code());
        assertThrows(ApiException.class, () -> AuditTables.platformKind("tenant"));
    }

    @Test
    void conditionSummaryKeepsOnlyProvidedFilters() {
        String summary =
                HospitalAuditService.conditionSummary(
                        new AuditViews.SearchRequest(
                                "2027-01-01T00:00:00+08:00",
                                "2027-02-01T00:00:00+08:00",
                                null,
                                "nurse.demo",
                                "system",
                                null,
                                null,
                                null,
                                "SUCCESS",
                                null,
                                null,
                                null,
                                null,
                                null));
        assertEquals(
                "开始=2027-01-01T00:00:00+08:00，结束=2027-02-01T00:00:00+08:00，账号=nurse.demo，模块=system，结果=SUCCESS",
                summary);
        assertEquals(
                "全部（默认时间窗）",
                HospitalAuditService.conditionSummary(
                        new AuditViews.SearchRequest(
                                null, null, null, null, null, null, null, null, null, null, null,
                                null, null, null)));
    }

    @Test
    void csvHasBomWatermarkColumnAndEscaping() {
        String watermark =
                AuditExportCsv.watermark(
                        "张医生（示例）", "zhang.demo", "虚构示例医院", LocalDateTime.of(2027, 1, 1, 9, 5));
        assertEquals("张医生（示例）（zhang.demo）· 虚构示例医院 · 2027-01-01 09:05", watermark);

        byte[] body =
                AuditExportCsv.render(
                        List.of("id", "detail"),
                        List.of(
                                Map.of("id", 1, "detail", "含,逗号"),
                                Map.of("id", 2, "detail", "含\"引号"),
                                Map.of("id", 3)),
                        watermark,
                        "审计日志导出（操作日志）· 条件：全部");
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFF"), "CSV 应带 UTF-8 BOM（Excel 中文兼容）");
        assertTrue(text.contains("审计日志导出（操作日志）"), "首行应为导出说明");
        assertTrue(
                text.lines().skip(1).findFirst().orElse("").startsWith("水印,id,detail"), "列头前应有水印列");
        assertTrue(text.contains("\"含,逗号\""));
        assertTrue(text.contains("\"含\"\"引号\""));
        // 每个数据行都以水印开头（PLT-AUD-007 文件加水印）
        assertTrue(text.lines().anyMatch(line -> line.startsWith(watermark + ",1,")));
    }

    @Test
    void csvNotesTruncationAtMaxRows() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < AuditExportCsv.MAX_ROWS; i++) {
            rows.add(Map.of("id", i));
        }
        byte[] body = AuditExportCsv.render(List.of("id"), rows, "wm", "note");
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("已达单次导出上限 " + AuditExportCsv.MAX_ROWS + " 行"));
    }
}
