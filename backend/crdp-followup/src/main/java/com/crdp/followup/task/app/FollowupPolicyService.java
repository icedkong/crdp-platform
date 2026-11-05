package com.crdp.followup.task.app;

import com.crdp.followup.task.infra.mapper.TaskParamMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 失访判定与未联系到自动跟进策略（E15-T05 / A-FU-012、A-FU-024；tech-plan §9「规则（租户参数）」）。
 *
 * <p>生效值＝本院 cfg_param 覆盖值，否则平台库 plt_param 默认值（平台库 V46 登记），再回落代码内置缺省（平台定义 缺失或值不可解析时）：
 *
 * <ul>
 *   <li>失访判定连续次数（A-FU-012「次数可配置」，A 默认 3）：窗口内<b>不同日期</b>连续 N 次「未联系到 / 号码错误」 且窗口已结束 → 失访候选；
 *   <li>未联系到跟进间隔天数（A-FU-024「间隔天数可配置」，默认 1 = 次日，Q28）：沟通业务日 + N 天为跟进任务开启日。
 * </ul>
 *
 * <p>「每个窗口最多 5 次」是医院确认的固定值（A-FU-024 / Q28），不开放配置（{@link #MAX_FOLLOWUPS_PER_WINDOW}）。 读取口径与
 * FilePolicyService（E10-T01）一致：每次读取实时合并覆盖值，平台侧默认值由适配器缓存 60 秒。
 */
@Service
public class FollowupPolicyService {

    /** 参数键（平台库 V46 登记，创建后不改名）。 */
    public static final String KEY_LOST_CANDIDATE_ATTEMPTS = "followup.lost.candidate.attempts";

    /** 参数键（平台库 V46 登记，创建后不改名）。 */
    public static final String KEY_FOLLOWUP_INTERVAL_DAYS =
            "followup.unreached.followup.interval.days";

    /** 代码内置缺省（plt_param 缺行或值不可解析时兜底；A 规格：连续 3 次、次日跟进）。 */
    static final int DEFAULT_LOST_CANDIDATE_ATTEMPTS = 3;

    static final int DEFAULT_FOLLOWUP_INTERVAL_DAYS = 1;

    /** 每窗口跟进任务数上限（A-FU-024「每个窗口最多 5 次」，医院已确认；固定值不开放配置）。 */
    public static final int MAX_FOLLOWUPS_PER_WINDOW = 5;

    private static final Logger log = LoggerFactory.getLogger(FollowupPolicyService.class);

    private final FollowupParamDefaults defaults;
    private final TaskParamMapper overrides;

    public FollowupPolicyService(FollowupParamDefaults defaults, TaskParamMapper overrides) {
        this.defaults = defaults;
        this.overrides = overrides;
    }

    /** 本院生效策略：失访判定连续次数与未联系到跟进间隔天数（A-FU-012 / A-FU-024）。 */
    public record ContactPolicy(int lostCandidateAttempts, int unreachedFollowupIntervalDays) {}

    /** 本院生效的失访与跟进策略。 */
    public ContactPolicy policy() {
        return new ContactPolicy(
                effectiveInt(KEY_LOST_CANDIDATE_ATTEMPTS, DEFAULT_LOST_CANDIDATE_ATTEMPTS),
                effectiveInt(KEY_FOLLOWUP_INTERVAL_DAYS, DEFAULT_FOLLOWUP_INTERVAL_DAYS));
    }

    // ---------------------------------------------------------------- 私有

    /** 生效数值：本院覆盖 → 平台默认 → 代码缺省（不可解析逐级回落，绝不因配置错误停摆刷新）。 */
    private int effectiveInt(String key, int fallback) {
        String override = overrides.findOverride(key);
        if (override != null) {
            Integer parsed = parseInt(override);
            if (parsed != null) {
                return parsed;
            }
            log.warn("租户覆盖参数 {}={} 不可解析，回落平台默认", key, override);
        }
        Integer platform =
                defaults.param(key)
                        .map(FollowupParamDefaults.ParamDefault::platformValue)
                        .map(FollowupPolicyService::parseInt)
                        .orElse(null);
        return platform != null ? platform : fallback;
    }

    private static Integer parseInt(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
