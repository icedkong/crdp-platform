package com.crdp.followup.plan.domain;

import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划节点的结构校验与归一（E14-T01 / A-FU-016）：三层「计划 > 子计划 > 任务」中的后两层落 共表 fu_plan_node（parentKey 为空 = 子计划
 * SUBPLAN，指向子计划 = 节点 NODE，任务即节点定义）。纯函数、不碰库，saveNodes 与 publish 前置共用。
 *
 * <p>口径（A-FU-016、tech-plan §8「节点参数」表）：
 *
 * <ul>
 *   <li>子计划：重复方式 FIXED（固定次数 ≥1）/ CYCLE（持续循环周期 ≥1，单位天 / 周 / 月 / 年，直到患者死亡、失访或人工停止）；
 *   <li>节点：基准 PLAN_START / LAST_VISIT、偏移 ≥0（单位天 / 周 / 月 / 年）、提醒提前量 ≥0（0 = 当天，单位天 / 周 / 月）、 窗口前 / 后各
 *       ≥0 且可不对称（单位天 / 周 / 月 / 年）、任务类型 FORM（须填表单 code）/ REMINDER（站内提醒）、 负责人类型 DOCTOR /
 *       WARD_DEFAULT、入组前已过窗口 BACKFILL / SKIP、启用条件 NORMAL / ERI（C）；
 *   <li>枚举一律归一为大写、数值去负；节点必须挂在同一计划的子计划下（parentKey 必须能解析到 SUBPLAN 行）。
 * </ul>
 *
 * <p>长度与数量上限：名称 ≤128、表单 code ≤64、子计划 ≤100 个、每子计划节点 ≤100 个（防呆上限，正常计划 远达不到：A 长期年度随访 1 个子计划 1
 * 个节点）。校验失败抛 40001（字段 原因 拼接为一句）。
 */
public final class PlanNodeRule {

    /** 单位：偏移与窗口可用天 / 周 / 月 / 年；提醒提前量按 A-FU-016 只有天 / 周 / 月。 */
    public static final Set<String> OFFSET_UNITS = Set.of("DAY", "WEEK", "MONTH", "YEAR");

    public static final Set<String> REMIND_UNITS = Set.of("DAY", "WEEK", "MONTH");

    public static final Set<String> REPEAT_MODES = Set.of("FIXED", "CYCLE");

    public static final Set<String> TASK_TYPES = Set.of("FORM", "REMINDER");

    public static final Set<String> OWNER_TYPES = Set.of("DOCTOR", "WARD_DEFAULT");

    public static final Set<String> PASSED_WINDOW_ACTIONS = Set.of("BACKFILL", "SKIP");

    public static final Set<String> ENROLL_MODES = Set.of("NORMAL", "ERI");

    private static final int NAME_MAX = 128;

    private static final int FORM_CODE_MAX = 64;

    private static final int SUBPLAN_MAX = 100;

    private static final int NODES_PER_SUBPLAN_MAX = 100;

    private PlanNodeRule() {}

    /** 待保存的节点入参（key / parentKey 是请求内临时引用，行 id 由保存顺序生成）。 */
    public record NodeInput(
            String key,
            String parentKey,
            String name,
            String repeatMode,
            Integer repeatCount,
            Integer cycleValue,
            String cycleUnit,
            String basis,
            Integer offsetValue,
            String offsetUnit,
            Integer remindValue,
            String remindUnit,
            Integer windowBeforeValue,
            String windowBeforeUnit,
            Integer windowAfterValue,
            String windowAfterUnit,
            String taskType,
            String ownerType,
            String formCode,
            String passedWindowAction,
            String enrollMode,
            Integer sortNo) {}

    /** 归一后的节点（nodeType = SUBPLAN / NODE；未填项取默认：基准 PLAN_START、任务 FORM、负责人 WARD_DEFAULT 等）。 */
    public record NormalizedNode(
            String nodeType,
            String parentKey,
            String name,
            String repeatMode,
            Integer repeatCount,
            Integer cycleValue,
            String cycleUnit,
            String basis,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBeforeValue,
            String windowBeforeUnit,
            int windowAfterValue,
            String windowAfterUnit,
            String taskType,
            String ownerType,
            String formCode,
            String passedWindowAction,
            String enrollMode,
            int sortNo) {}

