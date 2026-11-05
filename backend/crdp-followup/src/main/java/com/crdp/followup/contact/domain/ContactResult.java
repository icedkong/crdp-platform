package com.crdp.followup.contact.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 联系结果（E15-T04 / A-FU-006「联系结果」六值；C-FU-019 沿用）。
 *
 * <p>联系结果字典完整（tasks.csv E15-T04 验收）：已联系 / 未联系到 / 号码错误 / 拒访 / 家属代答 / 告知已故， 与 components.md §2
 * contact 域、平台库 V45 预置字典 {@code contact_result} 同一枚举。结果驱动业务规则，写入按本枚举校验（租户字典新增项没有对应语义，OQ-84）：
 *
 * <ul>
 *   <li>{@link #DECEASED} 触发死亡自动终止（A-FU-006 / A-FU-007，OQ-79②）；
 *   <li>{@link #NOT_REACHED} / {@link #WRONG_NUMBER} 计入失访判定（窗口内连续 3 次，A-FU-012，计数随 E15-T05）；
 *   <li>{@link #REFUSED} 不计入失访，改走移出计划（A-FU-012）。
 * </ul>
 */
public enum ContactResult {
    REACHED("REACHED", "已联系", false, false),
    NOT_REACHED("NOT_REACHED", "未联系到", false, true),
    WRONG_NUMBER("WRONG_NUMBER", "号码错误", false, true),
    REFUSED("REFUSED", "拒访", false, false),
    FAMILY("FAMILY", "家属代答", false, false),
    DECEASED("DECEASED", "告知已故", true, false);

    /** 全集（字典完整性断言与错误提示用，顺序即展示顺序）。 */
    public static final List<ContactResult> ALL = List.of(values());

    private final String code;
    private final String label;
    private final boolean deathTermination;
    private final boolean lostCounting;

    ContactResult(String code, String label, boolean deathTermination, boolean lostCounting) {
        this.code = code;
        this.label = label;
        this.deathTermination = deathTermination;
        this.lostCounting = lostCounting;
    }

    /** 存储编码（fu_contact_log.result，创建后不改名）。 */
    public String code() {
        return code;
    }

    /** 中文显示名（与平台库 V45 字典、components.md §2 一致）。 */
    public String label() {
        return label;
    }

    /** 是否触发死亡自动终止（A-FU-006「告知已故」）。 */
    public boolean triggersDeathTermination() {
        return deathTermination;
    }

    /** 是否计入失访判定（未联系到 / 号码错误，A-FU-012；计数随 E15-T05）。 */
    public boolean countsTowardLost() {
        return lostCounting;
    }

    /** 按编码解析（未知值空，写入侧 40001 不静默吞掉）。 */
    public static Optional<ContactResult> of(String code) {
        return Arrays.stream(values()).filter(result -> result.code.equals(code)).findFirst();
    }
}
