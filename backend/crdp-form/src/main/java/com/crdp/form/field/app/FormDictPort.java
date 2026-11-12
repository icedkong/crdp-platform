package com.crdp.form.field.app;

import java.util.List;

/**
 * 业务字典读取端口（E23-T20 / OQ-69④：fe_field.dict_code 绑定字段的选项来源）。
 *
 * <p>绑定业务字典（cfg_dict，如 pacemaker_type、pacing_mode）的字段不再走 fe_option_set / fe_option： FormSchema 下发与
 * FormDataValidator 校验时经本端口读本院生效字典项（平台预置 + 租户增量覆盖， PLT-CFG-002）。{@code crdp-form} 不依赖 {@code
 * crdp-system}（依赖方向只到 crdp-framework），实现在 crdp-server（包装 TenantDictService 的平台 + 租户合并结果，与
 * ImplantDictPort 同法）。
 *
 * <p>口径（与 ImplantDictPort 一致）：库列与 JSON 存<b>稳定字符串编码</b>（编码创建后不可改）；
 * 返回<b>含停用项</b>的全量清单（停用项不进下拉、历史值保留照常显示与保存，C-FLD-009）。
 */
public interface FormDictPort {

    /** 字典全部项（含本院停用的平台项与本院自有项，按 sortNo、itemCode 升序）； 字典不存在（未预置或平台停用）返回空列表。 */
    List<DictItem> items(String dictCode);

    /** 字典项：编码（库列 / JSON 存储值，字符串）+ 显示名 + 是否生效（停用项不进下拉，C-FLD-009）。 */
    record DictItem(String itemCode, String label, int sortNo, boolean enabled) {}
}