    /** 校验并归一整棵两层树（保持入参顺序）；不合法抛 40001。 */
    public static List<NormalizedNode> normalize(List<NodeInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw bad("节点不能为空：至少一个子计划及其节点（A-FU-016 三层结构）");
        }
        Map<String, Integer> keyIndex = new LinkedHashMap<>();
        List<NormalizedNode> normalized = new ArrayList<>(inputs.size());
        int subplans = 0;
        for (NodeInput input : inputs) {
            if (input.key() == null || input.key().isBlank()) {
                throw bad("节点缺少 key（同一请求内唯一引用）");
            }
            String key = input.key().trim();
            if (keyIndex.put(key, normalized.size()) != null) {
                throw bad("节点 key 重复：" + key);
            }
            boolean isSubplan = input.parentKey() == null || input.parentKey().isBlank();
            if (isSubplan) {
                subplans++;
                if (subplans > SUBPLAN_MAX) {
                    throw bad("子计划最多 " + SUBPLAN_MAX + " 个");
                }
            }
            normalized.add(normalizeNode(input, isSubplan));
        }
        if (subplans == 0) {
            throw bad("至少需要一个子计划（里程碑），节点挂在子计划下（A-FU-016）");
        }
        // 第二遍：解析 parentKey（SUBPLAN 行在前在后都允许，先归一再校验引用）
        Map<String, Integer> nodesPerSubplan = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            NormalizedNode node = normalized.get(i);
            if (!"NODE".equals(node.nodeType())) {
                continue;
            }
            String parentKey = node.parentKey().trim();
            Integer parentIndex = keyIndex.get(parentKey);
            if (parentIndex == null) {
                throw bad("节点的上级子计划不存在：" + parentKey);
            }
            if (!"SUBPLAN".equals(normalized.get(parentIndex).nodeType())) {
                throw bad("节点只能挂在子计划下（不能挂节点下）：" + parentKey);
            }
            int count = nodesPerSubplan.merge(parentKey, 1, Integer::sum);
            if (count > NODES_PER_SUBPLAN_MAX) {
                throw bad("同一子计划下节点最多 " + NODES_PER_SUBPLAN_MAX + " 个：" + parentKey);
            }
        }
        return normalized;
    }

    private static NormalizedNode normalizeNode(NodeInput input, boolean isSubplan) {
        String type = isSubplan ? "SUBPLAN" : "NODE";
        String name = requireName(input.name(), type);
        String repeatMode = null;
        Integer repeatCount = null;
        Integer cycleValue = null;
        String cycleUnit = null;
        if (isSubplan) {
            repeatMode = upper(input.repeatMode());
            if (!REPEAT_MODES.contains(repeatMode)) {
                throw bad("子计划「" + name + "」重复方式只支持固定次数（FIXED）或持续循环（CYCLE）");
            }
            if ("FIXED".equals(repeatMode)) {
                repeatCount = requirePositive(input.repeatCount(), "子计划「" + name + "」固定次数");
            } else {
                cycleValue = requirePositive(input.cycleValue(), "子计划「" + name + "」循环周期");
                cycleUnit = unit(input.cycleUnit(), OFFSET_UNITS, "DAY", "子计划「" + name + "」循环周期单位");
            }
        }
        String basis = "PLAN_START";
        int offsetValue = 0;
        String offsetUnit = "YEAR";
        int remindValue = 0;
        String remindUnit = "DAY";
        int windowBeforeValue = 0;
        String windowBeforeUnit = "DAY";
        int windowAfterValue = 0;
        String windowAfterUnit = "DAY";
        String taskType = "FORM";
        String ownerType = "WARD_DEFAULT";
        String formCode = null;
        String passedWindowAction = trimToNull(input.passedWindowAction());
        String enrollMode = trimToNull(input.enrollMode());
        if (!isSubplan) {
            basis = defaultIfBlank(upper(input.basis()), "PLAN_START");
            if (!"PLAN_START".equals(basis) && !"LAST_VISIT".equals(basis)) {
                throw bad("节点「" + name + "」基准只支持计划起算日期（PLAN_START）或上次实际随访日期（LAST_VISIT）");
            }
            offsetValue = requireNonNegative(input.offsetValue(), "节点「" + name + "」偏移");
            offsetUnit = unit(input.offsetUnit(), OFFSET_UNITS, "YEAR", "节点「" + name + "」偏移单位");
            remindValue = requireNonNegative(input.remindValue(), "节点「" + name + "」提醒提前量");
            remindUnit = unit(input.remindUnit(), REMIND_UNITS, "DAY", "节点「" + name + "」提醒提前量单位");
            windowBeforeValue =
                    requireNonNegative(input.windowBeforeValue(), "节点「" + name + "」窗口前");
            windowBeforeUnit =
                    unit(input.windowBeforeUnit(), OFFSET_UNITS, "DAY", "节点「" + name + "」窗口前单位");
            windowAfterValue = requireNonNegative(input.windowAfterValue(), "节点「" + name + "」窗口后");
            windowAfterUnit =
                    unit(input.windowAfterUnit(), OFFSET_UNITS, "DAY", "节点「" + name + "」窗口后单位");
            taskType = defaultIfBlank(upper(input.taskType()), "FORM");
            if (!TASK_TYPES.contains(taskType)) {
                throw bad("节点「" + name + "」任务类型只支持电话随访 + 表单（FORM）或站内提醒（REMINDER）");
            }
            ownerType = defaultIfBlank(upper(input.ownerType()), "WARD_DEFAULT");
            if (!OWNER_TYPES.contains(ownerType)) {
                throw bad("节点「" + name + "」负责人类型只支持责任医生（DOCTOR）或病区默认负责人（WARD_DEFAULT）");
            }
            if ("FORM".equals(taskType)) {
                formCode = input.formCode() == null ? null : input.formCode().trim();
                if (formCode == null) {
                    throw bad("节点「" + name + "」是表单任务，须填写随访记录表单 code");
                }
                if (formCode.length() > FORM_CODE_MAX) {
                    throw bad("节点「" + name + "」表单 code 最多 " + FORM_CODE_MAX + " 个字符");
                }
            }
            if (passedWindowAction != null && !PASSED_WINDOW_ACTIONS.contains(passedWindowAction)) {
                throw bad("节点「" + name + "」入组前已过窗口策略只支持补建补录（BACKFILL）或跳过（SKIP）");
            }
            if (enrollMode != null && !ENROLL_MODES.contains(enrollMode)) {
                throw bad("节点「" + name + "」启用条件只支持常规（NORMAL）或 ERI 期（ERI）");
            }
        }
        return new NormalizedNode(
                type,
                isSubplan ? null : input.parentKey().trim(),
                name,
                repeatMode,
                repeatCount,
                cycleValue,
                cycleUnit,
                basis,
                offsetValue,
                offsetUnit,
                remindValue,
                remindUnit,
                windowBeforeValue,
                windowBeforeUnit,
                windowAfterValue,
                windowAfterUnit,
                taskType,
                ownerType,
                formCode,
                passedWindowAction,
                enrollMode,
                input.sortNo() == null ? 0 : Math.max(0, input.sortNo()));
    }

    private static String requireName(String name, String type) {
        if (name == null || name.isBlank()) {
            throw bad(type + "名称不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > NAME_MAX) {
            throw bad(type + "名称最多 " + NAME_MAX + " 个字符");
        }
        return trimmed;
    }

    private static int requireNonNegative(Integer value, String field) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw bad(field + "不能为负数");
        }
        return value;
    }

    private static int requirePositive(Integer value, String field) {
        if (value == null || value < 1) {
            throw bad(field + "至少为 1");
        }
        return value;
    }

    private static String unit(String value, Set<String> allowed, String fallback, String field) {
        String normalized = upper(value);
        if (normalized == null || normalized.isBlank()) {
            return fallback;
        }
        if (!allowed.contains(normalized)) {
            throw bad(field + "只支持 " + String.join(" / ", sorted(allowed)));
        }
        return normalized;
    }

    private static List<String> sorted(Set<String> values) {
        List<String> list = new ArrayList<>(new HashSet<>(values));
        list.sort(String::compareTo);
        return list;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase();
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }
}
