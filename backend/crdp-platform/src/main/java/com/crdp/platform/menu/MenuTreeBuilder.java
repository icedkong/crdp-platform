package com.crdp.platform.menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MenuTreeBuilder {

    private MenuTreeBuilder() {}

    public static List<MenuNode> build(List<MenuRow> rows) {
        List<MenuRow> pages =
                rows.stream()
                        .filter(row -> "DIR".equals(row.getType()) || "MENU".equals(row.getType()))
                        .sorted(
                                Comparator.comparingInt(
                                        row -> row.getSortNo() == null ? 0 : row.getSortNo()))
                        .toList();
        Map<Long, List<MenuRow>> byParent = new LinkedHashMap<>();
        for (MenuRow row : pages) {
            long parent = row.getParentId() == null ? 0L : row.getParentId();
            byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(row);
        }
        return childrenOf(byParent, 0L);
    }

    private static List<MenuNode> childrenOf(Map<Long, List<MenuRow>> byParent, long parentId) {
        List<MenuNode> nodes = new ArrayList<>();
        for (MenuRow row : byParent.getOrDefault(parentId, List.of())) {
            nodes.add(
                    new MenuNode(
                            row.getId(),
                            row.getRouteName(),
                            row.getPath(),
                            row.getComponent(),
                            row.getType(),
                            new MenuMeta(
                                    row.getTitle(),
                                    row.getIcon(),
                                    row.getSortNo() == null ? 0 : row.getSortNo(),
                                    row.getHidden() != null && row.getHidden() == 1,
                                    row.getPermCode()),
                            childrenOf(byParent, row.getId())));
        }
        return nodes;
    }
}
