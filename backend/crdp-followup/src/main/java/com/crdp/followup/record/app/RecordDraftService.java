package com.crdp.followup.record.app;

import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.followup.task.infra.mapper.FollowupTaskMapper;
import com.crdp.form.record.app.FormService;
import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * E25-T01 / NFR-SAVE-001、NFR-SAVE-003：随访记录服务器自动保存草稿（tech-plan §6「服务器自动保存与版本冲突」；HTTP 端点 followup.md
 * §3 {@code PUT /api/hospital/followup/records/{id}/draft}）。
 *
 * <p>草稿语义（「草稿不触发提交逻辑」）：只经 {@link FormService#saveDraft} 过写入闸门（键 / 类型 / 派生重算 / 硬范围）合并变更字段；
 * 不执行提交校验（validateForSubmit 由 {@link RecordEntryService#submit}
 * 承担，E15-T02）、不写对比标红与统计、不生成下次任务、不写字段历史（提交 / 保存修改时才写，E11-T06）。 乐观锁由引擎承担：baseVersion 过期 40900
 * 并附冲突详情（patient.md §4.1：当前版本、更新人显示名、更新时间、两边不同字段），本层原样透传—— 前端（E25-T02
 * ConflictBanner）暂停自动保存，等用户选「保留我的并覆盖」或「载入服务器版本」。
 *
 * <p>审计（tech-plan「范围与审计」）：自动保存<b>不逐次写审计</b>；「保留我的并覆盖」= 以 currentVersion 作 baseVersion 重发并带 {@code
 * overwrite: true}，本层写 RECORD_DRAFT_OVERWRITE 审计（只记字段键，不落值——敏感值只存摘要，AGENTS.md §5.5）。
 *
 * <p>任务联动（E15-T02 接入，OQ-77② 的接入点即本方法保存成功之后）：记录关联的任务（fu_task.record_id，followup
 * V54）仍在「待随访」时置「进行中」——A 规格任务状态表「记录第一次联系或暂存」；{@link FollowupTaskStatusService#markInProgress} 只从
 * PENDING 进、幂等（逾期不退回，OQ-82①）。无关联任务 的记录（未来的补录 / 计划外）不联动。
 *
 * <p>载体：fe_record（followup V29 预留 version 乐观锁给 E25 复用）；fu_record（患者 / 任务关联与数据范围）随 E15-T07 落地后由业务层复用
 * 同一 FormService 口径，不与 fe_record 双写（OQ-63①、OQ-83①）。
 */
@Service
public class RecordDraftService {

    private final FormService formService;
    private final FollowupTaskMapper taskMapper;
    private final FollowupTaskStatusService statusService;
    private final AuditLogger audit;

    public RecordDraftService(
            FormService formService,
            FollowupTaskMapper taskMapper,
            FollowupTaskStatusService statusService,
            AuditLogger audit) {
        this.formService = formService;
        this.taskMapper = taskMapper;
        this.statusService = statusService;
        this.audit = audit;
    }

    /** 保存请求：changes 只带变更字段（null 值 = 清空该键）；overwrite 标志「保留我的并覆盖」（冲突流程，patient.md §4.1）。 */
    public record DraftCommand(
            Integer baseVersion, Map<String, Object> changes, Boolean overwrite) {}

    /** 保存结果：新版本号（下次保存的 baseVersion）与保存时间（表单顶部「已自动保存 HH:mm」）。 */
    public record DraftSavedView(int version, String savedAt) {}

    /** 保存草稿；{@code overwrite=true} 时写覆盖他人版本审计（正常自动保存不写审计）。 */
    public DraftSavedView saveDraft(long recordId, DraftCommand command) {
        if (command == null || command.baseVersion() == null) {
            throw new ApiException(400, 40001, "自动保存必须携带 baseVersion（乐观锁）");
        }
        if (command.changes() == null || command.changes().isEmpty()) {
            throw new ApiException(400, 40001, "自动保存必须携带变更字段（只在有新改动时发送）");
        }
        FormService.RecordView saved =
                formService.saveDraft(recordId, command.baseVersion(), command.changes());
        markLinkedTaskInProgress(recordId);
        if (Boolean.TRUE.equals(command.overwrite())) {
            audit.append(
                    AuditEntry.operation("followup", "RECORD_DRAFT_OVERWRITE")
                            .objectType("FORM_RECORD")
                            .objectId(String.valueOf(recordId))
                            .description(
                                    "自动保存覆盖他人版本：记录 "
                                            + recordId
                                            + " 自 baseVersion "
                                            + command.baseVersion()
                                            + " 保存为 v"
                                            + saved.version()
                                            + "（保留我的并覆盖，字段 "
                                            + String.join("、", sortedKeys(command))
                                            + "）")
                            .afterJson(
                                    "{\"recordId\":"
                                            + recordId
                                            + ",\"baseVersion\":"
                                            + command.baseVersion()
                                            + ",\"version\":"
                                            + saved.version()
                                            + ",\"changedKeys\":"
                                            + keysJson(command)
                                            + "}")
                            .result(com.crdp.framework.audit.AuditResult.SUCCESS)
                            .build());
        }
        return new DraftSavedView(saved.version(), saved.updatedAt());
    }

    /** 「任务变进行中」联动（E15-T02 / OQ-77②）：记录关联任务仍在 PENDING 时进入进行中（幂等——逾期 / 进行中 / 终态不动）； 无关联任务不联动。 */
    private void markLinkedTaskInProgress(long recordId) {
        FollowupTaskMapper.RecordLinkedTaskRow task = taskMapper.findTaskByRecordId(recordId);
        if (task == null) {
            return;
        }
        Long operator = CurrentSession.current().map(item -> item.userId()).orElse(null);
        statusService.markInProgress(task.id(), operator, "暂存草稿");
    }

    private static java.util.List<String> sortedKeys(DraftCommand command) {
        return new ArrayList<>(command.changes().keySet());
    }

    /** 变更字段键（JSON 数组，不含字段值——审计不落敏感值）。 */
    private static String keysJson(DraftCommand command) {
        StringBuilder json = new StringBuilder("[");
        for (String key : sortedKeys(command)) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(key).append('"');
        }
        return json.append(']').toString();
    }
}
