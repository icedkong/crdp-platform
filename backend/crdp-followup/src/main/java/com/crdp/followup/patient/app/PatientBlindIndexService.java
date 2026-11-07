package com.crdp.followup.patient.app;

import com.crdp.followup.patient.infra.mapper.PatientMapper;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 病案号盲索引批量存在性（E18-T03 导入预检「按盲索引查病案号重复」）—— 跨子域 app 接口： importer 只依赖本服务，不直接碰 patient
 * infra。全院口径（软删除不占号），只返回盲索引本身、不输出任何患者数据； 病区数据范围在导入确认时校验（E18-T04 / T06，与 {@link
 * PatientMapper#findLiveMrnBlinds} 的 IgnoreScope 同一理由）。
 */
@Service
public class PatientBlindIndexService {

    /** 单批查询的盲索引数（IN 子句长度上限保护；5000 患者规模 → 10 批）。 */
    private static final int QUERY_BATCH = 500;

    private final PatientMapper patients;

    public PatientBlindIndexService(PatientMapper patients) {
        this.patients = patients;
    }

    /** 给定盲索引里已在系统在册的子集（入参为空返回空集）。 */
    public Set<String> existingBlinds(Collection<String> blinds) {
        Set<String> existing = new HashSet<>();
        if (blinds == null || blinds.isEmpty()) {
            return existing;
        }
        List<String> all = List.copyOf(blinds);
        for (int start = 0; start < all.size(); start += QUERY_BATCH) {
            int end = Math.min(start + QUERY_BATCH, all.size());
            existing.addAll(patients.findLiveMrnBlinds(all.subList(start, end)));
        }
        return existing;
    }
}
