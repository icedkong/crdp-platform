package com.crdp.platform.tenant;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 从平台库读「作业应执行」的租户清单，供调度框架扇出。口径与技术方案第 5 章每日调度一致：试用 / 启用 / 宽限期。 宽限期默认正常使用（PLT-TEN-012），
 * 作业照常跑；停用租户不在清单里，即 PLT-TEN-016 的「暂停定时任务」（含 E05-T03 的到期作业本身——停用态无需再处理）。
 */
@Component
public class JdbcTenantDirectory implements TenantDirectory {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TenantRef> enabledTenants() {
        return TenantContext.callAsPlatform(
                () ->
                        jdbcTemplate.query(
                                """
                                SELECT id, code FROM plt_tenant
                                WHERE status IN ('ENABLED', 'TRIAL', 'GRACE')
                                ORDER BY id
                                """,
                                (rs, rowNum) ->
                                        new TenantRef(rs.getLong("id"), rs.getString("code"))));
    }
}
