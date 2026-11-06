package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.domain.NotifyChannel;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.notify.sms.SmsScene;
import com.crdp.framework.notify.sms.SmsTemplateOption;
import com.crdp.framework.notify.sms.SmsTemplateStore;
import com.crdp.framework.notify.sms.SmsTemplateView;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalTime;
import java.util.List;
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
 * E09-T04 / A-NTF-001、C-NTF-001：通知规则维护单元测试。
 *
 * <p>锁定：缺行回落目录默认值（与迁移 V22 种子同值）、修改全量覆盖并写 NOTICE_RULE_UPDATE 审计（含前后值）、 校验口径（渠道 /
 * 接收人非空且只认已知编码、sendTime HH:mm、advanceDays 0–365、 短信模板必须是平台审核过的 NOTIFY 模板、渠道不含 SMS 不能配模板）。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifyRuleServiceTest {

    private final NotifyRuleMapper mapper = mock(NotifyRuleMapper.class);
    private final SmsTemplateStore templates = mock(SmsTemplateStore.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final NotifyRuleService service = new NotifyRuleService(mapper, templates, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        lenient()
                .when(templates.findByCode("NOTIFY_OVERDUE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "NOTIFY_OVERDUE",
                                        SmsScene.NOTIFY.name(),
                                        "您有{1}项随访任务已逾期，请及时登录处理。",
                                        null,
                                        null,
                                        "APPROVED")));
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
                "doctor.demo",
                "张医生（示例）",
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
                0L,
                null);
    }

    /** GET 清单：目录全集 10 个事件；缺行回落默认值（已逾期含短信 + 抄送病区负责人）。 */
    @Test
    void listFallsBackToCatalogDefaults() {
        when(mapper.findAll()).thenReturn(List.of());

        List<NotifyRuleService.RuleView> items = service.list();

        assertEquals(NotifyEventType.values().length, items.size());
        NotifyRuleService.RuleView overdue =
                items.stream()
                        .filter(item -> "TASK_OVERDUE".equals(item.eventType()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(List.of(NotifyChannel.INBOX, NotifyChannel.SMS), overdue.channels());
        assertTrue(overdue.recipients().toString().contains("CC_WARD_LEADER"));
        assertEquals("NOTIFY_OVERDUE", overdue.smsTemplateCode());
        NotifyRuleService.RuleView preview =
                items.stream()
                        .filter(item -> "WINDOW_PREVIEW".equals(item.eventType()))
                        .findFirst()
                        .orElseThrow();
        assertEquals(7, preview.advanceDays());
        assertEquals("08:30", preview.sendTime());
    }

    /** 短信模板清单：只读、只列平台审核过的 NOTIFY 模板。 */
    @Test
    void smsTemplatesListsApprovedNotifyOnly() {
        when(templates.listApprovedByScene("NOTIFY"))
                .thenReturn(
                        List.of(
                                new SmsTemplateOption(
                                        "NOTIFY_DAILY_SUMMARY", "随访每日汇总", "您有{1}项随访任务待处理。"),
                                new SmsTemplateOption("NOTIFY_OVERDUE", "随访逾期提醒", "您有{1}项已逾期。")));

        List<NotifyRuleService.SmsTemplateItem> items = service.smsTemplates();

        assertEquals(2, items.size());
        assertEquals("NOTIFY_DAILY_SUMMARY", items.get(0).code());
    }

    /** 正常修改：全量覆盖、审计带前后值、返回合并后的最新值。 */
    @Test
    void updatePersistsMergedRuleAndAudits() {
        when(mapper.find("TASK_OVERDUE"))
                .thenReturn(
                        Optional.of(
                                new NotifyRuleRow(
                                        1L,
                                        "TASK_OVERDUE",
                                        true,
                                        null,
                                        LocalTime.of(8, 30),
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF,CC_WARD_LEADER",
                                        "NOTIFY_OVERDUE",
                                        null,
                                        null)))
                .thenReturn(
                        Optional.of(
                                new NotifyRuleRow(
                                        1L,
                                        "TASK_OVERDUE",
                                        false,
                                        null,
                                        LocalTime.of(9, 0),
                                        "INBOX",
                                        "OWNER",
                                        null,
                                        7L,
                                        null)));
        when(mapper.update(any(NotifyRuleRow.class))).thenReturn(1);

        NotifyRuleService.RuleView updated =
                service.update(
                        "TASK_OVERDUE",
                        new NotifyRuleService.RuleUpdate(
                                false, null, "09:00", List.of("INBOX"), List.of("OWNER"), null));

        assertEquals(false, updated.enabled());
        assertEquals("09:00", updated.sendTime());
        assertEquals(List.of(NotifyChannel.INBOX), updated.channels());

        ArgumentCaptor<NotifyRuleRow> saved = ArgumentCaptor.forClass(NotifyRuleRow.class);
        verify(mapper).update(saved.capture());
        assertEquals(false, saved.getValue().enabled());
        assertEquals(LocalTime.of(9, 0), saved.getValue().sendTime());
        assertEquals(7L, saved.getValue().updatedBy());

        ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(entry.capture());
        assertEquals("NOTICE_RULE_UPDATE", entry.getValue().action());
        assertEquals("TASK_OVERDUE", entry.getValue().objectId());
        assertTrue(entry.getValue().beforeJson().contains("INBOX,SMS"));
        assertTrue(entry.getValue().afterJson().contains("INBOX"));
    }

    // ---------------------------------------------------------------- 校验失败（40001）

    @Test
    void updateRejectsUnknownEventType() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "NOT_EXIST",
                                        new NotifyRuleService.RuleUpdate(
                                                true,
                                                null,
                                                null,
                                                List.of("INBOX"),
                                                List.of("OWNER"),
                                                null)));
        assertEquals(40001, error.code());
    }

    @Test
    void updateRejectsMissingEnabled() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "TASK_OPENED",
                                        new NotifyRuleService.RuleUpdate(
                                                null,
                                                null,
                                                null,
                                                List.of("INBOX"),
                                                List.of("OWNER"),
                                                null)));
        assertEquals(40001, error.code());
    }

    @Test
    void updateRejectsEmptyOrUnknownChannels() {
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OPENED",
                                new NotifyRuleService.RuleUpdate(
                                        true, null, null, List.of(), List.of("OWNER"), null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OPENED",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        null,
                                        null,
                                        List.of("EMAIL"),
                                        List.of("OWNER"),
                                        null)));
    }

    @Test
    void updateRejectsEmptyOrUnknownRecipients() {
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OPENED",
                                new NotifyRuleService.RuleUpdate(
                                        true, null, null, List.of("INBOX"), List.of(), null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OPENED",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        null,
                                        null,
                                        List.of("INBOX"),
                                        List.of("PATIENT"),
                                        null)));
    }

    @Test
    void updateRejectsBadSendTimeAndAdvanceDays() {
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OPENED",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        null,
                                        "25:00",
                                        List.of("INBOX"),
                                        List.of("OWNER"),
                                        null)));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OPENED",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        366,
                                        null,
                                        List.of("INBOX"),
                                        List.of("OWNER"),
                                        null)));
    }

    /** 渠道含 SMS 必配模板，且模板必须是平台审核过的 NOTIFY 场景模板。 */
    @Test
    void updateValidatesSmsTemplateWhenSmsChannel() {
        when(mapper.find("TASK_OVERDUE")).thenReturn(Optional.empty());
        // 未配模板
        ApiException missing =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "TASK_OVERDUE",
                                        new NotifyRuleService.RuleUpdate(
                                                true,
                                                null,
                                                null,
                                                List.of("INBOX", "SMS"),
                                                List.of("OWNER"),
                                                null)));
        assertEquals(40001, missing.code());
        // 模板不存在
        when(templates.findByCode("NOT_EXIST")).thenReturn(Optional.empty());
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OVERDUE",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        null,
                                        null,
                                        List.of("INBOX", "SMS"),
                                        List.of("OWNER"),
                                        "NOT_EXIST")));
        // 非 NOTIFY 场景（登录验证码模板不能拿来发通知）
        when(templates.findByCode("AUTH_LOGIN_CODE"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "AUTH_LOGIN_CODE",
                                        "AUTH_LOGIN",
                                        "验证码{1}。",
                                        null,
                                        null,
                                        "APPROVED")));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OVERDUE",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        null,
                                        null,
                                        List.of("INBOX", "SMS"),
                                        List.of("OWNER"),
                                        "AUTH_LOGIN_CODE")));
        // 未审核
        when(templates.findByCode("NOTIFY_DRAFT"))
                .thenReturn(
                        Optional.of(
                                new SmsTemplateView(
                                        "NOTIFY_DRAFT",
                                        "NOTIFY",
                                        "草稿{1}。",
                                        null,
                                        null,
                                        "PENDING")));
        assertThrows(
                ApiException.class,
                () ->
                        service.update(
                                "TASK_OVERDUE",
                                new NotifyRuleService.RuleUpdate(
                                        true,
                                        null,
                                        null,
                                        List.of("INBOX", "SMS"),
                                        List.of("OWNER"),
                                        "NOTIFY_DRAFT")));
        verify(mapper, never()).update(any(NotifyRuleRow.class));
    }

    /** 渠道不含 SMS 时不能配置短信模板。 */
    @Test
    void updateRejectsTemplateWithoutSmsChannel() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        "TASK_OPENED",
                                        new NotifyRuleService.RuleUpdate(
                                                true,
                                                null,
                                                null,
                                                List.of("INBOX"),
                                                List.of("OWNER"),
                                                "NOTIFY_OVERDUE")));
        assertEquals(40001, error.code());
        verify(templates, never()).findByCode(anyString());
    }
}
