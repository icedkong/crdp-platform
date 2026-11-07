package com.crdp.followup.importer.domain;

import java.util.List;

/**
 * E18-T01：版面适配器的解析结果——两种版面同一形状。
 *
 * @param layout 识别出的版面
 * @param patients 患者记录（宽表按行序；长表按「基线」表行序）
 * @param unmatchedFollowups 没能在「基线」表里找到病案号的随访行（长表；「病案号在基线表中不存在的随访行报错」属 E18-T03 校验报告，这里只收集）
 */
public record ImportParseResult(
        ImportLayout layout,
        List<ImportPatientRecord> patients,
        List<ImportFollowup> unmatchedFollowups) {

    public ImportParseResult {
        patients = List.copyOf(patients);
        unmatchedFollowups = List.copyOf(unmatchedFollowups);
    }
}
