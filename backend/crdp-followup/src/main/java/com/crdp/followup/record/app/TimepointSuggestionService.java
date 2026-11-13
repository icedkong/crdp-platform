package com.crdp.followup.record.app;

import com.crdp.followup.implant.app.DeviceContextService;
import com.crdp.followup.implant.app.ImplantGateway;
import com.crdp.followup.implant.infra.mapper.ImplantMapper.ImplantRow;
import com.crdp.followup.patient.app.PatientContactGateway;
import com.crdp.followup.plan.infra.mapper.PlanEnrollmentMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.record.infra.mapper.RecordParamMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 补录时建议随访时间点（E21-T14 / C-FU-014；docs/api/followup.md §3 {@code GET
 * /patients/{id}/records/suggest-timepoint}）。规则见 hospital-c-spec §5「业务规则与边界情况」：
 *
 * <ul>
 *   <li>按<b>随访日期距当时器械植入日期的天数</b>建议选项（字典 c_visit_timepoint，V90）：0–7 天「术后72小时/出院前」、 25–45
 *       天「术后1个月」、75–110 天「术后3个月」、150–215 天「术后6个月」、330–400 天「术后1年」、
 *       其后「每6-12个月」；空档区间（8–24、46–74、111–149、216–329 天）不建议，用户自选（建议值可改选，C-FU-014）；
 *   <li>器械接近 ERI（在组入组 mode = ERI 且随访日期 ≥ eri_since，E21-T07 翻入组模式）优先建议「ERI期随访」—— ER I
 *       期是状态口径，优先于天数区间；
 *   <li>「自动关联当时的器械」（C-PAT-007 区间匹配）复用 {@link DeviceContextService}；无匹配器械（implant 开关关闭 / 取出后 /
 *       首次植入前）无从算天数，不建议；
 *   <li>区间<b>可配置</b>（「建议值，可配置」）：租户参数 {@value #PARAM_RANGES} 覆盖 → 平台库默认（平台库 V73）→ 代码内置缺省； 非法配置（JSON
 *       解析失败 / 区间不合法）逐级回落缺省，绝不停摆补录（与 VisitNoService 同口径）；
 *   <li>earliestImplantDate 供补录日期下界（「不早于植入日期、不晚于今天」，C 规格
 *       §5「补录一份纸质随访记录」）——取患者<b>最早</b>一条植入记录（更换史下界）， 与天数计算用的「当时器械」分开。
 * </ul>
 *
 * <p>查询经 {@link ImplantGateway}（@DataScope）出行；本服务必须经 Spring 代理调用。越范围患者 40400（不暴露存在）。
 */
@Service
public class TimepointSuggestionService {

    /** 参数键（平台库 V73 登记，创建后不改名）：补录建议随访时间点的天数区间，租户可覆盖。 */
    public static final String PARAM_RANGES = "followup.timepoint.ranges";

    /** C 规格字段编码：随访时间点（模板字段编码定位稳定键，与 death / followup_date 定位同法，不硬编码键名）。 */
    static final String FIELD_CODE_VISIT_TIMEPOINT = "visit_timepoint";

    /** 「其后」（晚于最后一个区间上界）缺省建议：每6-12个月（选项 6，字典 c_visit_timepoint）。 */
    static final int DEFAULT_BEYOND_CODE = 6;

    /** 器械接近 ERI 时的建议：ERI期随访（选项 7；ERI 是状态口径，不是天数区间，不进区间配置）。 */
    static final int ERI_CODE = 7;

    /** 缺省区间（hospital-c-spec §5 建议值；租户参数 followup.timepoint.ranges 可覆盖）。 */
    private static final List<RangeRule> DEFAULT_RANGES =
            List.of(
                    new RangeRule(1, 0, 7),
                    new RangeRule(2, 25, 45),
                    new RangeRule(3, 75, 110),
                    new RangeRule(4, 150, 215),
                    new RangeRule(5, 330, 400));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(TimepointSuggestionService.class);

    private final PatientContactGateway patientScope;
    private final DeviceContextService deviceContext;
    private final ImplantGateway implantGateway;
    private final PlanEnrollmentMapper enrollmentMapper;
    private final FollowupRecordMapper recordMapper;
    private final FormService formService;
    private final RecordParamMapper paramMapper;
    private final TimepointParamDefaults paramDefaults;

    public TimepointSuggestionService(
            PatientContactGateway patientScope,
            DeviceContextService deviceContext,
            ImplantGateway implantGateway,
            PlanEnrollmentMapper enrollmentMapper,
            FollowupRecordMapper recordMapper,
            FormService formService,
            RecordParamMapper paramMapper,
            TimepointParamDefaults paramDefaults) {
        this.patientScope = patientScope;
        this.deviceContext = deviceContext;
        this.implantGateway = implantGateway;
        this.enrollmentMapper = enrollmentMapper;
        this.recordMapper = recordMapper;
        this.formService = formService;
        this.paramMapper = paramMapper;
        this.paramDefaults = paramDefaults;
    }

    // ---------------------------------------------------------------- 视图

    /**
     * 建议结果（补录弹窗「先填随访日期，系统建议随访时间点并匹配器械」）：device / daysSinceImplant 供表头 展示；suggestedTimepoint =
     * 选项编码（无建议 null——空档区间 / 无器械 / 表单无 visit_timepoint 字段）； earliestImplantDate 供补录日期下界（前端禁选更早日期）。
     */
    public record SuggestionView(
            String patientId,
            LocalDate visitDate,
            DeviceContextService.DeviceView device,
            LocalDate earliestImplantDate,
            Integer daysSinceImplant,
            boolean eriFollowup,
            Object suggestedTimepoint,
            String suggestedLabel) {}

    /** 生效区间规则（min–max 闭区间；code = 建议选项编码）。 */
    public record RangeRule(Object code, int minDays, int maxDays) {}

    /** 生效规则集（区间 + 「其后」编码）。 */
    private record RuleSet(List<RangeRule> ranges, int beyondCode) {}

    // ---------------------------------------------------------------- 建议

    /** 建议随访时间点（formCode 空时回落患者在组计划 FORM 节点表单，与补录创建同口径）。 越范围患者 40400；visitDate 必填 40001。 */
    public SuggestionView suggest(long patientId, LocalDate visitDate, String formCode) {
        if (patientScope.patientIdInScope(patientId) == null) {
            throw new ApiException(404, 40400, "患者不存在或不在您的数据范围内");
        }
        if (visitDate == null) {
            throw new ApiException(400, 40001, "visitDate 必填（按随访日期距植入天数建议时间点）");
        }
        String resolvedForm = resolveFormCode(patientId, formCode);
        DeviceContextService.DeviceView device = deviceContext.resolve(patientId, visitDate);
        Integer days =
                device == null || device.implantDate() == null
                        ? null
                        : (int) ChronoUnit.DAYS.between(device.implantDate(), visitDate);
        boolean eri = eriAtVisitDate(patientId, visitDate);
        Object suggestedCode = null;
        if (eri) {
            suggestedCode = ERI_CODE;
        } else if (days != null) {
            suggestedCode = codeForDays(days);
        }
        // 选项编码 / 显示名以模板为准（表单无 visit_timepoint 字段〔A / B〕不建议，编码不落到选项外）
        String label = null;
        Object canonicalCode = null;
        if (suggestedCode != null) {
            OptionMatch match = matchOption(resolvedForm, suggestedCode);
            if (match == null) {
                suggestedCode = null;
            } else {
                canonicalCode = match.code();
                label = match.label();
            }
        }
        return new SuggestionView(
                String.valueOf(patientId),
                visitDate,
                device,
                earliestImplantDate(patientId),
                days,
                eri,
                canonicalCode,
                label);
    }

    /**
     * 补录创建的初始 data（C-FU-013/014）：建议时间点种进草稿、可改选。按模板字段编码 visit_timepoint 定位 稳定键；无字段 / 无建议返回空
     * Map（不动表单）。值为选项编码的原类型（选项集为整数编码，V90）。
     */
    public Map<String, Object> initialData(String formCode, SuggestionView suggestion) {
        if (formCode == null || suggestion == null || suggestion.suggestedTimepoint() == null) {
            return Map.of();
        }
        FormService.SchemaView schema = schemaOf(formCode);
        if (schema == null) {
            return Map.of();
        }
        for (FormService.CategoryView category : schema.categories()) {
            for (FormService.FieldView field : category.fields()) {
                if (FIELD_CODE_VISIT_TIMEPOINT.equals(field.code())) {
                    Map<String, Object> seed = new LinkedHashMap<>();
                    seed.put(field.key(), suggestion.suggestedTimepoint());
                    return seed;
                }
            }
        }
        return Map.of();
    }

    // ---------------------------------------------------------------- 规则

    /** 天数 → 建议选项编码（区间闭 [min, max]；空档 null；晚于最后区间上界 → beyond）。 */
    private Object codeForDays(int days) {
        RuleSet rules = effectiveRules();
        if (rules.ranges().isEmpty()) {
            return null;
        }
        int lastMax = Integer.MIN_VALUE;
        for (RangeRule rule : rules.ranges()) {
            if (days >= rule.minDays() && days <= rule.maxDays()) {
                return rule.code();
            }
            lastMax = Math.max(lastMax, rule.maxDays());
        }
        return days > lastMax ? rules.beyondCode() : null;
    }

    /**
     * 器械接近 ERI 判定：在组（ACTIVE / PAUSED）入组 mode = ERI 且随访日期 ≥ eri_since（E21-T07 起 eri_since 记触发随访日期）。
     */
    private boolean eriAtVisitDate(long patientId, LocalDate visitDate) {
        PlanEnrollmentMapper.EriModeRow row = enrollmentMapper.findEriMode(patientId);
        return row != null
                && "ERI".equals(row.mode())
                && row.eriSince() != null
                && !visitDate.isBefore(row.eriSince());
    }

    /** 生效规则：租户覆盖 → 平台默认 → 代码缺省（非法逐级回落）。 */
    private RuleSet effectiveRules() {
        String override = paramMapper.findOverride(PARAM_RANGES);
        if (override != null) {
            RuleSet parsed = parseRules(override, "租户覆盖");
            if (!parsed.ranges().isEmpty()) {
                return parsed;
            }
        }
        String platform =
                paramDefaults
                        .param(PARAM_RANGES)
                        .map(TimepointParamDefaults.ParamDefault::platformValue)
                        .orElse(null);
        if (platform != null) {
            RuleSet parsed = parseRules(platform, "平台默认");
            if (!parsed.ranges().isEmpty()) {
                return parsed;
            }
        }
        return new RuleSet(DEFAULT_RANGES, DEFAULT_BEYOND_CODE);
    }

    /**
     * 解析区间配置 JSON：{@code {"ranges":[{"code":2,"min":25,"max":45},…],"beyond":6}}； ranges 空 /
     * 非法返回空区间（回落缺省）， beyond 缺省取代码内置 {@value #DEFAULT_BEYOND_CODE}。
     */
    private RuleSet parseRules(String raw, String source) {
        try {
            JsonNode root = JSON.readTree(raw);
            JsonNode ranges = root.get("ranges");
            if (ranges == null || !ranges.isArray() || ranges.isEmpty()) {
                log.warn("补录建议时间点区间配置{}为空（{}），回落缺省区间", source, raw);
                return new RuleSet(List.of(), DEFAULT_BEYOND_CODE);
            }
            List<RangeRule> parsed = new ArrayList<>();
            for (JsonNode item : ranges) {
                JsonNode code = item.get("code");
                JsonNode min = item.get("min");
                JsonNode max = item.get("max");
                if (code == null
                        || code.isNull()
                        || min == null
                        || !min.isInt()
                        || max == null
                        || !max.isInt()) {
                    log.warn("补录建议时间点区间配置{}含非法项（{}），回落缺省区间", source, item);
                    return new RuleSet(List.of(), DEFAULT_BEYOND_CODE);
                }
                if (min.asInt() < 0 || max.asInt() < min.asInt()) {
                    log.warn("补录建议时间点区间配置{}上下界非法（{}–{}），回落缺省区间", source, min, max);
                    return new RuleSet(List.of(), DEFAULT_BEYOND_CODE);
                }
                parsed.add(
                        new RangeRule(
                                code.isInt() ? code.asInt() : code.asText(),
                                min.asInt(),
                                max.asInt()));
            }
            JsonNode beyond = root.get("beyond");
            int beyondCode =
                    beyond != null && beyond.isInt() ? beyond.asInt() : DEFAULT_BEYOND_CODE;
            return new RuleSet(List.copyOf(parsed), beyondCode);
        } catch (Exception invalid) {
            log.warn("补录建议时间点区间配置{}解析失败（{}），回落缺省区间", source, raw);
            return new RuleSet(List.of(), DEFAULT_BEYOND_CODE);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 表单解析：显式传 formCode 用之；空取在组计划 FORM 节点表单（与 RecordBackfillService.resolveFormCode 同口径）。 */
    private String resolveFormCode(long patientId, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return recordMapper.findEnrollmentFormCode(patientId);
    }

    /** 模板版本取当前发布版（建议发生在创建草稿之前，与补录创建同一版本口径）。 */
    private FormService.SchemaView schemaOf(String formCode) {
        return formService.schema(formCode, formService.publishedVersion(formCode));
    }

    /** 模板 visit_timepoint 字段的选项集中找建议编码（数值 / 字符串编码都按字符串比较兼容）。 */
    private OptionMatch matchOption(String formCode, Object code) {
        FormService.SchemaView schema = schemaOf(formCode);
        if (schema == null) {
            return null;
        }
        for (FormService.CategoryView category : schema.categories()) {
            for (FormService.FieldView field : category.fields()) {
                if (!FIELD_CODE_VISIT_TIMEPOINT.equals(field.code())) {
                    continue;
                }
                for (FormService.OptionItemView option : field.optionSet()) {
                    if (String.valueOf(option.code()).equals(String.valueOf(code))) {
                        return new OptionMatch(option.code(), option.label());
                    }
                }
            }
        }
        return null;
    }

    /** 患者最早植入日期（补录日期下界；无植入记录 null——A / B 与未录植入的 C 患者无下界）。 */
    private LocalDate earliestImplantDate(long patientId) {
        LocalDate earliest = null;
        for (ImplantRow row : implantGateway.implantsOf(patientId)) {
            if (row.implantDate() == null) {
                continue;
            }
            if (earliest == null || row.implantDate().isBefore(earliest)) {
                earliest = row.implantDate();
            }
        }
        return earliest;
    }

    /** 命中选项（编码取模板原类型 + 显示名）。 */
    private record OptionMatch(Object code, String label) {}
}
