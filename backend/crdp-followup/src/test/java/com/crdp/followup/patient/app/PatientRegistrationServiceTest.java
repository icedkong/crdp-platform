package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientInsert;
import com.crdp.followup.patient.infra.mapper.PatientMapper;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002 单元测试（验收「重复病案号提示并阻止」）。
 *
 * <p>锁定口径：最小必填集校验（40001）、日期先后（入院 ≤ 手术 ≤ 出院）、重复病案号 40902 阻止（范围内带 脱敏摘要、范围外只报存在）、姓名 +
 * 出生日期相同为可能重复提示（C-PAT-002，不阻止）、写入只落加密列与盲 索引（明文不进库）、PATIENT_CREATE 审计 SUCCESS /
 * DENIED（敏感值只存摘要）。全部虚构数据。
 */
class PatientRegistrationServiceTest {

    private static final long WARD_ID = 3L;
    private static final long OPERATOR_ID = 9L;
    private static final String MRN = "MR2026-0001";
    private static final String NAME = "张小明（示例）";
    private static final LocalDate BIRTH = LocalDate.of(1955, 1, 1);
    private static final LocalDate SURGERY = LocalDate.of(2026, 3, 1);
    private static final LocalDate ADMISSION = LocalDate.of(2026, 2, 25);
    private static final LocalDate DISCHARGE = LocalDate.of(2026, 3, 8);

    private final PatientMapper mapper = mock(PatientMapper.class);
    private final PatientRegistrationGateway gateway = mock(PatientRegistrationGateway.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final PatientRegistrationService service =
            new PatientRegistrationService(mapper, gateway, crypto, audit);

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
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- 查重（A-PAT-007、C-PAT-002）

    @Test
    void checkDuplicateReturnsExistsFalseWhenFree() {
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(null);

        PatientRegistrationService.DuplicateCheckView view =
                service.check(
                        new PatientRegistrationService.DuplicateCheckRequest(MRN, null, null));

        assertFalse(view.exists());
        assertNull(view.patient(), "无重复时不下发摘要");
        assertTrue(view.possibleMatches().isEmpty());
    }

    @Test
    void checkDuplicateReturnsMaskedSummaryWithinScope() {
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(11L);
        when(gateway.duplicateSummaryByMrnBlind("BLIND:" + MRN))
                .thenReturn(
                        new PatientMapper.DuplicateRow(11L, "CIPHER:" + NAME, "示例二病区", DISCHARGE));

        PatientRegistrationService.DuplicateCheckView view =
                service.check(
                        new PatientRegistrationService.DuplicateCheckRequest(MRN, null, null));

        assertTrue(view.exists());
        assertNotNull(view.patient());
        assertEquals("11", view.patient().id());
        assertEquals("张*）", view.patient().nameMasked(), "姓名脱敏（三字以上保留首尾）");
        assertEquals("示例二病区", view.patient().wardName());
        assertEquals(DISCHARGE, view.patient().dischargeDate());
    }

    @Test
    void checkDuplicateHidesSummaryWhenOutOfScopeButStillReportsExists() {
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(11L);
        when(gateway.duplicateSummaryByMrnBlind("BLIND:" + MRN)).thenReturn(null);

        PatientRegistrationService.DuplicateCheckView view =
                service.check(
                        new PatientRegistrationService.DuplicateCheckRequest(MRN, null, null));

        assertTrue(view.exists(), "范围外同号也报存在（全院唯一约束）");
        assertNull(view.patient(), "范围外不泄露摘要（不暴露存在之外的信息）");
    }

    @Test
    void checkDuplicateFlagsSameNameAndBirthAsPossibleMatch() {
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(null);
        when(gateway.sameIdentityMatches("BLIND:" + NAME, BIRTH, "BLIND:" + MRN, 5))
                .thenReturn(
                        List.of(
                                new PatientMapper.DuplicateRow(
                                        21L, "CIPHER:" + NAME, "示例一病区", DISCHARGE)));

        PatientRegistrationService.DuplicateCheckView view =
                service.check(
                        new PatientRegistrationService.DuplicateCheckRequest(MRN, NAME, BIRTH));

        assertFalse(view.exists());
        assertEquals(1, view.possibleMatches().size());
        assertEquals("张*）", view.possibleMatches().get(0).nameMasked());
    }

    @Test
    void checkDuplicateIgnoresIdentityHintWhenNameOrBirthMissing() {
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(null);

        PatientRegistrationService.DuplicateCheckView view =
                service.check(
                        new PatientRegistrationService.DuplicateCheckRequest(MRN, NAME, null));

        verify(gateway, never())
                .sameIdentityMatches(
                        anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt());
        assertTrue(view.possibleMatches().isEmpty());
    }

    @Test
    void checkDuplicateRequiresMedicalRecordNo() {
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.check(
                                        new PatientRegistrationService.DuplicateCheckRequest(
                                                "  ", null, null)));
        assertEquals(40001, error.code());
        assertEquals("请填写病案号", error.getMessage());
    }

