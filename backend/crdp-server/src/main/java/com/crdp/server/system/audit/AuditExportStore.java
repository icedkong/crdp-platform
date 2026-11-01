package com.crdp.server.system.audit;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * E08-T04 / PLT-AUD-007 导出作业存储（job_export / job_export_approval，平台库 V28 与 租户库 base V19 同构；平台库多
 * tenant_id 列，用 {@link Schema} 区分）。医院端与平台端服务共用。
 *
 * <p>状态机：PENDING_APPROVAL（待审批）→ GENERATING（异步生成）→ READY（生成完成，expires_at 前可下载）；
 * REJECTED（审批驳回）、FAILED（生成失败）为终态。job_export 是作业表不是审计表，允许 UPDATE； 审批轨迹 job_export_approval 只追加。
 */
@Component
public class AuditExportStore {

    /** 两种库形态：平台库 job_export 带 tenant_id（按租户筛选导出时记录），租户库不带。 */
    public enum Schema {
        PLATFORM,
        TENANT
    }

    public static final String STATUS_PENDING = "PENDING_APPROVAL";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_FAILED = "FAILED";

    /** 生成完成后文件的可用时长：24 小时（platform-spec §9「导出下载链接有效期 24 小时」建议值）。 */
    public static final int DOWNLOAD_HOURS = 24;

    private final JdbcTemplate jdbc;

    public AuditExportStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建导出申请；status 由调用方决定（免审批直接 GENERATING，待审批 PENDING_APPROVAL）。 */
    public long insertJob(
            Schema schema,
            Long tenantId,
            String bizType,
            String exportType,
            String conditionJson,
            String reason,
            long applicantId,
            String applicantName,
            String status) {
        String sql =
                switch (schema) {
                    case PLATFORM ->
                            "INSERT INTO job_export (tenant_id, biz_type, export_type, condition_json,"
                                    + " reason, applicant_id, applicant_name, status) VALUES (?,?,?,?,?,?,?,?)";
                    case TENANT ->
                            "INSERT INTO job_export (biz_type, export_type, condition_json, reason,"
                                    + " applicant_id, applicant_name, status) VALUES (?,?,?,?,?,?,?)";
                };
        Object[] args =
                switch (schema) {
                    case PLATFORM ->
                            new Object[] {
                                tenantId,
                                bizType,
                                exportType,
                                conditionJson,
                                reason,
                                applicantId,
                                applicantName,
                                status
                            };
                    case TENANT ->
                            new Object[] {
                                bizType,
                                exportType,
                                conditionJson,
                                reason,
                                applicantId,
                                applicantName,
                                status
                            };
                };
        KeyHolder keys = new GeneratedKeyHolder();
        int[] argTypes =
                switch (schema) {
                    case PLATFORM ->
                            new int[] {
                                Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                                Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR
                            };
                    case TENANT ->
                            new int[] {
                                Types.VARCHAR,
                                Types.VARCHAR,
                                Types.VARCHAR,
                                Types.VARCHAR,
                                Types.BIGINT,
                                Types.VARCHAR,
                                Types.VARCHAR
                            };
                };
        jdbc.update(
                con -> {
                    PreparedStatement ps =
                            con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] == null) {
                            ps.setNull(i + 1, argTypes[i]);
                        } else {
                            ps.setObject(i + 1, args[i], argTypes[i]);
                        }
                    }
                    return ps;
                },
                keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 取作业行；不存在返回 null。 */
    public Map<String, Object> job(long id) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM job_export WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** CAS 状态迁移：仅当当前状态等于 expect 时更新为 target（防并发双审 / 重复触发）。 */
    public boolean transition(long id, String expect, String target) {
        return jdbc.update(
                        "UPDATE job_export SET status = ? WHERE id = ? AND status = ?",
                        target,
                        id,
                        expect)
                > 0;
    }

    /** 审批落定：写审批人与意见，同时 CAS 状态。 */
    public boolean decide(
            long id,
            String expect,
            String target,
            long approverId,
            String approverName,
            String comment) {
        boolean moved =
                jdbc.update(
                                "UPDATE job_export SET status = ?, approver_id = ?, approver_name = ?,"
                                        + " approve_comment = ? WHERE id = ? AND status = ?",
                                target,
                                approverId,
                                approverName,
                                comment,
                                id,
                                expect)
                        > 0;
        if (!moved) {
            return false;
        }
        jdbc.update(
                "INSERT INTO job_export_approval (export_id, action, approver_id, approver_name,"
                        + " comment) VALUES (?,?,?,?,?)",
                id,
                "REJECTED".equals(target) ? "REJECT" : "APPROVE",
                approverId,
                approverName,
                comment);
        return true;
    }

    /** 生成完成：行数、文件名、哈希、内容与下载截止时间。 */
    public void markReady(
            long id,
            long rowCount,
            String fileName,
            String fileHash,
            byte[] body,
            LocalDateTime expiresAt) {
        jdbc.update(
                "UPDATE job_export SET status = ?, row_count = ?, file_name = ?, file_hash = ?,"
                        + " file_body = ?, expires_at = ? WHERE id = ?",
                STATUS_READY,
                rowCount,
                fileName,
                fileHash,
                body,
                expiresAt,
                id);
    }

    /** 生成失败：原因入库，作业终态。 */
    public void markFailed(long id, String reason) {
        jdbc.update(
                "UPDATE job_export SET status = ?, fail_reason = ? WHERE id = ?",
                STATUS_FAILED,
                truncate(reason),
                id);
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
