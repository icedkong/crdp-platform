package com.crdp.system.org;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.DeptRow;
import com.crdp.system.infra.mapper.OrgMapper;
import com.crdp.system.infra.mapper.OrgRow;
import com.crdp.system.org.OrgTreeAssembler.OrgCampusNode;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T02 / PLT-ORG-001、002：院区与科室树维护（租户库，权限 tenant:org:view / tenant:org:edit）。
 *
 * <p>规则（docs/requirements/platform-spec.md「关键字段 · 院区与科室」与业务规则）：
 * 编码租户内唯一、创建后不可改；科室不提供删除，只能停用（停用科室不出现在业务选择器里，树中保留以便维护）；
 * 停用前可查影响面（成员数、下级科室数）；有启用中的下级时不允许停用（40901，先停下级）。 患者数据（E08+）尚未建表，影响面中的患者数在对应任务落地时补充。
 */
@Service
public class OrgAdminService {

    private static final int CODE_MAX = 64;
    private static final int NAME_MAX = 128;
    private static final int TYPE_MAX = 32;
    private static final int HIS_CODE_MAX = 64;
    private static final int REASON_MAX = 200;

    private final OrgMapper mapper;
    private final AuditLogger audit;
    private final com.crdp.system.event.OrgUserEvents events;

    public OrgAdminService(
            OrgMapper mapper, AuditLogger audit, com.crdp.system.event.OrgUserEvents events) {
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
    }

    // ---------------------------------------------------------------- 视图

    /** 组织树视图：singleOrg=true 时前端可隐藏院区层级（PLT-ORG-001）。 */
    public record OrgTreeView(boolean singleOrg, List<OrgCampusNode> orgs) {}

    public record OrgImpactView(
            long orgId,
            String code,
            String name,
            int deptCount,
            int enabledDeptCount,
            int memberCount) {}

    public record DeptImpactView(
            long deptId,
            String code,
            String name,
            int memberCount,
            int childCount,
            int enabledChildCount) {}

    public OrgTreeView tree() {
        List<OrgRow> orgs = mapper.listOrgs();
        List<OrgCampusNode> orgNodes = OrgTreeAssembler.assemble(orgs, mapper.listDepts());
        return new OrgTreeView(OrgTreeAssembler.singleOrg(orgs), orgNodes);
    }

    public OrgImpactView orgImpact(long orgId) {
        OrgRow org = requireOrg(orgId);
        List<DeptRow> depts = mapper.listDepts();
        long deptCount = depts.stream().filter(dept -> dept.getOrgId() == orgId).count();
        long enabled =
                depts.stream()
                        .filter(
                                dept ->
                                        dept.getOrgId() == orgId
                                                && "ENABLED".equals(dept.getStatus()))
                        .count();
        return new OrgImpactView(
                org.getId(),
                org.getCode(),
                org.getName(),
                (int) deptCount,
                (int) enabled,
                membersOfOrg(orgId, depts));
    }

    public DeptImpactView deptImpact(long deptId) {
        DeptRow dept = requireDept(deptId);
        return new DeptImpactView(
                dept.getId(),
                dept.getCode(),
                dept.getName(),
                mapper.countDeptMembers(List.of(deptId)),
                mapper.countChildren(deptId),
                mapper.countEnabledChildren(deptId));
    }

    // ---------------------------------------------------------------- 院区

    public record OrgSave(String code, String name) {}

    public record StatusChange(String action, String reason) {}

    public record OrgView(long id, String code, String name, String status) {}

    @Transactional
    public OrgView createOrg(OrgSave request) {
        String code = normalizeCode(request == null ? null : request.code(), "院区编码");
        String name = requireName(request == null ? null : request.name(), "院区名称");
        if (mapper.findOrgIdByCode(code) != null) {
            throw duplicate("院区编码已存在：" + code);
        }
        OrgRow org = new OrgRow();
        org.setCode(code);
        org.setName(name);
        try {
            mapper.insertOrg(org);
        } catch (DuplicateKeyException ex) {
            throw duplicate("院区编码已存在：" + code);
        }
        OrgRow created = requireOrg(org.getId());
        audit("ORG_CREATE", "CREATE", "sys_org:" + code, "新增院区「" + name + "」");
        return toOrgView(created);
    }

