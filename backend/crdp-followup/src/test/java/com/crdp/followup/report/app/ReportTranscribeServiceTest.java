package com.crdp.followup.report.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.report.infra.mapper.ReportFeatureMapper;
import com.crdp.followup.report.infra.mapper.ReportMapper;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E21-T17 / C-SCAN-012、C-FU-017 单测锁定（全部虚构数据）：转录到结构化记录完成口径——
 * 位置中无该随访记录时自动加入位置（保存后该记录自动加入扫描件的位置）、电子化状态自动变为 DONE（转录后电子化状态自动更新），二者与记录提交同一事务；留痕写 RPT_TRANSCRIBE 与
 * RPT_LOCATION_CHANGE（自动加入时）操作日志。
 */
@ExtendWith(MockitoExtension.class)
class ReportTranscribeServiceTest {

    private static final long OPERATOR = 7L;

    private static final long PATIENT = 301L;

    private final ReportMapper mapper = mock(ReportMapper.class);
    private final ReportFeatureMapper features = mock(ReportFeatureMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private final ReportTranscribeService service =
            new ReportTranscribeService(mapper, features, audit);

    /** 电子化状态行（PENDING 当前态，version 4）。 */
    private static ReportMapper.DigitizeRow digitizeRow(String status) {
        return new ReportMapper.DigitizeRow(900L, PATIENT, "纸质随访表", "随访程控记录单（示例）", status, 4);
    }

    /** 在册位置行。 */
    private static ReportMapper.LinkRow link(long reportId, long recordId, String anchorType) {
        return new ReportMapper.LinkRow(
                1L,
                reportId,
                "followup_record",
                recordId,
                anchorType,
                anchorType == null ? null : "battery",
                3,
                0);
    }

    @BeforeEach
    void login() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new org.springframework.security.authentication
                                .UsernamePasswordAuthenticationToken(principal(), null, List.of()));
    }

    private static SessionPrincipal principal() {
        return new SessionPrincipal(
                "token",
                "HOSPITAL",
                1L,
                "democ",
                "医院 C（示例租户）",
                OPERATOR,
                "doctor.demo",
                "张医生（示例）",
                List.of("DOCTOR"),
                List.of(),
                List.of("followup:record:edit"),
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

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 位置中没有该记录：插入整条记录位置（模板版本取记录固化版本）并把状态置 DONE，双审计留痕。 */
    @Test
    void completesTranscriptionAddsLocationAndMarksDone() {
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));
        when(mapper.linksOfPatient(PATIENT)).thenReturn(List.of());

        ReportTranscribeService.TranscribedView view =
                service.completeTranscription(900L, 501L, PATIENT, 3);

        assertEquals("900", view.id());
        assertEquals("DONE", view.digitizeStatus());
        assertEquals(5, view.version(), "状态落列推进 version + 1（与手工修改同口径）");
        verify(mapper)
                .insertLink(
                        argThat(
                                row ->
                                        row.reportId() == 900L
                                                && row.patientId() == PATIENT
                                                && "followup_record".equals(row.linkType())
                                                && row.linkRecordId() == 501L
                                                && row.anchorType() == null
                                                && row.anchorKey() == null
                                                && row.templateVersion() == 3));
        verify(mapper).updateDigitizeStatus(900L, "DONE", OPERATOR);

        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit, org.mockito.Mockito.times(2)).append(captor.capture());
        List<AuditEntry> entries = captor.getAllValues();
        AuditEntry locationChange =
                entries.stream()
                        .filter(item -> "RPT_LOCATION_CHANGE".equals(item.action()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(locationChange.description().contains("转录"), locationChange.description());
        AuditEntry transcribe =
                entries.stream()
                        .filter(item -> "RPT_TRANSCRIBE".equals(item.action()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("900", transcribe.objectId());
        assertTrue(transcribe.beforeJson().contains("PENDING"), transcribe.beforeJson());
        assertTrue(transcribe.afterJson().contains("DONE"), transcribe.afterJson());
        assertTrue(transcribe.description().contains("待录入 → 已电子化"), transcribe.description());
    }

    /** 位置中已有该记录（整条或分组同记录）：不重复插入位置，只更新状态。 */
    @Test
    void skipsLocationInsertWhenRecordAlreadyLinked() {
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));
        when(mapper.linksOfPatient(PATIENT)).thenReturn(List.of(link(900L, 501L, "section")));

        service.completeTranscription(900L, 501L, PATIENT, 3);

        verify(mapper, never()).insertLink(any());
        verify(mapper).updateDigitizeStatus(900L, "DONE", OPERATOR);
    }

    /** 已电子化的报告单重复转录：幂等——状态与位置都不动、不写审计（无状态变化不留痕）。 */
    @Test
    void alreadyDoneIsIdempotent() {
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("DONE"));
        when(mapper.linksOfPatient(PATIENT)).thenReturn(List.of(link(900L, 501L, null)));

        ReportTranscribeService.TranscribedView view =
                service.completeTranscription(900L, 501L, PATIENT, 3);

        assertEquals("DONE", view.digitizeStatus());
        assertEquals(4, view.version(), "无变化不推进 version");
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
        verify(mapper, never()).insertLink(any());
        verify(audit, never()).append(any());
    }

    /** 状态为 NULL（类型 / 名称未配置默认值）也可转录：置 DONE，before 记「未设置」。 */
    @Test
    void transcribesFromNullStatus() {
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow(null));
        when(mapper.linksOfPatient(PATIENT)).thenReturn(List.of(link(900L, 501L, null)));

        service.completeTranscription(900L, 501L, PATIENT, 3);

        verify(mapper).updateDigitizeStatus(900L, "DONE", OPERATOR);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertTrue(
                captor.getValue().description().contains("未设置 → 已电子化"),
                captor.getValue().description());
    }

    /** 开关 scan_progress 关闭（含无行）→ 40301（医院 A / B 不启用，AGENTS §5.7）。 */
    @Test
    void featureOffRejected() {
        lenient().when(features.findEnabled("scan_progress")).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.completeTranscription(900L, 501L, PATIENT, 3));
        assertEquals(40301, error.code());
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
    }

    /** 越范围 / 不存在 / 已删报告单 40400（写 DENIED 审计，不暴露对象存在）。 */
    @Test
    void outOfScopeDeniedAndAudited() {
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(mapper.findDigitizeRow(900L)).thenReturn(null);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.completeTranscription(900L, 501L, PATIENT, 3));
        assertEquals(40400, error.code());
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).append(captor.capture());
        assertEquals("RPT_TRANSCRIBE", captor.getValue().action());
        assertEquals("DENIED", String.valueOf(captor.getValue().result()));
    }

    /** 报告单与记录不属于同一患者 → 40001（不静默挂错位置）。 */
    @Test
    void patientMismatchRejected() {
        when(features.findEnabled("scan_progress")).thenReturn(true);
        when(mapper.findDigitizeRow(900L)).thenReturn(digitizeRow("PENDING"));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () -> service.completeTranscription(900L, 501L, 999L, 3));
        assertEquals(40001, error.code());
        verify(mapper, never()).updateDigitizeStatus(anyLong(), anyString(), anyLong());
        verify(mapper, never()).insertLink(any());
    }
}
