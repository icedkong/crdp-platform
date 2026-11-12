package com.crdp.followup.record.app;

import com.crdp.followup.record.infra.mapper.FollowupRecordMapper;
import com.crdp.followup.record.infra.mapper.FollowupRecordMapper.PatientRecordMetaRow;
import com.crdp.followup.record.infra.mapper.RecordFeatureMapper;
import com.crdp.form.history.app.FieldHistoryService;
import com.crdp.form.history.domain.ChangeSource;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 随访序号自动编号与重排（E21-T09 / C-FU-018；followup V95 列 sequence_no + 开关 record_sequence_no）。
 *
 * <p><b>编号口径</b>（hospital-c-spec §5「随访序号：同一患者按随访日期升序编号，从 1 起；补录后自动重排」）： 同一患者<b>已提交</b>记录按有效随访日期
 * （{@code visit_date ?? 表单随访日期字段 ?? 任务计划日期}，与 E21-T11 派生预演同一口径）升序编 1..N， 同日按 id 小者为先；草稿不占号（读侧
 * sequenceNoOf 预演落点）、软删行不在册。<b>内部记录号不变</b>：重排只写派生列 fu_record.sequence_no， fu_record.id、患者随访编号
 * （pt_patient.visit_no）与记录业务数据都不动，也不动 version / updated_by（系统重算不是用户编辑， 与对比标红重算 E17-T04 同款）。
 *
 * <p><b>重排触发</b>（调用方事务内，RecordEntryService 接线）：①记录提交（含补录插入——验收「补录插入后序号自动重排」， 排在插入点之后的记录序号整体
 * +1）；②修改已提交记录（表单随访日期字段可能改早 / 改晚）；③删除记录（软删后排在后面的记录序号回退）。 触发入口已对患者做过数据范围校验（40400）。
 *
 * <p><b>重排记入变更历史（Q13）</b>：序号变化的记录逐行写 fe_field_history（record_type = FU_RECORD、 虚拟稳定键 {@link
 * #FIELD_KEY_SEQUENCE_NO}——随访序号是派生系统字段、不在表单模板字段集内，键不与 f0xx 稳定键冲突、变更记录页 照常展示；source =
 * SYSTEM_RECALC，操作人 = 触发用户）。首编（旧值 null → N，含存量行首次被触发覆盖）同样留痕； 无变化零写入（幂等）。审计由触发操作自身的 RECORD_SUBMIT /
 * RECORD_UPDATE / RECORD_DELETE 条目覆盖，重排不单独写审计（与 E17-T04 同口径）。
 *
 * <p><b>租户差异</b>（AGENTS §5.7）：功能开关 {@code record_sequence_no}（V95 只落关闭行；无行 = 关闭 = A / B 默认行为， 医院 C
 * 随租户开通配置写入）—— 关闭的租户跳过重排（列恒 NULL），读侧维持 E21-T11 派生预演不变。
 */
@Service
public class FollowupSequenceService {

    /** 功能开关：随访序号自动编号与重排（医院 C；无行 / 0 = A / B 默认关闭）。 */
    static final String FEATURE_RECORD_SEQUENCE_NO = "record_sequence_no";

    /** 序号变更历史的虚拟稳定键（fe_field_history.field_key）：随访序号是派生系统字段、 不在表单模板字段集（f0xx）内，键不与模板稳定键冲突。 */
    static final String FIELD_KEY_SEQUENCE_NO = "sequence_no";

    /** 修改已提交记录触发重排的留痕原因（表单随访日期字段可能改早 / 改晚）。 */
    static final String REASON_UPDATE_SUBMITTED = "修改已提交随访记录，随访序号自动重排（C-FU-018）";

    /** 删除记录（软删）触发重排的留痕原因（排在后面的记录序号回退）。 */
    static final String REASON_DELETE = "删除随访记录，随访序号自动重排（C-FU-018）";

    private final RecordEntryAssistService assist;
    private final FollowupRecordMapper recordMapper;
    private final RecordFeatureMapper features;
    private final FieldHistoryService fieldHistory;

    public FollowupSequenceService(
            RecordEntryAssistService assist,
            FollowupRecordMapper recordMapper,
            RecordFeatureMapper features,
            FieldHistoryService fieldHistory) {
        this.assist = assist;
        this.recordMapper = recordMapper;
        this.features = features;
        this.fieldHistory = fieldHistory;
    }

    // ---------------------------------------------------------------- 重排（写侧）

    /** 提交触发（含补录插入）的留痕原因：补录单列（验收「补录插入后序号自动重排」）。 */
    static String reasonForSubmit(String source) {
        return FollowupRecordService.SOURCE_BACKFILL.equals(source)
                ? "补录插入随访记录，随访序号自动重排（C-FU-018）"
                : "提交随访记录，随访序号自动重排（C-FU-018）";
    }

    /**
     * 重排一名患者的随访序号（调用方事务内）：已提交记录按有效随访日期升序编 1..N，与落库值比较， 变化的行更新 sequence_no 并写 fe_field_history
     * （首编与顺延同样留痕；无变化零写入）；不在定序列表（无法定有效日期）但残留序号的行清空留痕（防脏号占位）。 开关关闭直接跳过。返回变化的行数。
     */
    @Transactional
    public int resequence(long patientId, String reason) {
        if (!Boolean.TRUE.equals(features.findEnabled(FEATURE_RECORD_SEQUENCE_NO))) {
            return 0;
        }
        List<PatientRecordMetaRow> ordered = assist.submittedRowsOrderedByEffectiveDate(patientId);
        Set<Long> numbered = new HashSet<>();
        int changed = 0;
        int sequence = 0;
        for (PatientRecordMetaRow row : ordered) {
            sequence++;
            numbered.add(row.id());
            if (!Objects.equals(row.sequenceNo(), sequence)) {
                writeSequence(row, row.sequenceNo(), sequence, reason);
                changed++;
            }
        }
        for (PatientRecordMetaRow row : assist.submittedRows(patientId)) {
            if (!numbered.contains(row.id()) && row.sequenceNo() != null) {
                writeSequence(row, row.sequenceNo(), null, reason);
                changed++;
            }
        }
        return changed;
    }

    /** 单行落库 + 变更历史留痕（旧值 → 新值；新值 null = 清空）。 */
    private void writeSequence(
            PatientRecordMetaRow row, Integer oldValue, Integer newValue, String reason) {
        recordMapper.updateSequenceNo(row.id(), newValue);
        fieldHistory.logChange(
                FieldHistoryService.FU_RECORD,
                row.id(),
                row.formCode(),
                row.templateVersion(),
                FIELD_KEY_SEQUENCE_NO,
                oldValue,
                newValue,
                ChangeSource.SYSTEM_RECALC,
                reason,
                null);
    }

    // ---------------------------------------------------------------- 详情读侧（落库值优先）

    /**
     * 详情随访序号：开关开启的<b>已提交</b>记录优先读落库值（重排后的口径）； 未编号（存量行未触发过重排）回落 E21-T11 派生预演；草稿与开关关闭租户（A /
     * B）维持派生预演（录入落点）， 行为与 E21-T11 一致。无可定日期 null。
     */
    public Integer sequenceNoOfDetail(
            FollowupRecordService.RecordView record, LocalDate currentEffective) {
        if ("SUBMITTED".equals(record.status())
                && Boolean.TRUE.equals(features.findEnabled(FEATURE_RECORD_SEQUENCE_NO))) {
            Integer persisted = recordMapper.findRecordSequenceNo(record.id());
            if (persisted != null) {
                return persisted;
            }
        }
        return assist.sequenceNoOf(record.patientId(), record.id(), currentEffective);
    }
}
