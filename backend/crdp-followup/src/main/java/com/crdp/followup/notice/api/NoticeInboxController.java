package com.crdp.followup.notice.api;

import com.crdp.followup.notice.app.NoticeInboxService;
import com.crdp.framework.security.PublicApi;
import com.crdp.framework.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 医院端站内信收件箱（E09-T02 / A-NTF-002、C-NTF-002；docs/api/notify.md §1）。
 *
 * <p>登录即可（@PublicApi：不绑定权限码，医院端令牌必须有效）；收件箱按 recipient_id 隔离， 本人以外 / 不存在的消息一律
 * 40400。患者姓名读取时按接收人当前字段策略脱敏（序列化面 @MaskedByPolicy）； 点击跳转由前端按 target 路由到任务 / 患者 /
 * 录入页，落点接口按数据范围再校验（越范围 40400，fu_task 在 ControlledTables）。
 */
@PublicApi
@Tag(name = "医院端站内信")
@RestController
public class NoticeInboxController {

    private final NoticeInboxService inbox;

    public NoticeInboxController(NoticeInboxService inbox) {
        this.inbox = inbox;
    }

    @Operation(summary = "本人站内信（A-NTF-002）：unread 只看未读；行含事件、标题、摘要、脱敏姓名与跳转 target")
    @GetMapping("/api/hospital/notify/messages")
    public ApiResponse<NoticeInboxService.InboxPage> messages(
            @RequestParam(value = "unread", required = false) Boolean unread,
            @RequestParam(value = "pageNo", required = false) Integer pageNo,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return ApiResponse.ok(inbox.page(unread, pageNo, pageSize));
    }

    @Operation(summary = "未读数：顶栏铃铛角标（前端 60 秒轮询或页面可见时刷新）")
    @GetMapping("/api/hospital/notify/messages/unread-count")
    public ApiResponse<UnreadCount> unreadCount() {
        return ApiResponse.ok(new UnreadCount(inbox.unreadCount()));
    }

    @Operation(summary = "标一条已读：别人的消息 / 不存在 40400（不暴露对象存在，写 DENIED 审计）")
    @PostMapping("/api/hospital/notify/messages/{id}/read")
    public ApiResponse<Void> markRead(@PathVariable("id") long id) {
        inbox.markRead(id);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "全部已读：只影响本人未读行")
    @PostMapping("/api/hospital/notify/messages/read-all")
    public ApiResponse<MarkedCount> markAllRead() {
        return ApiResponse.ok(new MarkedCount(inbox.markAllRead()));
    }

    /** 未读数。 */
    public record UnreadCount(long unread) {}

    /** 置为已读的条数。 */
    public record MarkedCount(int marked) {}
}
