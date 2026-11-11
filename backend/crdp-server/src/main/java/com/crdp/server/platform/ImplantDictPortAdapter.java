package com.crdp.server.platform;

import com.crdp.followup.implant.app.ImplantDictPort;
import com.crdp.system.dict.TenantDictService;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 植入记录业务字典端口适配器（E21-T04 / C-FLD-002、C-FLD-003）：包装 {@link TenantDictService} 的 平台 + 租户合并结果（平台预置见平台库
 * V70；生效项 = 平台项 − 本院停用 + 本院自有项）。
 *
 * <p>返回含停用项的全量清单（按 sortNo、itemCode 升序）——停用项不进下拉、历史记录的值保留照常显示 （C-FLD-009），是否进下拉由 ImplantService 按
 * {@code enabled} 过滤。
 */
@Component
public class ImplantDictPortAdapter implements ImplantDictPort {

    private final TenantDictService dicts;

    public ImplantDictPortAdapter(TenantDictService dicts) {
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
