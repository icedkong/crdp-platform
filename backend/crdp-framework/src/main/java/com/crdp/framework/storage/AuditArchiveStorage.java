package com.crdp.framework.storage;

import java.time.LocalDate;

/**
 * 审计归档桶端口（E08-T03 / PLT-AUD-010，设计见 ADR 0005 与 tech-plan §14）。
 *
 * <p>与业务文件桶 {@link ObjectStorage}（每院一桶，E10）不同：审计归档是单独的单可用区 OBS 桶，桶级 WORM（合规模式）默认保留 15 年
 * 由运维开启（E01-T08 / E22-T08）；应用侧每次写入同时设置对象级保留期 = 归档日 + 15 年。只写北京四一份，不做异地副本（已确认）。
 *
 * <p>实现约束（来自 OBS WORM 语义，tech-plan §2 核实记录）： ① 必须单次 PUT——分段上传在合并完成前不受 WORM 保护； ②
 * 受保护对象不可覆盖、不可删（保留期只能延长）；因此端口不提供删除 / 覆盖语义，键名由调用方保证确定性（同分区重跑同 key）。
 *
 * <p>实现：{@link ObsAuditArchiveStorage}（E22-T08，{@code storage.type=obs}，桶级 WORM 开启与生产演练见
 * docs/ops/audit-worm-archive.md）；默认 {@code none} 时为 {@link UnsupportedAuditArchiveStorage}。
 */
public interface AuditArchiveStorage {

    /** 是否已配置审计归档桶；未配置时归档作业只做分区维护，不动在线数据（云凭证随 E10/E22 注入）。 */
    boolean enabled();

    /**
     * 单次 PUT 写入并设置对象级合规保留（WORM）：retainUntil 之前任何账号不可删除或覆盖该对象。
     *
     * @param key 对象键（确定性命名：{前缀}/{表名}/{yyyyMM}.{jsonl.gz|manifest.json}）
     * @param retainUntil 保留截止日 = 归档日 + 15 年（PLT-AUD-010 验收口径）
     */
    void putWithRetention(String key, byte[] body, String contentType, LocalDate retainUntil);

    /** 读回对象内容（归档后校验与按需恢复查询用）；对象不存在返回 null。 */
    byte[] get(String key);
}
