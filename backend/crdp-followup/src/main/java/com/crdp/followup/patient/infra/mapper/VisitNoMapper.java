package com.crdp.followup.patient.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 随访编号年度流水只读写入（E21-T10 / C-PAT-003；表 pt_visit_no_seq，followup V84）。
 *
 * <p>并发口径：{@link #bumpSerial} 用 {@code LAST_INSERT_ID(seq + 1)} 在行锁内原子自增并把新值写回 本连接会话变量，{@link
 * #lastInsertId} 同连接读回——两个并发登记各自拿到不同的流水号，不需要「读最大值 + 1」 的快照竞态兜底。年度首例由 {@link #insertSeed} 建行（同样经
 * LAST_INSERT_ID 置 1），并发首插撞主键由调用方回到 bump 重试。
 *
 * <p>另读本院前缀覆盖值（cfg_param，写入走 crdp-system 的租户参数页），与 TaskParamMapper 同口径。
 */
@Mapper
public interface VisitNoMapper {

    /** 本院随访编号前缀覆盖值（参数键 followup.visitno.prefix，平台库 V71 登记）；未覆盖返回 null。 */
    @Select("SELECT param_value FROM cfg_param WHERE param_key = 'followup.visitno.prefix'")
    String findPrefixOverride();

    /** 原子自增年度流水（LAST_INSERT_ID(seq+1) 同步写回会话变量）；行不存在返回 0（年度首例走建行）。 */
    @Update(
            "UPDATE pt_visit_no_seq SET seq = LAST_INSERT_ID(seq + 1)"
                    + " WHERE prefix = #{prefix} AND seq_year = #{year}")
    int bumpSerial(@Param("prefix") String prefix, @Param("year") int year);

    /** 年度首例建行：seq 经 LAST_INSERT_ID(1) 置 1，会话变量同步为 1；并发首插由主键约束兜底。 */
    @Insert(
            "INSERT INTO pt_visit_no_seq (prefix, seq_year, seq)"
                    + " VALUES (#{prefix}, #{year}, LAST_INSERT_ID(1))")
    int insertSeed(@Param("prefix") String prefix, @Param("year") int year);

    /** 本连接最近一次 LAST_INSERT_ID 表达式的新值（与 bump / seed 同事务同连接读回）。 */
    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();
}
