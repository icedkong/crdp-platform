package com.crdp.followup.notice.app;

import com.crdp.followup.notice.infra.mapper.NotifySettingMapper;
import com.crdp.followup.notice.infra.mapper.NotifySettingRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户通知发送设置（E09-T07 落地存储与维护；docs/api/notify.md §2 settings）。
 *
 * <p>免打扰时段（默认 21:00–08:00，A-NTF-005 / C-NTF-005 医院已确认）与每人每日短信上限（默认 3 建议值） 由 E09-T05
 * 汇总作业执行——本任务先落配置面（保存即时生效，作业消费现行值）； 逾期类是否允许个人关闭（A-NTF-007）由本任务消费（个人偏好校验）。 修改写
 * NOTICE_SETTINGS_UPDATE 配置审计（PLT-AUD-001 口径）。
 */
@Service
public class NotifySettingsService {

    /** HH:mm 口径（与 ntf_rule.send_time 一致）。 */
    private static final Pattern TIME_OF_DAY = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** 每人每日短信上限范围（0 会关掉全部短信；A-NTF-005 是频控不是停发，下限 1）。 */
    private static final int DAILY_LIMIT_MIN = 1;

    private static final int DAILY_LIMIT_MAX = 20;

    private final NotifySettingMapper settings;
    private final AuditLogger audit;

    public NotifySettingsService(NotifySettingMapper settings, AuditLogger audit) {
        this.settings = settings;
        this.audit = audit;
    }

    /** 设置视图（notify.md §2）：HH:mm 口径；行缺失防御性回落默认值（种子保证有行）。 */
    public record SettingsView(
            String dndStart, String dndEnd, int smsDailyLimit, boolean overdueSmsOffAllowed) {

        static SettingsView of(NotifySettingRow row) {
            return new SettingsView(
                    row.dndStart().toString(),
                    row.dndEnd().toString(),
                    row.smsDailyLimit(),
                    row.overdueSmsOffAllowed());
        }
    }

    /** PUT 请求体：全量覆盖。 */
    public record SettingsUpdate(
            String dndStart, String dndEnd, Integer smsDailyLimit, Boolean overdueSmsOffAllowed) {}

    /** 生效设置（缺行回落默认值：21:00–08:00 / 3 / 不允许关闭逾期短信）。 */
    public SettingsView get() {
        NotifySettingRow row = settings.find();
        return row == null ? defaults() : SettingsView.of(row);
    }

    /** 逾期类短信是否允许个人关闭（A-NTF-007；个人偏好校验与展示用）。 */
    public boolean overdueSmsOffAllowed() {
        return get().overdueSmsOffAllowed();
    }

    /** 修改设置：校验 → 落库 → 审计；返回最新值。 */
    @Transactional
    public SettingsView update(SettingsUpdate request) {
        if (request == null) {
            throw invalid("请求体");
        }
        LocalTime dndStart = validateTime(request.dndStart(), "dndStart");
        LocalTime dndEnd = validateTime(request.dndEnd(), "dndEnd");
        if (dndStart.equals(dndEnd)) {
            throw invalid("dndStart / dndEnd（免打扰起止不能相同）");
        }
        if (request.smsDailyLimit() == null) {
            throw invalid("smsDailyLimit");
        }
        if (request.smsDailyLimit() < DAILY_LIMIT_MIN
                || request.smsDailyLimit() > DAILY_LIMIT_MAX) {
            throw invalid("smsDailyLimit（" + DAILY_LIMIT_MIN + "–" + DAILY_LIMIT_MAX + "）");
        }
        if (request.overdueSmsOffAllowed() == null) {
            throw invalid("overdueSmsOffAllowed");
        }
        SettingsView before = get();
        long operatorId = CurrentSession.requireHospital().userId();
        settings.update(
                new NotifySettingRow(
                        1L,
                        dndStart,
                        dndEnd,
                        request.smsDailyLimit(),
                        request.overdueSmsOffAllowed(),
                        operatorId,
                        null));
        SettingsView after = get();
        audit.append(
                AuditEntry.operation("notify", "NOTICE_SETTINGS_UPDATE")
                        .objectType("NTF_SETTING")
                        .objectId("1")
                        .description("修改通知发送设置（免打扰 / 频控 / 逾期短信可否个人关闭）")
                        .beforeJson(compact(before))
                        .afterJson(compact(after))
                        .build());
        return after;
    }

    // ---------------------------------------------------------------- 内部

    private static SettingsView defaults() {
        return new SettingsView("21:00", "08:00", 3, false);
    }

    private LocalTime validateTime(String value, String field) {
        if (value == null || !TIME_OF_DAY.matcher(value.trim()).matches()) {
            throw invalid(field + "（HH:mm）");
        }
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException broken) {
            throw invalid(field + "（HH:mm）");
        }
    }

    private static String compact(SettingsView view) {
        return "{\"dndStart\":\""
                + view.dndStart()
                + "\",\"dndEnd\":\""
                + view.dndEnd()
                + "\",\"smsDailyLimit\":"
                + view.smsDailyLimit()
                + ",\"overdueSmsOffAllowed\":"
                + view.overdueSmsOffAllowed()
                + "}";
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