    @Transactional
    public OrgView updateOrg(long orgId, OrgSave request) {
        OrgRow org = requireOrg(orgId);
        String name = requireName(request == null ? null : request.name(), "院区名称");
        // 编码创建后不可改：更新可省略，携带时必须与现值一致
        if (request != null
                && request.code() != null
                && !org.getCode().equals(normalizeCode(request.code(), "院区编码"))) {
            throw new ApiException(400, 40001, "院区编码创建后不可修改");
        }
        mapper.updateOrgName(orgId, name);
        if (!name.equals(org.getName())) {
            audit(
                    "ORG_UPDATE",
                    "UPDATE",
                    "sys_org:" + org.getCode(),
                    "院区名称「" + org.getName() + "」→「" + name + "」");
        }
        return toOrgView(requireOrg(orgId));
    }

    @Transactional
    public OrgView changeOrgStatus(long orgId, StatusChange request) {
        OrgRow org = requireOrg(orgId);
        boolean disable = parseDisable(request);
        if (disable) {
            int enabledDepts = mapper.countEnabledDeptsOfOrg(orgId);
            if (enabledDepts > 0) {
                throw new ApiException(
                        409, 40901, "该院区下还有 " + enabledDepts + " 个启用中的科室，请先停用科室再停用院区");
            }
        }
        String target = disable ? "DISABLED" : "ENABLED";
        if (org.getStatus().equals(target)) {
            throw new ApiException(409, 40901, disable ? "院区已是停用状态" : "院区已是启用状态");
        }
        mapper.updateOrgStatus(orgId, target);
        if (disable) {
            // PLT-ORG-013：停用院区发事件（同一事务），业务模块订阅后提示待办转交（不自动改派，OQ-10）
            events.orgDisabled(
                    orgId, org.getCode(), org.getName(), request == null ? null : request.reason());
        }
        audit(
                "ORG_STATUS",
                "STATUS",
                "sys_org:" + org.getCode(),
                (disable ? "停用" : "启用") + "院区「" + org.getName() + "」" + reasonSuffix(request));
        return toOrgView(requireOrg(orgId));
    }

    // ---------------------------------------------------------------- 科室

    /** 科室保存：orgId + parentDeptId（0=院区顶层）+ 编码 + 名称 + 类型 / 负责人 / HIS 编码 / 排序。 */
    public record DeptSave(
            Long orgId,
            Long parentDeptId,
            String code,
            String name,
            String deptType,
            Long leaderUserId,
            String hisCode,
            Integer sortNo) {}

    public record DeptView(
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
            String status) {}

    @Transactional
    public DeptView createDept(DeptSave request) {
        if (request == null || request.orgId() == null) {
            throw bad("所属院区必填");
        }
        requireOrg(request.orgId());
        String code = normalizeCode(request.code(), "科室编码");
        String name = requireName(request.name(), "科室名称");
        validateOptionalFields(request.deptType(), request.hisCode(), request.sortNo());
        if (mapper.findDeptIdByCode(code) != null) {
            throw duplicate("科室编码已存在：" + code);
        }
        long parentId = request.parentDeptId() == null ? 0 : request.parentDeptId();
        validateParentForCreate(request.orgId(), parentId);
        DeptRow dept = new DeptRow();
        dept.setOrgId(request.orgId());
        dept.setParentId(parentId);
        dept.setCode(code);
        dept.setName(name);
        dept.setDeptType(trimmed(request.deptType()));
        dept.setLeaderUserId(validatedLeader(request.leaderUserId()));
        dept.setHisCode(trimmed(request.hisCode()));
        dept.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        try {
            mapper.insertDept(dept);
        } catch (DuplicateKeyException ex) {
            throw duplicate("科室编码已存在：" + code);
        }
        DeptRow created = requireDept(dept.getId());
        audit(
                "DEPT_CREATE",
                "CREATE",
                "sys_dept:" + code,
                "新增科室「" + name + "」（" + orgLabel(created.getOrgId()) + "）");
        return toDeptView(created);
    }

