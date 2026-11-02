package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyUserPrefMapper;
import com.crdp.followup.notice.infra.mapper.NotifyUserPrefRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E09-T07 / A-NTF-007、C-NTF-007：个人通知偏好单元测试。
 *
 * <p>锁定：可选项只列本院规则含短信渠道的事件（缺行回落目录默认值，与规则页同口径）、 逾期类（TASK_OVERDUE）默认不可关（租户设置允许才可）、 保存全量覆盖并写
 * NOTICE_PREF_UPDATE 审计、未知事件 40001。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifyPreferenceServiceTest {

    private static final long USER = 7L;

    private final NotifyUserPrefMapper prefs = mock(NotifyUserPrefMapper.class);
    private final NotifyRuleMapper rules = mock(NotifyRuleMapper.class);
    private final NotifySettingsService settings = mock(NotifySettingsService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final NotifyPreferenceService service =
            new NotifyPreferenceService(prefs, rules, settings, audit);

    @BeforeEach
    void defaults() {
        lenient().when(rules.find(anyString())).thenReturn(Optional.empty());
        lenient().when(settings.overdueSmsOffAllowed()).thenReturn(false);
        lenient().when(prefs.find(USER)).thenReturn(null);
    }

    /** 视图：smsOff 空数组起步；可选项 = 生效渠道含 SMS 的事件（默认规则下：已逾期、每日汇总）；逾期类默认不可关。 */
    @Test
    void viewListsSmsCapableOptionsOnly() {
        NotifyPreferenceService.PrefView view = service.view(USER);

        assertEquals(List.of(), view.smsOff());
        assertEquals(false, view.overdueSmsOffAllowed());
        assertEquals(2, view.options().size(), "默认规则下含短信渠道的事件只有两个");
        assertEquals("TASK_OVERDUE", view.options().get(0).eventType());
        assertEquals(false, view.options().get(0).closeable(), "逾期类默认不可关");
        assertEquals("DAILY_DIGEST", view.options().get(1).eventType());
        assertEquals(true, view.options().get(1).closeable(), "非逾期类可关");
    }

    /** 租户允许时逾期类可关（A-NTF-007「逾期类是否允许关闭由租户配置」）；本院改规则后可选项跟着变。 */
    @Test
    void optionsFollowTenantSettingAndRules() {
        when(settings.overdueSmsOffAllowed()).thenReturn(true);
        when(rules.find("TASK_OPENED"))
                .thenReturn(
                        Optional.of(
                                new com.crdp.followup.notice.infra.mapper.NotifyRuleRow(
                                        1L,
                                        "TASK_OPENED",
                                        true,
                                        null,
                                        null,
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF",
                                        "NOTIFY_DAILY_SUMMARY",
                                        null,
                                        null)));

        NotifyPreferenceService.PrefView view = service.view(USER);

        assertEquals(true, view.overdueSmsOffAllowed());
        assertEquals(3, view.options().size(), "任务开启被本院配上短信后进入可选项");
        assertEquals(
                true,
                view.options().stream()
                        .filter(option -> "TASK_OVERDUE".equals(option.eventType()))
                        .findFirst()
                        .orElseThrow()
                        .closeable());
    }

    /** 保存：全量覆盖、CSV 落库、写审计后返回最新视图。 */
    @Test
    void updatePersistsCsvAndAudits() {
        when(prefs.find(USER))
                .thenReturn(null)
                .thenReturn(new NotifyUserPrefRow(1L, USER, "DAILY_DIGEST", USER, null));

        NotifyPreferenceService.PrefView updated =
                service.update(
                        USER, new NotifyPreferenceService.PrefUpdate(List.of("DAILY_DIGEST")));

        assertEquals(List.of("DAILY_DIGEST"), updated.smsOff());
        verify(prefs).upsert(USER, "DAILY_DIGEST", USER);
        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("NOTICE_PREF_UPDATE", entry.getValue().action());
        assertEquals(String.valueOf(USER), entry.getValue().objectId());
    }

    /** 未知事件编码 → 40001（不落库）。 */
    @Test
    void updateRejectsUnknownEvent() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        USER,
                                        new NotifyPreferenceService.PrefUpdate(
                                                List.of("NOT_EXIST"))));
        assertEquals(40001, error.code());
        verify(prefs, never()).upsert(anyLong(), anyString(), anyLong());
    }

    /** 逾期类在租户不允许时提交 → 40001（docs/api/notify.md §5 口径）；允许时通过。 */
    @Test
    void overdueOffDependsOnTenantSetting() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        USER,
                                        new NotifyPreferenceService.PrefUpdate(
                                                List.of("TASK_OVERDUE"))));
        assertEquals(40001, error.code());

        when(settings.overdueSmsOffAllowed()).thenReturn(true);
        when(prefs.find(USER))
                .thenReturn(null)
                .thenReturn(new NotifyUserPrefRow(1L, USER, "TASK_OVERDUE", USER, null));
        assertEquals(
                List.of("TASK_OVERDUE"),
                service.update(
                                USER,
                                new NotifyPreferenceService.PrefUpdate(List.of("TASK_OVERDUE")))
                        .smsOff());
    }

    /** 请求体缺失 / smsOff 为 null → 40001。 */
    @Test
    void updateRejectsMissingBody() {
        assertThrows(ApiException.class, () -> service.update(USER, null));
        assertThrows(
                ApiException.class,
                () -> service.update(USER, new NotifyPreferenceService.PrefUpdate(null)));
        verify(audit, never()).append(any(AuditEntry.class));
    }
}
