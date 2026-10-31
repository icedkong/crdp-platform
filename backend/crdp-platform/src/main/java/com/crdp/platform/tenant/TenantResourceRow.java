package com.crdp.platform.tenant;

public record TenantResourceRow(
        long tenantId,
        String code,
        String status,
        String dbName,
        String jdbcUrl,
        String username,
        String passwordCipher) {}
