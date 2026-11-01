package com.crdp.framework.audit;

import com.crdp.framework.crypto.DigestService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审计哈希链写入器（E08-T02 / PLT-AUD-008）：每条审计 INSERT 与封存计算在同一独立新事务里原子完成。
 *
 * <p>四步（同一连接）： ① 锁链头行（aud_chain_head 的 SELECT ... FOR UPDATE，迁移已播种，行恒存在）——并发写入按表串行化， 链不分叉； ② 前序哈希 =
 * 该表已封存行按主键倒序第一条的 hash（无已封存行取 {@link AuditChainTable#GENESIS}）； ③ 本条 hash = SHA-256(prevHash +
 * 规范化内容)，随行 INSERT（调用方 SQL 以 prev_hash、hash 两列收尾）； ④ 链头推进（last_id / last_hash）。事务失败整体回滚，链头与行不会半途脱节。
 *
 * <p>用独立新事务（REQUIRES_NEW）：审计常在业务事务边界外被调用，也避免与业务锁互相持有； 同表并发只竞争链头行锁。
 *
 * <p>前序哈希以「行」为准（不用链头缓存值）：测试自清理删除尾部行后，新写入自然衔接新尾部，链的定义与 {@link AuditChainVerifier} 的重算口径一致。
 */
@Component
public class AuditChainWriter {

    private final JdbcTemplate jdbc;
    private final DigestService digest;
    private final TransactionOperations tx;

    @org.springframework.beans.factory.annotation.Autowired
    public AuditChainWriter(
            JdbcTemplate jdbc, DigestService digest, PlatformTransactionManager txManager) {
        this(jdbc, digest, newTransactionTemplate(txManager));
    }

    AuditChainWriter(JdbcTemplate jdbc, DigestService digest, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.digest = digest;
        this.tx = tx;
    }

    /**
     * 封存并插入一条审计日志，返回新行主键。
     *
     * @param insertSql 调用方 INSERT 语句，列与 VALUES 占位符以 prev_hash、hash 收尾（占位符数 = args 数 + 2）
     * @param args 与 {@link AuditChainTable#hashColumns()} 对齐的业务参数
     */
    public long insert(AuditChainTable table, String insertSql, List<Object> args) {
        requireChainedInsert(insertSql, args);
        Long id =
                tx.execute(
                        status -> {
                            lockHead(table);
                            String prev = readLastHash(table);
                            String seal = digest.hashHex(table.hashInput(prev, args));
                            List<Object> sealed = new ArrayList<>(args);
                            sealed.add(prev);
                            sealed.add(seal);
                            jdbc.update(insertSql, sealed.toArray());
                            long newRow = requireLastInsertId();
                            advanceHead(table, newRow, seal);
                            return newRow;
                        });
        return id == null ? 0L : id;
    }

    // ---------------------------------------------------------------- 链头与前序哈希

    /** 锁链头行串行化同表并发写入。播种行缺失（异常场景）时补插后再锁。 */
    private void lockHead(AuditChainTable table) {
        String name = table.tableName();
        try {
            jdbc.queryForObject(
                    "SELECT last_hash FROM aud_chain_head WHERE table_name = ? FOR UPDATE",
                    String.class,
                    name);
        } catch (EmptyResultDataAccessException missingSeed) {
            jdbc.update("INSERT IGNORE INTO aud_chain_head (table_name) VALUES (?)", name);
            jdbc.queryForObject(
                    "SELECT last_hash FROM aud_chain_head WHERE table_name = ? FOR UPDATE",
                    String.class,
                    name);
        }
    }

    /** 前序哈希：已封存行按主键倒序第一条；链未开始（无已封存行）返回 GENESIS。 */
    private String readLastHash(AuditChainTable table) {
        // 用 ResultSetExtractor：queryForObject 在零行时抛异常，而「链未开始」是正常态
        String last =
                jdbc.query(
                        "SELECT hash FROM "
                                + table.tableName()
                                + " WHERE hash IS NOT NULL ORDER BY id DESC LIMIT 1",
                        rs -> rs.next() ? rs.getString(1) : null);
        return last == null ? AuditChainTable.GENESIS : last;
    }

    private void advanceHead(AuditChainTable table, long id, String hash) {
        int updated =
                jdbc.update(
                        "UPDATE aud_chain_head SET last_id = ?, last_hash = ? WHERE table_name = ?",
                        id,
                        hash,
                        table.tableName());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO aud_chain_head (table_name, last_id, last_hash) VALUES (?, ?, ?)",
                    table.tableName(),
                    id,
                    hash);
        }
    }

    private long requireLastInsertId() {
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) {
            throw new IllegalStateException("LAST_INSERT_ID() 未返回新行主键（连接被切换？）");
        }
        return id;
    }

    /** 契约自检：SQL 必须以 prev_hash、hash 两列收尾，占位符数 = 业务参数数 + 2。 */
    private static void requireChainedInsert(String insertSql, List<Object> args) {
        long placeholders = insertSql.chars().filter(ch -> ch == '?').count();
        if (placeholders != args.size() + 2L || !insertSql.contains("prev_hash")) {
            throw new IllegalArgumentException(
                    "审计 INSERT 语句必须以 prev_hash、hash 两列收尾（占位符数 = 参数数 + 2）：" + insertSql);
        }
    }

    private static TransactionOperations newTransactionTemplate(PlatformTransactionManager tm) {
        TransactionTemplate template = new TransactionTemplate(tm);
        template.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        return template;
    }
}
