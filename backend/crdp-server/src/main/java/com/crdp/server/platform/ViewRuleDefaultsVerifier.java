package com.crdp.server.platform;

import com.crdp.framework.tenant.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * S4（M3 审查）/ PLT-VIEW-008：启动时校验系统初始默认视角规则在平台库存在。
 *
 * <p>默认规则此前只在本机种子 V109（local / dev / demo）里，生产库一直缺失（缺的是报告单原件隐藏、通话 录音隐藏、RCT 设盲等限制性规则）。现由主迁移 V64
 * 在所有环境登记（与 V109 同内容、code 唯一幂等）。 本校验是安全网：规则被误删 / 迁移未执行时打 ERROR
 * 日志（说明来源与影响），不阻断启动——规则缺失时解析回落「可见、只读、禁止导出」， 视角会话仍受这一兜底约束（见 {@link
 * ViewRuleResolver#INITIAL_DEFAULT}）。
 *
 * <p>只读校验，不自动补写：补写走 Flyway（V64 / V109）或平台管理员在「视角规则」页维护。
 */
@Service
@Order(60)
public class ViewRuleDefaultsVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ViewRuleDefaultsVerifier.class);

    /** 关键默认规则编码（与 V64 / V109 同清单；登记来源即这两处）。 */
    static final List<String> DEFAULT_RULE_CODES =
            List.of(
                    "VR-GLOBAL-DEFAULT",
                    "VR-BASE-CONFIG-RO",
                    "VR-FOLLOWUP-RO",
                    "VR-FOLLOWUP-ADMIN-EDIT",
                    "VR-FIELD-PATIENT-NAME",
                    "VR-FIELD-PATIENT-IDCARD",
                    "VR-FIELD-PATIENT-PHONE",
                    "VR-FIELD-PATIENT-MRN",
                    "VR-FIELD-PATIENT-ADDRESS",
                    "VR-FIELD-USER-PHONE",
                    "VR-FIELD-USER-IDCARD",
                    "VR-FIELD-REPORT-ORIGINAL",
                    "VR-FIELD-CONTACT-RECORDING",
                    "VR-FIELD-RCT-GROUPING",
                    "VR-FIELD-RCT-RANDOM-TABLE",
                    "VR-FIELD-RCT-UNBLINDING");

    /** 布局依赖的角色绑定（OQ-99）：VR-FOLLOWUP-RO 空角色清单 = 全部角色，会把平台管理员的可编辑吞成只读。 */
    static final String FOLLOWUP_RO_CODE = "VR-FOLLOWUP-RO";

    private final JdbcTemplate platform;

    public ViewRuleDefaultsVerifier(JdbcTemplate platform) {
        this.platform = platform;
    }

    @Override
    public void run(ApplicationArguments args) {
        TenantContext.callAsPlatform(
                () -> {
                    verifyRuleRows();
                    verifyFollowupReadOnlyBinding();
                    return null;
                });
    }

    private void verifyRuleRows() {
        Map<String, Boolean> found = new LinkedHashMap<>();
        for (String code : DEFAULT_RULE_CODES) {
            found.put(code, false);
        }
        platform.query(
                "SELECT code FROM plt_view_rule WHERE code IN ("
                        + String.join(",", DEFAULT_RULE_CODES.stream().map(c -> "?").toList())
                        + ")",
                rs -> {
                    found.put(rs.getString(1), true);
                    return null;
                },
                DEFAULT_RULE_CODES.toArray());
        List<String> missing =
                found.entrySet().stream()
                        .filter(e -> !e.getValue())
                        .map(Map.Entry::getKey)
                        .toList();
        if (!missing.isEmpty()) {
            log.error(
                    "平台库缺少系统初始默认视角规则 {}：登记来源是平台库迁移 V64（本机 / 演示另有种子 V109，内容相同）。"
                            + "缺失时视角解析回落「可见、只读、禁止导出」，报告单原件隐藏、通话录音隐藏、RCT 设盲不生效；"
                            + "请核对 flyway_schema_history 是否执行过 V64，或按 V64 清单在「视角规则」页补登记。",
                    missing);
        } else {
            log.info("默认视角规则校验通过：{} 条全部在册（V64 / V109）", DEFAULT_RULE_CODES.size());
        }
    }

    /**
     * VR-FOLLOWUP-RO 的角色绑定校验：实现 / 客服角色已在册时，该规则必须绑定到它们—— 绑定缺失（如生产 V64 执行时角色尚未登记）会让规则作用于全部角色， 平台管理员经
     * VR-FOLLOWUP-ADMIN-EDIT 的可编辑被同层「更严」合并吞成只读（OQ-99）。 绑定关系按 V64 同款语句重放或由平台管理员在视角规则页补配。
     */
    private void verifyFollowupReadOnlyBinding() {
        Integer unbound =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_role r WHERE r.code IN ('IMPLEMENTATION',"
                                + " 'SUPPORT') AND NOT EXISTS (SELECT 1 FROM plt_view_rule_role vr"
                                + " JOIN plt_view_rule rule ON rule.id = vr.rule_id WHERE"
                                + " rule.code = ? AND vr.role_id = r.id)",
                        Integer.class,
                        FOLLOWUP_RO_CODE);
        if (unbound != null && unbound > 0) {
            log.error(
                    "默认视角规则 {} 存在 {} 个平台角色（实施 / 客服）未绑定（登记来源：迁移 V64 / 种子 V109 的"
                            + " plt_view_rule_role 插入，生产角色后于迁移登记时命中 0 行）。"
                            + "未绑定时该规则作用于全部角色，平台管理员的业务数据可编辑会被同层只读合并吞掉（OQ-99）；"
                            + "请按 V64 同款语句重放绑定，或在「视角规则」页把该规则绑定到实施 / 客服。",
                    FOLLOWUP_RO_CODE,
                    unbound);
        }
    }
}
