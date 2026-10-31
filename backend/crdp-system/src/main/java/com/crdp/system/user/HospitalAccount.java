package com.crdp.system.user;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.List;

public record HospitalAccount(
        long id,
        String username,
        String displayName,
        String passwordHash,
        String status,
        List<String> roles,
        List<Long> menuIds,
        List<String> permissionCodes,
        ScopeSnapshot scope) {}
