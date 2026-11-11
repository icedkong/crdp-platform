package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.crdp.framework.security.SessionPrincipal;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.resource.ResourceChangeRow;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

/** E05-T10 / PLT-PERM-012：资源变更记录写入——操作人取当前会话（姓名（账号）快照，无会话为空＝系统触发）、 超长字段按列宽截断。 */
@ExtendWith(MockitoExtension.class)
class ResourceChangeRecorderTest {

    @Mock private PlatformMapper platformMapper;

    private ResourceChangeRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ResourceChangeRecorder(platformMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void login() {
        SessionPrincipal principal =
                new SessionPrincipal(
                        "token",
                        "PLATFORM",
                        null,
                        null,
                        null,
                        9L,
                        "ops.demo",
                        "王运维（示例）",
                        List.of("PLATFORM_ADMIN"),
                        List.of(),
                        List.of("platform:menu:edit"),
                        List.of(),
                        1L,
                        null,
                        false,
                        null,
                        "127.0.0.1",
                        0,
                        0,
                        0);
        SecurityContextHolder.setContext(new SecurityContextImpl());
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void recordsOperatorSnapshotWhenSessionPresent() {
        login();
        recorder.record(
                ResourceChangeRecorder.TYPE_MENU,
                113L,
                "菜单资源",
                "PlatformMenu",
                ResourceChangeRecorder.ACTION_UPDATE,
                ResourceChangeRecorder.CHANNEL_PAGE,
                "维护菜单：标题 a → b");
        ArgumentCaptor<ResourceChangeRow> captor = ArgumentCaptor.forClass(ResourceChangeRow.class);
        verify(platformMapper).insertResourceChange(captor.capture());
        ResourceChangeRow row = captor.getValue();
        assertEquals("MENU", row.getResourceType());
        assertEquals(113L, row.getResourceId());
        assertEquals("菜单资源", row.getResourceName());
        assertEquals("PlatformMenu", row.getResourceIdent());
        assertEquals("UPDATE", row.getAction());
        assertEquals("PAGE", row.getChannel());
        assertEquals("维护菜单：标题 a → b", row.getDetail());
        assertEquals(9L, row.getOperatorId());
        assertEquals("王运维（示例）（ops.demo）", row.getOperatorName());
    }

    @Test
    void recordsWithoutOperatorWhenNoSession() {
        recorder.record(
                ResourceChangeRecorder.TYPE_API,
                null,
                "GET /api/platform/apis",
                "/api/platform/apis",
                ResourceChangeRecorder.ACTION_REGISTER,
                ResourceChangeRecorder.CHANNEL_SYNC,
                "接口首次登记");
        ArgumentCaptor<ResourceChangeRow> captor = ArgumentCaptor.forClass(ResourceChangeRow.class);
        verify(platformMapper).insertResourceChange(captor.capture());
        ResourceChangeRow row = captor.getValue();
        assertEquals("API", row.getResourceType());
        assertNull(row.getResourceId());
        assertEquals("REGISTER", row.getAction());
        assertEquals("SYNC", row.getChannel());
        assertNull(row.getOperatorId());
        assertNull(row.getOperatorName());
    }

    @Test
    void truncatesOverlongTexts() {
        String longName = "标".repeat(200);
        String longDetail = "摘".repeat(600);
        recorder.record(
                ResourceChangeRecorder.TYPE_MENU,
                1L,
                longName,
                longDetail,
                ResourceChangeRecorder.ACTION_DELETE,
                ResourceChangeRecorder.CHANNEL_PAGE,
                longDetail);
        ArgumentCaptor<ResourceChangeRow> captor = ArgumentCaptor.forClass(ResourceChangeRow.class);
        verify(platformMapper).insertResourceChange(captor.capture());
        ResourceChangeRow row = captor.getValue();
        assertEquals(128, row.getResourceName().length());
        assertEquals(128, row.getResourceIdent().length());
        assertEquals(512, row.getDetail().length());
        assertTrue(row.getResourceName().startsWith("标"));
    }

    @Test
    void insertsInsidePlatformContext() {
        recorder.record(
                ResourceChangeRecorder.TYPE_API,
                5L,
                "GET /x",
                "/x",
                ResourceChangeRecorder.ACTION_DELETE,
                ResourceChangeRecorder.CHANNEL_SYNC,
                "清除");
        // 无租户上下文时 callAsPlatform 由 Recorder 自行建立（启动扫描场景），插入不应抛
        verify(platformMapper).insertResourceChange(any(ResourceChangeRow.class));
    }
}
