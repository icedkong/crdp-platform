package com.crdp.followup.file.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.crdp.framework.security.*;
import com.crdp.framework.web.ApiException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PreviewAccessServiceTest {
    private final SessionStore sessions = mock(SessionStore.class);
    private final PermissionVersionStore versions = mock(PermissionVersionStore.class);
    private final SessionRefresher refresher = mock(SessionRefresher.class);
    private PreviewAccessService service;
    private SessionPrincipal actor;

    @BeforeEach
    void setup() {
        actor = FilePreviewServiceTest.actor("session", 501, 129);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        when(sessions.find("session")).thenReturn(Optional.of(actor));
        service = new PreviewAccessService(sessions, versions, refresher);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checksLiveSessionEveryTime() {
        assertThat(service.current()).isEqualTo(actor);
        verify(sessions).find("session");
    }

    @Test
    void logoutIsImmediatelyEnforced() {
        when(sessions.find("session")).thenReturn(Optional.empty());
        assertThatThrownBy(service::current)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(40100));
    }

    @Test
    void changedPermissionsReplaceCurrentPrincipal() {
        when(versions.current(any())).thenReturn(2L);
        var narrowed =
                new SessionPrincipal(
                        "session",
                        "HOSPITAL",
                        129L,
                        "demo129",
                        "医院（示例）",
                        501L,
                        "doctor.example",
                        "医生（示例）",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("followup"),
                        2,
                        ScopeSnapshot.restricted(List.of(), List.of(), false),
                        false,
                        null);
        when(refresher.refresh(actor)).thenReturn(Optional.of(narrowed));
        assertThat(service.current()).isEqualTo(narrowed);
        assertThat(CurrentSession.require().scope().wardIds()).isEmpty();
        verify(sessions).save(narrowed);
    }

    @Test
    void disabledAccountIsRevoked() {
        when(versions.current(any())).thenReturn(2L);
        when(refresher.refresh(actor)).thenReturn(Optional.empty());
        assertThatThrownBy(service::current).isInstanceOf(ApiException.class);
        verify(sessions).delete("session");
    }

    @Test
    void mismatchedAccountCannotReplaceCaller() {
        when(sessions.find("session"))
                .thenReturn(Optional.of(FilePreviewServiceTest.actor("session", 502, 129)));
        assertThatThrownBy(service::current).isInstanceOf(ApiException.class);
    }
}
