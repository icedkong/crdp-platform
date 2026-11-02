package com.crdp.framework.notify.sms;

import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 平台短信模板读取（plt_sms_template，平台库）。短信可能在登录前（无租户上下文）发出， 读写一律显式包 {@link
 * TenantContext#callAsPlatform}，不依赖调用方上下文。
 */
@Component
public class SmsTemplateStore {

    private final JdbcTemplate jdbc;

    public SmsTemplateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编码取模板（含未审核的，状态判断交给通道层给出准确失败原因）。 */
    public Optional<SmsTemplateView> findByCode(String code) {
        return TenantContext.callAsPlatform(
                () ->
                        jdbc
                                .query(
                                        "SELECT code, scene, content, provider_template_id, sign_name,"
                                                + " status FROM plt_sms_template WHERE code = ?",
                                        (rs, rowNum) ->
                                                new SmsTemplateView(
                                                        rs.getString("code"),
                                                        rs.getString("scene"),
                                                        rs.getString("content"),
                                                        rs.getString("provider_template_id"),
                                                        rs.getString("sign_name"),
                                                        rs.getString("status")),
                                        code)
                                .stream()
                                .findFirst());
    }

    /** 某场景已审核通过的可选模板（通知设置页只让租户在平台审核过的模板里选，notify.md §2）。 不要求已回填腾讯云模板 ID——是否可真实发送由通道层给出准确失败原因。 */
    public List<SmsTemplateOption> listApprovedByScene(String scene) {
        return TenantContext.callAsPlatform(
                () ->
                        jdbc.query(
                                "SELECT code, name, content FROM plt_sms_template"
                                        + " WHERE scene = ? AND status = 'APPROVED' ORDER BY code",
                                (rs, rowNum) ->
                                        new SmsTemplateOption(
                                                rs.getString("code"),
                                                rs.getString("name"),
                                                rs.getString("content")),
                                scene));
    }
}
