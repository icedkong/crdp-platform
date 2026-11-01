package com.crdp.system.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.system.infra.mapper.DeptRow;
import com.crdp.system.infra.mapper.OrgRow;
import com.crdp.system.org.OrgTreeAssembler.DeptNodeView;
import com.crdp.system.org.OrgTreeAssembler.OrgCampusNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** E06-T02 / PLT-ORG-001、002：组织树纯逻辑（组装、子树、移动校验、单院区口径）。 全部为虚构示例数据，不依赖数据库。 */
class OrgTreeAssemblerTest {

    private static OrgRow org(long id, String code, String name, String status) {
        OrgRow row = new OrgRow();
        row.setId(id);
        row.setCode(code);
        row.setName(name);
        row.setStatus(status);
        return row;
    }

    private static DeptRow dept(
            long id,
            long orgId,
            long parentId,
            String code,
            String name,
            int sortNo,
            String status) {
        DeptRow row = new DeptRow();
        row.setId(id);
        row.setOrgId(orgId);
        row.setParentId(parentId);
        row.setCode(code);
        row.setName(name);
        row.setSortNo(sortNo);
        row.setStatus(status);
        return row;
    }

    @Test
    void buildsMultiLevelTreeUnderEachCampus() {
        List<OrgCampusNode> orgs =
                OrgTreeAssembler.assemble(
                        List.of(
                                org(1, "ORG-A", "示例院区一", "ENABLED"),
                                org(2, "ORG-B", "示例院区二", "ENABLED")),
                        List.of(
                                dept(11, 1, 0, "D-110", "示例心内科", 20, "ENABLED"),
                                dept(12, 1, 11, "D-120", "示例心内科一病区", 10, "ENABLED"),
                                dept(13, 1, 11, "D-130", "示例心内科二病区", 5, "ENABLED"),
                                dept(21, 2, 0, "D-210", "示例呼吸科", 10, "ENABLED")));

        assertEquals(2, orgs.size());
        assertEquals("示例院区一", orgs.get(0).name());
        List<DeptNodeView> roots = orgs.get(0).depts();
        assertEquals(1, roots.size(), "每个院区只有一棵科室树");
        assertEquals("示例心内科", roots.get(0).name());
        // 同级按 sortNo 升序：二病区(5)在前，一病区(10)在后
        assertEquals("示例心内科二病区", roots.get(0).children().get(0).name());
        assertEquals("示例心内科一病区", roots.get(0).children().get(1).name());
        assertEquals(0, orgs.get(1).depts().get(0).children().size());
    }

    @Test
    void singleOrgOnlyWhenAtMostOneEnabledCampus() {
        assertTrue(OrgTreeAssembler.singleOrg(List.of()), "无院区也按单院区口径（引导先建院区）");
        assertTrue(
                OrgTreeAssembler.singleOrg(List.of(org(1, "ORG-A", "示例院区一", "ENABLED"))),
                "单院区医院可隐藏院区层级（PLT-ORG-001）");
        assertTrue(
                OrgTreeAssembler.singleOrg(
                        List.of(
                                org(1, "ORG-A", "示例院区一", "ENABLED"),
                                org(2, "ORG-B", "示例院区二", "DISABLED"))),
                "停用院区不计入院区层级展示");
        assertTrue(
                !OrgTreeAssembler.singleOrg(
                        List.of(
                                org(1, "ORG-A", "示例院区一", "ENABLED"),
                                org(2, "ORG-B", "示例院区二", "ENABLED"))),
                "多院区医院显示院区层级");
    }

    @Test
    void orphanDeptKeepsUnderItsOwnCampusAsRoot() {
        // parent_id 指向不存在的科室（脏数据）：不丢数据，回挂为本院区顶层科室
        List<OrgCampusNode> orgs =
                OrgTreeAssembler.assemble(
                        List.of(org(1, "ORG-A", "示例院区一", "ENABLED")),
                        List.of(dept(11, 1, 99, "D-110", "示例孤儿科", 10, "ENABLED")));
        assertEquals(1, orgs.get(0).depts().size());
        assertEquals("示例孤儿科", orgs.get(0).depts().get(0).name());
        assertEquals("D-110", orgs.get(0).depts().get(0).code());
    }

    @Test
    void subtreeContainsNodeAndAllDescendants() {
        List<DeptRow> depts =
                List.of(
                        dept(11, 1, 0, "D-110", "示例心内科", 10, "ENABLED"),
                        dept(12, 1, 11, "D-120", "示例一病区", 10, "ENABLED"),
                        dept(13, 1, 12, "D-130", "示例CCU", 10, "ENABLED"),
                        dept(14, 1, 0, "D-140", "示例呼吸科", 20, "ENABLED"));
        assertEquals(
                Set.of(11L, 12L, 13L),
                OrgTreeAssembler.subtreeIds(depts, 11L),
                "子树 = 本科室 + 全部下级（数据范围「本科室及下级」同口径）");
        assertEquals(Set.of(14L), OrgTreeAssembler.subtreeIds(depts, 14L));
    }

    @Test
    void moveValidationRejectsSelfAncestorAndForeignParent() {
        List<DeptRow> depts =
                List.of(
                        dept(11, 1, 0, "D-110", "示例心内科", 10, "ENABLED"),
                        dept(12, 1, 11, "D-120", "示例一病区", 10, "ENABLED"),
                        dept(13, 1, 12, "D-130", "示例CCU", 10, "ENABLED"),
                        dept(14, 2, 0, "D-210", "示例二院呼吸科", 10, "ENABLED"),
                        dept(15, 1, 0, "D-140", "示例呼吸科", 20, "ENABLED"));

        assertEquals("上级科室不能是本科室", OrgTreeAssembler.validateMove(depts, 11L, 11L).orElse(null));
        assertEquals(
                "上级科室不能是本科室或其下级科室",
                OrgTreeAssembler.validateMove(depts, 11L, 13L).orElse(null),
                "移入自己的下级会形成环");
        assertEquals("上级科室不存在", OrgTreeAssembler.validateMove(depts, 11L, 404L).orElse(null));
        assertEquals(
                "上级科室不属于同一院区",
                OrgTreeAssembler.validateMove(depts, 11L, 14L).orElse(null),
                "跨院区挂靠会破坏院区 → 科室的层级");
        assertTrue(OrgTreeAssembler.validateMove(depts, 11L, 15L).isEmpty(), "同级院区内的合法移动");
        assertTrue(OrgTreeAssembler.validateMove(depts, 12L, 15L).isEmpty(), "移到本科室子树之外合法");
    }
}
