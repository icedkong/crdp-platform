package com.crdp.framework.notify.sms;

import com.crdp.framework.tenant.TenantContext;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台短信发送与回执台账（plt_sms_send_log，平台库；E09-T03 验收「回执入库」）。
 *
 * <p>每条经平台通道的真实发送落一行（SUBMITTED / SUBMIT_FAILED），腾讯云状态回执按流水号（SerialNo / sid） 回填 DELIVERED /
 * RECEIVE_FAILED；对不上发送行的回执也入库（status=UNMATCHED_RECEIPT），便于对账而不是丢弃。 手机号只存脱敏值（AGENTS.md 第 5
 * 节：日志与台账不写明文）。
 */
@Component
public class SmsSendLogStore {

    /** 回执里的时间格式（腾讯云：yyyy-MM-dd HH:mm:ss，北京时间）。 */
    private static final DateTimeFormatter RECEIVE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public SmsSendLogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 发送已受理（拿到 SerialNo）。 */
    public void recordSubmitted(
            String purpose,
            String tenantCode,
            String templateCode,
            String providerTemplateId,
            String serialNo,
            String phoneMasked) {
        TenantContext.runAsPlatform(
                () ->
                        jdbc.update(
                                "INSERT INTO plt_sms_send_log"
                                        + " (purpose, tenant_code, template_code, provider_template_id,"
                                        + " serial_no, phone_masked, status)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, 'SUBMITTED')",
                                purpose,
                                tenantCode,
                                templateCode,
                                providerTemplateId,
                                serialNo,
                                phoneMasked));
    }

    /** 发送未受理（通道未配置 / 模板问题 / 敏感信息拒绝 / 服务商报错）。 */
    public void recordSubmitFailed(
            String purpose,
            String tenantCode,
            String templateCode,
            String phoneMasked,
            String errorCode,
            String errorMessage) {
        TenantContext.runAsPlatform(
                () ->
                        jdbc.update(
                                "INSERT INTO plt_sms_send_log"
                                        + " (purpose, tenant_code, template_code, phone_masked, status,"
                                        + " error_code, error_message)"
                                        + " VALUES (?, ?, ?, ?, 'SUBMIT_FAILED', ?, ?)",
                                ps -> {
                                    ps.setString(1, purpose);
                                    ps.setString(2, tenantCode);
                                    ps.setString(3, templateCode);
                                    ps.setString(4, phoneMasked);
                                    ps.setString(5, truncate(errorCode, 64));
                                    ps.setString(6, truncate(errorMessage, 255));
                                }));
    }

    /**
     * 回执入库：先按流水号回填已有发送行（DELIVERED / RECEIVE_FAILED，provider 原始状态在 receipt_status）；
     * 查不到（含本平台之外发起的发送）则补一行 status=UNMATCHED_RECEIPT（purpose=UNMATCHED），不丢回执。
     *
     * @return true = 回填了发送行；false = 落了 UNMATCHED_RECEIPT
     */
    public boolean applyReceipt(
            String sid,
            String reportStatus,
            String description,
            String userReceiveTime,
            String mobileMasked) {
        String status = "SUCCESS".equalsIgnoreCase(reportStatus) ? "DELIVERED" : "RECEIVE_FAILED";
        LocalDateTime receiveAt = parseTime(userReceiveTime);
        return Boolean.TRUE.equals(
                TenantContext.callAsPlatform(
                        () -> {
                            int updated =
                                    jdbc.update(
                                            "UPDATE plt_sms_send_log"
                                                    + " SET status = ?, receipt_status = ?,"
                                                    + " receipt_description = ?, user_receive_time = ?,"
                                                    + " receipt_time = CURRENT_TIMESTAMP(3)"
                                                    + " WHERE serial_no = ?"
                                                    + " AND status IN ('SUBMITTED', 'DELIVERED',"
                                                    + " 'RECEIVE_FAILED')",
                                            ps -> {
                                                ps.setString(1, status);
                                                ps.setString(2, reportStatus);
                                                ps.setString(3, truncate(description, 255));
                                                setTimestamp(ps, 4, receiveAt);
                                                ps.setString(5, sid);
                                            });
                            if (updated > 0) {
                                return true;
                            }
                            jdbc.update(
                                    "INSERT INTO plt_sms_send_log"
                                            + " (purpose, serial_no, phone_masked, status, receipt_status,"
                                            + " receipt_description, user_receive_time, receipt_time)"
                                            + " VALUES ('UNMATCHED', ?, ?, 'UNMATCHED_RECEIPT', ?, ?, ?,"
                                            + " CURRENT_TIMESTAMP(3))",
                                    ps -> {
                                        ps.setString(1, sid);
                                        ps.setString(2, mobileMasked);
                                        ps.setString(3, reportStatus);
                                        ps.setString(4, truncate(description, 255));
                                        setTimestamp(ps, 5, receiveAt);
                                    });
                            return false;
                        }));
    }

    private static void setTimestamp(java.sql.PreparedStatement ps, int index, LocalDateTime time)
            throws java.sql.SQLException {
        if (time == null) {
            ps.setNull(index, Types.TIMESTAMP);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(time));
        }
    }

    private static LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), RECEIVE_TIME);
        } catch (RuntimeException notParsable) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
