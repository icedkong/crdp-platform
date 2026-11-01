package com.crdp.framework.datascope;

import com.crdp.framework.security.ScopeSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

/**
 * 默认拒绝（AGENTS.md 规则 2、docs/api/README.md §2.5）：无范围上下文抛 {@link
 * DataScopeDeniedException}（40302），空范围追加 {@code AND 1 = 0} 返回空结果而不是报错。
 *
 * <p>E07-T01：空范围条件用 JSqlParser 改写进 WHERE 子句（M0 的字符串拼接对没有 WHERE 的 SQL 会产生 非法 SQL）；解析失败时拒绝执行（fail
 * closed），不静默放行。非空受限范围按病区 / 科室 / 本人的 EXISTS 改写属 E07-T02，此处仍只打标记。
 */
public final class DataScopeGuard {

    private DataScopeGuard() {}

    public static String enforce(String sql, ScopeSnapshot snapshot, boolean ignored) {
        if (!ControlledTables.referencedBy(sql)) {
            return sql;
        }
        if (ignored) {
            return sql;
        }
        if (snapshot == null) {
            throw new DataScopeDeniedException();
        }
        if (snapshot.all()) {
            return sql;
        }
        if (snapshot.empty()) {
            return appendDenyAll(sql);
        }
        return sql + " /* crdp-datascope:restricted */";
    }

    private static String appendDenyAll(String sql) {
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException failure) {
            throw new IllegalStateException("无法解析受控表 SQL 以追加空范围条件，拒绝执行：" + sql, failure);
        }
        if (!(statement instanceof Select select)) {
            throw new IllegalStateException("受控表空范围拦截只支持 SELECT，拒绝执行：" + sql);
        }
        List<PlainSelect> bodies = plainSelectsOf(select);
        if (bodies.isEmpty()) {
            throw new IllegalStateException("无法定位受控表 SQL 的查询体以追加空范围条件，拒绝执行：" + sql);
        }
        for (PlainSelect body : bodies) {
            body.setWhere(
                    body.getWhere() == null
                            ? denyAll()
                            : new AndExpression(body.getWhere(), denyAll()));
        }
        return statement.toString();
    }

    private static List<PlainSelect> plainSelectsOf(Select select) {
        List<PlainSelect> bodies = new ArrayList<>();
        PlainSelect plain = select.getPlainSelect();
        if (plain != null) {
            bodies.add(plain);
            return bodies;
        }
        SetOperationList set = select.getSetOperationList();
        if (set != null) {
            for (Select child : set.getSelects()) {
                PlainSelect childBody = child.getPlainSelect();
                if (childBody != null) {
                    bodies.add(childBody);
                }
            }
        }
        return bodies;
    }

    private static Expression denyAll() {
        return new EqualsTo(new LongValue(1), new LongValue(0));
    }
}
