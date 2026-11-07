package com.crdp.followup.importer.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.importer.app.ImportCommitService.ConfirmOptions;
import com.crdp.followup.importer.app.ImportJobService;
import com.crdp.followup.importer.app.ImportJobService.FileDownload;
import com.crdp.followup.importer.app.ImportJobService.IssueItem;
import com.crdp.followup.importer.app.ImportJobService.IssuePage;
import com.crdp.followup.importer.app.ImportJobService.UploadedJob;
import com.crdp.framework.web.ApiResponse;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;

/**
 * E18-T06 控制器测试：模板 / 报告的附件响应头（中文文件名进 filename*，RFC 5987）， 上传与确认 / 取消 / 重试 / 回滚的参数透传。
 * 权限注解（followup:patient:import）与功能开关由服务面测试覆盖（40301 / 门控），这里只锁定 HTTP 形状。
 */
class ImportJobControllerTest {

    private final ImportJobService imports = mock(ImportJobService.class);
    private final ImportJobController controller = new ImportJobController(imports);

    @Test
    void templateReturnsXlsxAttachment() {
        when(imports.template("WIDE"))
                .thenReturn(new FileDownload("xlsx".getBytes(), "患者导入模板（宽表）.xlsx"));

        var response = controller.template("WIDE");

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals("xlsx".getBytes(), response.getBody());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment"), "attachment 附件下载：" + disposition);
        assertTrue(disposition.contains("filename*="), "中文文件名进 filename*：" + disposition);
        assertTrue(
                response.getHeaders().getContentType().toString().contains("spreadsheetml"),
                "MIME 为 xlsx");
    }

    @Test
    void uploadReadsMultipartFileAndReturnsJobId() throws IOException {
        MockMultipartFile file =
                new MockMultipartFile("file", "患者导入（示例）.xlsx", null, "bytes".getBytes());
        when(imports.upload("患者导入（示例）.xlsx", "bytes".getBytes())).thenReturn(new UploadedJob(9));

        ApiResponse<UploadedJob> response = controller.upload(file);

        assertEquals(0, response.code());
        assertEquals(9, response.data().jobId());
    }

    @Test
    void issuesPassesPagingAndLevel() {
        when(imports.issues(9L, "ERROR", 2, 20))
                .thenReturn(
                        new IssuePage(
                                List.of(
                                        new IssueItem(
                                                1,
                                                "ERROR",
                                                "患者数据",
                                                3,
                                                "M",
                                                "手术日期",
                                                "BAD_DATE",
                                                "不是有效日期（示例）")),
                                21,
                                2,
                                20));

        ApiResponse<IssuePage> response = controller.issues(9L, "ERROR", 2, 20);

        assertEquals(21, response.data().total());
        verify(imports).issues(eq(9L), eq("ERROR"), eq(2), eq(20));
    }

    @Test
    void confirmPassesOptions() {
        ConfirmOptions options = new ConfirmOptions("UPDATE", 5L);
        when(imports.confirm(9L, options)).thenReturn(new UploadedJob(9));

        ApiResponse<UploadedJob> response = controller.confirm(9L, options);

        assertEquals(9, response.data().jobId());
        verify(imports).confirm(9L, options);
    }

    @Test
    void resultReturnsAnnotatedReportAttachment() {
        when(imports.report(9L))
                .thenReturn(new FileDownload("report".getBytes(), "导入校验报告-作业9.xlsx"));

        var response = controller.result(9L);

        assertArrayEquals("report".getBytes(), response.getBody());
        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.contains("filename*="));
    }

    @Test
    void rollbackBatchPassesJobAndBatchNo() {
        when(imports.rollbackBatch(9L, 2)).thenReturn(null);
        controller.rollbackBatch(9L, 2);
        verify(imports).rollbackBatch(9L, 2);
        when(imports.retry(9L)).thenReturn(new UploadedJob(9));
        controller.retry(9L);
        verify(imports).retry(9L);
        controller.cancel(9L);
        verify(imports).cancel(9L);
    }
}
