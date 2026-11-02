package com.crdp.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.crdp.followup.notice.app.NotifyDigestService;
import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** E09-T05：汇总作业定时注册——逐租户在各自租户上下文内触发（AGENTS §5.1：异步任务必须显式设置租户上下文）， 单租户失败只记 WARN、不阻断其他租户。 */
@ExtendWith(MockitoExtension.class)
class NotifyDigestSchedulerTest {

    @Mock private NotifyDigestService digest;
    @Mock private TenantDirectory directory;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** 两租户各触发一次，且每次执行时都在对应租户上下文内（tenantCode 可读、非平台态）。 */
    @Test
    void pollsEveryEnabledTenantInItsOwnContext() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));
        List<String> polledIn = new ArrayList<>();
        when(digest.poll())
                .thenAnswer(
                        inv -> {
                            polledIn.add(TenantContext.require().tenantCode());
                            return NotifyDigestService.DigestOutcome.notDue();
                        });

        new NotifyDigestScheduler(digest, directory).tick();

        assertEquals(List.of("demoa", "demob"), polledIn, "逐租户、各自上下文内触发");
    }

    /** 首个租户抛错（如租户库不可达）不阻断后续租户。 */
    @Test
    void tenantFailureDoesNotBlockOthers() {
        when(directory.enabledTenants())
                .thenReturn(List.of(new TenantRef(1L, "demoa"), new TenantRef(2L, "demob")));
        when(digest.poll())
                .thenThrow(new RuntimeException("租户库不可达（示例）"))
                .thenReturn(NotifyDigestService.DigestOutcome.notDue());

        new NotifyDigestScheduler(digest, directory).tick();

        org.mockito.Mockito.verify(digest, org.mockito.Mockito.times(2)).poll();
    }

    /** 空租户清单安全空跑。 */
    @Test
    void noTenantsIsNoop() {
        when(directory.enabledTenants()).thenReturn(List.of());
        new NotifyDigestScheduler(digest, directory).tick();
        org.mockito.Mockito.verifyNoInteractions(digest);
    }
}
