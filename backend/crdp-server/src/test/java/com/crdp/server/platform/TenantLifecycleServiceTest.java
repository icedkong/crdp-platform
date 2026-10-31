package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E05-T02（PLT-TEN-010、011、013）纯函数：状态流转矩阵与到期设置的合并校验。
 *
 * <p>状态流转严格按平台规格第 2 章的状态图：人工可达的边只有 转正（试用→启用）、手动停用（启用→停用）、恢复（停用→启用）；
 * 宽限期→启用只走续期（PLT-TEN-013），试用/宽限期→停用、开通与注销各态的人工流转一律 40901。
 */
class TenantLifecycleServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    /** 宽限期/停用中续期立即恢复；其余状态到期设置不改状态。 */
    @Test
    void renewalRestoresGraceAndDisabledTenants() {
        assertEquals("ENABLED", TenantLifecycleService.statusAfterRenewal("GRACE"));
        assertEquals("ENABLED", TenantLifecycleService.statusAfterRenewal("DISABLED"));
        assertEquals("TRIAL", TenantLifecycleService.statusAfterRenewal("TRIAL"));
        assertEquals("ENABLED", TenantLifecycleService.statusAfterRenewal("ENABLED"));
        ApiException provisioning =
                assertThrows(
                        ApiException.class,
                        () -> TenantLifecycleService.statusAfterRenewal("PROVISIONING"));
        assertEquals(40901, provisioning.code());
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () -> TenantLifecycleService.statusAfterRenewal("CANCELLING"))
                        .code());
        assertEquals(
                40901,
                assertThrows(
                                ApiException.class,
                                () -> TenantLifecycleService.statusAfterRenewal("CANCELLED"))
                        .code());
    }

    @Test
    void manualTransitionsFollowTheSpecDiagram() {
        // 仅有的人工边
        assertEquals("ENABLED", TenantLifecycleService.transitionTarget("TRIAL", "ENABLE"));
        assertEquals("ENABLED", TenantLifecycleService.transitionTarget("DISABLED", "ENABLE"));
        assertEquals("DISABLED", TenantLifecycleService.transitionTarget("ENABLED", "DISABLE"));

        // 非法 action
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantLifecycleService.transitionTarget("TRIAL", "RESTART"))
                        .code());

        // 状态图上没有的边全部 40901，且文案说明原因
        assertMessage("试用租户须先转正", "TRIAL", "DISABLE");
        assertMessage("宽限期租户请通过「续期」恢复启用", "GRACE", "ENABLE");
        assertMessage("自动停用", "GRACE", "DISABLE");
        assertMessage("开通", "PROVISIONING", "ENABLE");
        assertMessage("开通", "PROVISIONING", "DISABLE");
        assertMessage("注销", "CANCELLING", "ENABLE");
        assertMessage("注销", "CANCELLED", "DISABLE");
        assertMessage("已是启用状态", "ENABLED", "ENABLE");
        assertMessage("已是停用状态", "DISABLED", "DISABLE");
    }

    private static void assertMessage(String fragment, String status, String action) {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () -> TenantLifecycleService.transitionTarget(status, action));
        assertEquals(40901, ex.code());
        assertTrue(ex.getMessage().contains(fragment), ex.getMessage());
    }

    @Test
    void longTermExpiryClearsReminderAndGrace() {
        TenantLifecycleService.ExpiryView merged =
                TenantLifecycleService.mergedExpiry(
                        fixed(LocalDate.of(2027, 1, 31), List.of(30, 7, 1), 15, true),
                        new TenantLifecycleService.ExpireRequest(
                                "LONG_TERM", null, null, null, null, "转长期"),
                        TODAY);

        assertEquals("LONG_TERM", merged.expireType());
        assertNull(merged.expireAt());
        assertNull(merged.remindDays());
        assertNull(merged.graceDays());
        assertEquals(false, merged.graceReadOnly());
    }

    @Test
    void fixedExpiryFillsDefaultsForFirstTimeConfiguration() {
        TenantLifecycleService.ExpiryView merged =
                TenantLifecycleService.mergedExpiry(
                        new TenantLifecycleService.ExpiryView("LONG_TERM", null, null, null, false),
                        new TenantLifecycleService.ExpireRequest(
                                "FIXED", "2027-06-30", null, null, null, "开通一年"),
                        TODAY);

        assertEquals("FIXED", merged.expireType());
        assertEquals(LocalDate.of(2027, 6, 30), merged.expireAt());
        assertEquals(List.of(30, 7, 1), merged.remindDays(), "默认提醒节点 30、7、1 天");
        assertEquals(15, merged.graceDays(), "默认宽限期 15 天");
        assertEquals(false, merged.graceReadOnly());
    }

    @Test
    void omittedReminderAndGraceKeepCurrentValuesOnRenewal() {
        TenantLifecycleService.ExpiryView current =
                fixed(LocalDate.of(2026, 11, 30), List.of(7, 1), 30, true);
        TenantLifecycleService.ExpiryView merged =
                TenantLifecycleService.mergedExpiry(
                        current,
                        new TenantLifecycleService.ExpireRequest(
                                "FIXED", "2027-11-30", null, null, null, "续费一年"),
                        TODAY);

        assertEquals(LocalDate.of(2027, 11, 30), merged.expireAt());
        assertEquals(List.of(7, 1), merged.remindDays(), "续期只改到期日，保留已配置的提醒节点");
        assertEquals(30, merged.graceDays(), "保留已配置的宽限期天数");
        assertEquals(true, merged.graceReadOnly());
    }

    @Test
    void explicitReminderNodesAreDeduplicatedAndSorted() {
        TenantLifecycleService.ExpiryView merged =
                TenantLifecycleService.mergedExpiry(
                        fixed(LocalDate.of(2027, 1, 31), List.of(30, 7, 1), 15, false),
                        new TenantLifecycleService.ExpireRequest(
                                "FIXED", "2027-01-31", List.of(1, 14, 7, 14), 20, true, "调整节点"),
                        TODAY);

        assertEquals(List.of(14, 7, 1), merged.remindDays());
        assertEquals(20, merged.graceDays());
        assertEquals(true, merged.graceReadOnly());
    }

    @Test
    void expiryValidationRejectsBadInput() {
        TenantLifecycleService.ExpiryView current =
                fixed(LocalDate.of(2027, 1, 31), List.of(30, 7, 1), 15, false);

        assertExpiry(40001, current, "FIXED", null, null, null, null, "有到期日必填到期日");
        assertExpiry(40001, current, "FIXED", "2027/01/31", null, null, null, "格式");
        assertExpiry(40001, current, "FIXED", "2026-11-01", null, null, null, "到期日须晚于当天");
        assertExpiry(40001, current, "FIXED", "2026-10-31", null, null, null, "过去的到期日");
        assertExpiry(40001, current, null, null, null, null, null, "类型必填");
        assertExpiry(40001, current, "YEARLY", null, null, null, null, "未知类型");
        assertExpiry(40001, current, "FIXED", "2027-01-31", List.of(), null, null, "提醒节点至少一个");
        assertExpiry(40001, current, "FIXED", "2027-01-31", List.of(0, 7), null, null, "提醒节点下限");
        assertExpiry(40001, current, "FIXED", "2027-01-31", List.of(366), null, null, "提醒节点上限");
        assertExpiry(40001, current, "FIXED", "2027-01-31", null, 0, null, "宽限期下限");
        assertExpiry(40001, current, "FIXED", "2027-01-31", null, 366, null, "宽限期上限");
    }

    private static void assertExpiry(
            int expectedCode,
            TenantLifecycleService.ExpiryView current,
            String expireType,
            String expireAt,
            List<Integer> remindDays,
            Integer graceDays,
            Boolean graceReadOnly,
            String label) {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantLifecycleService.mergedExpiry(
                                        current,
                                        new TenantLifecycleService.ExpireRequest(
                                                expireType,
                                                expireAt,
                                                remindDays,
                                                graceDays,
                                                graceReadOnly,
                                                "示例原因"),
                                        TODAY),
                        label);
        assertEquals(expectedCode, ex.code(), label + "：" + ex.getMessage());
    }

    @Test
    void reasonIsRequiredAndBounded() {
        assertEquals("正式签约", TenantLifecycleService.reasonOf("  正式签约 "));
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> TenantLifecycleService.reasonOf(null))
                        .code());
        assertEquals(
                40001,
                assertThrows(ApiException.class, () -> TenantLifecycleService.reasonOf("   "))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () -> TenantLifecycleService.reasonOf("长".repeat(201)))
                        .code());
        assertEquals("长".repeat(200), TenantLifecycleService.reasonOf("长".repeat(200)));
    }

    @Test
    void remindDaysRoundTripsThroughStorageFormat() {
        assertEquals(List.of(30, 7, 1), TenantLifecycleService.parseRemindDays("30,7,1"));
        assertNull(TenantLifecycleService.parseRemindDays(null));
        assertEquals("30,7,1", TenantLifecycleService.joinRemindDays(List.of(7, 30, 1)));
        assertNull(TenantLifecycleService.joinRemindDays(null));
    }

    private static TenantLifecycleService.ExpiryView fixed(
            LocalDate expireAt, List<Integer> remindDays, int graceDays, boolean graceReadOnly) {
        return new TenantLifecycleService.ExpiryView(
                "FIXED", expireAt, remindDays, graceDays, graceReadOnly);
    }
}