    @Transactional
    public DeptView updateDept(long deptId, DeptSave request) {
        DeptRow dept = requireDept(deptId);
        if (request == null) {
            throw bad("请求不能为空");
        }
        String name = requireName(request.name(), "科室名称");
        validateOptionalFields(request.deptType(), request.hisCode(), request.sortNo());
        // 编码创建后不可改：更新可省略编码（移动 / 排序类整对象提交也允许不带），携带时必须与现值一致
        if (request.code() != null
                && !dept.getCode().equals(normalizeCode(request.code(), "科室编码"))) {
            throw new ApiException(400, 40001, "科室编码创建后不可修改");
        }
        long orgId = request.orgId() == null ? dept.getOrgId() : request.orgId();
        long parentId = request.parentDeptId() == null ? 0 : request.parentDeptId();
        requireOrg(orgId);
        validateParent(orgId, parentId, deptId);
        DeptRow update = new DeptRow();
        update.setId(deptId);
        update.setOrgId(orgId);
        update.setParentId(parentId);
        update.setCode(dept.getCode());
        update.setName(name);
        update.setDeptType(trimmed(request.deptType()));
        update.setLeaderUserId(validatedLeader(request.leaderUserId()));
        update.setHisCode(trimmed(request.hisCode()));
        update.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        mapper.updateDept(update);
        audit(
                "DEPT_UPDATE",
                "UPDATE",
                "sys_dept:" + dept.getCode(),
                "修改科室「" + dept.getName() + "」→「" + name + "」");
        return toDeptView(requireDept(deptId));
    }

    @Transactional
    public DeptView changeDeptStatus(long deptId, StatusChange request) {
        DeptRow dept = requireDept(deptId);
        boolean disable = parseDisable(request);
        if (disable) {
            int enabledChildren = mapper.countEnabledChildren(deptId);
            if (enabledChildren > 0) {
                throw new ApiException(
                        409, 40901, "该科室下还有 " + enabledChildren + " 个启用中的下级科室，请先停用下级科室");
            }
        }
        String target = disable ? "DISABLED" : "ENABLED";
        if (dept.getStatus().equals(target)) {
            throw new ApiException(409, 40901, disable ? "科室已是停用状态" : "科室已是启用状态");
        }
        mapper.updateDeptStatus(deptId, target);
        if (disable) {
            // PLT-ORG-013：停用科室发事件（同一事务），业务选择器随即不再出现该科室
            events.deptDisabled(
                    deptId,
                    dept.getCode(),
                    dept.getName(),
                    dept.getOrgId(),
                    orgLabel(dept.getOrgId()),
                    request == null ? null : request.reason());
        }
        audit(
                "DEPT_STATUS",
                "STATUS",
                "sys_dept:" + dept.getCode(),
                (disable ? "停用" : "启用") + "科室「" + dept.getName() + "」" + reasonSuffix(request));
        return toDeptView(requireDept(deptId));
    }

    // ---------------------------------------------------------------- 内部

    private OrgView toOrgView(OrgRow org) {
        return new OrgView(org.getId(), org.getCode(), org.getName(), org.getStatus());
    }

    private DeptView toDeptView(DeptRow dept) {
        return new DeptView(
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
                dept.getStatus());
    }

    private OrgRow requireOrg(long orgId) {
        OrgRow org = mapper.findOrg(orgId);
        if (org == null) {
            throw new ApiException(404, 40400, "院区不存在或不在本院");
        }
        return org;
    }

    private DeptRow requireDept(long deptId) {
        DeptRow dept = mapper.findDept(deptId);
        if (dept == null) {
            throw new ApiException(404, 40400, "科室不存在或不在本院");
        }
        return dept;
    }

    /** 新建时的上级校验：0 = 院区顶层；否则上级必须存在且属于同一院区。 */
    private void validateParentForCreate(long orgId, long parentId) {
        if (parentId == 0) {
            return;
        }
        DeptRow parent = requireDept(parentId);
        if (parent.getOrgId() != orgId) {
            throw bad("上级科室不属于所填院区");
        }
    }

