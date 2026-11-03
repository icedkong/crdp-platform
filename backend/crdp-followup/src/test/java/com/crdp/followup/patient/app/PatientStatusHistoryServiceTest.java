package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.PatientStatusHistoryMapper;
import com.crdp.followup.patient.infra.mapper.PatientStatusHistoryMapper.StatusRow;
import com.crdp.followup.patient.infra.mapper.StatusHistoryInsert;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * E13-T03 状态时间线单元测试。锁定口径：登记初始行 = ACTIVE / source REGISTRATION（OQ-71②「登记即随访中」）； 流转入口（E14-T06
 * 预留）校验状态枚举、只追加写入；查询越范围患者 40400。全部虚构数据。
 */
class PatientStatusHistoryServiceTest {

    private static final long PATIENT_ID = 101L;
    private static final long OPERATOR_ID = 9L;
    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 3, 8);

    private final PatientContactGateway patientScope = mock(PatientContactGateway.class);
    private final PatientStatusGateway gateway = mock(PatientStatusGateway.class);
    private final PatientStatusHistoryMapper mapper = mock(PatientStatusHistoryMapper.class);

    private final PatientStatusHistoryService service =
            new PatientStatusHistoryService(patientScope, gateway, mapper);

    @Test
    void recordRegistrationWritesInitialActiveRow() {
        service.recordRegistration(PATIENT_ID, EFFECTIVE, OPERATOR_ID);

        ArgumentCaptor<StatusHistoryInsert> captor =
                ArgumentCaptor.forClass(StatusHistoryInsert.class);
        verify(mapper).insertHistory(captor.capture());
        StatusHistoryInsert row = captor.getValue();
        assertEquals(PATIENT_ID, row.getPatientId());
        assertEquals("ACTIVE", row.getStatus(), "登记即随访中（OQ-71②）");
        assertEquals(EFFECTIVE, row.getEffectiveDate());
        assertEquals("REGISTRATION", row.getSource());
        assertEquals(OPERATOR_ID, row.getCreatedBy());
    }

    @Test
    void recordValidatesStatusEnumAndRequiresEffectiveDate() {
        ApiException status =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.record(
                                        PATIENT_ID,
                                        "SLEEPING",
                                        EFFECTIVE,
                                        null,
                                        "MANUAL",
                                        OPERATOR_ID));
        assertEquals(40001, status.code());

        ApiException date =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.record(
                                        PATIENT_ID,
                                        "paused",
                                        null,
                                        "原因（示例）",
                                        "MANUAL",
                                        OPERATOR_ID));
        assertEquals(40001, date.code());
        verify(mapper, never()).insertHistory(any());
    }

    @Test
    void recordNormalizesStatusCase() {
        service.record(PATIENT_ID, "paused", EFFECTIVE, "外地随访（示例）", "MANUAL", OPERATOR_ID);

        ArgumentCaptor<StatusHistoryInsert> captor =
                ArgumentCaptor.forClass(StatusHistoryInsert.class);
        verify(mapper).insertHistory(captor.capture());
        assertEquals("PAUSED", captor.getValue().getStatus());
        assertEquals("外地随访（示例）", captor.getValue().getReason());
    }

    @Test
    void historyOfReturnsTimelineWithinScope() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(PATIENT_ID);
        List<StatusRow> rows =
                List.of(
                        new StatusRow(
                                2L, "PAUSED", EFFECTIVE, "外地随访（示例）", "MANUAL", OPERATOR_ID, null),
                        new StatusRow(
                                1L,
                                "ACTIVE",
                                EFFECTIVE.minusYears(1),
                                null,
                                "REGISTRATION",
                                OPERATOR_ID,
                                null));
        when(gateway.historyOf(PATIENT_ID)).thenReturn(rows);

        assertEquals(rows, service.historyOf(PATIENT_ID));
    }

    @Test
    void historyOfOutOfScopePatientGets40400() {
        when(patientScope.patientIdInScope(PATIENT_ID)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class, () -> service.historyOf(PATIENT_ID));

        assertEquals(40400, error.code());
        assertEquals(404, error.httpStatus());
        verify(gateway, never()).historyOf(PATIENT_ID);
    }
}
