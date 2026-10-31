package com.fu.platform.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MenuTreeBuilderTest {

    @Test
    void nestsDirectoriesAndDropsButtons() {
        MenuRow dashboard = row(10L, 0L, "MENU", "工作台", "HospitalDashboard", "/dashboard", 10);
        MenuRow followup = row(20L, 0L, "DIR", "随访", "Followup", "/followup", 20);
        MenuRow patient = row(21L, 20L, "MENU", "患者档案", "FollowupPatient", "/followup/patient", 10);
        MenuRow plain = row(211L, 21L, "BUTTON", "查看明文", null, null, 10);

        List<MenuNode> tree = MenuTreeBuilder.build(List.of(patient, plain, followup, dashboard));

        assertEquals(
                List.of("HospitalDashboard", "Followup"),
                tree.stream().map(MenuNode::name).toList());
        assertEquals("FollowupPatient", tree.get(1).children().get(0).name());
        assertTrue(tree.stream().noneMatch(node -> "BUTTON".equals(node.type())));
    }

    private static MenuRow row(
            long id, long parent, String type, String title, String name, String path, int sort) {
        MenuRow row = new MenuRow();
        row.setId(id);
        row.setParentId(parent);
        row.setType(type);
        row.setTitle(title);
        row.setRouteName(name);
        row.setPath(path);
        row.setComponent(type.equals("DIR") ? "LAYOUT" : "placeholder/index");
        row.setSortNo(sort);
        row.setHidden(0);
        return row;
    }
}
