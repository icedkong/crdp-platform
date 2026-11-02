package com.crdp.system.org;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.crdp.system.infra.mapper.DeptRow;
import com.crdp.system.infra.mapper.OrgMapper;
import com.crdp.system.infra.mapper.OrgRow;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T03 / PLT-ORG-003：科室批量导入（Excel）。同步一次性模型（OQ-52）：文件 ≤{@link #MAX_ROWS} 行、全
 * 文本列，上传即校验并逐行落库，<b>单行失败不影响其他行</b>（platform-spec §6 业务规则，与用户导入同口径）， 返回逐行结果（新增 / 更新 /
 * 失败与原因）；宽表异步作业模型（预检、确认、结果文件）属患者导入（E15+/E18），科室表规模小不引入。
 *
 * <p>列：院区编码、科室编码、上级科室编码、科室名称、科室类型、HIS 科室编码、负责人账号、排序。 院区须已存在（导入不建院区）；科室编码存在即更新（名称 / 类型 / 负责人 / HIS /
 * 排序 / 上级），不存在即新增； 上级科室编码可引用库内已有科室或<b>同文件中任意位置</b>的编码（空 = 院区顶层）；不能把自己或子孙设为上级（防环）；
 * 已存在科室不能借导入跨院区移动；负责人账号必须是本院启用用户。
 *
 * <p>HIS 同步（规格「从 HIS 同步科室」）接口待医院确认，首期只做 Excel 导入（OQ-52）。审计写一条 {@code ORG_IMPORT}
 * 作业级摘要；模板示例行全部为虚构数据。
 */
@Service
public class DeptImportService {

    /** 列顺序即模板顺序，导入按表头逐列精确匹配。 */
    static final List<String> HEADERS =
            List.of("院区编码", "科室编码", "上级科室编码", "科室名称", "科室类型", "HIS科室编码", "负责人账号", "排序");

    static final int MAX_ROWS = 1000;
    private static final int CODE_MAX = 64;
    private static final int NAME_MAX = 128;
    private static final int TYPE_MAX = 32;
    private static final int HIS_CODE_MAX = 64;
    private static final int SORT_MAX = 9999;

    /** 逐行结果：action = CREATED / UPDATED / FAILED（失败行给原因，列号从 1 计）。 */
    public record RowResult(int rowNo, String code, String action, String message) {}

    /** 导入报告：合计、新增、更新、失败与逐行明细。 */
    public record ImportReport(
            int total, int created, int updated, int failed, List<RowResult> rows) {}

    /** 解析后的一行（尚未做跨行 / 跨库校验）。 */
    private record ParsedRow(
            int rowNo,
            String orgCode,
            String code,
            String parentCode,
            String name,
            String deptType,
            String hisCode,
            String leaderUsername,
            String sortText) {}

    private final OrgMapper mapper;
    private final AuditLogger audit;

    public DeptImportService(OrgMapper mapper, AuditLogger audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    /** 模板：表头 + 两行示例（全部虚构数据；示例科室编码 DEPT-EXAMPLE-* 不与种子冲突）。 */
    public byte[] template() {
        return SimpleXlsx.write(
                HEADERS,
                List.of(
                        List.of(
                                "ORG-DEMO",
                                "DEPT-EXAMPLE-1",
                                "",
                                "示例内科（示例）",
                                "临床",
                                "",
                                "nurse.demo",
                                "10"),
                        List.of(
                                "ORG-DEMO",
                                "DEPT-EXAMPLE-2",
                                "DEPT-EXAMPLE-1",
                                "示例内科二病区（示例）",
                                "临床",
                                "",
                                "",
                                "20")));
    }

    /** 导入（一个事务：全部成功行一起提交，失败行只进报告不阻断）。 */
    @Transactional
    public ImportReport importDepts(InputStream file) {
        List<List<String>> grid;
        try {
            grid = SimpleXlsx.read(file);
        } catch (IllegalStateException ex) {
            throw bad("文件无法解析：" + ex.getMessage());
        }
        if (grid.isEmpty()) {
            throw bad("文件为空：请使用下载的模板填写");
        }
        List<String> header = grid.get(0);
        if (header.size() != HEADERS.size()
                || !HEADERS.equals(header.stream().map(String::trim).toList())) {
            throw bad("表头与模板不一致：请下载最新模板（" + String.join("、", HEADERS) + "）");
        }
        List<ParsedRow> rows = parseRows(grid);
        if (rows.isEmpty()) {
            throw bad("文件没有数据行");
        }
        if (rows.size() > MAX_ROWS) {
            throw bad("单次最多导入 " + MAX_ROWS + " 行，当前 " + rows.size() + " 行，请分批上传");
        }

        // 快照：院区、库内已有科室、负责人（避免逐行回库）
        Map<String, OrgRow> orgsByCode = new LinkedHashMap<>();
        for (OrgRow org : mapper.listOrgs()) {
            orgsByCode.put(org.getCode(), org);
        }
        Map<String, DeptRow> existingByCode = new LinkedHashMap<>();
        for (DeptRow dept : mapper.listDepts()) {
            existingByCode.put(dept.getCode(), dept);
        }
        Map<String, Long> leaders = new HashMap<>();

        // 文件内编码去重：第二次出现即失败
        Map<String, Integer> codeRowCount = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            codeRowCount.merge(row.code(), 1, Integer::sum);
        }

        // 逐行校验（列内规则 + 院区 / 负责人 / 库内冲突）
        Map<Integer, String> errors = new LinkedHashMap<>();
        Map<String, ParsedRow> rowsByCode = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            String error = validateRow(row, orgsByCode, existingByCode, leaders, codeRowCount);
            if (error == null) {
                rowsByCode.put(row.code(), row);
            } else {
                errors.put(row.rowNo(), error);
            }
        }
        // 防环（文件叠加库内的有效上级链）只看校验通过的行
        for (ParsedRow row : rows) {
            if (errors.containsKey(row.rowNo())) {
                continue;
            }
            String cycle = cycleCheck(row, rowsByCode, existingByCode);
            if (cycle != null) {
                errors.put(row.rowNo(), cycle);
                rowsByCode.remove(row.code());
            }
        }

        // 执行：父引用可解析（院区顶层 / 库内已有 / 本批已落库）即可处理，多趟直到无进展
        Map<String, Long> idsByCode = new LinkedHashMap<>();
        existingByCode.forEach((code, dept) -> idsByCode.put(code, dept.getId()));
        Map<Integer, RowResult> results = new LinkedHashMap<>();
        LinkedHashSet<ParsedRow> pending =
                new LinkedHashSet<>(
                        rows.stream().filter(row -> !errors.containsKey(row.rowNo())).toList());
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (ParsedRow row : List.copyOf(pending)) {
                Long parentId = resolveParentId(row, idsByCode);
                if (parentId == null) {
                    continue; // 等待本批上级先落库
                }
                boolean created = !idsByCode.containsKey(row.code());
                apply(row, orgsByCode.get(row.orgCode()).getId(), parentId, idsByCode, leaders);
                results.put(
                        row.rowNo(),
                        new RowResult(
                                row.rowNo(), row.code(), created ? "CREATED" : "UPDATED", null));
                pending.remove(row);
                progress = true;
            }
        }
        for (ParsedRow row : pending) {
            results.put(
                    row.rowNo(),
                    new RowResult(
                            row.rowNo(),
                            row.code(),
                            "FAILED",
                            "上级科室「" + row.parentCode() + "」未能导入（其所在行校验失败或循环引用）"));
        }
        errors.forEach(
                (rowNo, message) -> {
                    ParsedRow row =
                            rows.stream()
                                    .filter(item -> item.rowNo() == rowNo)
                                    .findFirst()
                                    .orElse(null);
                    results.put(
                            rowNo,
                            new RowResult(rowNo, row == null ? "" : row.code(), "FAILED", message));
                });

        List<RowResult> ordered =
                results.values().stream()
                        .sorted(java.util.Comparator.comparing(RowResult::rowNo))
                        .toList();
        int created =
                (int) ordered.stream().filter(item -> "CREATED".equals(item.action())).count();
        int updated =
                (int) ordered.stream().filter(item -> "UPDATED".equals(item.action())).count();
        int failed = ordered.size() - created - updated;
        audit(
                "ORG_IMPORT",
                "CREATE",
                "sys_dept:import",
                "批量导入科室：共 "
                        + ordered.size()
                        + " 行，新增 "
                        + created
                        + "，更新 "
                        + updated
                        + "，失败 "
                        + failed
                        + (failed > 0 ? "（失败行见导入报告）" : ""));
        return new ImportReport(ordered.size(), created, updated, failed, ordered);
    }

    // ---------------------------------------------------------------- 解析与校验

    /** 跳过整行为空的行；列内规则与手工维护一致（编码字符集、长度、排序整数）。 */
    private static List<ParsedRow> parseRows(List<List<String>> grid) {
        List<ParsedRow> rows = new ArrayList<>();
        for (int i = 1; i < grid.size(); i++) {
            List<String> cells = grid.get(i);
            if (cells.stream().allMatch(cell -> cell == null || cell.trim().isEmpty())) {
                continue;
            }
            rows.add(
                    new ParsedRow(
                            i + 1, // 行号按 Excel 习惯从 1 计（含表头行）
                            cell(cells, 0),
                            cell(cells, 1),
                            cell(cells, 2),
                            cell(cells, 3),
                            cell(cells, 4),
                            cell(cells, 5),
                            cell(cells, 6),
                            cell(cells, 7)));
        }
        return rows;
    }

    private String validateRow(
            ParsedRow row,
            Map<String, OrgRow> orgsByCode,
            Map<String, DeptRow> existingByCode,
            Map<String, Long> leaders,
            Map<String, Integer> codeRowCount) {
        if (row.orgCode().isBlank()) {
            return "院区编码必填";
        }
        OrgRow org = orgsByCode.get(row.orgCode());
        if (org == null) {
            return "院区不存在：" + row.orgCode();
        }
        if (row.code().isBlank()) {
            return "科室编码必填";
        }
        if (row.code().length() > CODE_MAX || !row.code().matches("[A-Za-z0-9_-]+")) {
            return "科室编码只允许字母、数字、下划线与短横线（≤" + CODE_MAX + " 位）：" + row.code();
        }
        if (codeRowCount.get(row.code()) > 1) {
            return "科室编码在文件内重复：" + row.code();
        }
        if (row.parentCode().equals(row.code())) {
            return "上级科室不能是本科室";
        }
        if (row.name().isBlank()) {
            return "科室名称必填";
        }
        if (row.name().length() > NAME_MAX) {
            return "科室名称过长（最多 " + NAME_MAX + " 个字符）";
        }
        if (row.deptType().length() > TYPE_MAX) {
            return "科室类型过长（最多 " + TYPE_MAX + " 个字符）";
        }
        if (row.hisCode().length() > HIS_CODE_MAX) {
            return "HIS 科室编码过长（最多 " + HIS_CODE_MAX + " 个字符）";
        }
        Integer sortNo = parseSort(row.sortText());
        if (!row.sortText().isBlank() && sortNo == null) {
            return "排序号必须是整数：" + row.sortText();
        }
        if (sortNo != null && sortNo > SORT_MAX) {
            return "排序号过大（最多 " + SORT_MAX + "）";
        }
        DeptRow existing = existingByCode.get(row.code());
        if (existing != null && existing.getOrgId() != org.getId()) {
            return "科室「" + row.code() + "」已属于其他院区，导入不能跨院区移动（请在科室编辑中移动）";
        }
        if (!row.leaderUsername().isBlank()) {
            Long leaderId =
                    leaders.computeIfAbsent(
                            row.leaderUsername(), username -> mapperLeader(username));
            if (leaderId == null) {
                return "负责人账号不存在或未启用：" + row.leaderUsername();
            }
        }
        return null;
    }

    private Long mapperLeader(String username) {
        return mapper.findEnabledUserIdByUsername(username);
    }

    /** 防环：从本科室的上级开始沿「文件有效上级 ∪ 库内上级」上溯，回到本科室编码即环； 链超过全量科室数视为环（防御脏数据）。 */
    private static String cycleCheck(
            ParsedRow row, Map<String, ParsedRow> rowsByCode, Map<String, DeptRow> existingByCode) {
        if (row.parentCode().isBlank()) {
            return null;
        }
        Map<Long, String> codeById = new HashMap<>();
        existingByCode.values().forEach(dept -> codeById.put(dept.getId(), dept.getCode()));
        Map<Long, Long> parentById = new HashMap<>();
        existingByCode.values().forEach(dept -> parentById.put(dept.getId(), dept.getParentId()));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(row.code());
        String cursor = row.parentCode();
        int guard = existingByCode.size() + rowsByCode.size() + 1;
        while (cursor != null && !cursor.isBlank()) {
            if (!seen.add(cursor)) {
                return "上级科室关系存在循环引用（" + row.code() + " → " + cursor + "）";
            }
            ParsedRow fileRow = rowsByCode.get(cursor);
            if (fileRow != null) {
                cursor = fileRow.parentCode();
            } else {
                DeptRow dbRow = existingByCode.get(cursor);
                if (dbRow == null) {
                    return null; // 指向库内不存在的编码：留给执行阶段判定
                }
                Long parentId = parentById.get(dbRow.getId());
                cursor = parentId == null || parentId == 0 ? null : codeById.get(parentId);
            }
            if (--guard < 0) {
                return "上级科室关系存在循环引用";
            }
        }
        return null;
    }

    /** 上级编号：0=院区顶层；库内已有 → 其 id；本批已落库 → 新 id；否则 null（等下一趟）。 */
    private static Long resolveParentId(ParsedRow row, Map<String, Long> idsByCode) {
        if (row.parentCode().isBlank()) {
            return 0L;
        }
        // 停用科室仍可作为上级（与手工编辑同口径），兼容历史结构
        return idsByCode.get(row.parentCode());
    }

    private void apply(
            ParsedRow row,
            long orgId,
            long parentId,
            Map<String, Long> idsByCode,
            Map<String, Long> leaders) {
        DeptRow dept = new DeptRow();
        dept.setOrgId(orgId);
        dept.setParentId(parentId);
        dept.setCode(row.code());
        dept.setName(row.name());
        dept.setDeptType(blankToNull(row.deptType()));
        dept.setLeaderUserId(
                row.leaderUsername().isBlank() ? null : leaders.get(row.leaderUsername()));
        dept.setHisCode(blankToNull(row.hisCode()));
        Integer sortNo = parseSort(row.sortText());
        dept.setSortNo(sortNo == null ? 0 : sortNo);
        Long existingId = idsByCode.get(row.code());
        if (existingId == null) {
            mapper.insertDept(dept);
            idsByCode.put(row.code(), dept.getId());
        } else {
            dept.setId(existingId);
            mapper.updateDept(dept);
        }
    }

    /** 排序号：空 = 未填（落库取 0）；非整数返回 null 由逐行校验报错。 */
    private static Integer parseSort(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

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