    /** 更新时的上级校验（含防环）：复用树装配器与 IT / 单测同一套口径。 */
    private void validateParent(long orgId, long parentId, long deptId) {
        if (parentId == 0) {
            return;
        }
        List<DeptRow> depts = mapper.listDepts();
        // 防环 / 同院区检查交给装配器；这里再补「上级存在且属于所填院区」。
        // 不能用 Optional.map(可能返回 null 的函数) 表达三态（合法 / 不合法 / 不存在）——
        // map 把 null 当成「无值」，会让合法移动被误判为「上级科室不存在」。
        String violation = OrgTreeAssembler.validateMove(depts, deptId, parentId).orElse(null);
        if (violation == null) {
            DeptRow parent =
                    depts.stream()
                            .filter(row -> row.getId() != null && row.getId() == parentId)
                            .findFirst()
                            .orElse(null);
            if (parent == null) {
                violation = "上级科室不存在";
            } else if (parent.getOrgId() == null || parent.getOrgId() != orgId) {
                violation = "上级科室不属于所填院区";
            }
        }
        if (violation != null) {
            throw new ApiException(409, 40901, violation);
        }
    }

    private void validateOptionalFields(String deptType, String hisCode, Integer sortNo) {
        lengthLimit("科室类型", deptType, TYPE_MAX);
        lengthLimit("HIS 科室编码", hisCode, HIS_CODE_MAX);
        if (sortNo != null && sortNo < 0) {
            throw bad("排序号不能为负数");
        }
    }

    /** 负责人必须是本院启用用户；为空表示不设负责人。 */
    private Long validatedLeader(Long leaderUserId) {
        if (leaderUserId == null) {
            return null;
        }
        Long enabled = mapper.findEnabledUserId(leaderUserId);
        if (enabled == null) {
            throw bad("负责人不存在或未启用");
        }
        return leaderUserId;
    }

    private int membersOfOrg(long orgId, List<DeptRow> allDepts) {
        List<Long> orgDeptIds =
                allDepts.stream()
                        .filter(dept -> dept.getOrgId() == orgId)
                        .map(DeptRow::getId)
                        .toList();
        return orgDeptIds.isEmpty() ? 0 : mapper.countDeptMembers(orgDeptIds);
    }

    private static boolean parseDisable(StatusChange request) {
        String action = request == null || request.action() == null ? "" : request.action().trim();
        if ("DISABLE".equalsIgnoreCase(action)) {
            return true;
        }
        if ("ENABLE".equalsIgnoreCase(action)) {
            return false;
        }
        throw bad("action 只允许 ENABLE / DISABLE");
    }

    private static String reasonSuffix(StatusChange request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty()) {
            return "";
        }
        lengthLimit("变更原因", reason, REASON_MAX);
        return "，原因：" + reason;
    }

    private static String normalizeCode(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw bad(label + "必填");
        }
        String code = raw.trim();
        lengthLimit(label, code, CODE_MAX);
        if (!code.matches("[A-Za-z0-9_-]+")) {
            throw bad(label + "只允许字母、数字、下划线与短横线");
        }
        return code;
    }

    private static String requireName(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw bad(label + "必填");
        }
        String name = raw.trim();
        lengthLimit(label, name, NAME_MAX);
        return name;
    }

    private static void lengthLimit(String label, String value, int max) {
        if (value != null && value.trim().length() > max) {
            throw bad(label + "过长（最多 " + max + " 个字符）");
        }
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    private static ApiException duplicate(String message) {
        return new ApiException(409, 40902, message);
    }

    private String orgLabel(long orgId) {
        OrgRow org = mapper.findOrg(orgId);
        return org == null ? "院区 " + orgId : org.getName();
    }

    /** 组织维护属配置变更，全部写操作审计（AGENTS 第 5 节第 4 条）。审计不含口令、令牌或患者明文。 */
    private void audit(String eventType, String action, String target, String detail) {
        audit.record(
                new AuditEvent(
                        eventType,
                        CurrentSession.current().map(principal -> principal.userId()).orElse(null),
                        action,
                        target,
                        detail));
    }
}
