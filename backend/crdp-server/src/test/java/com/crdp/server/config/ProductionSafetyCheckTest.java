package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.crypto.CryptoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class ProductionSafetyCheckTest {

    @Test
    void prodKmsRefusesToStartUntilTheProviderIsConnected() {
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> new ProductionSafetyCheck(environment("prod"), properties("kms")));
        assertTrue(failure.getMessage().contains("KMS"));
        assertTrue(failure.getMessage().contains("demo"));
    }

    @Test
    void prodLocalModeIsStillRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> new ProductionSafetyCheck(environment("prod"), properties("local")));
    }

    @Test
    void demoLocalModeStarts() {
        assertDoesNotThrow(
                () -> new ProductionSafetyCheck(environment("demo"), properties("local")));
    }

    private static Environment environment(String profile) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[] {profile});
        return environment;
    }

    private static CryptoProperties properties(String mode) {
        CryptoProperties properties = new CryptoProperties();
        properties.setMode(mode);
        return properties;
    }
}
