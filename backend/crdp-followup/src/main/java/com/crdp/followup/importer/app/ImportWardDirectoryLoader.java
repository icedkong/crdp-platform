package com.crdp.followup.importer.app;

import com.crdp.followup.importer.domain.ImportWardDirectory;
import com.crdp.followup.importer.domain.ImportWardDirectory.WardEntry;
import com.crdp.followup.ward.app.WardService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * E18-T04：从病区字典构造导入用 {@link ImportWardDirectory}（A-WARD-006）。 跨子域取数走病区 app 接口（{@link
 * WardService#list()}，含停用——停用要单独报「已停用」而不是「未映射」）； 别名即历史写法映射，OQ-74④ 定稿复用 {@code ward.alias} 列（病区管理页
 * E13-T08 已可维护，不另建 ward_alias 表）。 调用需租户上下文（病区字典在租户库）；一次校验 / 提交加载一次，内存快照不缓存 （字典量级小，避免别名维护后映射口径滞后）。
 */
@Service
public class ImportWardDirectoryLoader {

    private final WardService wards;

    public ImportWardDirectoryLoader(WardService wards) {
        this.wards = wards;
    }

    /** 加载当前租户的病区值映射字典。 */
    public ImportWardDirectory load() {
        List<WardEntry> entries =
                wards.list().items().stream()
                        .map(
                                view ->
                                        new WardEntry(
                                                Long.parseLong(view.id()),
                                                view.code(),
                                                view.name(),
                                                view.status(),
                                                view.aliases()))
                        .toList();
        return new ImportWardDirectory(entries);
    }
}
