package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.ContactVersionInsert;
import com.crdp.followup.patient.infra.mapper.PatientContactMapper;
import com.crdp.followup.patient.infra.mapper.PatientContactMapper.ContactRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T03 单元测试（tasks.csv 验收：联系方式改动保留历史）。
 *
 * <p>锁定口径：新增版本只 INSERT 不覆盖（槽位内 version 递增、旧行不动）；与当前版本号码与微信标志都相同 → 40001
 * 不制造无变化版本；生效日期不得晚于今天、不得早于当前版本；电话只存加密列 + 盲索引（purpose pt-contact-phone）；输出一律脱敏（138****5678 口径）；越范围患者
 * 40400； 每次新增写 PATIENT_CONTACT_ADD 审计（脱敏摘要、无明文）。全部虚构数据。
 */
class PatientContactServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;
    // 手机号夹具为虚构测试号段（138-0000 / 139-0000 段，AGENTS.md §5.5），非真实号码
    private static final String PHONE_V1 = "13800001234";
    private static final String PHONE_V2 = "13900008888";
    private static final LocalDate TODAY = LocalDate.now();

    private final PatientContactGateway gateway = mock(PatientContactGateway.class);
    private final PatientContactMapper mapper = mock(PatientContactMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PatientContactService service =
            new PatientContactService(gateway, mapper, crypto, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal(), null, List.of()));
        when(crypto.blindIndex(anyString(), anyString()))
                .thenAnswer(invocation -> "BLIND:" + invocation.getArgument(1));
        when(crypto.encrypt(anyString(), anyString()))
                .thenAnswer(invocation -> "CIPHER:" + invocation.getArgument(1));
        when(crypto.decrypt(anyString()))
                .thenAnswer(
                        invocation -> invocation.getArgument(0).toString().replace("CIPHER:", ""));
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        when(mapper.insertVersion(any()))
                .thenAnswer(
                        invocation -> {
                            ContactVersionInsert row = invocation.getArgument(0);
                            row.setId(1L);
                            return 1;
                        });
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 新增版本（保留历史）

    @Test
    void firstVersionIsVersionOneWithEncryptedColumns() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_1")).thenReturn(null);

        PatientContactService.ContactVersionView view =
                service.addVersion(
                        PATIENT_ID,
                        new PatientContactService.AddRequest(
                                "PHONE_1", " 138-0000-1234 ", TODAY, null, null, null, null));

        assertTrue(view.current());
        assertEquals(1, view.version());
        assertEquals("138****1234", view.phoneMasked(), "查询输出一律脱敏");
        assertEquals("MANUAL", view.source(), "来源缺省 MANUAL");

        ArgumentCaptor<ContactVersionInsert> captor =
                ArgumentCaptor.forClass(ContactVersionInsert.class);
        verify(mapper).insertVersion(captor.capture());
        ContactVersionInsert row = captor.getValue();
        assertEquals(PATIENT_ID, row.getPatientId());
        assertEquals("PHONE_1", row.getSlot());
        assertEquals("CIPHER:13800001234", row.getPhoneCipher(), "电话只存密文（归一化后）");
        assertEquals("BLIND:13800001234", row.getPhoneBlind(), "盲索引按归一化号码计算");
        assertEquals(1, row.getVersion());
        assertEquals(TODAY, row.getEffectiveDate());
        assertEquals(OPERATOR_ID, row.getCreatedBy());
    }

    @Test
    void changedNumberOpensNewVersionAndKeepsOldRow() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_1"))
                .thenReturn(
                        new ContactRow(
                                1L,
                                "PHONE_1",
                                "CIPHER:" + PHONE_V1,
                                false,
                                TODAY.minusYears(2),
                                "REGISTRATION",
                                null,
                                1,
                                null,
                                OPERATOR_ID,
                                null));

        PatientContactService.ContactVersionView view =
                service.addVersion(
                        PATIENT_ID,
                        new PatientContactService.AddRequest(
                                "PHONE_1", PHONE_V2, TODAY, "FOLLOWUP", 555L, true, "第 2 年随访时换号"));

        assertEquals(2, view.version(), "换号 = 该槽位新版本（版本 2）");
        assertTrue(view.current());
        assertEquals("FOLLOWUP", view.source());
        assertEquals(555L, view.sourceRefId());
        assertEquals("139****8888", view.phoneMasked());

        ArgumentCaptor<ContactVersionInsert> captor =
                ArgumentCaptor.forClass(ContactVersionInsert.class);
        verify(mapper).insertVersion(captor.capture());
        assertEquals(2, captor.getValue().getVersion(), "只 INSERT 新版本，旧行不动（保留历史）");
        assertTrue(captor.getValue().getWechatAdded());
    }

    @Test
    void sameNumberWithDifferentWechatFlagStillOpensVersion() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_1"))
                .thenReturn(
                        new ContactRow(
                                1L,
                                "PHONE_1",
                                "CIPHER:" + PHONE_V1,
                                false,
                                TODAY,
                                "MANUAL",
                                null,
                                1,
                                null,
                                OPERATOR_ID,
                                null));

        PatientContactService.ContactVersionView view =
                service.addVersion(
                        PATIENT_ID,
                        new PatientContactService.AddRequest(
                                "PHONE_1", PHONE_V1, null, null, null, true, null));

        assertEquals(2, view.version(), "微信标志变化也是一次联系方式变化（版本快照）");
        assertEquals(TODAY, view.effectiveDate(), "生效日期缺省今天");
    }

    @Test
    void identicalVersionIsRejectedWithoutInsert() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_2"))
                .thenReturn(
                        new ContactRow(
                                7L,
                                "PHONE_2",
                                "CIPHER:" + PHONE_V1,
                                null,
                                TODAY,
                                "MANUAL",
                                null,
                                3,
                                null,
                                OPERATOR_ID,
                                null));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addVersion(
                                        PATIENT_ID,
                                        new PatientContactService.AddRequest(
                                                "PHONE_2",
                                                " 138 0000 1234 ",
                                                TODAY,
                                                null,
                                                null,
                                                null,
                                                null)));

        assertEquals(40001, error.code());
        assertEquals("联系方式与当前版本一致，无需新增版本", error.getMessage());
        verify(mapper, never()).insertVersion(any());
        verify(audit, never()).append(any());
    }

    @Test
    void effectiveDateMustNotBeFutureNorBeforeCurrentVersion() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_1"))
                .thenReturn(
                        new ContactRow(
                                1L,
                                "PHONE_1",
                                "CIPHER:" + PHONE_V1,
                                false,
                                TODAY.minusDays(10),
                                "MANUAL",
                                null,
                                1,
                                null,
                                OPERATOR_ID,
                                null));

        assertRejected(
                new PatientContactService.AddRequest(
                        "PHONE_1", PHONE_V2, TODAY.plusDays(1), null, null, null, null),
                "生效日期不能晚于今天");
        assertRejected(
                new PatientContactService.AddRequest(
                        "PHONE_1", PHONE_V2, TODAY.minusDays(11), null, null, null, null),
                "生效日期不能早于当前版本的生效日期（" + TODAY.minusDays(10) + "）");
    }

    @Test
    void validatesSlotPhoneAndSource() {
        assertRejected(
                new PatientContactService.AddRequest(null, PHONE_V1, TODAY, null, null, null, null),
                "请选择号码槽位（PHONE_1 电话1 / PHONE_2 电话2）");
        assertRejected(
                new PatientContactService.AddRequest(
                        "WECHAT", PHONE_V1, TODAY, null, null, null, null),
                "号码槽位只支持 PHONE_1（电话1）或 PHONE_2（电话2）");
        assertRejected(
                new PatientContactService.AddRequest(
                        "phone_1", "  ", TODAY, null, null, null, null),
                "请填写电话号码");
        assertRejected(
                new PatientContactService.AddRequest(
                        "phone_1", "不是号码", TODAY, null, null, null, null),
                "电话号码格式不正确（5–20 位数字，可带 + 前缀）");
        assertRejected(
                new PatientContactService.AddRequest(
                        "phone_1", PHONE_V1, TODAY, "OTHER", null, null, null),
                "来源只支持 REGISTRATION / FOLLOWUP / IMPORT / MANUAL");
    }

    @Test
    void outOfScopePatientGets40400WithoutLeakingExistence() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addVersion(
                                        PATIENT_ID,
                                        new PatientContactService.AddRequest(
                                                "PHONE_1", PHONE_V1, TODAY, null, null, null,
                                                null)));

        assertEquals(40400, error.code());
        assertEquals(404, error.httpStatus());
        verify(mapper, never()).insertVersion(any());
    }

    @Test
    void concurrentSameSlotInsertTranslatesTo40900() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_1")).thenReturn(null);
        // doThrow 写法：避免 when(...) 触发 @BeforeEach 的回填桩（参数为 null 时 NPE）
        org.mockito.Mockito.doThrow(new DuplicateKeyException("uk_pt_contact_version"))
                .when(mapper)
                .insertVersion(any());

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addVersion(
                                        PATIENT_ID,
                                        new PatientContactService.AddRequest(
                                                "PHONE_1", PHONE_V1, TODAY, null, null, null,
                                                null)));

        assertEquals(40900, error.code(), "并发同槽位由唯一键兜底，提示刷新重试");
    }

    @Test
    void addVersionWritesAuditWithMaskedSummaryOnly() {
        when(gateway.currentVersion(PATIENT_ID, "PHONE_1")).thenReturn(null);

        service.addVersion(
                PATIENT_ID,
                new PatientContactService.AddRequest(
                        "PHONE_1", PHONE_V1, TODAY, "REGISTRATION", null, false, null));

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals("PATIENT_CONTACT_ADD", entry.action());
        assertEquals("PATIENT", entry.objectType());
        assertEquals(String.valueOf(PATIENT_ID), entry.objectId());
        assertEquals(AuditResult.SUCCESS, entry.result());
        assertFalse(entry.afterJson().contains(PHONE_V1), "审计摘要不得出现号码明文");
        assertFalse(entry.description().contains(PHONE_V1), "审计描述不得出现号码明文");
        assertTrue(entry.afterJson().contains("138****1234"), "审计摘要存脱敏值");
    }

    // ---------------------------------------------------------------- 查询（当前与历史，脱敏）

    @Test
    void infoOfListsHistoryWithCurrentFlagAndMaskedPhones() {
        when(gateway.versionsOf(PATIENT_ID))
                .thenReturn(
                        List.of(
                                new ContactRow(
                                        2L,
                                        "PHONE_1",
                                        "CIPHER:" + PHONE_V2,
                                        true,
                                        TODAY,
                                        "FOLLOWUP",
                                        555L,
                                        2,
                                        "第 2 年随访时换号",
                                        OPERATOR_ID,
                                        null),
                                new ContactRow(
                                        1L,
                                        "PHONE_1",
                                        "CIPHER:" + PHONE_V1,
                                        false,
                                        TODAY.minusYears(2),
                                        "REGISTRATION",
                                        null,
                                        1,
                                        null,
                                        OPERATOR_ID,
                                        null)));

        PatientContactService.ContactInfoView view = service.infoOf(PATIENT_ID);

        assertEquals(2, view.items().size(), "当前与历史版本并列返回");
        assertEquals("139****8888", view.items().get(0).phoneMasked());
        assertTrue(view.items().get(0).current(), "版本 2 为电话1当前版本");
        assertEquals("138****1234", view.items().get(1).phoneMasked());
        assertFalse(view.items().get(1).current(), "旧版本保留但不再当前");
        assertEquals(555L, view.items().get(0).sourceRefId());
        assertNull(view.items().get(1).sourceRefId());
    }

    @Test
    void infoOfEmptyWhenNoVersionEverAdded() {
        when(gateway.versionsOf(PATIENT_ID)).thenReturn(List.of());

        assertTrue(service.infoOf(PATIENT_ID).items().isEmpty(), "未登记过联系方式返回空清单");
    }

    @Test
    void infoOfOutOfScopePatientGets40400() {
        when(gateway.patientIdInScope(PATIENT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.infoOf(PATIENT_ID));

        assertEquals(40400, error.code());
    }

    // ---------------------------------------------------------------- 辅助

    private void assertRejected(PatientContactService.AddRequest request, String message) {
        ApiException error =
                assertThrows(ApiException.class, () -> service.addVersion(PATIENT_ID, request));
        assertEquals(40001, error.code());
        assertEquals(message, error.getMessage());
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "虚构示例医院",
                OPERATOR_ID,
                "doctor.demo",
                "演示医生",
                List.of("FOLLOWUP_DOCTOR"),
                List.of(),
                List.of("followup:patient:view", "followup:patient:edit"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                0L, // 绝对时长用部署配置的全局值,
                null);
    }
}
