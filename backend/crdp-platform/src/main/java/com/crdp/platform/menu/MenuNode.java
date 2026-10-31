package com.crdp.platform.menu;

import java.util.List;

/** 下发给前端的菜单节点。component 为 LAYOUT，或 views 下的相对路径。 */
public record MenuNode(
        long id,
        String name,
        String path,
        String component,
        String type,
        MenuMeta meta,
        List<MenuNode> children) {}
