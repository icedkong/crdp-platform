package com.crdp.server.web;

public record HelloResponse(
        String tenantCode, String tenantName, String database, String message) {}
