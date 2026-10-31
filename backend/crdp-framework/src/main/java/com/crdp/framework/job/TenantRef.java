package com.crdp.framework.job;

/** 调度框架看到的租户：平台库里的主键与编码。 */
public record TenantRef(long tenantId, String tenantCode) {}
