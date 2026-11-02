package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.infra.mapper.NotifySettingMapper;
import com.crdp.followup.notice.infra.mapper.NotifySettingRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalTime;
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
 * E09-T07 / A-NTF-005、A-NTF-007：租户通知发送设置单元测试。
 *
 * <p>锁定：缺行回落默认值（21:00–08:00 / 3 / 逾期短信不可个人关闭）、修改全量覆盖并写 NOTICE_SETTINGS_UPDATE 审计（含前后值）、
 * 校验口径（HH:mm、上限 1–20、起止相同、字段缺失 → 40001）。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifySettingsServiceTest {

    private final NotifySettingMapper settings = mock(NotifySettingMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final NotifySettingsService service = new NotifySettingsService(settings, audit);

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
                "护士管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of("followup:notice:config"),
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

    /** 行缺失防御性回落默认值（种子保证有行；A-NTF-005 医院已确认默认，A-NTF-007 默认不允许关闭逾期短信）。 */
    @Test
    void getFallsBackToDefaults() {
        when(settings.find()).thenReturn(null);

        NotifySettingsService.SettingsView view = service.get();

        assertEquals("21:00", view.dndStart());
        assertEquals("08:00", view.dndEnd());
        assertEquals(3, view.smsDailyLimit());
        assertFalse(view.overdueSmsOffAllowed());
        assertFalse(service.overdueSmsOffAllowed());
    }

    /** 修改：全量覆盖、审计带前后值、返回最新值（跨零点时段合法）。 */
    @Test
    void updatePersistsAndAudits() {
        when(settings.find())
                .thenReturn(
                        new NotifySettingRow(
                                1L, LocalTime.of(21, 0), LocalTime.of(8, 0), 3, false, null, null))
                .thenReturn(
                        new NotifySettingRow(
                                1L, LocalTime.of(22, 30), LocalTime.of(7, 30), 5, true, 7L, null));

        NotifySettingsService.SettingsView updated =
                service.update(new NotifySettingsService.SettingsUpdate("22:30", "07:30", 5, true));

        assertEquals("22:30", updated.dndStart());
        assertEquals(5, updated.smsDailyLimit());
        assertTrue(updated.overdueSmsOffAllowed());

        ArgumentCaptor<NotifySettingRow> saved = ArgumentCaptor.forClass(NotifySettingRow.class);
        verify(settings).update(saved.capture());
        assertEquals(LocalTime.of(22, 30), saved.getValue().dndStart());
        assertEquals(7L, saved.getValue().updatedBy());

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("NOTICE_SETTINGS_UPDATE", entry.getValue().action());
        assertTrue(entry.getValue().beforeJson().contains("21:00"));
        assertTrue(entry.getValue().afterJson().contains("22:30"));
    }

    // ---------------------------------------------------------------- 校验失败（40001）

    @Test
    void updateRejectsBadTimeAndRange() {
        when(settings.find())
                .thenReturn(
                        new NotifySettingRow(
                                1L, LocalTime.of(21, 0), LocalTime.of(8, 0), 3, false, null, null));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate(
                                        "25:00", "08:00", 3, false)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate("21:00", "8点", 3, false)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate(
                                        "21:00", "21:00", 3, false)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate(
                                        "21:00", "08:00", 0, false)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate(
                                        "21:00", "08:00", 21, false)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate(
                                        "21:00", "08:00", null, false)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                new NotifySettingsService.SettingsUpdate(
                                        "21:00", "08:00", 3, null)));
        assertThrows(ApiException.class, () -> service.update(null));
        verify(settings, never()).update(org.mockito.ArgumentMatchers.any(NotifySettingRow.class));
    }
}
