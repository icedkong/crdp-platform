package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.VisitNoMapper;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.web.ApiException;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 随访编号规则与生成（E21-T10 / C-PAT-003、Q10：按可配置规则自动生成，默认「前缀-建档年份-年内 3 位流水号」，如 105-2026-001）。
 *
 * <p><b>规则可配置</b>（tasks.csv 验收）：前缀为租户参数 {@value #PARAM_PREFIX}（平台库 plt_param 登记、本院 cfg_param 覆盖，平台库
 * V71），生效值＝本院覆盖 → 平台默认 → 代码内置缺省 {@value #DEFAULT_PREFIX}（OQ-131①：出厂默认按规格示例预置 「105」，UAT
 * 时请医院确认含义）。非法配置（空白 / 含连字符 / 超长）逐级回落缺省，绝不因配置错误停摆登记（与 FollowupPolicyService 同口径）。分隔符与流水号宽度（3 位，超过
 * 999 自然进位）为固定规则不开放配置（Q10 只放前缀）。
 *
 * <p><b>年度流水</b>：pt_visit_no_seq 按（前缀，建档年份）一行计数，{@code LAST_INSERT_ID} 原子自增（行锁内完成，并发登记
 * 各得不同流水号）；年度首例建行，并发首插由主键兜底重试。建档年份按业务时区 Asia/Shanghai（A-NFR-006）。流水号在登记事务内 取号，登记回滚即弃号（允许留空号，不回收）。
 *
 * <p>历史患者手工填写纸质原编号不走本生成器，由登记侧校验本院唯一（C-PAT-003 / Q10，40902）。
 */
@Service
public class VisitNoService {

    /** 参数键（平台库 V71 登记，创建后不改名）：随访编号前缀，租户可覆盖（Q10「前缀由租户配置」）。 */
    public static final String PARAM_PREFIX = "followup.visitno.prefix";

    /** 出厂默认前缀（OQ-131① 暂行：按规格示例「105」预置，租户参数可改，UAT 请医院确认含义）。 */
    static final String DEFAULT_PREFIX = "105";

    /** 前缀长度上限（与 plt_param 取值范围 maxLength 一致，平台库 V71）。 */
    static final int PREFIX_MAX = 16;

    /** 年内流水号宽度（C-PAT-003「年内 3 位流水号」；超过 999 自然进位，不截断）。 */
    static final int SERIAL_WIDTH = 3;

    /** 年度首例建行的并发重试次数（撞主键即对方已建行，一次回读 bump 通常即成功）。 */
    private static final int SEED_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(VisitNoService.class);

    private final VisitNoParamDefaults defaults;
    private final VisitNoMapper mapper;

    public VisitNoService(VisitNoParamDefaults defaults, VisitNoMapper mapper) {
        this.defaults = defaults;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- 规则

    /** 本院生效的编号规则（前缀来自租户参数链；sample 供登记页提示）。 */
    public record VisitNoRule(String prefix) {

        /** 该年份年内首个编号（登记页「如 105-2026-001」提示用）。 */
        public String sample(int year) {
            return prefix + "-" + year + "-" + pad(1);
        }
    }

    /** 本院生效规则：生效前缀＝本院 cfg_param 覆盖 → 平台库默认 → 代码内置缺省（非法逐级回落）。 */
    public VisitNoRule rule() {
        return new VisitNoRule(effectivePrefix());
    }

    // ---------------------------------------------------------------- 生成

    /** 生成下一个随访编号：前缀-建档年份-年内流水号（须在登记事务内调用，同连接读回自增值）。 */
    public String nextVisitNo() {
        VisitNoRule rule = rule();
        int year = currentYear();
        return rule.prefix() + "-" + year + "-" + pad(nextSerial(rule.prefix(), year));
    }

    /** 当前建档年份的年内首号示例（登记页「如 105-2026-001」提示用）。 */
    public String sampleForCurrentYear() {
        return rule().sample(currentYear());
    }

    /** 建档年份：按业务时区 Asia/Shanghai 取登记当天的年份（A-NFR-006）。 */
    static int currentYear() {
        return LocalDate.ofInstant(Instant.now(), PlanDateCalculator.BUSINESS_ZONE).getYear();
    }

    // ---------------------------------------------------------------- 私有

    private String effectivePrefix() {
        String override = mapper.findPrefixOverride();
        String candidate = override != null ? override : platformDefault();
        String sanitized = sanitize(candidate);
        return sanitized != null ? sanitized : DEFAULT_PREFIX;
    }

    private String platformDefault() {
        return defaults.param(PARAM_PREFIX)
                .map(VisitNoParamDefaults.ParamDefault::platformValue)
                .orElse(null);
    }

    /**
     * 合法前缀：去空白后非空、长度 ≤ {@value #PREFIX_MAX}、不含连字符与空白（连字符是「前缀-年份-流水号」的分隔符）。 非法返回
     * null（调用方回落缺省并记日志，不阻断登记）。
     */
    private String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()
                || trimmed.length() > PREFIX_MAX
                || trimmed.contains("-")
                || trimmed.chars().anyMatch(Character::isWhitespace)) {
            log.warn("随访编号前缀配置非法（{}），回落出厂默认 {}", raw, DEFAULT_PREFIX);
            return null;
        }
        return trimmed;
    }

    /** 年度流水：已有行原子自增；年度首例建行，并发首插由主键兜底回到自增。 */
    private long nextSerial(String prefix, int year) {
        for (int attempt = 0; attempt < SEED_ATTEMPTS; attempt++) {
            if (mapper.bumpSerial(prefix, year) == 1) {
                return mapper.lastInsertId();
            }
            try {
                mapper.insertSeed(prefix, year);
                return mapper.lastInsertId();
            } catch (DuplicateKeyException concurrentSeed) {
                // 并发首插：对方已建行，回到 bump 重试
            }
        }
        throw new ApiException(500, 50000, "随访编号生成繁忙，请稍后重试");
    }

    /** 流水号补零到 {@value #SERIAL_WIDTH} 位（超过自然进位）。 */
    static String pad(long serial) {
        return String.format("%0" + SERIAL_WIDTH + "d", serial);
    }
}
