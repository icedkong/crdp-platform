package com.crdp.framework.datascope;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/**
 * E07-T02：受限范围的 SQL 改写（PLT-PERM-007、A-WARD-003、C-WARD-004）。快照由登录时按「多角色并集」 冻结（{@link
 * com.crdp.framework.security.ScopeSnapshot}），这里只负责把它翻译成 SQL 条件：
 *
 * <ul>
 *   <li>患者表 {@code pt_patient}：条件直接落在外层 WHERE —— {@code (ward_id IN (..) OR dept_id IN (..) OR
 *       responsible_user_id = uid)}，三个分支按快照存在与否拼装，分支之间恒为 OR（病区与科室不存在 上下级关系，医院已确认）；
 *   <li>子表（ControlledTables 其余表）：统一 {@code EXISTS (SELECT 1 FROM pt_patient crdp_p WHERE crdp_p.id
 *       = <别名>.patient_id AND crdp_p.deleted = 0 AND (...))}，转病区只改患者一行，子表无需同步；
 *   <li>「仅本人负责的患者」在患者侧是 {@code responsible_user_id = uid}；任务表另含任务负责人分支 {@code fu_task.assignee_id =
 *       uid}（A-WARD-003「责任医生或任务负责人为本人的患者」）。
 * </ul>
 *
 * <p>改写覆盖外层、UNION 各分支、WITH 项、FROM / JOIN 子查询与 WHERE 子查询；受控表若出现在这些位置 之外（如 SELECT 项里的标量子查询），按默认拒绝口径抛
 * {@link IllegalStateException} 拒绝执行，不静默放行（与 E07-T01 解析失败 fail closed 同向）。
 */
final class RestrictedScopeRewriter {

    /** 患者主表及其范围列（tech-plan §5；表由 M2 患者域建，列名以技术方案为准）。 */
    private static final String PATIENT_TABLE = "pt_patient";

    private static final String WARD_COLUMN = "ward_id";
    private static final String DEPT_COLUMN = "dept_id";
    private static final String RESPONSIBLE_COLUMN = "responsible_user_id";
    private static final String CHILD_PATIENT_COLUMN = "patient_id";

    /** EXISTS 内部使用的不冲突患者别名（外层 SQL 不可能引用改写器引入的名字）。 */
    private static final String SCOPED_PATIENT_ALIAS = "crdp_p";

    /** 「仅本人负责的患者」的任务负责人分支：表名 → 负责人列。目前只有任务表（fu_task.assignee_id）； 其余子表归属患者，由患者侧条件覆盖。 */
    private static final Map<String, String> SELF_OWNER_COLUMNS = Map.of("fu_task", "assignee_id");

    private static final Pattern CONTROLLED_WORD =
            Pattern.compile("(?i)\\b(" + String.join("|", ControlledTables.NAMES) + ")\\b");

    private final ScopeSnapshot snapshot;
    private final long userId;
    private int coveredRefs;

    private RestrictedScopeRewriter(ScopeSnapshot snapshot, long userId) {
        this.snapshot = snapshot;
        this.userId = userId;
    }

    /** 解析并改写。非 SELECT、解析失败或有改写器覆盖不到的受控表位置时抛 {@link IllegalStateException}。 */
    static String rewrite(String sql, ScopeSnapshot snapshot, long userId) {
        RestrictedScopeRewriter rewriter = new RestrictedScopeRewriter(snapshot, userId);
        Statement statement;
        try {
            statement = parse(sql);
        } catch (JSQLParserException failure) {
            throw new IllegalStateException("无法解析受控表 SQL 以追加数据范围条件，拒绝执行：" + sql, failure);
        }
        if (!(statement instanceof Select select)) {
            throw new IllegalStateException("受控表数据范围改写只支持 SELECT，拒绝执行：" + sql);
        }
        rewriter.visitSelect(select);
        rewriter.verifyAllControlledRefsCovered(sql);
        return statement.toString();
    }

    private static Statement parse(String sql) throws JSQLParserException {
        return CCJSqlParserUtil.parse(sql);
    }

    // ---------------------------------------------------------------- 遍历