    // ---------------------------------------------------------------- 登记（A-PAT-003）

    @Test
    void createPersistsEncryptedColumnsAndBlindIndexesAndWritesAudit() {
        wardEnabled();
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(null);
        when(mapper.findWards()).thenReturn(List.of(new PatientMapper.OptionRow(WARD_ID, "示例二病区")));
        when(mapper.insertPatient(any()))
                .thenAnswer(
                        invocation -> {
                            PatientInsert row = invocation.getArgument(0);
                            row.setId(101L);
                            return 1;
                        });

        PatientRegistrationService.CreatedView view = service.create(request(MRN, NAME).build());

        assertEquals("101", view.id());
        assertEquals("张*）", view.nameMasked());
        assertEquals("示例二病区", view.wardName());

        ArgumentCaptor<PatientInsert> captor = ArgumentCaptor.forClass(PatientInsert.class);
        verify(mapper).insertPatient(captor.capture());
        PatientInsert row = captor.getValue();
        assertEquals("CIPHER:" + MRN, row.getMrnCipher(), "病案号只存密文");
        assertEquals("BLIND:" + MRN, row.getMrnBlind());
        assertEquals("CIPHER:" + NAME, row.getNameCipher(), "姓名只存密文");
        assertEquals("BLIND:" + NAME, row.getNameBlind());
        assertEquals("M", row.getGender());
        assertEquals(OPERATOR_ID, row.getCreatedBy());
        assertEquals(1, row.getDischargeStatus());

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(auditCaptor.capture());
        AuditEntry entry = auditCaptor.getValue();
        assertEquals("PATIENT_CREATE", entry.action());
        assertEquals("PATIENT", entry.objectType());
        assertEquals("101", entry.objectId());
        assertEquals(AuditResult.SUCCESS, entry.result());
        assertFalse(entry.afterJson().contains(MRN), "审计摘要不得出现病案号明文");
        assertFalse(entry.description().contains(NAME), "审计描述不得出现姓名明文");
        assertTrue(entry.afterJson().contains("mrnMasked"), "审计摘要存脱敏值");
    }

    @Test
    void createBlocksDuplicateMedicalRecordNoWith40902() {
        wardEnabled();
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(11L);
        when(gateway.duplicateSummaryByMrnBlind("BLIND:" + MRN))
                .thenReturn(
                        new PatientMapper.DuplicateRow(11L, "CIPHER:" + NAME, "示例二病区", DISCHARGE));

        ApiException error =
                assertThrows(ApiException.class, () -> service.create(request(MRN, NAME).build()));

        assertEquals(40902, error.code());
        assertEquals(409, error.httpStatus());
        verify(mapper, never()).insertPatient(any());

        PatientRegistrationService.DuplicateCheckView data =
                (PatientRegistrationService.DuplicateCheckView) error.data();
        assertTrue(data.exists());
        assertEquals("11", data.patient().id(), "范围内重复带脱敏摘要，供前端跳转已有患者");
        assertEquals("张*）", data.patient().nameMasked());

        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(auditCaptor.capture());
        assertEquals(AuditResult.DENIED, auditCaptor.getValue().result());
        assertEquals(40902, auditCaptor.getValue().errorCode());
    }

