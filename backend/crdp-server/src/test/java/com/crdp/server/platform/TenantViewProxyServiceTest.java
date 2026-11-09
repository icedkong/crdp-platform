package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import com.crdp.server.platform.TenantViewProxyService.ViewCapabilities;
import com.crdp.server.platform.TenantViewProxyService.WriteClass;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E20-T06 / PLT-VIEW-013、017、018（验收「默认关闭，开启需授权并记审计」）单元测试：写接口分类（配置类 / 业务数据 /
 * 不放行）、代为操作原因校验（二次确认后必填）、代为配置资源范围并集、能力判定纯函数、前后值快照截断。
 */
class TenantViewProxyServiceTest {

    // ---------------------------------------------------------------- 分类（classify，纯函数）

    @Test
    void baseModuleWritesAreConfigClass() {
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("base", "tenant:user:edit"),
                "用户编辑属配置类（PLT-VIEW-013 配置类资源）");
        assertEquals(
                WriteClass.CONFIG, TenantViewProxyService.classify("base", "tenant:role:edit"));
        assertEquals(
                WriteClass.CONFIG, TenantViewProxyService.classify("base", "tenant:dict:edit"));
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("base", "tenant:session:kick"),
                "在线会话强制下线属配置类");
    }

    @Test
    void followupConfigActionsAndFieldDictionaryAreConfigClass() {
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("followup", "followup:abnormal:config"),
                "随访异常规则配置属配置类");
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("followup", "followup:notice:config"));
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("followup", "followup:scan:config"),
                "报告单与电子化资料配置属配置类");
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("followup", "followup:field:edit"),
                "表单字段字典维护属配置类");
    }

    @Test
    void followupBusinessWritesAreBusinessClass() {
        assertEquals(
                WriteClass.BUSINESS,
                TenantViewProxyService.classify("followup", "followup:patient:edit"));
        assertEquals(
                WriteClass.BUSINESS,
                TenantViewProxyService.classify("followup", "followup:record:edit"));
        assertEquals(
                WriteClass.BUSINESS,
                TenantViewProxyService.classify("followup", "followup:task:assign"));
        assertEquals(
                WriteClass.BUSINESS,
                TenantViewProxyService.classify("followup", "followup:scan:upload"),
                "电子化资料上传是业务数据写（配置动作才是配置类）");
    }

    @Test
    void plainAndResetActionsAreNeverReleased() {
        assertEquals(
                WriteClass.NEVER,
                TenantViewProxyService.classify("followup", "followup:patient:plain"),
                "明文查看对视角一律不放行（平台铁律，AGENTS.md §5.3）");
        assertEquals(
                WriteClass.NEVER,
                TenantViewProxyService.classify("base", "tenant:user:reset"),
                "医院侧重置口不放行——重置只走 PLT-VIEW-017 专用代理口");
        assertEquals(
                WriteClass.NEVER,
                TenantViewProxyService.classify("followup", null),
                "未绑码接口 fail closed");
    }

    @Test
    void futureModulesFallToBusinessClass() {
        assertEquals(
                WriteClass.BUSINESS,
                TenantViewProxyService.classify("rct", "rct:crf:edit"),
                "后续模块数据按业务数据同一规则（platform-spec §8），默认只读规则 fail closed");
    }

    @Test
    void multiCandidateCodeClassifiesByFirst() {
        assertEquals(
                WriteClass.CONFIG,
                TenantViewProxyService.classify("base", "tenant:user:view,tenant:user:edit"));
    }

    // ---------------------------------------------------------------- 原因校验（requireReason，纯函数）

    @Test
    void headerReasonIsPercentDecoded() {
        assertEquals(
                "配置核对（示例）",
                TenantViewProxyService.requireHeaderReason(
                        java.net.URLEncoder.encode(
                                "配置核对（示例）", java.nio.charset.StandardCharsets.UTF_8)),
                "头值按 encodeURIComponent 百分号编码传输（HTTP 头不承载非 ASCII）");
        assertEquals("fix config", TenantViewProxyService.requireHeaderReason("fix%20config"));
        assertThrows(
                ApiException.class,
                () -> TenantViewProxyService.requireHeaderReason("%zz"),
                "坏编码按原因缺失处理");
        assertThrows(ApiException.class, () -> TenantViewProxyService.requireHeaderReason(null));
    }

    @Test
    void reasonRequiredAndBounded() {
        assertEquals("配置核对（示例）", TenantViewProxyService.requireReason(" 配置核对（示例） "));
        assertThrows(ApiException.class, () -> TenantViewProxyService.requireReason(null));
        assertThrows(
                ApiException.class,
                () -> TenantViewProxyService.requireReason(" "),
                "空白原因不允许（二次确认必须填写）");
        assertThrows(
                ApiException.class,
                () -> TenantViewProxyService.requireReason("改"),
                "单字符原因不允许（防敷衍）");
        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () -> TenantViewProxyService.requireReason("长".repeat(501)));
        assertEquals(40001, tooLong.code());
    }

    // ---------------------------------------------------------------- 能力判定（纯函数）

    @Test
    void configScopeDefaultsToAllAndUnionsRoles() {
        ViewCapabilities all = new ViewCapabilities(true, null, false, false, false);
        assertTrue(all.configAllows("user"), "未配置范围 = 全部配置类资源（已确认）");

        ViewCapabilities scoped =
                new ViewCapabilities(true, List.of("user", "dict"), false, false, false);
        assertTrue(scoped.configAllows("user"));
        assertTrue(scoped.configAllows("dict"));
        assertEquals(false, scoped.configAllows("role"), "范围外资源即使可代为配置也不放行（受控）");

        ViewCapabilities off = new ViewCapabilities(false, null, false, false, false);
        assertEquals(false, off.configAllows("user"), "默认关闭：不可代为配置时全部拒绝");
    }

    @Test
    void unionScopeTreatsNullAsAll() {
        assertNull(TenantViewProxyService.unionScope(List.of()), "无角色无范围");
        assertNull(
                TenantViewProxyService.unionScope(java.util.Arrays.asList(List.of("user"), null)),
                "任一可代为配置角色未配范围 → 全部（并集口径）");
        assertEquals(
                List.of("user", "dict"),
                TenantViewProxyService.unionScope(List.of(List.of("user"), List.of("dict"))));
    }

    // ---------------------------------------------------------------- 快照截断（纯函数）

    @Test
    void snapshotTruncation() {
        assertNull(TenantViewProxyService.truncate(null));
        assertEquals("{}", TenantViewProxyService.truncate("{}"));
        String huge = "x".repeat(TenantViewProxyService.SNAPSHOT_MAX + 10);
        String truncated = TenantViewProxyService.truncate(huge);
        assertTrue(truncated.endsWith("…(截断)"));
        assertEquals(TenantViewProxyService.SNAPSHOT_MAX + 5, truncated.length());
    }
}
