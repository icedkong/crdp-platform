package com.crdp.framework.job;

/** 单个租户的作业执行结果。失败时 error 是原因摘要（截断到表列宽度）。 */
public record TenantJobResult(long tenantId, String tenantCode, boolean success, String error) {}