    private void visitSelect(Select select) {
        if (select == null) {
            return;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                visitSelect(item.getSelect());
            }
        }
        if (select instanceof PlainSelect plain) {
            rewriteBody(plain);
            return;
        }
        if (select instanceof SetOperationList set) {
            for (Select child : set.getSelects()) {
                visitSelect(child);
            }
            return;
        }
        if (select instanceof ParenthesedSelect paren) {
            visitSelect(
                    paren.getPlainSelect() != null ? paren.getPlainSelect() : paren.getSelect());
        }
    }

    private void rewriteBody(PlainSelect body) {
        List<String[]> refs = new ArrayList<>();
        collectControlledRefs(body.getFromItem(), refs);
        if (body.getJoins() != null) {
            for (Join join : body.getJoins()) {
                collectControlledRefs(join.getRightItem(), refs);
            }
        }
        if (body.getWhere() != null) {
            body.getWhere()
                    .accept(
                            new ExpressionVisitorAdapter<Void>() {
                                @Override
                                public <S> Void visit(Select subSelect, S context) {
                                    visitSelect(subSelect);
                                    return null;
                                }
                            },
                            null);
        }
        if (refs.isEmpty()) {
            return;
        }
        // 患者直接条件在前、子表 EXISTS 在后：条件落在根表上更直观，语义与声明顺序无关
        Expression extra = null;
        for (String[] ref : refs) {
            if (PATIENT_TABLE.equals(ref[1])) {
                extra = and(extra, directPatientCondition(ref[0]));
            }
        }
        for (String[] ref : refs) {
            if (!PATIENT_TABLE.equals(ref[1])) {
                extra = and(extra, existsCondition(ref[0], ref[1]));
            }
        }
        coveredRefs += refs.size();
        body.setWhere(body.getWhere() == null ? extra : new AndExpression(body.getWhere(), extra));
    }

    /** FROM / JOIN 项里的受控表引用；子查询项递归处理，不作为引用计数。 */
    private void collectControlledRefs(FromItem item, List<String[]> refs) {
        if (item instanceof Table table) {
            String name = table.getName().toLowerCase();
            if (ControlledTables.NAMES.contains(name)) {
                Alias alias = table.getAlias();
                String qualifier =
                        alias == null || alias.getName() == null ? name : alias.getName().trim();
                refs.add(new String[] {qualifier, name});
            }
            return;
        }
        if (item instanceof ParenthesedSelect sub) {
            visitSelect(sub);
        }
    }

    // ---------------------------------------------------------------- 条件构造

    /** 患者表直接条件：三个分支 OR，缺失分支不拼（快照为空列表 / 未开启本人）。 */
    private Expression directPatientCondition(String qualifier) {
        List<Expression> branches = new ArrayList<>();
        if (!snapshot.wardIds().isEmpty()) {
            branches.add(inCondition(qualifier, WARD_COLUMN, snapshot.wardIds()));
        }
        if (!snapshot.deptIds().isEmpty()) {
            branches.add(inCondition(qualifier, DEPT_COLUMN, snapshot.deptIds()));
        }
        if (snapshot.self()) {
            branches.add(equalsCondition(qualifier, RESPONSIBLE_COLUMN, userId));
        }
        return orGroup(branches);
    }

    /** 子表 EXISTS：经 patient_id 关联患者，患者侧 deleted = 0，范围分支与患者表同构，另含表自身负责人列。 */
    private Expression existsCondition(String qualifier, String tableName) {
        Table patient = new Table(PATIENT_TABLE);
        patient.setAlias(new Alias(SCOPED_PATIENT_ALIAS, false));
        PlainSelect inner = new PlainSelect();
        inner.addSelectItems(new LongValue(1));
        inner.setFromItem(patient);
        Expression correlation =
                new EqualsTo(
                        column(SCOPED_PATIENT_ALIAS, "id"),
                        column(qualifier, CHILD_PATIENT_COLUMN));
        Expression predicate =
                new AndExpression(
                        correlation,
                        new EqualsTo(column(SCOPED_PATIENT_ALIAS, "deleted"), new LongValue(0)));
        List<Expression> branches = new ArrayList<>();
        if (!snapshot.wardIds().isEmpty()) {
            branches.add(inCondition(SCOPED_PATIENT_ALIAS, WARD_COLUMN, snapshot.wardIds()));
        }
        if (!snapshot.deptIds().isEmpty()) {
            branches.add(inCondition(SCOPED_PATIENT_ALIAS, DEPT_COLUMN, snapshot.deptIds()));
        }
        if (snapshot.self()) {
            branches.add(equalsCondition(SCOPED_PATIENT_ALIAS, RESPONSIBLE_COLUMN, userId));
            String ownerColumn = SELF_OWNER_COLUMNS.get(tableName);
            if (ownerColumn != null) {
                branches.add(equalsCondition(qualifier, ownerColumn, userId));
            }
        }
        inner.setWhere(and(predicate, orGroup(branches)));
        ParenthesedSelect sub = new ParenthesedSelect();
        sub.setSelect(inner);
        ExistsExpression exists = new ExistsExpression();
        exists.setRightExpression(sub);
        return exists;
    }

    private InExpression inCondition(String qualifier, String column, List<Long> values) {
        List<Expression> literals = new ArrayList<>(values.size());
        for (Long value : values) {
            literals.add(new LongValue(value));
        }
        InExpression in = new InExpression();
        in.setLeftExpression(column(qualifier, column));
        in.setRightExpression(new ParenthesedExpressionList<>(literals));
        return in;
    }

    private EqualsTo equalsCondition(String qualifier, String column, long value) {
        return new EqualsTo(column(qualifier, column), new LongValue(value));
    }

    private static Column column(String qualifier, String name) {
        return new Column(new Table(qualifier), name);
    }

    private static Expression orGroup(List<Expression> branches) {
        if (branches.size() == 1) {
            return branches.get(0);
        }
        Expression acc = branches.get(0);
        for (int i = 1; i < branches.size(); i++) {
            acc = new OrExpression(acc, branches.get(i));
        }
        return new ParenthesedExpressionList<>(acc);
    }

    private static Expression and(Expression left, Expression right) {
        return left == null ? right : new AndExpression(left, right);
    }

    // ---------------------------------------------------------------- 兜底校验

    /**
     * 原文里受控表出现的次数必须与已覆盖的 FROM / JOIN 引用数一致，否则说明受控表在改写器 覆盖不到的位置（如 SELECT 项标量子查询），拒绝执行而不是带着未过滤的子查询放行。
     */
    private void verifyAllControlledRefsCovered(String sql) {
        Matcher matcher = CONTROLLED_WORD.matcher(sql);
        int occurrences = 0;
        List<String> seen = new ArrayList<>();
        while (matcher.find()) {
            occurrences++;
            String word = matcher.group(1).toLowerCase();
            if (!seen.contains(word)) {
                seen.add(word);
            }
        }
        if (occurrences != coveredRefs) {
            throw new IllegalStateException(
                    "受控表（"
                            + String.join(", ", seen)
                            + "）出现在数据范围改写覆盖不到的位置（仅支持外层 / UNION 分支 / WITH 项 / FROM-JOIN 与 WHERE"
                            + " 子查询的 FROM / JOIN），拒绝执行："
                            + sql);
        }
    }
}
