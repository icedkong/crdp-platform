package com.crdp.followup.notice.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.domain.NotifyEventPayload;
import com.crdp.followup.notice.domain.NotifyEventType;
import com.crdp.followup.notice.domain.RecipientOption;
import com.crdp.followup.notice.infra.mapper.NotifyRecipientMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * E09-T04 / A-NTF-004、C-NTF-004：接收人规则单元测试。
 *
 * <p>锁定：负责人病区复核（已不负责该病区且非全院 → 不再收到，转病区成员兜底）、全院范围不受病区限制、 无负责人 →
 * 病区全部启用成员、逾期抄送病区「默认随访负责人」、显式接收人清单只留启用用户、租户主动取消 WARD_STAFF 时不兜底、任何路径都不解析患者（患者不在
 * sys_user，结构上收不到）。全部虚构数据。
 */
@ExtendWith(MockitoExtension.class)
class NotifyRecipientResolverTest {

    private static final long OWNER = 11L;
    private static final long OTHER_STAFF = 12L;
    private static final long LEADER = 13L;
    private static final long WARD = 3L;
    private static final long DISABLED_USER = 14L;

    private final NotifyRecipientMapper mapper = mock(NotifyRecipientMapper.class);
    private final NotifyRecipientResolver resolver = new NotifyRecipientResolver(mapper);

    @BeforeEach
    void commonStubs() {
        lenient().when(mapper.countEnabledUser(OWNER)).thenReturn(1);
        lenient().when(mapper.countEnabledUser(OTHER_STAFF)).thenReturn(1);
        lenient().when(mapper.countEnabledUser(LEADER)).thenReturn(1);
        lenient().when(mapper.countEnabledUser(DISABLED_USER)).thenReturn(0);
    }

    private static NotifyEventPayload taskEvent(Long ownerId) {
        return new NotifyEventPayload(
                4242L,
                ownerId,
                WARD,
                990_001L,
                "张小明（示例）",
                "第 4 年随访",
                "2026-11-02",
                "2026-11-16",
                null,
                null);
    }

    /** 负责人负责该病区：只发负责人（验收「任务开启、逾期等事件按规则发给责任人」）。 */
    @Test
    void ownerWhoManagesWardIsTheSoleRecipient() {
        when(mapper.countWardMembership(OWNER, WARD)).thenReturn(1);

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OPENED,
                        List.of(RecipientOption.OWNER, RecipientOption.WARD_STAFF),
                        taskEvent(OWNER));

        assertEquals(List.of(OWNER), resolution.recipientIds());
        verify(mapper, never()).listEnabledWardStaff(WARD);
    }

    /** 负责人已不负责该病区且非全院范围：负责人收不到，落到该病区全部启用成员。 */
    @Test
    void ownerWhoLeftWardFallsBackToWardStaff() {
        when(mapper.countWardMembership(OWNER, WARD)).thenReturn(0);
        when(mapper.countHospitalWideScope(OWNER)).thenReturn(0);
        when(mapper.listEnabledWardStaff(WARD)).thenReturn(List.of(OTHER_STAFF, LEADER));

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OPENED,
                        List.of(RecipientOption.OWNER, RecipientOption.WARD_STAFF),
                        taskEvent(OWNER));

        assertEquals(List.of(OTHER_STAFF, LEADER), resolution.recipientIds());
    }

    /** 全院范围角色（如租户管理员）不受病区限制（A-WARD-007）。 */
    @Test
    void hospitalWideOwnerStillReceives() {
        when(mapper.countWardMembership(OWNER, WARD)).thenReturn(0);
        when(mapper.countHospitalWideScope(OWNER)).thenReturn(1);

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OVERDUE,
                        List.of(RecipientOption.OWNER, RecipientOption.WARD_STAFF),
                        taskEvent(OWNER));

        assertEquals(List.of(OWNER), resolution.recipientIds());
    }

    /** 任务无负责人（验收：该病区全部医生收到，其他病区收不到——成员清单即该病区关联）。 */
    @Test
    void missingOwnerFallsBackToWardStaff() {
        when(mapper.listEnabledWardStaff(WARD)).thenReturn(List.of(OTHER_STAFF, OWNER));

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OPENED,
                        List.of(RecipientOption.OWNER, RecipientOption.WARD_STAFF),
                        taskEvent(null));

        assertEquals(List.of(OTHER_STAFF, OWNER), resolution.recipientIds());
    }

    /** 已逾期抄送病区负责人（默认随访负责人，OQ-60）：追加且去重。 */
    @Test
    void overdueEventAlsoCopiesWardLeader() {
        when(mapper.countWardMembership(OWNER, WARD)).thenReturn(1);
        when(mapper.findWardDefaultOwner(WARD)).thenReturn(LEADER);

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OVERDUE,
                        List.of(
                                RecipientOption.OWNER,
                                RecipientOption.WARD_STAFF,
                                RecipientOption.CC_WARD_LEADER),
                        taskEvent(OWNER));

        assertEquals(List.of(OWNER, LEADER), resolution.recipientIds());
    }

    /** 负责人本身就是病区负责人：抄送去重，只收一条。 */
    @Test
    void ccWardLeaderDeduplicatesOwner() {
        when(mapper.countWardMembership(OWNER, WARD)).thenReturn(1);
        when(mapper.findWardDefaultOwner(WARD)).thenReturn(OWNER);

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OVERDUE,
                        List.of(RecipientOption.OWNER, RecipientOption.CC_WARD_LEADER),
                        taskEvent(OWNER));

        assertEquals(List.of(OWNER), resolution.recipientIds());
    }

    /** 负责人被停用：视为无负责人，走病区成员兜底。 */
    @Test
    void disabledOwnerFallsBackToWardStaff() {
        when(mapper.listEnabledWardStaff(WARD)).thenReturn(List.of(OTHER_STAFF));

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OPENED,
                        List.of(RecipientOption.OWNER, RecipientOption.WARD_STAFF),
                        taskEvent(DISABLED_USER));

        assertEquals(List.of(OTHER_STAFF), resolution.recipientIds());
    }

    /** 租户主动取消 WARD_STAFF 选项：无有效负责人时不兜底（配置即规则）。 */
    @Test
    void noFallbackWhenWardStaffOptionRemoved() {
        when(mapper.countWardMembership(OWNER, WARD)).thenReturn(0);
        when(mapper.countHospitalWideScope(OWNER)).thenReturn(0);

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.TASK_OPENED,
                        List.of(RecipientOption.OWNER),
                        taskEvent(OWNER));

        assertTrue(resolution.recipientIds().isEmpty());
    }

    /** 每日汇总：负载显式接收人优先，只保留启用用户并去重。 */
    @Test
    void explicitRecipientIdsWinAndFilterDisabled() {
        NotifyEventPayload digest =
                new NotifyEventPayload(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(OWNER, OTHER_STAFF, DISABLED_USER, OWNER));

        NotifyRecipientResolver.Resolution resolution =
                resolver.resolve(
                        NotifyEventType.DAILY_DIGEST,
                        List.of(RecipientOption.OWNER, RecipientOption.WARD_STAFF),
                        digest);

        assertEquals(List.of(OWNER, OTHER_STAFF), resolution.recipientIds());
    }
}
