package com.crdp.server.auth;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.menu.MenuNode;
import com.crdp.platform.menu.MenuTreeBuilder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class MenuQuery {

    private final PlatformMapper platformMapper;

    public MenuQuery(PlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

    public List<MenuNode> tree(String side, List<Long> menuIds) {
        Set<Long> ids = new HashSet<>(menuIds);
        return TenantContext.callAsPlatform(
                () ->
                        MenuTreeBuilder.build(
                                platformMapper.listMenus(side).stream()
                                        .filter(row -> ids.contains(row.getId()))
                                        .toList()));
    }
}
