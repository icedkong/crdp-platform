package com.crdp.system.org;

import com.crdp.system.infra.mapper.DeptRow;
import com.crdp.system.infra.mapper.OrgRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * E06-T02 / PLT-ORG-001、002：组织树纯逻辑。输入为本租户库的院区与科室行（无分页，院级数据量小），
 * 输出嵌套树；同时提供「本科室及下级」子树计算与上级科室移动校验，供服务层与数据范围复用同一口径。
 */
public final class OrgTreeAssembler {

    private OrgTreeAssembler() {}

    /** 院区节点：科室子树按 sortNo 升序、id 升序稳定排序。 */
    public record OrgCampusNode(
            long id, String code, String name, String status, List<DeptNodeView> depts) {}

    /** 科室节点（多级）。停用科室同样返回（维护视角要能看到并重新启用）。 */
    public record DeptNodeView(
            long id,
            long orgId,
            long parentId,
            String code,
            String name,
            String deptType,
            Long leaderUserId,
            String leaderName,
            String hisCode,
            int sortNo,
            String status,
            List<DeptNodeView> children) {}

    /** 组装「院区 → 科室树」。院区按 id 升序；科室按 sortNo 升序、id 升序。 父科室缺失的脏数据回挂为本院区顶层科室，不丢节点。 */
    public static List<OrgCampusNode> assemble(List<OrgRow> orgs, List<DeptRow> depts) {
        Map<Long, List<DeptRow>> byParent = new HashMap<>();
        for (DeptRow dept : depts) {
            byParent.computeIfAbsent(dept.getParentId(), key -> new ArrayList<>()).add(dept);
        }
        Map<Long, List<DeptRow>> byOrg = new HashMap<>();
        for (DeptRow dept : depts) {
            byOrg.computeIfAbsent(dept.getOrgId(), key -> new ArrayList<>()).add(dept);
        }
        Set<Long> deptIds =
                depts.stream().map(DeptRow::getId).collect(Collectors.toUnmodifiableSet());
        Map<Long, DeptNodeView> built = new HashMap<>();
        List<OrgCampusNode> campusNodes = new ArrayList<>(orgs.size());
        for (OrgRow org : sortedOrgs(orgs)) {
            campusNodes.add(
                    new OrgCampusNode(
                            org.getId(),
                            org.getCode(),
                            org.getName(),
                            org.getStatus(),
                            rootsAsNodes(
                                    byOrg.getOrDefault(org.getId(), List.of()),
                                    byParent,
                                    built,
                                    deptIds)));
        }
        return campusNodes;
    }

    /** 单院区口径（PLT-ORG-001「单院区医院可隐藏院区层级」）：启用中的院区 ≤ 1 即视为单院区。 */
    public static boolean singleOrg(List<OrgRow> orgs) {
        return orgs.stream().filter(org -> "ENABLED".equals(org.getStatus())).count() <= 1;
    }

    /** 本科室 + 全部下级（数据范围「本科室及下级」同一口径）。 */
    public static Set<Long> subtreeIds(List<DeptRow> depts, long rootId) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (DeptRow dept : depts) {
            children.computeIfAbsent(dept.getParentId(), key -> new ArrayList<>())
                    .add(dept.getId());
        }
        Set<Long> ids = new java.util.HashSet<>();
        collect(rootId, children, ids);
        return ids;
    }

    /** 移动校验：上级科室必须存在、属同一院区、不是本科室或其下级（防环）。 返回空表示合法，否则返回给用户看的校验文案（进 40001/40901 前的统一入口）。 */
    public static Optional<String> validateMove(
            List<DeptRow> depts, long deptId, long newParentId) {
        if (newParentId == 0) {
            return Optional.empty();
        }
        if (newParentId == deptId) {
            return Optional.of("上级科室不能是本科室");
        }
        Map<Long, DeptRow> byId =
                depts.stream()
                        .collect(
                                Collectors.toMap(
                                        DeptRow::getId,
                                        row -> row,
                                        (a, b) -> a,
                                        LinkedHashMap::new));
        DeptRow parent = byId.get(newParentId);
        if (parent == null) {
            return Optional.of("上级科室不存在");
        }
        DeptRow self = byId.get(deptId);
        // Long 必须用 Objects.equals：!= 是引用比较，orgId 超出 [-128,127] 时恒不等
        if (self != null && !java.util.Objects.equals(parent.getOrgId(), self.getOrgId())) {
            return Optional.of("上级科室不属于同一院区");
        }
        if (subtreeIds(depts, deptId).contains(newParentId)) {
            return Optional.of("上级科室不能是本科室或其下级科室");
        }
        return Optional.empty();
    }

    private static List<OrgRow> sortedOrgs(List<OrgRow> orgs) {
        return orgs.stream().sorted(Comparator.comparingLong(OrgRow::getId)).toList();
    }

    private static List<DeptNodeView> rootsAsNodes(
            List<DeptRow> orgDepts,
            Map<Long, List<DeptRow>> byParent,
            Map<Long, DeptNodeView> built,
            Set<Long> deptIds) {
        List<DeptNodeView> nodes = new ArrayList<>();
        for (DeptRow dept : sorted(orgDepts)) {
            if (dept.getParentId() != 0 && deptIds.contains(dept.getParentId())) {
                continue; // 非顶层，由父节点递归
            }
            nodes.add(asNode(dept, byParent, built));
        }
        return nodes;
    }

    private static DeptNodeView asNode(
            DeptRow dept, Map<Long, List<DeptRow>> byParent, Map<Long, DeptNodeView> built) {
        DeptNodeView cached = built.get(dept.getId());
        if (cached != null) {
            return cached;
        }
        List<DeptNodeView> children = new ArrayList<>();
        DeptNodeView node =
                new DeptNodeView(
                        dept.getId(),
                        dept.getOrgId(),
                        dept.getParentId(),
                        dept.getCode(),
                        dept.getName(),
                        dept.getDeptType(),
                        dept.getLeaderUserId(),
                        dept.getLeaderName(),
                        dept.getHisCode(),
                        dept.getSortNo() == null ? 0 : dept.getSortNo(),
                        dept.getStatus(),
                        children);
        built.put(dept.getId(), node);
        for (DeptRow child : sorted(byParent.getOrDefault(dept.getId(), List.of()))) {
            children.add(asNode(child, byParent, built));
        }
        return node;
    }

    private static List<DeptRow> sorted(List<DeptRow> rows) {
        return rows.stream()
                .sorted(
                        Comparator.comparingInt(
                                        (DeptRow row) ->
                                                row.getSortNo() == null ? 0 : row.getSortNo())
                                .thenComparingLong(DeptRow::getId))
                .toList();
    }

    private static void collect(long id, Map<Long, List<Long>> children, Set<Long> into) {
        into.add(id);
        for (Long child : children.getOrDefault(id, List.of())) {
            collect(child, children, into);
        }
    }
}
