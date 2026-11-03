package com.crdp.followup.patient.infra.mapper;

import com.crdp.framework.datascope.DataScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 患者列表检索（E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009；E13-T06 高级检索
 * A-PAT-014、A-NFR-009；docs/api/patient.md §1）。
 *
 * <p>病区隔离（验收「按病区隔离」）由语句上的 {@code @DataScope(resource = "patient")} 承担： E07-T02 拦截器按登录快照改写为 {@code
 * (p.ward_id IN (..) OR p.dept_id IN (..) OR p.responsible_user_id = uid)}， 服务层与 SQL
 * 都不拼范围条件；范围为空时拦截器追加 {@code AND (1 = 0)} 返回空列表。高级检索的条件树（E13-T06）
 * 同样不含病区条件——拦截器对整条语句自动追加（A-NFR-009「自动追加病区条件」）。
 *
 * <p>姓名 / 病案号精确检索（验收「按姓名精确检索」）：两列为加密列 + 盲索引（followup V37）， 服务层先算检索词的姓名 / 病案号盲索引，这里只按盲索引相等匹配
 * （A-PAT-002「病案号、姓名精确查找」，不做模糊）。 手术年龄 = 手术日期 − 出生日期（周岁，V36 口径），SQL 内一次算完。
 *
 * <p>高级检索片段注入（E13-T06）：{@code ${c.advancedWhere}} 由服务层经 {@link
 * com.crdp.followup.patient.domain.AdvancedConditionCompiler} 从**字段字典目录白名单**生成——列名只来自
 * 白名单绑定、运算符为固定枚举，取值全部走 {@code #{c.advancedBinds[i]}} 预编译参数位，请求原文不进 SQL 文本
 * （A-NFR-009「转换为参数化查询，不允许输入原始 SQL」；与 orderClause 同为服务端生成片段）。
 *
 * <p>{@code pt_patient_summary} 也在受控表清单：拦截器对 JOIN 引用追加 EXISTS（经 patient_id 关联患者），
 * 因登记即建行（E13-T03）1:1 成立；followup V40 为老库补齐缺失行，保证 LEFT JOIN 不丢在册患者。
 */
@Mapper
public interface PatientSearchMapper {

    /** 列表行原始数据：姓名 / 病案号为密文，由应用层解密后脱敏输出。 */
    record PatientRowRaw(
            Long id,
            String nameCipher,
            String mrnCipher,
            String legacyNo,
            String gender,
            Integer age,
            String wardName,
            String doctorName,
            LocalDate surgeryDate,
            LocalDate dischargeDate,
            String status,
            LocalDate nextVisitDate,
            Integer baselineCompleteness,
            BigDecimal followupCompleteness) {}

    /**
     * 检索条件（全部可空 = 不过滤；nameBlind / mrnBlind 同源于一个检索词，OR 匹配）。 advancedWhere / advancedBinds
     * 为高级检索条件树的编译产物（参数化片段 + 参数位取值）。
     */
    record SearchCriteria(
            String nameBlind,
            String mrnBlind,
            List<Long> wardIds,
            List<Long> deptIds,
            List<String> status,
            Long doctorId,
            Integer dischargeStatus,
            String patientType,
            LocalDate surgeryFrom,
            LocalDate surgeryTo,
            LocalDate dischargeFrom,
            LocalDate dischargeTo,
            LocalDate nextVisitFrom,
            LocalDate nextVisitTo,
            String orderClause,
            String advancedWhere,
            List<Object> advancedBinds) {}

    /** 分页检索一页（排序子句由服务层白名单生成后经 ${} 注入，字段码不透传）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT p.id, p.name_cipher AS nameCipher, p.mrn_cipher AS mrnCipher,
                   p.legacy_no AS legacyNo, p.gender,
                   TIMESTAMPDIFF(YEAR, p.birth_date, p.surgery_date) AS age,
                   w.name AS wardName, u.display_name AS doctorName,
                   p.surgery_date AS surgeryDate, p.discharge_date AS dischargeDate,
                   p.followup_status AS status, s.next_plan_date AS nextVisitDate,
                   s.baseline_completeness AS baselineCompleteness,
                   s.followup_completeness AS followupCompleteness
            FROM pt_patient p
            LEFT JOIN pt_patient_summary s ON s.patient_id = p.id
            LEFT JOIN ward w ON w.id = p.ward_id
            LEFT JOIN sys_user u ON u.id = p.responsible_user_id
            WHERE p.deleted = 0
            <if test="c.nameBlind != null">
              AND (p.name_blind = #{c.nameBlind} OR p.mrn_blind = #{c.mrnBlind})
            </if>
            <if test="c.wardIds != null">
              AND p.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.deptIds != null">
              AND p.dept_id IN
              <foreach collection="c.deptIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.status != null">
              AND p.followup_status IN
              <foreach collection="c.status" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.doctorId != null">AND p.responsible_user_id = #{c.doctorId}</if>
            <if test="c.dischargeStatus != null">AND p.discharge_status = #{c.dischargeStatus}</if>
            <if test="c.patientType != null">AND p.patient_type = #{c.patientType}</if>
            <if test="c.surgeryFrom != null">AND p.surgery_date &gt;= #{c.surgeryFrom}</if>
            <if test="c.surgeryTo != null">AND p.surgery_date &lt;= #{c.surgeryTo}</if>
            <if test="c.dischargeFrom != null">AND p.discharge_date &gt;= #{c.dischargeFrom}</if>
            <if test="c.dischargeTo != null">AND p.discharge_date &lt;= #{c.dischargeTo}</if>
            <if test="c.nextVisitFrom != null">AND s.next_plan_date &gt;= #{c.nextVisitFrom}</if>
            <if test="c.nextVisitTo != null">AND s.next_plan_date &lt;= #{c.nextVisitTo}</if>
            <if test="c.advancedWhere != null">
              AND ${c.advancedWhere}
            </if>
            ORDER BY ${c.orderClause}
            LIMIT #{offset}, #{limit}
            </script>
            """)
    List<PatientRowRaw> search(
            @Param("c") SearchCriteria criteria,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 条件总数（与 {@link #search} 同一条件树；病区 / 科室范围改写同口径）。 */
    @DataScope(resource = "patient")
    @Select(
            """
            <script>
            SELECT COUNT(*)
            FROM pt_patient p
            LEFT JOIN pt_patient_summary s ON s.patient_id = p.id
            WHERE p.deleted = 0
            <if test="c.nameBlind != null">
              AND (p.name_blind = #{c.nameBlind} OR p.mrn_blind = #{c.mrnBlind})
            </if>
            <if test="c.wardIds != null">
              AND p.ward_id IN
              <foreach collection="c.wardIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.deptIds != null">
              AND p.dept_id IN
              <foreach collection="c.deptIds" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.status != null">
              AND p.followup_status IN
              <foreach collection="c.status" item="item" open="(" separator="," close=")">#{item}</foreach>
            </if>
            <if test="c.doctorId != null">AND p.responsible_user_id = #{c.doctorId}</if>
            <if test="c.dischargeStatus != null">AND p.discharge_status = #{c.dischargeStatus}</if>
            <if test="c.patientType != null">AND p.patient_type = #{c.patientType}</if>
            <if test="c.surgeryFrom != null">AND p.surgery_date &gt;= #{c.surgeryFrom}</if>
            <if test="c.surgeryTo != null">AND p.surgery_date &lt;= #{c.surgeryTo}</if>
            <if test="c.dischargeFrom != null">AND p.discharge_date &gt;= #{c.dischargeFrom}</if>
            <if test="c.dischargeTo != null">AND p.discharge_date &lt;= #{c.dischargeTo}</if>
            <if test="c.nextVisitFrom != null">AND s.next_plan_date &gt;= #{c.nextVisitFrom}</if>
            <if test="c.nextVisitTo != null">AND s.next_plan_date &lt;= #{c.nextVisitTo}</if>
            <if test="c.advancedWhere != null">
              AND ${c.advancedWhere}
            </if>
            </script>
            """)
    long count(@Param("c") SearchCriteria criteria);
}
