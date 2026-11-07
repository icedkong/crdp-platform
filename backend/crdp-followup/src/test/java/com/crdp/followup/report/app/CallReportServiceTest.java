package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.infra.mapper.ReportConfigMapper;
import com.crdp.followup.report.infra.mapper.ReportInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper.LinkInsert;
import com.crdp.followup.report.infra.mapper.ReportMapper.RecordRow;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E16-T08 单测锁定（A-ATT-017，全部虚构数据）：沟通记录带录音 → 同事务自动生成「通话记录」报告单（同一批 sys_file、两处同一数据）；
 * 位置预选任务对应随访记录（整条）、无记录进入「未指定位置」；CALL 类型停用跳过；删沟通记录连带软删。
 */
@ExtendWith(MockitoExtension.class)
class CallReportServiceTest {

    private static final long PATIENT = 301L;

    private static final long CONTACT = 5001L;

    private static final long TASK = 9001L;

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final ReportConfigMapper config = mock(ReportConfigMapper.class);
    private final FormService formService = mock(FormService.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final CallReportService service =
            new CallReportService(mapper, config, formService, audit);

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsCallReportWithSameFilesTaskRecordLocationAndContactLink() {
        readyCallType();
        RecordRow record =
                new RecordRow(
                        88L,
                        TASK,
                        "第2年随访",
                        "TASK",
                        null,
                        LocalDate.of(2026, 6, 10),
                        "A_FOLLOWUP",
                        2,
                        "SUBMITTED");
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of(record));
        when(formService.publishedVersion("RPT_CALL")).thenReturn(1);
        when(formService.writeGateData(anyString(), anyInt(), any()))
                .thenReturn(Map.of("call_time", "2026-11-05T09:00:00"));
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(700L);
                            return 1;
                        });

        Long reportId =
                service.createForContact(
                        PATIENT,
                        CONTACT,
                        TASK,
                        LocalDateTime.of(2026, 11, 5, 9, 0),
                        "患者诉近期胸闷（示例）",
                        List.of(6001L, 6002L),
                        7L);

        assertEquals(Long.valueOf(700L), reportId, "返回自动生成的报告单 id（随沟通创建响应回传）");
        ArgumentCaptor<ReportInsert> reportCaptor = ArgumentCaptor.forClass(ReportInsert.class);
        verify(mapper).insertReport(reportCaptor.capture());
        ReportInsert row = reportCaptor.getValue();
        assertEquals("CALL", row.typeCode());
        assertEquals("通话记录", row.typeName());
        assertEquals("随访电话录音", row.nameSnapshot(), "取 CALL 类型首个启用名称（V62 预置）");
        assertEquals(LocalDate.of(2026, 11, 5), row.reportDate(), "报告时间 = 沟通时间当天");
        assertEquals(Long.valueOf(CONTACT), row.contactLogId(), "关联单独存放（不算位置，tech-plan §10）");
        assertTrue(row.fieldValues().contains("2026-11-05T09:00:00"), "通话时间落类型字段值");

        ArgumentCaptor<LinkInsert> linkCaptor = ArgumentCaptor.forClass(LinkInsert.class);
        verify(mapper).insertLink(linkCaptor.capture());
        assertEquals("followup_record", linkCaptor.getValue().linkType());
        assertEquals(Long.valueOf(88L), linkCaptor.getValue().linkRecordId());
        assertNull(linkCaptor.getValue().anchorType(), "位置 = 任务对应随访记录（整条）");
        assertEquals(2, linkCaptor.getValue().templateVersion(), "模板版本取记录固化版本");

        verify(mapper, times(2))
                .insertFile(argThat(file -> file.fileId() == 6001L || file.fileId() == 6002L));
        verify(audit).append(any(AuditEntry.class)); // RPT_CREATE（自动生成）
    }

    @Test
    void noTaskRecordMeansUnassignedLocationAndStillCreatesFiles() {
        readyCallType();
        // 任务未开始录入（fu_task.record_id 为空）→ 无对应随访记录
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of());
        lenient().when(formService.publishedVersion("RPT_CALL")).thenReturn(1);
        lenient()
                .when(formService.writeGateData(anyString(), anyInt(), any()))
                .thenReturn(Map.of());
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(701L);
                            return 1;
                        });

        Long reportId =
                service.createForContact(
                        PATIENT,
                        CONTACT,
                        null,
                        LocalDateTime.of(2026, 11, 5, 9, 0),
                        null,
                        List.of(6001L),
                        7L);

        assertNotNull(reportId);
        verify(mapper, never()).insertLink(any()); // 「未指定位置」组（A-ATT-017）
        verify(mapper).insertFile(any());
    }

    @Test
    void skipsWhenNoAudio() {
        Long reportId =
                service.createForContact(
                        PATIENT,
                        CONTACT,
                        TASK,
                        LocalDateTime.of(2026, 11, 5, 9, 0),
                        null,
                        List.of(),
                        7L);

        assertNull(reportId, "无录音不生成（录音是通话记录报告单的载体）");
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void skipsWhenCallTypeDisabledOrMissing() {
        when(config.findTypeIdByCode("CALL")).thenReturn(null);

        assertNull(
                service.createForContact(
                        PATIENT,
                        CONTACT,
                        TASK,
                        LocalDateTime.of(2026, 11, 5, 9, 0),
                        null,
                        List.of(6001L),
                        7L),
                "CALL 类型不存在 → 跳过生成（OQ-103①），不阻断沟通记录创建");

        when(config.findTypeIdByCode("CALL")).thenReturn(9L);
        when(config.findType(9L)).thenReturn(typeRow("DISABLED"));

        assertNull(
                service.createForContact(
                        PATIENT,
                        CONTACT,
                        TASK,
                        LocalDateTime.of(2026, 11, 5, 9, 0),
                        null,
                        List.of(6001L),
                        7L),
                "CALL 类型停用（A-ATT-003 可停用）→ 同样跳过");
        verify(mapper, never()).insertReport(any());
    }

    @Test
    void stillCreatesReportWhenFormGateRejectsFieldValues() {
        readyCallType();
        when(mapper.recordsOfPatient(PATIENT)).thenReturn(List.of());
        when(formService.publishedVersion("RPT_CALL"))
                .thenThrow(new ApiException(404, 40400, "表单不存在"));
        when(mapper.insertReport(any()))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, ReportInsert.class).setId(702L);
                            return 1;
                        });

        Long reportId =
                service.createForContact(
                        PATIENT,
                        CONTACT,
                        TASK,
                        LocalDateTime.of(2026, 11, 5, 9, 0),
                        null,
                        List.of(6001L),
                        7L);

        assertNotNull(reportId, "字段闸门不通过只影响 field_values（OQ-103②），报告单照常生成");
        verify(mapper).insertReport(argThat(row -> row.fieldValues() == null));
    }

    @Test
    void softDeleteForContactRemovesLinkedReportsAndAudits() {
        ReportMapper.ReportHeadRow head =
                new ReportMapper.ReportHeadRow(
                        700L, PATIENT, "CALL", "通话记录", "随访电话录音", LocalDate.of(2026, 11, 5));
        when(mapper.callReportsOfContact(CONTACT)).thenReturn(List.of(head));
        when(mapper.softDeleteReport(eq(700L), anyString(), anyLong())).thenReturn(1);

        service.softDeleteForContact(CONTACT, 7L);

        verify(mapper).softDeleteReport(eq(700L), argThat(reason -> !reason.isBlank()), eq(7L));
        verify(audit).append(any(AuditEntry.class)); // RPT_DELETE（连带删除）
    }

    @Test
    void softDeleteForContactIsIdempotentOnConcurrentlyDeletedReport() {
        ReportMapper.ReportHeadRow head =
                new ReportMapper.ReportHeadRow(
                        700L, PATIENT, "CALL", "通话记录", "随访电话录音", LocalDate.of(2026, 11, 5));
        when(mapper.callReportsOfContact(CONTACT)).thenReturn(List.of(head));
        when(mapper.softDeleteReport(eq(700L), anyString(), anyLong())).thenReturn(0);

        service.softDeleteForContact(CONTACT, 7L);

        verify(audit, never()).append(any(AuditEntry.class)); // 已被他人删除：不重复留痕
    }

    // ---------------------------------------------------------------- 桩与工具

    private void readyCallType() {
        when(config.findTypeIdByCode("CALL")).thenReturn(9L);
        when(config.findType(9L)).thenReturn(typeRow("ENABLED"));
        when(config.findNames("CALL", true))
                .thenReturn(
                        List.of(
                                new ReportConfigMapper.NameRow(
                                        41L, "CALL", "通话记录", "随访电话录音", null, null, null, 10,
                                        "ENABLED")));
    }

    private static ReportConfigMapper.TypeRow typeRow(String status) {
        return new ReportConfigMapper.TypeRow(
                9L,
                "CALL",
                "通话记录",
                "RPT_CALL",
                "通话记录表单（示例）",
                true,
                null,
                90,
                status,
                "与沟通记录录音同源（A-ATT-017）",
                0L);
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "demoa",
                "医院 A（示例租户）",
                7L,
                "admin.demo",
                "管理员（示例）",
                List.of("TENANT_ADMIN"),
                List.of(),
                List.of(),
                List.of("followup"),
                1L,
                com.crdp.framework.security.ScopeSnapshot.allowAll(),
                false,
                null,
                "127.0.0.1",
                0L,
                0L,
                0L,
                0L,
                null);
    }
}
