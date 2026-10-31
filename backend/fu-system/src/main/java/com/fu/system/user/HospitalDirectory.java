package com.fu.system.user;

import com.fu.framework.security.ScopeSnapshot;
import com.fu.system.infra.mapper.HospitalMapper;
import com.fu.system.infra.mapper.HospitalUserRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 读取当前租户库中的账号。调用前必须已经放入租户上下文。 */
@Component
public class HospitalDirectory {

    private final HospitalMapper mapper;

    public HospitalDirectory(HospitalMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<HospitalAccount> find(String username) {
        HospitalUserRow row = mapper.findUser(username);
        if (row == null || row.getId() == null) {
            return Optional.empty();
        }
        long userId = row.getId();
        List<String> scopeTypes = mapper.scopeTypes(userId);
        boolean all = scopeTypes.stream().anyMatch("ALL"::equals);
        ScopeSnapshot scope =
                all
                        ? ScopeSnapshot.allowAll()
                        : ScopeSnapshot.restricted(
                                wants(scopeTypes, "WARD") ? mapper.wardIds(userId) : List.of(),
                                wants(scopeTypes, "DEPT", "CUSTOM_DEPT", "RESPONSIBLE_DEPT")
                                        ? mapper.deptIds(userId)
                                        : List.of(),
                                wants(scopeTypes, "SELF", "RESPONSIBLE_PATIENT"));
        return Optional.of(
                new HospitalAccount(
                        userId,
                        row.getUsername(),
                        row.getDisplayName(),
                        row.getPasswordHash(),
                        row.getStatus(),
                        mapper.roles(userId),
                        mapper.menuIds(userId),
                        List.of(),
                        scope));
    }

    private static boolean wants(List<String> types, String... names) {
        for (String name : names) {
            if (types.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
