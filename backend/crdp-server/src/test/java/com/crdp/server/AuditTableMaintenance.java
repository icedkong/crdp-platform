package com.crdp.server;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 测试自清理专用：在维护开关下对审计表执行 DELETE / UPDATE（E08-T02 只读触发器的唯一逃生口）。
 *
 * <p>迁移 V17 / V26 起，审计表在数据库层拒绝 UPDATE / DELETE（PLT-AUD-011）。IT 的自清理必须走本助手： 同一事务连接上先
 * SET @crdp_audit_maintenance = 1 再执行语句（触发器按连接级会话变量放行）， 结束时清掉变量——会话变量随连接存活，连接归还池后若仍置位，
 * 后续普通操作会踩空只读保护。 生产与正常运行从不设置该变量； 运维清算同样口径（用完即清，见 V17
 * 脚本注释）。注意：清理会在哈希链上留下空洞，属预期——链的完整性由验链作业（PLT-AUD-009）对真实环境校验。
 */
public final class AuditTableMaintenance {

    private AuditTableMaintenance() {}

    /** 带维护开关的 DELETE，返回影响行数。 */
    public static int delete(JdbcTemplate jdbc, String sql, Object... args) {
        return run(jdbc, sql, args);
    }

    /** 带维护开关的 UPDATE（篡改用例构造断链用），返回影响行数。 */
    public static int update(JdbcTemplate jdbc, String sql, Object... args) {
        return run(jdbc, sql, args);
    }

    private static int run(JdbcTemplate jdbc, String sql, Object... args) {
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null) {
            throw new IllegalStateException("JdbcTemplate 必须携带 DataSource 才能开维护事务");
        }
        TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Integer affected =
                tx.execute(
                        status -> {
                            try {
                                // 会话变量绑定在本事务连接上：后续 DELETE 与触发器看到同一变量
                                jdbc.execute("SET @crdp_audit_maintenance = 1");
                                return jdbc.update(sql, args);
                            } finally {
                                // 连接要归还池：必须清掉维护开关，不能泄漏给后续普通操作
                                jdbc.execute("SET @crdp_audit_maintenance = NULL");
                            }
                        });
        return affected == null ? 0 : affected;
    }
}
