package com.crdp.server.web;

import com.crdp.framework.notify.sms.MockSmsInbox;
import com.crdp.framework.notify.sms.MockSmsMessage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 演示用模拟短信列表。只在 {@code crdp.sms.provider=mock} 时注册。不发真实短信，页面上的验证码只属于虚构账号。 */
@RestController
@ConditionalOnProperty(name = "crdp.sms.provider", havingValue = "mock")
public class MockSmsPageController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);

    private final MockSmsInbox inbox;

    public MockSmsPageController(MockSmsInbox inbox) {
        this.inbox = inbox;
    }

    @GetMapping(value = "/dev/mock-sms", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        List<MockSmsMessage> messages = inbox.recent();
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">");
        html.append("<title>模拟短信（虚构数据）</title>");
        html.append("<style>body{font-family:sans-serif;margin:24px;}");
        html.append("table{border-collapse:collapse;width:100%;}td,th{border:1px solid #ccc;");
        html.append("padding:8px;text-align:left;} .warn{background:#fff7e6;padding:12px;}");
        html.append("</style></head><body>");
        html.append("<h1>模拟短信</h1>");
        html.append("<p class=\"warn\">这些短信没有发到任何手机，也没有离开本机。");
        html.append("只用于虚构账号演示。接入华为云 KMS 之前禁止导入真实患者数据。");
        html.append("验证码同时写在后端日志里，检索「模拟短信」。</p>");
        if (messages.isEmpty()) {
            html.append("<p>还没有模拟短信。在医院端或平台端点「获取验证码」后再刷新本页。</p>");
        } else {
            html.append("<table><thead><tr><th>时间（北京）</th><th>场景</th><th>手机号</th>");
            html.append("<th>模板</th><th>参数（验证码）</th></tr></thead><tbody>");
            for (MockSmsMessage message : messages) {
                html.append("<tr><td>")
                        .append(
                                escape(
                                        TIME.format(
                                                Instant.ofEpochMilli(message.sentAtEpochMilli()))))
                        .append("</td><td>")
                        .append(escape(message.scene()))
                        .append("</td><td>")
                        .append(escape(message.phoneMasked()))
                        .append("</td><td>")
                        .append(escape(message.templateCode()))
                        .append("</td><td><strong>")
                        .append(escape(message.params()))
                        .append("</strong></td></tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
