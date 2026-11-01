package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * E05-T03（PLT-TEN-012）纯函数：到期提醒节点判定与自动流转的日期计算。
 *
 * <p>口径（平台规格第 2 章「到期流程」与技术方案第 5 章）：提醒节点 = 到期日 − N 天（N 取租户配置，默认 30、7、1）， 只对试用 /
 * 启用且有到期日的租户生效；到期当天（含）进入宽限期；宽限期覆盖 [到期日, 到期日 + 宽限天数)， 即满 graceDays 天后的第一天自动停用（15 天宽限、11-01 到期 →
 * 11-01..11-15 宽限、11-16 停用）；长期有效租户不提醒、不进入宽限期。
 */
class TenantExpiryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

    // ---------------------------------------------------------------- 提醒节点

    @Test
    void remindNodeHitsWhenDaysLeftMatchesConfiguration() {
        LocalDate expireAt = TODAY.plusDays(30);
        assertTrue(TenantExpiryService.isRemindNode(expireAt, List.of(30, 7, 1), TODAY));
        assertTrue(TenantExpiryService.isRemindNode(TODAY.plusDays(7), List.of(30, 7, 1), TODAY));
        assertTrue(TenantExpiryService.isRemindNode(TODAY.plusDays(1), List.of(30, 7, 1), TODAY));
        // 非节点日不提醒
        assertFalse(TenantExpiryService.isRemindNode(TODAY.plusDays(29), List.of(30, 7, 1), TODAY));
        assertFalse(TenantExpiryService.isRemindNode(TODAY.plusDays(2), List.of(30, 7, 1), TODAY));
    }

    @Test
    void remindNodeRequiresFutureExpiryAndConfiguredNodes() {
        // 到期日当天与已过期的租户不再发「到期前」提醒（进入宽限期走续费提示）
        assertFalse(TenantExpiryService.isRemindNode(TODAY, List.of(30, 7, 1), TODAY));
        assertFalse(TenantExpiryService.isRemindNode(TODAY.minusDays(1), List.of(30, 7, 1), TODAY));
        // 未配置提醒节点（长期有效被清空）不提醒
        assertFalse(TenantExpiryService.isRemindNode(TODAY.plusDays(7), null, TODAY));
        assertFalse(TenantExpiryService.isRemindNode(TODAY.plusDays(7), List.of(), TODAY));
    }

    // ---------------------------------------------------------------- 到期转宽限

    @Test
    void entersGraceOnOrAfterExpiryForTrialAndEnabledOnly() {
        LocalDate expireAt = TODAY;
        assertTrue(TenantExpiryService.entersGrace("ENABLED", "FIXED", expireAt, TODAY));
        assertTrue(TenantExpiryService.entersGrace("TRIAL", "FIXED", TODAY.minusDays(3), TODAY));
        // 未到期不转；到期日次日起仍是启用（作业补跑）也转
        assertFalse(TenantExpiryService.entersGrace("ENABLED", "FIXED", TODAY.plusDays(1), TODAY));
        // 宽限期 / 停用 / 开通 / 注销各态不重复转；长期有效不进入宽限期（规格已确认）
        assertFalse(TenantExpiryService.entersGrace("GRACE", "FIXED", expireAt, TODAY));
        assertFalse(TenantExpiryService.entersGrace("DISABLED", "FIXED", expireAt, TODAY));
        assertFalse(TenantExpiryService.entersGrace("PROVISIONING", "FIXED", expireAt, TODAY));
        assertFalse(TenantExpiryService.entersGrace("CANCELLING", "FIXED", expireAt, TODAY));
        assertFalse(TenantExpiryService.entersGrace("ENABLED", "LONG_TERM", null, TODAY));
        assertFalse(TenantExpiryService.entersGrace("TRIAL", "LONG_TERM", null, TODAY));
    }

    // ---------------------------------------------------------------- 宽限期满自动停用

    @Test
    void autoDisableStartsTheDayAfterGraceWindowCloses() {
        LocalDate expireAt = LocalDate.of(2026, 11, 1);
        // 宽限 15 天：11-01..11-15 宽限，11-16（到期日 + 15）起自动停用
        assertFalse(
                TenantExpiryService.autoDisable(
                        "GRACE", "FIXED", expireAt, 15, LocalDate.of(2026, 11, 15)));
        assertTrue(
                TenantExpiryService.autoDisable(
                        "GRACE", "FIXED", expireAt, 15, LocalDate.of(2026, 11, 16)));
        // 补跑：宽限期已过数日仍停用
        assertTrue(
                TenantExpiryService.autoDisable(
                        "GRACE", "FIXED", expireAt, 15, LocalDate.of(2026, 12, 1)));
        // 宽限 1 天：到期次日即停用
        assertFalse(
                TenantExpiryService.autoDisable(
                        "GRACE", "FIXED", expireAt, 1, LocalDate.of(2026, 11, 1)));
        assertTrue(
                TenantExpiryService.autoDisable(
                        "GRACE", "FIXED", expireAt, 1, LocalDate.of(2026, 11, 2)));
    }

    @Test
    void autoDisableOnlyAppliesToGraceFixedTenants() {
        LocalDate expireAt = LocalDate.of(2026, 10, 1);
        assertFalse(TenantExpiryService.autoDisable("ENABLED", "FIXED", expireAt, 15, TODAY));
        assertFalse(TenantExpiryService.autoDisable("DISABLED", "FIXED", expireAt, 15, TODAY));
        assertFalse(TenantExpiryService.autoDisable("GRACE", "LONG_TERM", null, null, TODAY));
        // 宽限天数缺失（数据异常）不动，留给人工处理
        assertFalse(TenantExpiryService.autoDisable("GRACE", "FIXED", expireAt, null, TODAY));
    }

    // ---------------------------------------------------------------- 展示口径

    @Test
    void daysLeftCountsInclusiveOfExpiryDay() {
        assertEquals(30, TenantExpiryService.daysLeft(TODAY.plusDays(30), TODAY));
        assertEquals(1, TenantExpiryService.daysLeft(TODAY.plusDays(1), TODAY));
        assertEquals(0, TenantExpiryService.daysLeft(TODAY, TODAY));
        // 已过期或长期有效返回空，前端不展示倒计时
        assertEquals(null, TenantExpiryService.daysLeft(TODAY.minusDays(1), TODAY));
        assertEquals(null, TenantExpiryService.daysLeft(null, TODAY));
    }
}
