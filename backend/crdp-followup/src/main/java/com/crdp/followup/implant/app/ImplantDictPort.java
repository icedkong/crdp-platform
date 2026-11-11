package com.crdp.followup.implant.app;

import java.util.List;

/**
 * 植入记录业务字典读取端口（E21-T04 / C-FLD-002、C-FLD-003）。
 *
 * <p>起搏器类型、品牌、<b>统一起搏模式</b>（C-FLD-003 / Q08：患者登记表与程控记录表两套选项合并为一个
 * 字典，植入记录与程控记录共用）、植入指征、电极植入部位（按腔室）的业务字典值预置在平台库 （{@code plt_dict} / {@code plt_dict_item}，平台库
 * V70），租户经 {@code cfg_dict} 增量覆盖（停用平台项、 新增本院项、排序，PLT-CFG-002）。{@code crdp-followup} 不依赖 {@code
 * crdp-system}，经本端口取本院 生效字典项；实现在 crdp-server（包装 TenantDictService 的平台 + 租户合并结果）。
 *
 * <p>口径：库列存稳定字符串编码（编码创建后不可改）；停用项不进下拉、历史记录的值保留照常显示 （C-FLD-009）——因此 {@link #items(String)}
 * 返回<b>含停用项</b>的全量清单，调用方按需过滤。
 */
public interface ImplantDictPort {

    /** 字典编码（与平台库 V70 预置一致）。 */
    String DICT_PACING_MODE = "pacing_mode";

    /** 起搏器类型字典编码（附录 A 患者 #8）。 */
    String DICT_DEVICE_TYPE = "pacemaker_type";

    /** 品牌字典编码（附录 A 患者 #9）。 */
    String DICT_BRAND = "pacemaker_brand";

    /** 植入指征字典编码（附录 A 植入 #11 / Q16）。 */
    String DICT_INDICATION = "implant_indication";

    /** 电极植入部位字典编码前缀（按腔室 lead_position_ra / rv / lv，Q15）。 */
    String DICT_LEAD_POSITION_PREFIX = "lead_position_";

    /** 字典全部项（含本院停用的平台项与本院自有项，按 sortNo、itemCode 升序）； 字典不存在（未预置或平台停用）返回空列表。 */
    List<DictItem> items(String dictCode);

    /** 字典项：编码（库列存储值）+ 显示名 + 是否生效（停用项不进下拉，C-FLD-009）。 */
    record DictItem(String itemCode, String label, int sortNo, boolean enabled) {}
}
