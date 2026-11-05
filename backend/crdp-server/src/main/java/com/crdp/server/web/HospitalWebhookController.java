package com.crdp.server.web;

import com.crdp.framework.security.RequiresPermission;
import com.crdp.framework.web.ApiResponse;
import com.crdp.system.webhook.WebhookAdminService;
import com.crdp.system.webhook.WebhookDeliveryService;
import com.crdp.system.webhook.WebhookEventType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007：医院端「机构设置 · Webhook」维护接口（路径与 docs/api/notify.md §6、
 * system.md §4 同步）。查看与维护都用 {@code tenant:webhook:edit}（规格权限点表只有维护一个码）。 增删改 / 测试 /
 * 重投全写操作审计（WEBHOOK_*）。
 */
@Tag(name = "医院端机构设置 · Webhook")
@RestController
@RequestMapping("/api/hospital/system/webhooks")
@Validated
public class HospitalWebhookController {

    private final WebhookAdminService admin;
    private final WebhookDeliveryService delivery;

    public HospitalWebhookController(WebhookAdminService admin, WebhookDeliveryService delivery) {
        this.admin = admin;
        this.delivery = delivery;
    }

    public record WebhookRequest(
            @Size(max = 100) String name,
            @Size(max = 500) String url,
            @Size(max = 128) String secret,
            List<String> events,
            Boolean enabled) {}

    @Operation(summary = "本院 Webhook 清单（不含签名密钥；events 为订阅事件编码）")
    @GetMapping
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<List<WebhookAdminService.WebhookView>> list() {
        return ApiResponse.ok(admin.list());
    }

    @Operation(
            summary =
                    "新建 Webhook（仅 HTTPS 公网地址，内网 / 回环拒绝 40001）。签名密钥系统生成，"
                            + "只在本次响应回显一次（secretShownOnce），请立即配置到接收方")
    @PostMapping
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<WebhookAdminService.SaveResult> create(@RequestBody WebhookRequest request) {
        return ApiResponse.ok(
                admin.create(
                        new WebhookAdminService.CreateCommand(
                                request.name(),
                                request.url(),
                                request.secret(),
                                request.events())));
    }

    @Operation(
            summary = "修改 Webhook（全量字段可改；secret 传 null / 空白保持不变，传新值即更换并在响应回显一次）。" + "回调地址仍走内网拒绝校验")
    @PutMapping("/{id}")
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<WebhookAdminService.SaveResult> update(
            @PathVariable long id, @RequestBody WebhookRequest request) {
        return ApiResponse.ok(
                admin.update(
                        new WebhookAdminService.UpdateCommand(
                                id,
                                request.name(),
                                request.url(),
                                request.secret(),
                                request.events(),
                                request.enabled())));
    }

    @Operation(summary = "删除 Webhook（队列与投递记录一并清除，写审计）")
    @DeleteMapping("/{id}")
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<Void> delete(@PathVariable long id) {
        admin.delete(id);
        return ApiResponse.ok(null);
    }

    @Operation(
            summary =
                    "连通性测试：立即推送一条 WEBHOOK_TEST（不占重试队列），响应回显时间戳与签名头值，"
                            + "接收方按 notify.md §6 校验（验收「签名可校验」）")
    @PostMapping("/{id}/test")
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<WebhookDeliveryService.TestResult> test(@PathVariable long id) {
        return ApiResponse.ok(delivery.test(id));
    }

    @Operation(summary = "投递记录（按状态过滤；pageNo 从 1、pageSize 默认 20 上限 100）")
    @GetMapping("/{id}/deliveries")
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<WebhookDeliveryService.DeliveryPage> deliveries(
            @PathVariable long id,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(delivery.deliveries(id, status, pageNo, pageSize));
    }

    @Operation(summary = "失败手动重投（FAILED → 重新排队，从第 1 次重计退避；写审计）")
    @PostMapping("/{id}/deliveries/{deliveryId}/redeliver")
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<WebhookDeliveryService.RedeliverResult> redeliver(
            @PathVariable long id, @PathVariable long deliveryId) {
        return ApiResponse.ok(delivery.redeliver(id, deliveryId));
    }

    /** 可订阅事件目录（配置页多选项：编码 + 中文名；A / C 差异由本院勾选体现）。 */
    public record EventOption(String code, String label) {}

    @Operation(summary = "可订阅事件目录（TASK_OPENED / TASK_OVERDUE / RECORD_SUBMITTED / PATIENT_DEAD）")
    @GetMapping("/events")
    @RequiresPermission("tenant:webhook:edit")
    public ApiResponse<List<EventOption>> events() {
        return ApiResponse.ok(
                WebhookEventType.subscribable().stream()
                        .map(type -> new EventOption(type.name(), type.label()))
                        .toList());
    }
}
