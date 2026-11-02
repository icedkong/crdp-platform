package com.crdp.framework.notify.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** E09-T03 验收二：回执入库。腾讯云状态回执的三种历史 / 现行报文形态都能解析，逐条入库（sid 关联、手机号脱敏）； 坏报文不炸、不丢可识别条目。 */
class SmsReceiptHandlerTest {

    private final SmsSendLogStore sendLog = mock(SmsSendLogStore.class);
    private final SmsReceiptHandler handler = new SmsReceiptHandler(new ObjectMapper(), sendLog);

    @Test
    void parsesRootArrayForm() {
        int stored =
                handler.handle(
                        """
                        [{"user_receive_time":"2027-02-01 16:30:05","nation_code":"86",
                          "mobile":"13700005678","report_status":"SUCCESS","errmsg":"DELIVRD",
                          "description":"用户签收成功","sid":"5000:1045710669"}]
                        """);
        assertEquals(1, stored);
        verify(sendLog)
                .applyReceipt(
                        "5000:1045710669",
                        "SUCCESS",
                        "用户签收成功",
                        "2027-02-01 16:30:05",
                        "137****5678");
    }

    @Test
    void parsesCallbackArrayForm() {
        int stored =
                handler.handle(
                        """
                        {"sms_status_callback":[
                          {"user_receive_time":"2027-02-01 16:30:05","nationcode":"86",
                           "mobile":"13700005678","report_status":"FAIL","errmsg":"11",
                           "description":"instance not exist","sid":"sid-2"}]}
                        """);
        assertEquals(1, stored);
        verify(sendLog)
                .applyReceipt(
                        "sid-2",
                        "FAIL",
                        "instance not exist",
                        "2027-02-01 16:30:05",
                        "137****5678");
    }

    @Test
    void parsesNestedContentForm() {
        int stored =
                handler.handle(
                        """
                        {"sms_status_callback":{"call_back_time":"2027-02-01 16:30:06",
                         "call_back_count":1,"content":[
                          {"sid":"sid-3","report_status":"SUCCESS","description":"deliver",
                           "user_receive_time":"2027-02-01 16:30:04","user_receive_unix_time":1799999984,
                           "nation_code":"86","mobile":"13700005678","report_code":0}]}}
                        """);
        assertEquals(1, stored);
        verify(sendLog)
                .applyReceipt("sid-3", "SUCCESS", "deliver", "2027-02-01 16:30:04", "137****5678");
    }

    @Test
    void parsesSingleObjectForm() {
        int stored =
                handler.handle(
                        """
                        {"sid":"sid-4","report_status":"SUCCESS","errmsg":"DELIVRD",
                         "description":"deliver","user_receive_time":"2027-02-01 16:30:05",
                         "nation_code":"86","mobile":"13700005678"}
                        """);
        assertEquals(1, stored);
        verify(sendLog)
                .applyReceipt("sid-4", "SUCCESS", "deliver", "2027-02-01 16:30:05", "137****5678");
    }

    @Test
    void batchReceiptsAreEachStored() {
        int stored =
                handler.handle(
                        """
                        {"sms_status_callback":[
                          {"sid":"sid-a","report_status":"SUCCESS","description":"d1"},
                          {"sid":"sid-b","report_status":"FAIL","description":"d2"}]}
                        """);
        assertEquals(2, stored);
        verify(sendLog).applyReceipt("sid-a", "SUCCESS", "d1", null, null);
        verify(sendLog).applyReceipt("sid-b", "FAIL", "d2", null, null);
    }

    @Test
    void itemWithoutSidIsSkipped() {
        int stored = handler.handle("{\"sms_status_callback\":[{\"report_status\":\"SUCCESS\"}]}");
        assertEquals(0, stored);
        verify(sendLog, never())
                .applyReceipt(
                        Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void oneBadItemDoesNotAbortTheBatch() {
        // 第一条入库抛错（如库瞬断），第二条照常入库（腾讯按 result!=0 重推，部分入库好过整批丢弃）
        Mockito.doThrow(new RuntimeException("db hiccup"))
                .when(sendLog)
                .applyReceipt("sid-a", "SUCCESS", null, null, null);
        int stored =
                handler.handle(
                        "{\"sms_status_callback\":[{\"sid\":\"sid-a\",\"report_status\":\"SUCCESS\"},"
                                + "{\"sid\":\"sid-b\",\"report_status\":\"SUCCESS\"}]}");
        assertEquals(1, stored);
        verify(sendLog).applyReceipt(eq("sid-b"), eq("SUCCESS"), isNull(), isNull(), isNull());
    }

    @Test
    void malformedBodyIsIgnoredSafely() {
        assertEquals(0, handler.handle("not-json"));
        assertEquals(0, handler.handle("{\"unexpected\":1}"));
        assertEquals(0, handler.handle(""));
    }

    @Test
    void mobileIsMaskedBeforeStoring() {
        handler.handle(
                "{\"sid\":\"sid-9\",\"report_status\":\"SUCCESS\",\"mobile\":\"13700005678\"}");
        verify(sendLog).applyReceipt("sid-9", "SUCCESS", null, null, "137****5678");
    }

    @Test
    void shortMobileIsFullyMasked() {
        handler.handle("{\"sid\":\"sid-10\",\"report_status\":\"SUCCESS\",\"mobile\":\"123\"}");
        verify(sendLog).applyReceipt("sid-10", "SUCCESS", null, null, "***");
    }

    @Test
    void insertSqlCarriesUnmatchedReceipt() {
        // 白盒：UNMATCHED 行由 store 负责插入（见 TencentSmsChannelTest 的 store 用例），
        // 这里确认 handler 对未知 sid 也要求入库（含回执描述）
        handler.handle(
                "{\"sid\":\"sid-unknown\",\"report_status\":\"FAIL\",\"description\":\"黑名单\"}");
        verify(sendLog).applyReceipt("sid-unknown", "FAIL", "黑名单", null, null);
    }
}
