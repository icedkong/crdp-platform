package com.crdp.server.web;

import java.util.List;

public record MeResponse(
        long userId,
        String username,
        String displayName,
        String sessionType,
        String tenantCode,
        String tenantName,
        List<String> roles,
        long permissionVersion) {}
