package com.crdp.server.system.user;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.excel.SimpleXlsx;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SecurityProperties;
import com.crdp.framework.web.ApiException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T08 / PLT-ORG-007：用户批量导入（Excel）。与科室导入（{@link com.crdp.system.org.DeptImportService}，
 * E06-T03）同一同步一次性模型（OQ-52）：文件 ≤{@link #MAX_ROWS} 行、上传即逐行校验并落库，<b>单行失败不影响其他行</b>（platform-spec §6
 * 业务规则「导入时任一行失败不影响 其他行，返回失败行与原因」），返回逐行结果。
 *
 * <p>列：账号、姓名、工号、手机号、主科室编码、角色编码、负责病区编码、负责科室编码。 清单列（角色 / 负责病区 / 负责科室）用分号（半角 {@code ;} 或全角 {@code
 * ；}）分隔多个编码。
 *
 * <p>导入口径（规格未细化处的暂行决定，PR 与 docs/api/system.md §2 已注明）：
 *
 * <ul>
 *   <li><b>只新建、不更新</b>：账号已存在（库内或文件内重复）该行失败——导入面向批量开通（如 E23 上线约 30 名用户），
 *       覆盖既有用户的角色与负责范围应走用户管理编辑（有最后的租户管理员保护与权限版本递增）；
 *   <li>逐行校验与手工新建同口径（账号字符集 / 长度、姓名必填、大陆手机号、主科室与角色必填、模板角色不可直接分配、 未知科室 / 病区 / 角色逐行报原因），规则复用 {@link
 *       UserAccountAdminService} 的纯函数，避免两处口径漂移；
 *   <li><b>初始密码不随导入返回</b>：每行生成随机初始密码只落哈希并置「首登强制改密」，凭据按 platform-spec §6 新建用户流程
 *       「由用户首次短信登录后设置密码」（短信下行未接入前不回显、不写审计与日志，避免批量明文外泄）；
 *   <li>审计写一条 {@code USER_IMPORT} 作业级摘要（不逐行写、不含手机号明文）；模板示例行全部为虚构数据。
 * </ul>
 */
@Service
public class UserImportService {

    /** 列顺序即模板顺序，导入按表头逐列精确匹配。 */
    static final List<String> HEADERS =
            List.of("账号", "姓名", "工号", "手机号", "主科室编码", "角色编码", "负责病区编码", "负责科室编码");

    static final int MAX_ROWS = 1000;

    private static final String PHONE_PURPOSE = "sys-user-phone";

    /** 逐行结果：action = CREATED / FAILED（失败行给原因，行号按 Excel 习惯含表头从 1 计）。 */
    public record RowResult(int rowNo, String username, String action, String message) {}

    /** 导入报告：合计、新增、失败与逐行明细（导入不更新既有用户，无 UPDATED）。 */
    public record ImportReport(int total, int created, int failed, List<RowResult> rows) {}

    /** 解析后的一行（尚未做跨行 / 跨库校验）。 */
    private record ParsedRow(
            int rowNo,
            String username,
            String displayName,
            String staffNo,
            String phone,
            String mainDeptCode,
            List<String> roleCodes,
            List<String> wardCodes,
            List<String> deptScopeCodes) {}

    private final JdbcTemplate jdbc;
    private final CryptoService cryptoService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;
    private final AuditLogger audit;

    /** E05-T09 / PLT-ORG-010：批量导入也是新建，达到用户数上限的行按行失败（单行失败不影响其他行的口径不变）。 */
    private final com.crdp.server.platform.TenantQuotaGuard quotaGuard;

    private final RandomGenerator random = new SecureRandom();

    public UserImportService(
            JdbcTemplate jdbc,
            CryptoService cryptoService,
            PasswordEncoder passwordEncoder,
            SecurityProperties securityProperties,
            AuditLogger audit,
            com.crdp.server.platform.TenantQuotaGuard quotaGuard) {
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
        this.audit = audit;
        this.quotaGuard = quotaGuard;
    }

    /** 模板：表头 + 两行示例（全部虚构数据；示例账号 it.nurse.example 不与种子冲突）。 */
    public byte[] template() {
        return SimpleXlsx.write(
                HEADERS,
                List.of(
                        List.of(
                                "it.nurse.example",
                                "李护士（示例）",
                                "S-1001",
                                "13900000003",
                                "DEPT-CARDIO",
                                "FOLLOWUP_DOCTOR",
                                "W-DEMO-1",
                                ""),
                        List.of(
                                "it.doctor.example",
                                "王医生（示例）",
                                "",
                                "13900000004",
                                "DEPT-CARDIO",
                                "FOLLOWUP_DOCTOR；FOLLOWUP_CRC",
                                "",
                                "DEPT-CARDIO")));
    }

    /** 导入（一个事务：全部成功行一起提交，失败行只进报告不阻断）。 */
    @Transactional
    public ImportReport importUsers(InputStream file) {
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

        // 快照：库内账号、科室、角色、病区（避免逐行回库）；手机号唯一性按盲索引与库内比对
        Map<String, Long> usernames = new LinkedHashMap<>();
        for (Map<String, Object> row :
                jdbc.queryForList("SELECT id, username FROM sys_user", (Object[]) new Object[0])) {
            usernames.put(String.valueOf(row.get("username")), asLong(row.get("id")));
        }
        // 手机号唯一性按盲索引比对（库内不存明文，AGENTS 第 5 节）
        LinkedHashSet<String> phoneIndexes = new LinkedHashSet<>();
        for (String idx : jdbc.queryForList("SELECT phone_idx FROM sys_user", String.class)) {
            if (idx != null && !idx.isBlank()) {
                phoneIndexes.add(idx);
            }
        }
        Map<String, Long> deptIdsByCode = idsByCode("SELECT id, code FROM sys_dept");
        Map<String, Long> wardIdsByCode = idsByCode("SELECT id, code FROM ward");
        Map<String, Map<String, Object>> rolesByCode = new LinkedHashMap<>();
        for (Map<String, Object> role :
                jdbc.queryForList(
                        "SELECT id, code, name, role_type FROM sys_role",
                        (Object[]) new Object[0])) {
            rolesByCode.put(String.valueOf(role.get("code")), role);
        }
        // 文件内去重：账号与手机号第二次出现即失败
        Map<String, Integer> usernameRowCount = new LinkedHashMap<>();
        Map<String, Integer> phoneRowCount = new LinkedHashMap<>();
        for (ParsedRow row : rows) {
            usernameRowCount.merge(row.username(), 1, Integer::sum);
            phoneRowCount.merge(row.phone(), 1, Integer::sum);
        }

        int created = 0;
        List<RowResult> results = new ArrayList<>(rows.size());
        for (ParsedRow row : rows) {
            String error =
                    validateRow(
                            row,
                            usernames,
                            phoneIndexes,
                            deptIdsByCode,
                            wardIdsByCode,
                            rolesByCode,
                            usernameRowCount,
                            phoneRowCount);
            if (error != null) {
                results.add(new RowResult(row.rowNo(), row.username(), "FAILED", error));
                continue;
            }
            try {
                insertUser(row, deptIdsByCode, wardIdsByCode, rolesByCode);
                created++;
                results.add(new RowResult(row.rowNo(), row.username(), "CREATED", null));
            } catch (DuplicateKeyException ex) {
                // 并发兜底：导入过程中他人新建了同账号 / 同手机号
                results.add(new RowResult(row.rowNo(), row.username(), "FAILED", "账号或手机号已被其他用户占用"));
            } catch (ApiException ex) {
                // PLT-ORG-010：配额满额等业务拒绝按行失败（insertUser 里配额校验在落库前）
                results.add(new RowResult(row.rowNo(), row.username(), "FAILED", ex.getMessage()));
            }
        }
        int failed = results.size() - created;
        audit(
                "USER_IMPORT",
                "CREATE",
                "sys_user:import",
                "批量导入用户：共 "
                        + results.size()
                        + " 行，新增 "
                        + created
                        + "，失败 "
                        + failed
                        + (failed > 0 ? "（失败行见导入报告）" : ""));
        return new ImportReport(results.size(), created, failed, results);
    }

    // ---------------------------------------------------------------- 解析与校验

    /** 跳过整行为空的行；清单列按分号拆分（半角 / 全角皆可，剔除空段）。 */
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
                            splitCodes(cell(cells, 5)),
                            splitCodes(cell(cells, 6)),
                            splitCodes(cell(cells, 7))));
        }
        return rows;
    }

    /** 清单列拆分：半角 ; 或全角 ；分隔，逐段去空白，空段剔除，保序不去重（重复段在引用解析时自然收敛）。 */
    static List<String> splitCodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> codes = new ArrayList<>();
        for (String part : raw.split("[;；]")) {
            String code = part.trim();
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** 单行校验：列内规则复用手工新建的纯函数（{@link UserAccountAdminService}）， 再叠加文件内重复、库内已存在与引用存在性；返回 null 表示通过。 */
    private String validateRow(
            ParsedRow row,
            Map<String, Long> usernames,
            LinkedHashSet<String> phoneIndexes,
            Map<String, Long> deptIdsByCode,
            Map<String, Long> wardIdsByCode,
            Map<String, Map<String, Object>> rolesByCode,
            Map<String, Integer> usernameRowCount,
            Map<String, Integer> phoneRowCount) {
        String fieldError = fieldError(row);
        if (fieldError != null) {
            return fieldError;
        }
        if (usernameRowCount.get(row.username()) > 1) {
            return "账号在文件内重复：" + row.username();
        }
        if (phoneRowCount.get(row.phone()) > 1) {
            return "手机号在文件内重复：" + UserAccountAdminService.maskedPhone(row.phone());
        }
        if (usernames.containsKey(row.username())) {
            return "账号已存在：" + row.username() + "（如需修改请在用户管理中编辑，导入不更新既有用户）";
        }
        if (phoneIndexes.contains(cryptoService.blindIndex(PHONE_PURPOSE, row.phone()))) {
            return "该手机号已绑定本院其他账号：" + UserAccountAdminService.maskedPhone(row.phone());
        }
        if (!deptIdsByCode.containsKey(row.mainDeptCode())) {
            return "主科室不存在：" + row.mainDeptCode();
        }
        for (String roleCode : row.roleCodes()) {
            Map<String, Object> role = rolesByCode.get(roleCode);
            if (role == null) {
                return "角色不存在：" + roleCode;
            }
            if ("TEMPLATE".equals(String.valueOf(role.get("role_type")))) {
                return "模板角色「" + role.get("name") + "」不能直接分配，请先复制成自定义角色（PLT-ORG-005）：" + roleCode;
            }
        }
        for (String wardCode : row.wardCodes()) {
            if (!wardIdsByCode.containsKey(wardCode)) {
                return "负责病区不存在：" + wardCode;
            }
        }
        for (String deptCode : row.deptScopeCodes()) {
            if (!deptIdsByCode.containsKey(deptCode)) {
                return "负责科室不存在：" + deptCode;
            }
        }
        return null;
    }

    /** 列内规则：与手工新建同口径（字符集 / 长度 / 手机号格式），异常文案直接转行错误。 */
    private static String fieldError(ParsedRow row) {
        try {
            UserAccountAdminService.normalizeUsername(row.username());
            UserAccountAdminService.requireDisplayName(row.displayName());
            UserAccountAdminService.optionalStaffNo(row.staffNo());
            UserAccountAdminService.requirePhone(row.phone());
        } catch (ApiException ex) {
            return ex.getMessage();
        }
        if (row.roleCodes().isEmpty()) {
            return "角色编码必填（至少一个，多个用分号分隔）";
        }
        return null;
    }

    // ---------------------------------------------------------------- 落库

    /** 新建一个用户：随机初始密码只落哈希（不返回），首登强制改密；角色 / 负责病区 / 负责科室一并写入。 */
    private void insertUser(
            ParsedRow row,
            Map<String, Long> deptIdsByCode,
            Map<String, Long> wardIdsByCode,
            Map<String, Map<String, Object>> rolesByCode) {
        // PLT-ORG-010：批量导入也是新建（导入即启用），配额满额该行失败
        quotaGuard.assertUserQuotaAvailable(jdbc, "导入用户");
        String temporary =
                UserAccountAdminService.generateTemporaryPassword(
                        row.username(),
                        UserAccountAdminService.effectiveMinPasswordLength(
                                jdbc, securityProperties),
                        random);
        String phoneIdx = cryptoService.blindIndex(PHONE_PURPOSE, row.phone());
        jdbc.update(
                "INSERT INTO sys_user (username, display_name, password_hash, status,"
                        + " must_change_password, staff_no, main_dept_id, phone, phone_idx)"
                        + " VALUES (?, ?, ?, 'ENABLED', 1, ?, ?, ?, ?)",
                row.username(),
                row.displayName(),
                passwordEncoder.encode(temporary),
                row.staffNo().isBlank() ? null : row.staffNo(),
                deptIdsByCode.get(row.mainDeptCode()),
                cryptoService.encrypt(PHONE_PURPOSE, row.phone()),
                phoneIdx);
        Long userId = usernamesIdOf(row.username());
        for (String roleCode : new LinkedHashSet<>(row.roleCodes())) {
            jdbc.update(
                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                    userId,
                    asLong(rolesByCode.get(roleCode).get("id")));
        }
        for (String wardCode : new LinkedHashSet<>(row.wardCodes())) {
            jdbc.update(
                    "INSERT INTO sys_user_ward (user_id, ward_id) VALUES (?, ?)",
                    userId,
                    wardIdsByCode.get(wardCode));
        }
        for (String deptCode : new LinkedHashSet<>(row.deptScopeCodes())) {
            jdbc.update(
                    "INSERT INTO sys_user_dept_scope (user_id, dept_id) VALUES (?, ?)",
                    userId,
                    deptIdsByCode.get(deptCode));
        }
    }

    private Long usernamesIdOf(String username) {
        List<Long> ids =
                jdbc.queryForList(
                        "SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        if (ids.isEmpty()) {
            throw new IllegalStateException("导入用户新建后未找到账号：" + username);
        }
        return ids.get(0);
    }

    private Map<String, Long> idsByCode(String sql) {
        Map<String, Long> byCode = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, (Object[]) new Object[0])) {
            byCode.put(String.valueOf(row.get("code")), asLong(row.get("id")));
        }
        return byCode;
    }

    private static long asLong(Object value) {
        return ((Number) java.util.Objects.requireNonNull(value)).longValue();
    }

    private static String cell(List<String> cells, int index) {
        if (index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
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
