package com.crdp.server.config;

import com.crdp.followup.file.infra.scan.VirusScanProperties;
import java.util.Arrays;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** OQR-08：生产扫描配置在启动时校验，不接受 off 或隐式本机回落。 */
@Component
public class FileScanProductionSafetyCheck {
    public FileScanProductionSafetyCheck(Environment environment) {
        validate(environment);
    }

    static void validate(Environment environment) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) return;
        VirusScanProperties properties =
                Binder.get(environment)
                        .bind("crdp.file.virus-scan", VirusScanProperties.class)
                        .orElseGet(VirusScanProperties::new);
        if (!"clamd".equals(properties.getMode())
                || environment.getProperty("crdp.file.virus-scan.clamd.host", "").isBlank())
            throw new IllegalStateException(
                    "生产必须启用病毒扫描（crdp.file.virus-scan.mode=clamd），并配置 CRDP_CLAMD_HOST；禁止回退 off");
        properties.validateClamd();
    }
}
