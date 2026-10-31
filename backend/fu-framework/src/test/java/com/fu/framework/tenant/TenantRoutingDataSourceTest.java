package com.fu.framework.tenant;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantRoutingDataSourceTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void routesToThePoolRegisteredForTheCurrentTenant() throws Exception {
        DataSource hospitalA = mock(DataSource.class);
        DataSource hospitalB = mock(DataSource.class);
        Connection connectionA = mock(Connection.class);
        when(hospitalA.getConnection()).thenReturn(connectionA);

        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.registerPlatform(mock(DataSource.class));
        routing.registerTenant(1L, hospitalA);
        routing.registerTenant(2L, hospitalB);

        TenantContext.useTenant(1L, "demoa");
        assertSame(hospitalA, routing.current());
        assertSame(connectionA, routing.getConnection());
    }

    @Test
    void keepsTenantsOnSeparatePools() {
        DataSource hospitalA = mock(DataSource.class);
        DataSource hospitalB = mock(DataSource.class);
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.registerPlatform(mock(DataSource.class));
        routing.registerTenant(1L, hospitalA);
        routing.registerTenant(2L, hospitalB);

        TenantContext.useTenant(2L, "demob");
        assertSame(hospitalB, routing.current());

        TenantContext.useTenant(1L, "demoa");
        assertSame(hospitalA, routing.current());
    }

    @Test
    void rejectsAccessWhenTenantContextIsMissing() {
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.registerPlatform(mock(DataSource.class));
        routing.registerTenant(1L, mock(DataSource.class));

        assertThrows(MissingTenantContextException.class, routing::current);
    }

    @Test
    void usesThePlatformPoolOnlyWhenContextIsPlatform() {
        DataSource platform = mock(DataSource.class);
        DataSource hospitalA = mock(DataSource.class);
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.registerPlatform(platform);
        routing.registerTenant(1L, hospitalA);

        TenantContext.usePlatform();
        assertSame(platform, routing.current());
    }
}
