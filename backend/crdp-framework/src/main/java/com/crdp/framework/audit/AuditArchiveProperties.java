package com.crdp.framework.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计归档策略（E08-T03 / PLT-AUD-010）。验收口径默认值：在线保留 1 年（12 个月）、归档对象 WORM 锁定 15 年； 均可按等保测评或合同年限加严（保留期只能延长）。
 */
@ConfigurationProperties(prefix = "crdp.audit.archive")
public class AuditArchiveProperties {

    /** 在线保留月数：上界早于「今天 − 该月数」的月分区才允许归档删除。 */
    private int onlineRetentionMonths = 12;

    /** WORM 对象级保留年数：retainUntil = 归档日 + 该年数。 */
    private int wormRetentionYears = 15;

    /** 预建未来月分区数（REORGANIZE pmax），保证新月份审计写入即有分区。 */
    private int partitionsAhead = 3;

    public int getOnlineRetentionMonths() {
        return onlineRetentionMonths;
    }

    public void setOnlineRetentionMonths(int onlineRetentionMonths) {
        this.onlineRetentionMonths = onlineRetentionMonths;
    }

    public int getWormRetentionYears() {
        return wormRetentionYears;
    }

    public void setWormRetentionYears(int wormRetentionYears) {
        this.wormRetentionYears = wormRetentionYears;
    }

    public int getPartitionsAhead() {
        return partitionsAhead;
    }

    public void setPartitionsAhead(int partitionsAhead) {
        this.partitionsAhead = partitionsAhead;
    }

    /** 转归档器策略对象。 */
    public AuditArchiver.ArchivePolicy toPolicy() {
        return new AuditArchiver.ArchivePolicy(
                onlineRetentionMonths, wormRetentionYears, partitionsAhead);
    }
}
