package com.crdp.followup.notice.app;

import com.crdp.followup.notice.domain.NotifyChannel;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.domain.NotifyRuleCodec;
import com.crdp.followup.notice.infra.mapper.NotifyRuleMapper;
import com.crdp.followup.notice.infra.mapper.NotifyRuleRow;
import com.crdp.followup.notice.infra.mapper.NotifyUserPrefMapper;
import com.crdp.followup.notice.infra.mapper.NotifyUserPrefRow;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人通知偏好（E09-T07 / A-NTF-007、C-NTF-007；docs/api/notify.md §5，个人中心「通知偏好」页签）。
 *
 * <p>用户可关闭<b>非逾期类</b>事件的短信（只影响 SMS，站内信不提供个人关闭）； 逾期类（TASK_OVERDUE）能否关闭由租户设置 {@code
 * ntf_setting.overdue_sms_off_allowed} 决定，不允许时提交含逾期类 → 40001。 偏好由规则引擎投递短信前检查（关闭 → 台账记
 * SKIPPED，不并入每日汇总）。修改写 NOTICE_PREF_UPDATE 审计（本人）。
 */
@Service
public class NotifyPreferenceService {

    private final NotifyUserPrefMapper prefs;
    private final NotifyRuleMapper rules;
    private final NotifySettingsService settings;
    private final AuditLogger audit;

    public NotifyPreferenceService(
            NotifyUserPrefMapper prefs,
            NotifyRuleMapper rules,
            NotifySettingsService settings,
            AuditLogger audit) {
        this.prefs = prefs;
        this.rules = rules;
        this.settings = settings;
        this.audit = audit;
    }

    /** 可配置项：本院规则含短信渠道的事件（个人偏好只管短信）。 */
    public record PrefOption(String eventType, String label, boolean closeable) {}

    /** GET 视图：本人已关短信的事件 + 逾期类可否关闭（前端据此禁用选项）+ 可选项。 */
    public record PrefView(
            List<String> smsOff, boolean overdueSmsOffAllowed, List<PrefOption> options) {}

    /** PUT 请求体：全量覆盖本人已关短信的事件集合。 */
    public record PrefUpdate(List<String> smsOff) {}

    /** 本人偏好视图（未设置过 smsOff 为空数组）。 */
    public PrefView view(long userId) {
        NotifyUserPrefRow row = prefs.find(userId);
        boolean overdueAllowed = settings.overdueSmsOffAllowed();
        return new PrefView(
                row == null ? List.of() : parseCsv(row.smsOff()),
                overdueAllowed,
                options(overdueAllowed));
    }

    /** 保存本人偏好：只认已知事件编码；逾期类在租户不允许时 40001；写审计后返回最新视图。 */
    @Transactional
    public PrefView update(long userId, PrefUpdate request) {
        if (request == null || request.smsOff() == null) {
            throw invalid("请求体");
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String token : request.smsOff()) {
            String code = token == null ? "" : token.trim();
            if (NotifyEventType.fromCode(code).isEmpty()) {
                throw invalid("smsOff（未知通知事件 " + code + "）");
            }
            parsed.add(code);
        }
        boolean overdueAllowed = settings.overdueSmsOffAllowed();
        if (!overdueAllowed && parsed.contains("TASK_OVERDUE")) {
            throw new ApiException(400, 40001, "参数不合法：本院不允许个人关闭逾期类短信（已逾期是必须触达的提醒）");
        }
        NotifyUserPrefRow before = prefs.find(userId);
        String smsOff = String.join(",", parsed);
        prefs.upsert(userId, smsOff, userId);
        audit.append(
                AuditEntry.operation("notify", "NOTICE_PREF_UPDATE")
                        .objectType("NTF_USER_PREF")
                        .objectId(String.valueOf(userId))
                        .description("修改个人通知偏好（关闭部分事件短信）")
                        .beforeJson(
                                before == null
                                        ? "{\"smsOff\":\"\"}"
                                        : "{\"smsOff\":\"" + before.smsOff() + "\"}")
                        .afterJson("{\"smsOff\":\"" + smsOff + "\"}")
                        .build());
        return view(userId);
    }

    // ---------------------------------------------------------------- 内部

    /** 可选项：目录中生效渠道含 SMS 的事件（按本院规则缺行回落默认值），closeable=非逾期类或租户允许。 */
    private List<PrefOption> options(boolean overdueAllowed) {
        List<PrefOption> items = new ArrayList<>();
        for (NotifyEventType type : NotifyEventType.values()) {
            NotifyRuleRow row = rules.find(type.name()).orElse(null);
            List<NotifyChannel> channels =
                    row == null
                            ? type.defaultChannels()
                            : NotifyRuleCodec.parseChannels(row.channels());
            if (!channels.contains(NotifyChannel.SMS)) {
                continue;
            }
            items.add(
                    new PrefOption(
                            type.name(),
                            type.label(),
                            type != NotifyEventType.TASK_OVERDUE || overdueAllowed));
        }
        return items;
    }

    /** 解析 sms_off CSV：原样取 token（未知事件编码也保留展示，避免老库残留后页面丢已选项）。 */
    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return List.copyOf(tokens);
    }

    private static ApiException invalid(String field) {
        return new ApiException(400, 40001, "参数不合法：" + field);
    }
}
