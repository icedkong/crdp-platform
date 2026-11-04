package com.crdp.followup.plan.infra.mapper;

/** 计划节点新建行（useGeneratedKeys 回写 id，E14-T01；SUBPLAN / NODE 共形）。 */
public class PlanNodeInsertRow {

    private long id;
    private Long planId;
    private Long parentId;
    private String nodeType;
    private String name;
    private String repeatMode;
    private Integer repeatCount;
    private Integer cycleValue;
    private String cycleUnit;
    private String basis;
    private int offsetValue;
    private String offsetUnit;
    private int remindValue;
    private String remindUnit;
    private int windowBeforeValue;
    private String windowBeforeUnit;
    private int windowAfterValue;
    private String windowAfterUnit;
    private String taskType;
    private String ownerType;
    private String formCode;
    private String passedWindowAction;
    private String enrollMode;
    private int sortNo;

    public PlanNodeInsertRow(
            Long planId,
            Long parentId,
            String nodeType,
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
            int sortNo) {
        this.planId = planId;
        this.parentId = parentId;
        this.nodeType = nodeType;
        this.name = name;
        this.repeatMode = repeatMode;
        this.repeatCount = repeatCount;
        this.cycleValue = cycleValue;
        this.cycleUnit = cycleUnit;
        this.basis = basis;
        this.offsetValue = offsetValue;
        this.offsetUnit = offsetUnit;
        this.remindValue = remindValue;
        this.remindUnit = remindUnit;
        this.windowBeforeValue = windowBeforeValue;
        this.windowBeforeUnit = windowBeforeUnit;
        this.windowAfterValue = windowAfterValue;
        this.windowAfterUnit = windowAfterUnit;
        this.taskType = taskType;
        this.ownerType = ownerType;
        this.formCode = formCode;
        this.passedWindowAction = passedWindowAction;
        this.enrollMode = enrollMode;
        this.sortNo = sortNo;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Long getPlanId() {
        return planId;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public String getName() {
        return name;
    }

    public String getRepeatMode() {
        return repeatMode;
    }

    public Integer getRepeatCount() {
        return repeatCount;
    }

    public Integer getCycleValue() {
        return cycleValue;
    }

    public String getCycleUnit() {
        return cycleUnit;
    }

    public String getBasis() {
        return basis;
    }

    public int getOffsetValue() {
        return offsetValue;
    }

    public String getOffsetUnit() {
        return offsetUnit;
    }

    public int getRemindValue() {
        return remindValue;
    }

    public String getRemindUnit() {
        return remindUnit;
    }

    public int getWindowBeforeValue() {
        return windowBeforeValue;
    }

    public String getWindowBeforeUnit() {
        return windowBeforeUnit;
    }

    public int getWindowAfterValue() {
        return windowAfterValue;
    }

    public String getWindowAfterUnit() {
        return windowAfterUnit;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public String getFormCode() {
        return formCode;
    }

    public String getPassedWindowAction() {
        return passedWindowAction;
    }

    public String getEnrollMode() {
        return enrollMode;
    }

    public int getSortNo() {
        return sortNo;
    }
}
