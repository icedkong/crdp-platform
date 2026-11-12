package com.crdp.server.platform;

import com.crdp.form.field.app.FormDictPort;
import com.crdp.system.dict.TenantDictService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 表单字段字典绑定端口适配器（E23-T20 / OQ-69④）：包装 {@link TenantDictService} 的平台 + 租户合并结果 （平台预置见平台库 V70；生效项 = 平台项
 * − 本院停用 + 本院自有项）。
 *
 * <p>返回含停用项的全量清单（按 sortNo、itemCode 升序）——停用项带停用标记随 FormSchema 下发（不进下拉、
 * 历史值照常显示与保存，C-FLD-009），是否进下拉由前端按 {@code disabled} 过滤。
 */
@Component
public class FormDictPortAdapter implements FormDictPort {

    private final TenantDictService dicts;

    public FormDictPortAdapter(TenantDictService dicts) {
        this.dicts = dicts;
    }

    @Override
    public List<DictItem> items(String dictCode) {
        return dicts.itemsOrEmpty(dictCode).stream()
                .map(
                        item ->
                                new DictItem(
                                        item.itemCode(),
                                        item.label(),
                                        item.sortNo(),
                                        "ENABLED".equals(item.status())))
                .toList();
    }
}
