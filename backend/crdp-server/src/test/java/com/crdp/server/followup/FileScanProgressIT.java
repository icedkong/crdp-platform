package com.crdp.server.followup;

import static org.assertj.core.api.Assertions.*;

import com.crdp.followup.file.infra.mapper.*;
import com.crdp.framework.tenant.TenantContext;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OQR-08：真实迁移与 Mapper，两个租户各自回滚，不扫描或更改真实文件。 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class FileScanProgressIT {
    @Autowired SysFileMapper files;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private SysFileRow fixture(String tenant) {
        var row = new SysFileRow();
        row.setObjectKey("t/" + tenant + "/files/oqr08-" + UUID.randomUUID() + ".pdf");
        row.setOriginalName("扫描进度（示例）.pdf");
        row.setFormatCode("pdf");
        row.setMimeType("application/pdf");
        row.setSizeBytes(8);
        row.setSha256("0".repeat(64));
        row.setStatus("PENDING");
        row.setVirusScanStatus("UNSCANNED");
        row.setUploaderId(501L);
        files.insert(row);
        return row;
    }

    @Test
    void retryOrderDoesNotStarveUntouchedFilesAndConclusionsAreGuarded() {
        for (long tenant : List.of(1L, 2L)) {
            String code = tenant == 1 ? "demoa" : "demob";
            TenantContext.runAsTenant(
                    tenant,
                    code,
                    () ->
                            new TransactionTemplate(transactions)
                                    .executeWithoutResult(
                                            tx -> {
                                                try {
                                                    var failed = fixture(code);
                                                    var later = fixture(code);
                                                    assertThat(
                                                                    files.markScanAttempt(
                                                                            failed.getId()))
                                                            .isOne();
                                                    assertThat(
                                                                    files.markScanRetry(
                                                                            failed.getId(),
                                                                            "OBJECT_MISSING"))
                                                            .isOne();
                                                    var pending =
                                                            files
                                                                    .findUnscanned(
                                                                            Integer.MAX_VALUE)
                                                                    .stream()
                                                                    .map(SysFileRow::getId)
                                                                    .toList();
                                                    assertThat(pending.indexOf(later.getId()))
                                                            .isLessThan(
                                                                    pending.indexOf(
                                                                            failed.getId()));
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT virus_scan_attempts FROM sys_file WHERE id=?",
                                                                            Integer.class,
                                                                            failed.getId()))
                                                            .isOne();
                                                    assertThat(
                                                                    files.markVirusScanResult(
                                                                            failed.getId(),
                                                                            "INFECTED"))
                                                            .isOne();
                                                    assertThat(
                                                                    files.markVirusScanResult(
                                                                            failed.getId(),
                                                                            "CLEAN"))
                                                            .isZero();
                                                    assertThat(
                                                                    files.markScanAttempt(
                                                                            failed.getId()))
                                                            .isZero();
                                                    assertThat(
                                                                    files.find(failed.getId())
                                                                            .getVirusScanStatus())
                                                            .isEqualTo("INFECTED");
                                                    assertThat(
                                                                    jdbc.queryForObject(
                                                                            "SELECT virus_scan_retry_reason FROM sys_file WHERE id=?",
                                                                            String.class,
                                                                            failed.getId()))
                                                            .isNull();
                                                    assertThat(
                                                                    files.find(later.getId())
                                                                            .getVirusScanStatus())
                                                            .isEqualTo("UNSCANNED");
                                                } finally {
                                                    tx.setRollbackOnly();
                                                }
                                            }));
        }
    }
}