    @Test
    void createBlocksDuplicateEvenWhenExistingPatientOutOfScope() {
        wardEnabled();
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(11L);
        when(gateway.duplicateSummaryByMrnBlind("BLIND:" + MRN)).thenReturn(null);

        ApiException error =
                assertThrows(ApiException.class, () -> service.create(request(MRN, NAME).build()));

        assertEquals(40902, error.code());
        PatientRegistrationService.DuplicateCheckView data =
                (PatientRegistrationService.DuplicateCheckView) error.data();
        assertTrue(data.exists());
        assertNull(data.patient(), "范围外只报重复，不泄露摘要");
    }

    @Test
    void createTranslatesUniqueKeyRaceTo40902() {
        wardEnabled();
        when(gateway.liveIdByMrnBlind("BLIND:" + MRN)).thenReturn(null);
        when(gateway.duplicateSummaryByMrnBlind("BLIND:" + MRN))
                .thenReturn(
                        new PatientMapper.DuplicateRow(11L, "CIPHER:" + NAME, "示例二病区", DISCHARGE));
        when(mapper.insertPatient(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_pt_patient_mrn"));

        ApiException error =
                assertThrows(ApiException.class, () -> service.create(request(MRN, NAME).build()));

        assertEquals(40902, error.code(), "并发同号由唯一键兜底，与前置查重同口径");
    }

    // ---------------------------------------------------------------- 最小必填集与校验（A-PAT-003）

    @Test
    void createValidatesMinimalRequiredSet() {
        wardEnabled();
        assertRejected(request(null, NAME).build(), "请填写病案号");
        assertRejected(request(MRN, "  ").build(), "请填写姓名");
        assertRejected(request(MRN, NAME).withWard(null).build(), "请选择病区");
        assertRejected(request(MRN, NAME).withGender(null).build(), "请选择性别");
        assertRejected(request(MRN, NAME).withSurgery(null).build(), "请填写手术日期");
        assertRejected(request(MRN, NAME).withAdmission(null).build(), "请填写入院日期");
        assertRejected(request(MRN, NAME).withDischarge(null).build(), "请填写出院日期");
        assertRejected(request(MRN, NAME).withDischargeStatus(null).build(), "请选择出院状态");
        assertRejected(request(MRN, NAME).withGender("X").build(), "性别只支持男（M）或女（F）");
        assertRejected(request(MRN, NAME).withDischargeStatus(9).build(), "出院状态取值不正确");
    }

    @Test
    void createValidatesWardAndDeptExistAndEnabled() {
        when(mapper.countEnabledWard(WARD_ID)).thenReturn(0);
        assertRejected(request(MRN, NAME).build(), "病区不存在或已停用");

        wardEnabled();
        when(mapper.countEnabledDept(7L)).thenReturn(0);
        assertRejected(request(MRN, NAME).withDept(7L).build(), "科室不存在或已停用");
    }

    @Test
    void createValidatesDateOrder() {
        wardEnabled();
        assertRejected(
                request(MRN, NAME).withSurgery(ADMISSION.minusDays(1)).build(), "手术日期不能早于入院日期");
        assertRejected(
                request(MRN, NAME).withDischarge(SURGERY.minusDays(1)).build(), "出院日期不能早于手术日期");
        assertRejected(
                request(MRN, NAME).withBirth(LocalDate.now().plusDays(1)).build(), "出生日期不能晚于今天");
    }

    @Test
    void createTrimsInputsAndNormalizesGenderCase() {
        wardEnabled();
        when(gateway.liveIdByMrnBlind(anyString())).thenReturn(null);
        when(mapper.findWards()).thenReturn(List.of(new PatientMapper.OptionRow(WARD_ID, "示例二病区")));
        when(mapper.insertPatient(any()))
                .thenAnswer(
                        invocation -> {
                            PatientInsert row = invocation.getArgument(0);
                            row.setId(102L);
                            return 1;
                        });

        service.create(request("  " + MRN + "  ", " " + NAME + " ").withGender("m").build());

        ArgumentCaptor<PatientInsert> captor = ArgumentCaptor.forClass(PatientInsert.class);
        verify(mapper).insertPatient(captor.capture());
        assertEquals(MRN, captor.getValue().getMrnCipher().replace("CIPHER:", ""), "入库前去除首尾空白");
        assertEquals(NAME, captor.getValue().getNameCipher().replace("CIPHER:", ""));
        assertEquals("M", captor.getValue().getGender(), "性别统一大写存储");
    }

    @Test
    void optionsListsEnabledWardsDeptsAndCodedItems() {
        when(mapper.findWards())
                .thenReturn(
                        List.of(
                                new PatientMapper.OptionRow(WARD_ID, "示例二病区"),
                                new PatientMapper.OptionRow(4L, "示例三病区")));
        when(mapper.findDepts()).thenReturn(List.of(new PatientMapper.OptionRow(7L, "虚构心内科")));

        PatientRegistrationService.RegistrationOptionsView options = service.options();

        assertEquals(2, options.wards().size());
        assertEquals(1, options.depts().size());
        assertEquals(
                List.of("M", "F"), options.genders().stream().map(item -> item.code()).toList());
        assertEquals(
                List.of("1", "2", "3"),
                options.dischargeStatuses().stream()
                        .map(PatientRegistrationService.OptionItem::code)
                        .toList());
        assertEquals(
                List.of("存活", "病重自动出院", "死亡"),
                options.dischargeStatuses().stream()
                        .map(PatientRegistrationService.OptionItem::label)
                        .toList());
    }

    // ---------------------------------------------------------------- 辅助

    private void wardEnabled() {
        when(mapper.countEnabledWard(WARD_ID)).thenReturn(1);
    }

    private void assertRejected(PatientRegistrationService.CreateRequest request, String message) {
        ApiException error = assertThrows(ApiException.class, () -> service.create(request));
        assertEquals(40001, error.code());
        assertEquals(message, error.getMessage());
    }

    /** 固定的 A 出院登记合法请求；with* 语法糖只改单个字段。 */
    private static RequestBuilder request(String mrn, String name) {
        return new RequestBuilder(mrn, name);
    }

    static final class RequestBuilder {

        private final String mrn;
        private final String name;
        private String gender = "M";
        private LocalDate birth = BIRTH;
        private Long dept;
        private Long ward = WARD_ID;
        private LocalDate surgery = SURGERY;
        private LocalDate admission = ADMISSION;
        private LocalDate discharge = DISCHARGE;
        private Integer dischargeStatus = 1;

        private RequestBuilder(String mrn, String name) {
            this.mrn = mrn;
            this.name = name;
        }

        RequestBuilder withGender(String value) {
            this.gender = value;
            return this;
        }

        RequestBuilder withBirth(LocalDate value) {
            this.birth = value;
            return this;
        }

        RequestBuilder withDept(Long value) {
            this.dept = value;
            return this;
        }

        RequestBuilder withWard(Long value) {
            this.ward = value;
            return this;
        }

        RequestBuilder withSurgery(LocalDate value) {
            this.surgery = value;
            return this;
        }

        RequestBuilder withAdmission(LocalDate value) {
            this.admission = value;
            return this;
        }

        RequestBuilder withDischarge(LocalDate value) {
            this.discharge = value;
            return this;
        }

        RequestBuilder withDischargeStatus(Integer value) {
            this.dischargeStatus = value;
            return this;
        }

        PatientRegistrationService.CreateRequest build() {
            return new PatientRegistrationService.CreateRequest(
                    mrn,
                    name,
                    gender,
                    birth,
                    dept,
                    ward,
                    surgery,
                    admission,
                    discharge,
                    dischargeStatus,
                    null);
        }
    }

    /** 直接当作 CreateRequest 用（等价 build()）。 */
    private PatientRegistrationService.CreateRequest requestOf(String mrn, String name) {
        return request(mrn, name).build();
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
                List.of("followup:patient:create", "followup:patient:view"),
                List.of("followup"),
                1L,
                ScopeSnapshot.allowAll(),
                false,
                null,
                null,
                0L,
                0L,
                0L,
                null);
    }
}
