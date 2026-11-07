package com.crdp.followup.importer.app;

import com.crdp.followup.importer.app.ImportFieldRuleLoader.ImportFieldRules;
import com.crdp.followup.importer.app.ImportRowPayloads.BaselineRow;
import com.crdp.followup.importer.app.ImportRowPayloads.CellValue;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupGroup;
import com.crdp.followup.importer.app.ImportRowPayloads.FollowupRow;
import com.crdp.followup.importer.app.ImportRowPayloads.WideRow;
import com.crdp.followup.importer.domain.ImportCellValidator;
import com.crdp.followup.importer.domain.ImportCellValidator.Checked;
import com.crdp.followup.importer.domain.ImportFieldRule;
import com.crdp.followup.importer.domain.ImportIssue;
import com.crdp.followup.importer.domain.ImportIssue.Level;
import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.ImportWardDirectory.Resolution;
import com.crdp.followup.importer.domain.WideFrontColumns;
import com.crdp.followup.importer.domain.WideLayoutAdapter;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.IssueInsert;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.JobRow;
import com.crdp.followup.importer.infra.mapper.ImportStagingMapper.StagedRow;
import com.crdp.followup.patient.app.PatientBlindIndexService;
import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.app.FormDerivedEngine;
import com.crdp.form.rule.app.FormDerivedEngine.DerivedOutcome;
import com.crdp.form.rule.app.FormDerivedEngine.DerivedStatus;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * E18-T03 / tech-plan §12④⑤：导入预检的<strong>校验与批注版校验报告</strong>（tasks.csv 验收「错误单元格带批注返回」）。
 *
 * <p>校验（{@link #validate}，预检流程内调用或单独重跑，幂等——重跑先清旧问题）： 按字段字典做类型、范围、选项、必填与跨字段校验（{@link
 * ImportCellValidator}，Q36 / Q37 口径）， 宽表前置列与基线列比对（以基线列为准，不一致警告），派生字段与重算结果比对（{@link
 * FormDerivedEngine}，以重算结果为准，不一致警告，A 规格 §4「派生字段有原值」行）； 「病区/中心」字段按病区字典与别名映射解析（E18-T04 /
 * A-WARD-006：未映射、已停用、一值多区报错——未映射值导入前必须处理）； 按盲索引做跨行检查——文件内病案号重复、病案号已存在于系统（重复，确认时选更新 / 跳过，A-PAT-018）、
 * 长表「同一病案号 + 随访年度出现两行」「随访行病案号不在基线表」（报错，A 规格 §4 验收原文）。 问题落 imp_issue（followup
 * V71），供导入向导（E18-T06）分页展示与确认门控（E18-T05：有 ERROR 时 confirm 40001）。
 *
 * <p>批注版报告（{@link #annotatedReport}）：从暂存行重建源工作表（表头 + 单元格原文与坐标都在 imp_row 密文里，E18-T02 的暂存形态），
 * 在问题单元格上标色加批注，外加一张「校验报告」问题清单表（级别、工作表、行、列字母、表头、原值（敏感列脱敏）、原因）——
 * A-PAT-018「校验报告逐个列出问题单元格（行、列、原值、原因），可下载带标注的 Excel」。报告字节由上传 / 下载端点（E18-T06）按租户加密存储并写审计。
 *
 * <p>预检不写业务表（只写 imp_job / imp_row / imp_issue）；跨子域查重经 {@link PatientBlindIndexService}（app
 * 接口，全院存在性口径——病区范围在导入确认时校验）。
 */
@Service
public class ImportValidationService {

    /** 读暂存行与写问题的批次（与暂存写入口径一致，内存只有一批）。 */
    static final int BATCH = 500;

    private static final Set<String> VALIDATABLE = Set.of("PRECHECKING", "PRECHECKED");

    private final ImportStagingMapper staging;
    private final ImportFieldRuleLoader ruleLoader;
    private final CryptoService crypto;
    private final PatientBlindIndexService patientBlindIndex;
    private final FormDerivedEngine derivedEngine;
    private final ObjectMapper json;
    private final ImportWardDirectoryLoader wardDirectoryLoader;

    public ImportValidationService(
            ImportStagingMapper staging,
            ImportFieldRuleLoader ruleLoader,
            CryptoService crypto,
            PatientBlindIndexService patientBlindIndex,
            FormDerivedEngine derivedEngine,
            ObjectMapper json,
            ImportWardDirectoryLoader wardDirectoryLoader) {
        this.staging = staging;
        this.ruleLoader = ruleLoader;
        this.crypto = crypto;
        this.patientBlindIndex = patientBlindIndex;
        this.derivedEngine = derivedEngine;
        this.json = json;
        this.wardDirectoryLoader = wardDirectoryLoader;
    }

    /** 校验结果摘要（向导页统计「错误 / 警告」；患者行 = 宽表行 + 长表基线行）。 */
    public record ValidationSummary(
            long jobId, long errorCount, long warnCount, long patientRows, long followupRows) {}

    /** 全量校验结果：问题清单 + 行数统计。 */
    record ValidationResult(List<ImportIssue> issues, long patientRows, long followupRows) {}

    /** 一次暂存行的解析形态（rowType → 载荷 + 溯源）。 */
    record StagedPayload(StagedRow row, Object payload) {}

    // ---------------------------------------------------------------- 入口

    /**
     * 校验一个作业的暂存行并落 imp_issue（幂等：先清旧问题）。 只能在预检流程内（PRECHECKING，暂存行已全部写完）或 PRECHECKED 后重跑；异常向上抛（预检流程内转
     * PRECHECK_FAILED，独立调用由调用方处置）。
     */
    public ValidationSummary validate(long jobId) {
        JobRow job = requireJob(jobId);
        if (!VALIDATABLE.contains(job.status())) {
            throw new ApiException(409, 40901, "导入任务不在预检中 / 预检完成状态（当前 " + job.status() + "），不能校验");
        }
        ValidationResult result = validatePayloads(job, ruleLoader.load(), readPayloads(jobId));
        staging.deleteIssues(jobId);
        for (int start = 0; start < result.issues().size(); start += BATCH) {
            staging.insertIssues(
                    result
                            .issues()
                            .subList(start, Math.min(start + BATCH, result.issues().size()))
                            .stream()
                            .map(issue -> IssueInsert.of(jobId, issue))
                            .toList());
        }
        long errors =
                result.issues().stream().filter(issue -> issue.level() == Level.ERROR).count();
        return new ValidationSummary(
                jobId,
                errors,
                result.issues().size() - errors,
                result.patientRows(),
                result.followupRows());
    }

    /** 生成批注版校验报告 xlsx 字节（预检完成后可用；问题即时重算，报告与库内问题同口径）。 */
    public byte[] annotatedReport(long jobId) {
        JobRow job = requireJob(jobId);
        if (!VALIDATABLE.contains(job.status())) {
            throw new ApiException(409, 40901, "导入任务预检未完成，不能生成校验报告");
        }
        ImportFieldRules rules = ruleLoader.load();
        List<StagedPayload> payloads = readPayloads(jobId);
        ValidationResult result = validatePayloads(job, rules, payloads);
        return ImportReportComposer.compose(job, rules, payloads, result.issues());
    }

    // ---------------------------------------------------------------- 校验主体

    /** 全量校验（读暂存行 → 解密 → 逐行 / 跨行校验；病区字典快照一次，E18-T04）。 */
    private ValidationResult validatePayloads(
            JobRow job, ImportFieldRules rules, List<StagedPayload> payloads) {
        List<ImportIssue> issues = new ArrayList<>();
        Validator validator =
                new Validator(
                        rules, issues, sheetColumnIndex(payloads), wardDirectoryLoader.load());
        long patientRows = 0;
        long followupRows = 0;
        for (StagedPayload staged : payloads) {
            switch (staged.row().rowType()) {
                case "PATIENT" -> {
                    validator.wideRow(staged.row(), (WideRow) staged.payload());
                    patientRows++;
                }
                case "BASELINE" -> {
                    validator.baselineRow(staged.row(), (BaselineRow) staged.payload());
                    patientRows++;
                }
                case "FOLLOWUP" -> {
                    validator.followupRow(staged.row(), (FollowupRow) staged.payload());
                    followupRows++;
                }
                default -> throw new IllegalStateException("未知暂存行类型：" + staged.row().rowType());
            }
        }
        validator.crossRow(payloads);
        issues.sort(ImportValidationService::compareIssues);
        return new ValidationResult(issues, patientRows, followupRows);
    }

    /** 问题排序：工作表 → 行 → 列（报告与问题清单同一顺序）。 */
    static int compareIssues(ImportIssue a, ImportIssue b) {
        int bySheet = a.sheetName().compareTo(b.sheetName());
        if (bySheet != 0) {
            return bySheet;
        }
        int byRow = Integer.compare(a.rowNo(), b.rowNo());
        if (byRow != 0) {
            return byRow;
        }
        return Integer.compare(
                a.colNo() == null ? -1 : a.colNo(), b.colNo() == null ? -1 : b.colNo());
    }

    /**
     * 每张工作表的 fieldKey → 列号索引（长表列序随文件，从各行坐标合并而来——同一列在全表唯一）。 宽表列序确定（前置 9 +
     * 锚点序），不用合并也能算；统一走「行坐标合并」口径最简单，宽表坐标本就同源。
     */
    private static Map<String, Map<String, Integer>> sheetColumnIndex(
            List<StagedPayload> payloads) {
        Map<String, Map<String, Integer>> index = new LinkedHashMap<>();
        for (StagedPayload staged : payloads) {
            Map<String, CellValue> values = valuesOf(staged.payload());
            if (values == null) {
                continue;
            }
            Map<String, Integer> sheet =
                    index.computeIfAbsent(staged.row().sheetName(), key -> new HashMap<>());
            values.forEach((fieldKey, cell) -> sheet.putIfAbsent(fieldKey, cell.c()));
        }
        return index;
    }

    private static Map<String, CellValue> valuesOf(Object payload) {
        if (payload instanceof WideRow wide) {
            return wide.baseline();
        }
        if (payload instanceof BaselineRow baseline) {
            return baseline.baseline();
        }
        if (payload instanceof FollowupRow followup) {
            return followup.values();
        }
        return null;
    }

    private static CellValue mrnCellOf(Object payload) {
        if (payload instanceof WideRow wide) {
            return wide.mrnCell();
        }
        if (payload instanceof BaselineRow baseline) {
            return baseline.mrnCell();
        }
        if (payload instanceof FollowupRow followup) {
            return followup.mrnCell();
        }
        return null;
    }

    private List<StagedPayload> readPayloads(long jobId) {
        List<StagedPayload> payloads = new ArrayList<>();
        long afterId = 0;
        while (true) {
            List<StagedRow> batch = staging.findRows(jobId, afterId, BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (StagedRow staged : batch) {
                payloads.add(new StagedPayload(staged, decryptPayload(staged)));
                afterId = staged.id();
            }
            if (batch.size() < BATCH) {
                break;
            }
        }
        return payloads;
    }

    private Object decryptPayload(StagedRow row) {
        String plaintext = crypto.decrypt(row.payloadCipher());
        Class<?> type =
                switch (row.rowType()) {
                    case "PATIENT" -> WideRow.class;
                    case "BASELINE" -> BaselineRow.class;
                    case "FOLLOWUP" -> FollowupRow.class;
                    default -> throw new IllegalStateException("未知暂存行类型：" + row.rowType());
                };
        try {
            return json.readValue(plaintext, type);
        } catch (Exception failure) {
            throw new IllegalStateException("暂存行载荷解析失败（imp_row.id=" + row.id() + "）", failure);
        }
    }

    // ---------------------------------------------------------------- 行级校验

    /** 一次校验的可变上下文（问题累加 + 跨行索引 + 病区字典快照）。 */
    private final class Validator {

        private final ImportFieldRules rules;
        private final List<ImportIssue> issues;
        private final Map<String, Map<String, Integer>> sheetColumns;
        private final ImportWardDirectory wardDirectory;

        Validator(
                ImportFieldRules rules,
                List<ImportIssue> issues,
                Map<String, Map<String, Integer>> sheetColumns,
                ImportWardDirectory wardDirectory) {
            this.rules = rules;
            this.issues = issues;
            this.sheetColumns = sheetColumns;
            this.wardDirectory = wardDirectory;
        }

        void wideRow(StagedRow staged, WideRow payload) {
            Map<String, Object> typed = new LinkedHashMap<>();
            checkBaseline(staged, payload.baseline(), typed, true);
            checkFrontColumns(staged.sheetName(), payload);
            for (FollowupGroup group : payload.followups()) {
                checkFollowupValues(staged, group.values(), group.group());
            }
            checkDerived(staged.sheetName(), payload.baseline(), rules.baselineByKey(), typed);
            checkMrn(staged, payload.mrn(), payload.mrnCell());
        }

        void baselineRow(StagedRow staged, BaselineRow payload) {
            Map<String, Object> typed = new LinkedHashMap<>();
            checkBaseline(staged, payload.baseline(), typed, false);
            checkDerived(staged.sheetName(), payload.baseline(), rules.baselineByKey(), typed);
            checkMrn(staged, payload.mrn(), payload.mrnCell());
        }

        void followupRow(StagedRow staged, FollowupRow payload) {
            checkFollowupValues(staged, payload.values(), null);
            checkMrn(staged, payload.mrn(), payload.mrnCell());
            CellValue yearCell = payload.yearCell();
            if (payload.year() == null) {
                String raw = yearCell == null ? null : yearCell.normalized();
                add(
                        staged.sheetName(),
                        staged.rowNo(),
                        yearCell,
                        "随访年度",
                        ImportIssue.CODE_YEAR_INVALID,
                        Level.ERROR,
                        raw == null ? "随访年度为空，无法确定是第几年" : "随访年度「" + raw + "」不是有效年度（应为整数年度，如 3）");
            }
        }

        /** 基线字段校验 + 必填空检查（历史导入口径只警告）。 */
        private void checkBaseline(
                StagedRow staged,
                Map<String, CellValue> values,
                Map<String, Object> typedSink,
                boolean wide) {
            checkValues(
                    staged.sheetName(), staged.rowNo(), values, rules.baselineByKey(), typedSink);
            rules.baselineByKey()
                    .forEach(
                            (fieldKey, rule) -> {
                                if (rule.required() && !values.containsKey(fieldKey)) {
                                    Integer colNo = columnOf(staged.sheetName(), rule, wide, 0);
                                    requiredEmpty(staged.sheetName(), staged.rowNo(), colNo, rule);
                                }
                            });
        }

        /** 随访字段校验 + 必填空检查（宽表 group = 第几组即第几年；长表行 group 为 null）。 */
        private void checkFollowupValues(
                StagedRow staged, Map<String, CellValue> values, Integer group) {
            checkValues(staged.sheetName(), staged.rowNo(), values, rules.followupByKey(), null);
            rules.followupByKey()
                    .forEach(
                            (fieldKey, rule) -> {
                                if (rule.required() && !values.containsKey(fieldKey)) {
                                    Integer colNo =
                                            columnOf(
                                                    staged.sheetName(),
                                                    rule,
                                                    group != null,
                                                    group == null ? 0 : group);
                                    requiredEmpty(staged.sheetName(), staged.rowNo(), colNo, rule);
                                }
                            });
        }

        private void requiredEmpty(
                String sheetName, int rowNo, Integer colNo, ImportFieldRule rule) {
            ImportCellValidator.check(rule, null)
                    .findings()
                    .forEach(
                            finding ->
                                    add(
                                            sheetName,
                                            rowNo,
                                            colNo,
                                            rule.headerName(),
                                            finding.code(),
                                            finding.level(),
                                            finding.message()));
        }

        /**
         * 字段级校验（值 → 规则，坐标用行内单元格坐标），顺带累计类型化值（派生重算用）。 「病区/中心」字段（wardFieldKey）不走固定选项集，改走病区字典值映射
         * （E18-T04 / A-WARD-006）。
         */
        private void checkValues(
                String sheetName,
                int rowNo,
                Map<String, CellValue> values,
                Map<String, ImportFieldRule> ruleIndex,
                Map<String, Object> typedSink) {
            values.forEach(
                    (fieldKey, cell) -> {
                        ImportFieldRule rule = ruleIndex.get(fieldKey);
                        if (rule == null) {
                            return; // 目录里已没有的字段（字典改版）：无法校验，按原样导入
                        }
                        if (fieldKey.equals(rules.wardFieldKey())) {
                            checkWardValue(sheetName, rowNo, cell, rule, typedSink);
                            return;
                        }
                        Checked checked = ImportCellValidator.check(rule, cell.toCell());
                        checked.findings()
                                .forEach(
                                        finding ->
                                                add(
                                                        sheetName,
                                                        rowNo,
                                                        cell.c(),
                                                        rule.headerName(),
                                                        finding.code(),
                                                        finding.level(),
                                                        finding.message()));
                        if (typedSink != null && checked.value() != null) {
                            typedSink.put(fieldKey, checked.value());
                        }
                    });
        }

        /** 必填空单元格的列号：优先该表实测列位（sheetColumns），宽表再按确定列序兜底。 */
        private Integer columnOf(String sheetName, ImportFieldRule rule, boolean wide, int group) {
            Map<String, Integer> sheet = sheetColumns.get(sheetName);
            if (sheet != null && sheet.containsKey(rule.fieldKey())) {
                return sheet.get(rule.fieldKey());
            }
            if (!wide) {
                return null; // 长表全表都没出现过的列：无法定位，问题钉在该行行首
            }
            return rules.baselineByKey().containsKey(rule.fieldKey())
                    ? wideBaselineColumn(rules, rules.baselineRules().indexOf(rule))
                    : wideFollowupColumn(rules, group, rules.followupRules().indexOf(rule));
        }

        /** 宽表基线列位：前置 9 + 该字段前的锚点字段数（宽表列 = 带锚点字段，OQ-72①）。 */
        private static int wideBaselineColumn(ImportFieldRules rules, int fieldIndex) {
            int anchored = 0;
            for (int i = 0; i < fieldIndex && i < rules.baselineRules().size(); i++) {
                if (rules.baselineRules().get(i).originalHeader() != null) {
                    anchored++;
                }
            }
            return WideLayoutAdapter.FRONT_COLUMNS.size() + anchored;
        }

        /** 宽表第 group 组随访列位：前置 9 + 基线锚点数 + (group−1) × 随访锚点数 + 组内锚点序。 */
        private static int wideFollowupColumn(ImportFieldRules rules, int group, int fieldIndex) {
            int anchoredBaseline =
                    (int)
                            rules.baselineRules().stream()
                                    .filter(rule -> rule.originalHeader() != null)
                                    .count();
            int anchoredInGroup = 0;
            for (int i = 0; i < fieldIndex && i < rules.followupRules().size(); i++) {
                if (rules.followupRules().get(i).originalHeader() != null) {
                    anchoredInGroup++;
                }
            }
            int anchoredFollowup =
                    (int)
                            rules.followupRules().stream()
                                    .filter(rule -> rule.originalHeader() != null)
                                    .count();
            return WideLayoutAdapter.FRONT_COLUMNS.size()
                    + anchoredBaseline
                    + (group - 1) * anchoredFollowup
                    + anchoredInGroup;
        }

        /** 宽表前置列与基线列比对（以基线列为准，不一致警告；序号 / 数据完整度不比，E18-T01 口径）。 */
        private void checkFrontColumns(String sheetName, WideRow payload) {
            payload.front()
                    .forEach(
                            (name, front) -> {
                                String baselineLabel =
                                        WideFrontColumns.FRONT_TO_BASELINE_LABEL.get(name);
                                ImportFieldRule rule =
                                        baselineLabel == null
                                                ? null
                                                : rules.baselineByLabel(baselineLabel);
                                CellValue baseline =
                                        rule == null
                                                ? null
                                                : payload.baseline().get(rule.fieldKey());
                                if (rule == null || baseline == null) {
                                    return; // 基线没有对应字段（如长表口径）或该行基线列为空：没有可比项
                                }
                                if (!WideFrontColumns.consistent(
                                        front.toCell(), baseline.toCell())) {
                                    add(
                                            sheetName,
                                            front.r(),
                                            front.c(),
                                            name,
                                            ImportIssue.CODE_FRONT_MISMATCH,
                                            Level.WARN,
                                            "前置列与基线列不一致（以基线列为准）"
                                                    + (rule.sensitive()
                                                            ? ""
                                                            : "：前置列「"
                                                                    + front.normalized()
                                                                    + "」/ 基线「"
                                                                    + baseline.normalized()
                                                                    + "」"));
                                }
                            });
        }

        /** 派生字段有原值时与重算结果比对（以重算结果为准，不一致警告；来源缺失允许手工值，不提示）。 */
        private void checkDerived(
                String sheetName,
                Map<String, CellValue> values,
                Map<String, ImportFieldRule> ruleIndex,
                Map<String, Object> typed) {
            boolean hasDerived =
                    ruleIndex.values().stream().anyMatch(rule -> rule.derivedDefJson() != null);
            if (!hasDerived) {
                return;
            }
            Map<String, DerivedOutcome> outcomes =
                    derivedEngine.recalculate(versionFields(ruleIndex), typed);
            values.forEach(
                    (fieldKey, cell) -> {
                        ImportFieldRule rule = ruleIndex.get(fieldKey);
                        if (rule == null
                                || rule.derivedDefJson() == null
                                || cell.normalized() == null) {
                            return;
                        }
                        DerivedOutcome outcome = outcomes.get(fieldKey);
                        if (outcome == null || outcome.status() != DerivedStatus.OK) {
                            return; // 来源缺失 / 无效 / 防御跳过：允许手工值（A-FLD-005），不提示
                        }
                        Object original = ImportCellValidator.check(rule, cell.toCell()).value();
                        boolean same =
                                original instanceof Number number
                                        && outcome.value() instanceof Number computed
                                        && number.doubleValue() == computed.doubleValue();
                        if (!same) {
                            add(
                                    sheetName,
                                    cell.r(),
                                    cell.c(),
                                    rule.headerName(),
                                    ImportIssue.CODE_DERIVED_MISMATCH,
                                    Level.WARN,
                                    "与派生重算结果不一致，导入以重算结果 "
                                            + outcome.value()
                                            + " 为准（原值"
                                            + (rule.sensitive()
                                                    ? "（略）"
                                                    : "「" + cell.normalized() + "」")
                                            + "）");
                        }
                    });
        }

        /**
         * 病区/中心取值按病区字典解析（编码 / 名称 / 别名，E18-T04 / A-WARD-006）。 未映射、对应病区已停用、一值多区都报
         * <strong>ERROR</strong>——未映射值导入前必须处理（tasks.csv 验收；有 ERROR 确认提交被拦， E18-T05）；
         * 空值不在这里报（必填为空按历史导入口径仅警告，由通用路径产出 REQUIRED_EMPTY）； 匹配成功把病区 id 记为类型化值（E18-T05 提交写
         * pt_patient.ward_id 用同一映射）。
         */
        private void checkWardValue(
                String sheetName,
                int rowNo,
                CellValue cell,
                ImportFieldRule rule,
                Map<String, Object> typedSink) {
            String raw = cell.toCell().normalized();
            if (raw == null) {
                ImportCellValidator.check(rule, cell.toCell())
                        .findings()
                        .forEach(
                                finding ->
                                        add(
                                                sheetName,
                                                rowNo,
                                                cell,
                                                rule.headerName(),
                                                finding.code(),
                                                finding.level(),
                                                finding.message()));
                return;
            }
            Resolution resolution = wardDirectory.resolve(raw);
            switch (resolution.status()) {
                case MATCHED -> {
                    if (typedSink != null) {
                        typedSink.put(rule.fieldKey(), resolution.ward().id());
                    }
                }
                case UNMAPPED ->
                        add(
                                sheetName,
                                rowNo,
                                cell,
                                rule.headerName(),
                                ImportIssue.CODE_WARD_UNMAPPED,
                                Level.ERROR,
                                "取值「"
                                        + raw
                                        + "」不在病区/中心字典内（可为："
                                        + wardSummary()
                                        + "）；请修正原表，或在病区管理为对应病区维护该别名映射后再导入");
                case DISABLED ->
                        add(
                                sheetName,
                                rowNo,
                                cell,
                                rule.headerName(),
                                ImportIssue.CODE_WARD_DISABLED,
                                Level.ERROR,
                                "取值「"
                                        + raw
                                        + "」对应的病区「"
                                        + resolution.ward().name()
                                        + "」已停用，不能导入；请先在病区管理恢复该病区，或修正原表");
                case AMBIGUOUS -> {
                    StringBuilder names = new StringBuilder();
                    resolution
                            .candidates()
                            .forEach(
                                    ward -> {
                                        if (names.length() > 0) {
                                            names.append("、");
                                        }
                                        names.append(ward.name());
                                    });
                    add(
                            sheetName,
                            rowNo,
                            cell,
                            rule.headerName(),
                            ImportIssue.CODE_WARD_AMBIGUOUS,
                            Level.ERROR,
                            "取值「"
                                    + raw
                                    + "」同时匹配多个病区（"
                                    + names
                                    + "），无法确定唯一归属；请在病区管理修正编码 / 名称 / 别名后重新预检");
                }
            }
        }

        /** 未映射提示的候选病区清单（启用病区，截断与选项提示同口径）。 */
        private String wardSummary() {
            String summary = wardDirectory.enabledSummary(ImportWardDirectory.SUMMARY_LIMIT);
            return summary.isEmpty() ? "（病区字典为空，请先在病区管理建病区）" : summary;
        }

        /** 病案号身份检查（空 = 错误，钉在病案号单元格上）。 */
        private void checkMrn(StagedRow staged, String mrn, CellValue mrnCell) {
            if (mrn == null) {
                add(
                        staged.sheetName(),
                        staged.rowNo(),
                        mrnCell,
                        mrnHeader(),
                        ImportIssue.CODE_MRN_BLANK,
                        Level.ERROR,
                        "病案号为空，无法关联与查重");
            }
        }

        // ---------------------------------------------------------------- 跨行

        /** 盲索引跨行检查：文件内重复 / 系统已存在（警告，确认时按重复策略处理）、 随访行无基线 / 同一病案号 + 年度两行（错误，A 规格 §4 验收原文）。 */
        void crossRow(List<StagedPayload> payloads) {
            Map<String, StagedPayload> firstByBlind = new LinkedHashMap<>();
            Map<String, Integer> firstRowByBlindYear = new LinkedHashMap<>();
            Set<String> baselineBlinds = new HashSet<>();
            Map<Long, StagedPayload> payloadById = new HashMap<>();
            for (StagedPayload staged : payloads) {
                payloadById.put(staged.row().id(), staged);
                if ("BASELINE".equals(staged.row().rowType()) && staged.row().mrnBlind() != null) {
                    baselineBlinds.add(staged.row().mrnBlind());
                }
            }
            for (StagedPayload staged : payloads) {
                StagedRow row = staged.row();
                if (!"PATIENT".equals(row.rowType()) && !"BASELINE".equals(row.rowType())) {
                    continue;
                }
                String blind = row.mrnBlind();
                if (blind == null) {
                    continue; // 病案号为空已在行级报错
                }
                StagedPayload first = firstByBlind.putIfAbsent(blind, staged);
                if (first != null) {
                    add(
                            row.sheetName(),
                            row.rowNo(),
                            mrnCellOf(staged.payload()),
                            mrnHeader(),
                            ImportIssue.CODE_DUP_FILE,
                            Level.WARN,
                            "病案号与第 " + first.row().rowNo() + " 行重复（文件内），确认提交时按重复策略处理");
                }
            }
            // 系统内已存在的病案号（重复 → 更新 / 跳过，A-PAT-018）：提示在文件内首次出现行
            Set<String> existing =
                    patientBlindIndex.existingBlinds(
                            firstByBlind.values().stream()
                                    .map(staged -> staged.row().mrnBlind())
                                    .toList());
            firstByBlind.forEach(
                    (blind, staged) -> {
                        if (existing.contains(blind)) {
                            add(
                                    staged.row().sheetName(),
                                    staged.row().rowNo(),
                                    mrnCellOf(staged.payload()),
                                    mrnHeader(),
                                    ImportIssue.CODE_DUP_EXISTING,
                                    Level.WARN,
                                    "病案号已存在（重复患者），确认提交时选择「更新」或「跳过」");
                        }
                    });
            for (StagedPayload staged : payloads) {
                StagedRow row = staged.row();
                if (!"FOLLOWUP".equals(row.rowType())) {
                    continue;
                }
                FollowupRow payload = (FollowupRow) staged.payload();
                if (row.mrnBlind() != null && !baselineBlinds.contains(row.mrnBlind())) {
                    add(
                            row.sheetName(),
                            row.rowNo(),
                            payload.mrnCell(),
                            mrnHeader(),
                            ImportIssue.CODE_NO_BASELINE,
                            Level.ERROR,
                            "病案号在「基线」表中不存在，随访行没有归属患者");
                }
                if (row.mrnBlind() != null && payload.year() != null) {
                    String key = row.mrnBlind() + "#" + payload.year();
                    Integer firstRow = firstRowByBlindYear.putIfAbsent(key, row.rowNo());
                    if (firstRow != null) {
                        add(
                                row.sheetName(),
                                row.rowNo(),
                                payload.yearCell(),
                                "随访年度",
                                ImportIssue.CODE_DUP_YEAR,
                                Level.ERROR,
                                "同一病案号与随访年度（第 "
                                        + payload.year()
                                        + " 年）出现两行（第 "
                                        + firstRow
                                        + " 行已出现），无法确定唯一记录");
                    }
                }
            }
        }

        private String mrnHeader() {
            ImportFieldRule mrn =
                    rules.mrnFieldKey() == null
                            ? null
                            : rules.baselineByKey().get(rules.mrnFieldKey());
            return mrn == null ? "病案号" : mrn.headerName();
        }

        /** 规则 → 版本字段快照行（FormDerivedEngine 的入参形态；派生比对专用）。 */
        private List<VersionFieldRow> versionFields(Map<String, ImportFieldRule> ruleIndex) {
            List<VersionFieldRow> fields = new ArrayList<>();
            ruleIndex.forEach(
                    (fieldKey, rule) ->
                            fields.add(
                                    new VersionFieldRow(
                                            null,
                                            null,
                                            null,
                                            0,
                                            fieldKey,
                                            null,
                                            rule.label(),
                                            null,
                                            rule.type().code(),
                                            rule.unit(),
                                            rule.decimals(),
                                            null,
                                            null,
                                            null,
                                            rule.required(),
                                            null,
                                            null,
                                            rule.derivedDefJson(),
                                            rule.sensitive(),
                                            null,
                                            null,
                                            0)));
            return fields;
        }

        // ---------------------------------------------------------------- 问题累加

        private void add(
                String sheetName,
                int rowNo,
                CellValue cell,
                String header,
                String code,
                Level level,
                String message) {
            issues.add(
                    new ImportIssue(
                            sheetName,
                            rowNo,
                            cell == null ? null : cell.c(),
                            header,
                            level,
                            code,
                            message));
        }

        private void add(
                String sheetName,
                int rowNo,
                Integer colNo,
                String header,
                String code,
                Level level,
                String message) {
            issues.add(new ImportIssue(sheetName, rowNo, colNo, header, level, code, message));
        }
    }

    // ---------------------------------------------------------------- 共用

    private JobRow requireJob(long jobId) {
        JobRow job = staging.findJob(jobId);
        if (job == null) {
            throw new ApiException(404, 40400, "导入任务不存在");
        }
        return job;
    }
}
