package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientSummaryMapper;
import org.springframework.stereotype.Service;

/**
 * 当前状态摘要用例（E13-T03 / tech-plan §7 pt_patient_summary「每名患者一行……每次提交后重算，不可直接修改」）。
 *
 * <p>hospital-a-spec §2：患者当前状态为派生层（生存状态、最近随访日期、最近 EF、当前用药、终点事件、下次窗口）， 「永远取最新一条已提交记录」且不可直接修改。
 * 本任务落地表与唯一重算入口 {@link #recompute}：
 *
 * <ul>
 *   <li>登记事务内首次调用即建行（E13-T04 列表完整度列的读取锚点，初始派生列全 NULL）；
 *   <li>「每次提交后重算」由 E15 提交链路再次调用：随访派生列由 E15 写入、下次窗口由 E14 入组调度写入、两个 完整度列分别随 E13-T02 / E15
 *       写入——各写入方在同一入口扩展自己的列；
 *   <li>表无任何直接修改接口（GET /patients/{id}/summary 随 E13-T05 档案概览落地）。
 */
@Service
public class PatientSummaryService {

    private final PatientSummaryMapper mapper;

    public PatientSummaryService(PatientSummaryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 重算（登记建行与后续提交共用触达）：行不存在则建行；已存在刷新重算时间与触发人。 派生列的取数重算随对应任务在本入口扩展（E14 窗口、E15 随访派生、E13-T02 基线完整度）。
     */
    public void recompute(long patientId, Long triggeredBy) {
        mapper.upsertRecompute(patientId, triggeredBy);
    }

    /**
     * 写入基线资料完整度（E13-T02 / A-PAT-019：已填基线必填 ÷ 应填，0–100）：分节保存 / 暂存后由基线用例按 模板进度口径算出并经本入口落列（行不存在则建行）。
     */
    public void updateBaselineCompleteness(long patientId, int completeness, Long triggeredBy) {
        mapper.updateBaselineCompleteness(patientId, completeness, triggeredBy);
    }
}
