package com.crdp.followup.importer.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * E18-T01：解析后的患者记录——宽表与长表两种版面解析到的<strong>同一个模型</strong>（tasks.csv E18-T01 验收）。
 *
 * <p>承载（A 规格 §4 导入格式说明、宽表列结构表）：
 *
 * <ul>
 *   <li>{@code baseline}：基线字段稳定键 → 非空单元格（空值为「不在地图里」，不是空串——两种版面的空列 （长表多出的出生日期列、宽表多出的 #217 列）天然对齐）；
 *   <li>{@code followups}：随访记录列表（宽表 = 该行的各非空年度组；长表 = 「随访记录」表里该病案号的各行）；
 *   <li>{@code frontColumns}：宽表前置列里保留的 7 列（患者编号、病案号、姓名、性别、手术年龄、入院日期、诊断分类）， 供 E18-T03
 *       比对「前置列与基线列是否冲突」（以基线列为准，不一致报警告）；「序号」忽略、「数据完整度」不导入 （完整度由系统按 A-PAT-019
 *       重新计算），两列不进模型；长表没有前置列，恒为空；
 *   <li>{@code mrnCell}：权威病案号单元格——基线列优先（「以基线列为准」），基线列空白时回落宽表前置列病案号；
 *   <li>{@code legacyNoCell}：原系统编号（宽表 = 前置列「患者编号」，长表 = 可选固定列「原系统编号」，A-PAT-018 新增患者字段）。
 * </ul>
 *
 * <p>{@link #equals} / {@link #hashCode} 按<strong>语义内容</strong>比较（病案号、原系统编号、非空基线值、
 * 按年度排序的随访值），空值、单元格坐标与前置列不参与——同一份内容的宽表与长表解析结果相等； 溯源信息（行号、列号、前置列）通过访问器单独取用。 类型 / 范围 / 选项 / 日期转换与校验属
 * E18-T03，模型里保留的是原始文本。
 */
public final class ImportPatientRecord {

    private final ImportCell mrnCell;
    private final ImportCell legacyNoCell;
    private final Map<String, ImportCell> frontColumns;
    private final Map<String, ImportCell> baseline;
    private final List<ImportFollowup> followups;
    private final int rowNo;

    public ImportPatientRecord(
            ImportCell mrnCell,
            ImportCell legacyNoCell,
            Map<String, ImportCell> frontColumns,
            Map<String, ImportCell> baseline,
            List<ImportFollowup> followups,
            int rowNo) {
        this.mrnCell = mrnCell;
        this.legacyNoCell = legacyNoCell;
        this.frontColumns = new LinkedHashMap<>(frontColumns);
        this.baseline = new LinkedHashMap<>(baseline);
        this.followups = List.copyOf(followups);
        this.rowNo = rowNo;
    }

    /** 病案号（归一化文本；可空）。 */
    public String mrn() {
        return mrnCell == null ? null : mrnCell.normalized();
    }

    /** 原系统编号（归一化文本；可空）。 */
    public String legacyNo() {
        return legacyNoCell == null ? null : legacyNoCell.normalized();
    }

    public ImportCell mrnCell() {
        return mrnCell;
    }

    public ImportCell legacyNoCell() {
        return legacyNoCell;
    }

    /** 宽表保留的前置列（前置列名 → 非空单元格）；长表为空。 */
    public Map<String, ImportCell> frontColumns() {
        return frontColumns;
    }

    /** 基线字段稳定键 → 非空单元格。 */
    public Map<String, ImportCell> baseline() {
        return baseline;
    }

    /** 随访记录（宽表按组序、长表按行序）。 */
    public List<ImportFollowup> followups() {
        return followups;
    }

    /** 溯源行号（宽表 = 该数据行；长表 = 「基线」表该行）。 */
    public int rowNo() {
        return rowNo;
    }

    // ---------------------------------------------------------------- 语义相等

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ImportPatientRecord other)) {
            return false;
        }
        return Objects.equals(mrn(), other.mrn())
                && Objects.equals(legacyNo(), other.legacyNo())
                && normalizedBaseline().equals(other.normalizedBaseline())
                && normalizedFollowups().equals(other.normalizedFollowups());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mrn(), legacyNo(), normalizedBaseline(), normalizedFollowups());
    }

    @Override
    public String toString() {
        return "ImportPatientRecord{mrn="
                + mrn()
                + ", legacyNo="
                + legacyNo()
                + ", baselineFields="
                + baseline.size()
                + ", followups="
                + followups.size()
                + ", rowNo="
                + rowNo
                + "}";
    }

    private Map<String, String> normalizedBaseline() {
        Map<String, String> normalized = new TreeMap<>();
        baseline.forEach((key, cell) -> normalized.put(key, cell.normalized()));
        return normalized;
    }

    /** 随访的语义投影：按年度排序（解析不了的年度排最后、保持行序），值取归一化文本。 */
    private List<Map<Integer, Map<String, String>>> normalizedFollowups() {
        List<ImportFollowup> sorted = new ArrayList<>(followups);
        sorted.sort(
                (a, b) -> {
                    if (a.year() == null && b.year() == null) {
                        return 0;
                    }
                    return a.year() == null
                            ? 1
                            : b.year() == null ? -1 : Integer.compare(a.year(), b.year());
                });
        List<Map<Integer, Map<String, String>>> normalized = new ArrayList<>();
        for (ImportFollowup followup : sorted) {
            Map<String, String> values = new TreeMap<>();
            followup.values().forEach((key, cell) -> values.put(key, cell.normalized()));
            normalized.add(Map.of(followup.year() == null ? -1 : followup.year(), values));
        }
        return normalized;
    }
}
