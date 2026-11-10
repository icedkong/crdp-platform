package com.crdp.system.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.security.SmsCodeStore;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.HospitalMapper;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E06-T11 / PLT-ORG-011：个人中心校验与换绑命名空间（纯单元；MySQL 行为由 BrandAndProfileIT 覆盖）。 手机号密文 + 盲索引、验证码与登录 /
 * 重置分命名空间、 院内冲突 40902、审计脱敏。
 */
class ProfileServiceTest {

    // 手机号夹具为虚构测试号段（139-0000 段，AGENTS.md §5.5），非真实号码
    private static final String CURRENT_PHONE_CIPHER = "cipher:13900000002";
    private static final String CURRENT_PHONE = "13900000002";
    private static final String NEW_PHONE = "13900000099";

    private final HospitalDirectory directory = mock(HospitalDirectory.class);
    private final HospitalMapper mapper = mock(HospitalMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final SmsCodeStore smsCodeStore = mock(SmsCodeStore.class);
    private final SmsCodeSender smsSender = mock(SmsCodeSender.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final ProfileService service =
            new ProfileService(directory, mapper, crypto, smsCodeStore, smsSender, audit);

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院甲",
                5L,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of(),
                List.of("base"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L);
    }

    private HospitalAccount account(String phoneCipher) {
        return new HospitalAccount(
                5L,
                "doctor.demo",
                "演示医生",
                "hash",
                "ENABLED",
                phoneCipher,
                false,
                LocalDateTime.now().minusDays(10),
                List.of(),
                List.of(),
                List.of(),
                ScopeSnapshot.allowAll());
    }

    @Test
    void viewMasksPhoneAndKeepsAccountImmutable() {
        when(directory.findById(5L)).thenReturn(Optional.of(account(CURRENT_PHONE_CIPHER)));
        when(crypto.decrypt(CURRENT_PHONE_CIPHER)).thenReturn(CURRENT_PHONE);
        when(mapper.createdAt(5L)).thenReturn(LocalDateTime.of(2027, 1, 4, 9, 0));

        ProfileService.ProfileView view = service.view(principal());

        assertEquals("doctor.demo", view.username());
        assertEquals("139****0002", view.phoneMasked());
        assertEquals("虚构示例医院甲", view.tenantName());
        assertTrue(view.createdAt().contains("+08:00"), "创建时间带北京时间时区");
    }

    @Test
    void displayNameValidation() {
        SessionPrincipal principal = principal();
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> service.updateDisplayName(principal, " "))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.updateDisplayName(principal, "长".repeat(65)))
                        .code());
        verify(mapper, never()).updateDisplayName(anyLong(), anyString());
    }

    @Test
    void phoneCodeRequiresValidNewPhone() {
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.requestPhoneCode(principal(), "12345"))
                        .code(),
                "手机号格式校验");
        verify(smsCodeStore, never()).issue(anyString(), anyString());
    }

    @Test
    void phoneCodeRejectsSameAsCurrentAndUsesOwnNamespace() {
        when(directory.findById(5L)).thenReturn(Optional.of(account(CURRENT_PHONE_CIPHER)));
        when(crypto.decrypt(CURRENT_PHONE_CIPHER)).thenReturn(CURRENT_PHONE);

        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> service.requestPhoneCode(principal(), CURRENT_PHONE))
                        .code(),
                "新号与当前号相同直接拒绝，不发码");

        service.requestPhoneCode(principal(), NEW_PHONE);
        // 命名空间：rebind:租户:账号:新号（登录 login: / 重置 reset: 的码不能用来换绑）
        verify(smsCodeStore).issue("rebind:demoa:doctor.demo:" + NEW_PHONE, NEW_PHONE);
        verify(smsSender).sendPhoneBindCode(NEW_PHONE, null);
    }

    @Test
    void rebindRejectsDuplicateWithinTenant() {
        when(directory.findById(5L)).thenReturn(Optional.of(account(CURRENT_PHONE_CIPHER)));
        when(crypto.blindIndex("sys-user-phone", NEW_PHONE)).thenReturn("idx-new");
        HospitalAccount other =
                new HospitalAccount(
                        9L,
                        "nurse.demo",
                        "演示护士",
                        "hash",
                        "ENABLED",
                        null,
                        false,
                        null,
                        List.of(),
                        List.of(),
                        List.of(),
                        ScopeSnapshot.allowAll());
        // 盲索引命中本院其他账号
        when(directory.findByPhoneIdx("idx-new")).thenReturn(Optional.of(other));

        ApiException conflict =
                assertThrows(
                        ApiException.class,
                        () -> service.rebindPhone(principal(), NEW_PHONE, "123456"));
        assertEquals(40902, conflict.code());
        verify(mapper, never()).updatePhone(anyLong(), anyString(), anyString());
    }

    @Test
    void rebindWritesCipherAndMaskedAudit() {
        when(directory.findById(5L)).thenReturn(Optional.of(account(CURRENT_PHONE_CIPHER)));
        when(crypto.decrypt(CURRENT_PHONE_CIPHER)).thenReturn(CURRENT_PHONE);
        when(crypto.blindIndex("sys-user-phone", NEW_PHONE)).thenReturn("idx-new");
        when(crypto.encrypt("sys-user-phone", NEW_PHONE)).thenReturn("cipher-new");
        when(directory.findByPhoneIdx("idx-new")).thenReturn(Optional.empty());

        service.rebindPhone(principal(), NEW_PHONE, "123456");

        verify(smsCodeStore).verify("rebind:demoa:doctor.demo:" + NEW_PHONE, "123456");
        verify(mapper).updatePhone(5L, "cipher-new", "idx-new");
        verify(audit)
                .record(
                        org.mockito.ArgumentMatchers.<AuditEvent>argThat(
                                event ->
                                        event != null
                                                && event.eventType().equals("USER_PHONE_REBIND")
                                                && event.detail() != null
                                                && event.detail().contains("139****0002")
                                                && event.detail().contains("139****0099")
                                                && !event.detail().contains(CURRENT_PHONE)
                                                && !event.detail().contains(NEW_PHONE)));
    }

    @Test
    void loginLogsAreScopedToSelf() {
        service.loginLogs(principal());
        verify(mapper).loginLogs(eq("doctor.demo"), eq(20));
    }
}
