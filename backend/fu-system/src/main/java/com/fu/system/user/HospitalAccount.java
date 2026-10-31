package com.fu.system.user;

import com.fu.framework.security.ScopeSnapshot;
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
