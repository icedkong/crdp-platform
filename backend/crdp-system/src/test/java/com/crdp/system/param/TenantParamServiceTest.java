package com.crdp.system.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import com.crdp.system.config.PlatformConfigPort;
import com.crdp.system.infra.mapper.ConfigMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E06-T10 / PLT-CFG-004、011 单元（MySQL 行为由 DictParamOverrideIT 覆盖）： 覆盖校验（锁定 40001、 范围 40001、未知
 * 40400）、生效值与锁定失效（stale）计算、变更记录与审计、恢复默认的幂等。 无登录会话时操作人记 null（与 BrandAdminServiceTest 同口径）。
 */
class TenantParamServiceTest {

    private final PlatformConfigPort port = mock(PlatformConfigPort.class);
    private final ConfigMapper mapper = mock(ConfigMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final TenantParamService service = new TenantParamService(port, mapper, audit);

    private static final PlatformConfigPort.PlatformParamDef POOL =
            new PlatformConfigPort.PlatformParamDef(
                    "tenant.db.pool.max",
                    "每租户连接池上限",
                    null,
                    "NUMBER",
                    "20",
                    true,
                    "{\"min\":5,\"max\":50}");

    private static final PlatformConfigPort.PlatformParamDef LOCKED =
            new PlatformConfigPort.PlatformParamDef(
                    "platform.brand.locked", "平台锁定示例", null, "BOOLEAN", "false", false, null);

    @Test
    void listMergesPlatformDefsWithOverrides() {
        when(port.params()).thenReturn(List.of(POOL, LOCKED));
        ConfigMapper.ParamRow override = new ConfigMapper.ParamRow();
        override.setParamKey("tenant.db.pool.max");
        override.setParamValue("30");
        override.setUpdatedAt(java.time.LocalDateTime.of(2026, 11, 1, 10, 0));
        when(mapper.listParamOverrides()).thenReturn(List.of(override));

        List<TenantParamService.ParamView> views = service.list();

        assertEquals(2, views.size());
        // 按参数键排序：platform.brand.locked 在前、tenant.db.pool.max 在后
        TenantParamService.ParamView locked = views.get(0);
        assertFalse(locked.overridden());
        assertEquals("false", locked.effectiveValue(), "未覆盖 → 平台默认值");
        TenantParamService.ParamView pool = views.get(1);
        assertEquals("20", pool.platformValue());
        assertTrue(pool.overridden());
        assertEquals("30", pool.tenantValue());
        assertEquals("30", pool.effectiveValue(), "覆盖值在范围内 → 生效");
        assertTrue(pool.updatedAt().contains("+08:00"));
    }

    @Test
    void overrideRejectsLockedUnknownAndOutOfRange() {
        when(port.param("platform.brand.locked")).thenReturn(Optional.of(LOCKED));
        assertEquals(
                "该参数为平台锁定，不允许租户覆盖",
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.override(
                                                "platform.brand.locked",
                                                new TenantParamService.OverrideRequest(
                                                        "true", null)))
                        .getMessage());

        when(port.param("nosuch")).thenReturn(Optional.empty());
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.override(
                                                "nosuch",
                                                new TenantParamService.OverrideRequest("1", null)))
                        .code());

        when(port.param("tenant.db.pool.max")).thenReturn(Optional.of(POOL));
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.override(
                                                "tenant.db.pool.max",
                                                new TenantParamService.OverrideRequest(
                                                        "500", null)))
                        .code(),
                "超出范围 5..50");
        verify(mapper, never()).insertParam(any());
        verify(mapper, never()).updateParam(any());
    }

    @Test
    void overrideWritesLogAndAuditWithOldValue() {
        when(port.param("tenant.db.pool.max")).thenReturn(Optional.of(POOL));
        ConfigMapper.ParamRow existing = new ConfigMapper.ParamRow();
        existing.setParamKey("tenant.db.pool.max");
        existing.setParamValue("30");
        when(mapper.findParam("tenant.db.pool.max")).thenReturn(existing);

        service.override(
                "tenant.db.pool.max", new TenantParamService.OverrideRequest("40", "试点科室较多"));

        verify(mapper).updateParam(argThat(row -> row != null && "40".equals(row.getParamValue())));
        verify(mapper)
                .insertParamLog(
                        argThat(
                                row ->
                                        row != null
                                                && "OVERRIDE".equals(row.getAction())
                                                && "30".equals(row.getOldValue())
                                                && "40".equals(row.getNewValue())
                                                && "试点科室较多".equals(row.getReason())));
        verify(audit)
                .record(
                        argThat(
                                (AuditEvent event) ->
                                        event != null
                                                && "PARAM_OVERRIDE".equals(event.eventType())
                                                && event.detail()
                                                        .contains("tenant.db.pool.max=40")));
    }

    @Test
    void lockedParamKeepsOverrideButFallsBackToPlatformValue() {
        // 平台改为锁定后：覆盖行仍在（overridden=true），但生效值回落平台值并置 stale（spec §10）
        when(port.params())
                .thenReturn(
                        List.of(
                                new PlatformConfigPort.PlatformParamDef(
                                        "tenant.db.pool.max",
                                        "每租户连接池上限",
                                        null,
                                        "NUMBER",
                                        "20",
                                        false, // 平台已锁定
                                        "{\"min\":5,\"max\":50}")));
        ConfigMapper.ParamRow stale = new ConfigMapper.ParamRow();
        stale.setParamKey("tenant.db.pool.max");
        stale.setParamValue("30");
        when(mapper.listParamOverrides()).thenReturn(List.of(stale));

        TenantParamService.ParamView view = service.list().get(0);
        assertTrue(view.overridden(), "覆盖行保留");
        assertEquals("30", view.tenantValue());
        assertEquals("20", view.effectiveValue(), "锁定后覆盖失效，回落平台默认值");
        assertTrue(view.stale(), "提示覆盖已失效");
    }

    @Test
    void resetIsIdempotentAndLogsOnlyWhenOverridden() {
        when(port.param("tenant.db.pool.max")).thenReturn(Optional.of(POOL));
        when(mapper.findParam("tenant.db.pool.max")).thenReturn(null);

        TenantParamService.ParamView view = service.reset("tenant.db.pool.max");
        assertFalse(view.overridden());
        assertEquals("20", view.effectiveValue());
        verify(mapper, never()).deleteParam(any());
        verify(mapper, never()).insertParamLog(any());

        // 有覆盖：删行 + 记 RESET + 审计
        ConfigMapper.ParamRow existing = new ConfigMapper.ParamRow();
        existing.setParamKey("tenant.db.pool.max");
        existing.setParamValue("40");
        when(mapper.findParam("tenant.db.pool.max")).thenReturn(existing);
        service.reset("tenant.db.pool.max");
        verify(mapper).deleteParam("tenant.db.pool.max");
        verify(mapper)
                .insertParamLog(
                        argThat(
                                row ->
                                        row != null
                                                && "RESET".equals(row.getAction())
                                                && "40".equals(row.getOldValue())
                                                && row.getNewValue() == null));
        verify(audit)
                .record(
                        argThat(
                                (AuditEvent event) ->
                                        event != null && "PARAM_RESET".equals(event.eventType())));
    }
}
