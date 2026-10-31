package com.fu.platform.menu;

/** 对齐 Vben 路由 meta。 */
public record MenuMeta(
        String title, String icon, int order, boolean hideInMenu, String permission) {}
