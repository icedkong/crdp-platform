package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FileScanProductionSafetyCheckTest {
    private MockEnvironment environment() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    @Test
    void productionMissingOrOffRefusesStartup() {
        var env = environment();
        assertThrows(
                IllegalStateException.class, () -> FileScanProductionSafetyCheck.validate(env));
        env.withProperty("crdp.file.virus-scan.mode", "off")
                .withProperty("crdp.file.virus-scan.clamd.host", "scan.internal");
        assertThrows(
                IllegalStateException.class, () -> FileScanProductionSafetyCheck.validate(env));
    }

    @Test
    void requiresExplicitHostAndBoundedTimeouts() {
        var env = environment().withProperty("crdp.file.virus-scan.mode", "clamd");
        assertThrows(
                IllegalStateException.class, () -> FileScanProductionSafetyCheck.validate(env));
        env.withProperty("crdp.file.virus-scan.clamd.host", "scan.internal");
        assertDoesNotThrow(() -> FileScanProductionSafetyCheck.validate(env));
        for (String value : new String[] {"0ms", "-1ms", "61s"}) {
            env.withProperty("crdp.file.virus-scan.clamd.read-timeout", value);
            assertThrows(
                    IllegalStateException.class, () -> FileScanProductionSafetyCheck.validate(env));
        }
    }

    @Test
    void developmentCanExplicitlyDisableScanning() {
        var env = new MockEnvironment().withProperty("crdp.file.virus-scan.mode", "off");
        env.setActiveProfiles("local");
        assertDoesNotThrow(() -> FileScanProductionSafetyCheck.validate(env));
    }
}
