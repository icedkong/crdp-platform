package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.platform.infra.mapper.PlatformConfigMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T10 / PLT-CFG-001：平台字典管理（平台库 plt_dict / plt_dict_item，权限 platform:dict:edit）。
 *
 * <p>全局字典类型与字典项（性别、证件类型、科室类型等）。字典编码与项编码创建后不可改（platform-spec §10 业务规则）；字典项停用不删除（system.md §4
 * 口径）。全部写操作写平台审计（DICT_CREATE / DICT_UPDATE / DICT_ITEM_CREATE /
 * DICT_ITEM_UPDATE，PLT-CFG-011）并立即作废定义缓存——租户端的继承视图随之即时生效。
 */
@Service
public class PlatformDictAdminService {

    private static final Pattern DICT_CODE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    private static final Pattern ITEM_CODE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int NAME_MAX = 128;
    private static final int LABEL_MAX = 128;
    private static final int DESC_MAX = 255;

    private final PlatformConfigMapper mapper;
    private final PlatformConfigCache cache;
    private final AuditLogger audit;

    public PlatformDictAdminService(
            PlatformConfigMapper mapper, PlatformConfigCache cache, AuditLogger audit) {
        this.mapper = mapper;
        this.cache = cache;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 字典类型视图（含项数与最近维护时间；含停用行，管理页可见可恢复）。 */
    public record DictTypeView(
            String code,
            String name,
            String description,
            String status,
            int itemCount,
            int enabledItemCount,
            String updatedAt) {}

    /** 字典项视图（含停用行；停用项不进入租户继承视图的生效集，但保留可恢复）。 */
    public record DictItemView(
            String itemCode, String label, int sortNo, String status, String updatedAt) {}

    public record DictSave(String code, String name, String description) {}

    public record DictUpdate(String name, String description, String status) {}

    public record ItemSave(String itemCode, String label, Integer sortNo) {}

    public record ItemUpdate(String label, Integer sortNo, String status) {}

    // ---------------------------------------------------------------- 读取

    /** 全部字典类型（按编码排序；含停用）。 */
    public List<DictTypeView> list() {
        return TenantContext.callAsPlatform(
                () -> {
                    List<PlatformConfigMapper.DictRow> dicts = mapper.listDicts();
                    List<PlatformConfigMapper.ItemRow> items = mapper.listAllItems();
                    List<DictTypeView> views = new ArrayList<>();
                    for (PlatformConfigMapper.DictRow dict : dicts) {
                        long count =
                                items.stream()
                                        .filter(item -> dict.getId().equals(item.getDictId()))
                                        .count();
                        long enabled =
                                items.stream()
                                        .filter(
                                                item ->
                                                        dict.getId().equals(item.getDictId())
                                                                && "ENABLED"
                                                                        .equals(item.getStatus()))
                                        .count();
                        views.add(
                                new DictTypeView(
                                        dict.getCode(),
                                        dict.getName(),
                                        dict.getDescription(),
                                        dict.getStatus(),
                                        (int) count,
                                        (int) enabled,
                                        formatTime(dict.getUpdatedAt())));
                    }
                    return views;
                });
    }

    /** 字典项（含停用行，按 sortNo、itemCode 升序）；未知字典 40400。 */
    public List<DictItemView> items(String code) {
        return TenantContext.callAsPlatform(
                () -> {
                    PlatformConfigMapper.DictRow dict = requireDict(code);
                    List<DictItemView> views = new ArrayList<>();
                    for (PlatformConfigMapper.ItemRow item : mapper.listItems(dict.getId())) {
                        views.add(
                                new DictItemView(
                                        item.getItemCode(),
                                        item.getLabel(),
                                        item.getSortNo(),
                                        item.getStatus(),
                                        formatTime(item.getUpdatedAt())));
                    }
                    return views;
                });
    }

    // --------------------------------------------------------------- 字典类型维护

    /** 新建字典类型：编码全局唯一（40902）、创建后不可改。 */
    @Transactional
    public DictTypeView createDict(DictSave save) {
        if (save == null) {
            throw bad("请求体不能为空");
        }
        String code = trim(save.code());
        if (code == null || !DICT_CODE.matcher(code).matches()) {
            throw bad("字典编码必须是小写字母开头的 2–64 位小写字母、数字或下划线");
        }
        String name = requireName(save.name(), "名称");
        String description = optionalLength(save.description(), DESC_MAX, "说明");
        return TenantContext.callAsPlatform(
                () -> {
                    if (mapper.findDict(code) != null) {
                        throw new ApiException(409, 40902, "字典编码已存在");
                    }
                    PlatformConfigMapper.DictRow row = new PlatformConfigMapper.DictRow();
                    row.setCode(code);
                    row.setName(name);
                    row.setDescription(description);
                    row.setStatus("ENABLED");
                    row.setUpdatedBy(operator());
                    mapper.insertDict(row);
                    audit.record(
                            new AuditEvent(
                                    "DICT_CREATE",
                                    row.getUpdatedBy(),
                                    "CREATE",
                                    "plt_dict:" + code,
                                    "平台字典已创建：" + code + "（" + name + "）"));
                    cache.evict();
                    return viewOf(code);
                });
    }

    /** 维护字典类型：名称 / 说明 / 停用启用；编码不可改（携带不同值 40001）。 */
    @Transactional
    public DictTypeView updateDict(String code, DictUpdate update) {
        if (update == null) {
            throw bad("请求体不能为空");
        }
        return TenantContext.callAsPlatform(
                () -> {
                    PlatformConfigMapper.DictRow dict = requireDict(code);
                    String name = requireName(update.name(), "名称");
                    String description = optionalLength(update.description(), DESC_MAX, "说明");
                    String status = statusOf(update.status());
                    dict.setName(name);
                    dict.setDescription(description);
                    dict.setStatus(status);
                    dict.setUpdatedBy(operator());
                    mapper.updateDict(dict);
                    audit.record(
                            new AuditEvent(
                                    "DICT_UPDATE",
                                    dict.getUpdatedBy(),
                                    "UPDATE",
                                    "plt_dict:" + code,
                                    "平台字典已更新：" + code + "，状态=" + status + "（即时生效：全部租户）"));
                    cache.evict();
                    return viewOf(code);
                });
    }

    // --------------------------------------------------------------- 字典项维护

    /** 新增字典项：项编码字典内唯一（40902）、创建后不可改。 */
    @Transactional
    public List<DictItemView> addItem(String dictCode, ItemSave save) {
        if (save == null) {
            throw bad("请求体不能为空");
        }
        String itemCode = trim(save.itemCode());
        if (itemCode == null || !ITEM_CODE.matcher(itemCode).matches()) {
            throw bad("项编码必须是 1–64 位字母、数字、下划线或中横线");
        }
        String label = requireName(save.label(), "显示文字");
        if (label.length() > LABEL_MAX) {
            throw bad("显示文字不能超过 " + LABEL_MAX + " 个字符");
        }
        return TenantContext.callAsPlatform(
                () -> {
                    PlatformConfigMapper.DictRow dict = requireDict(dictCode);
                    if (mapper.findItem(dict.getId(), itemCode) != null) {
                        throw new ApiException(409, 40902, "项编码已存在");
                    }
                    PlatformConfigMapper.ItemRow row = new PlatformConfigMapper.ItemRow();
                    row.setDictId(dict.getId());
                    row.setItemCode(itemCode);
                    row.setLabel(label);
                    row.setSortNo(save.sortNo() == null ? 0 : save.sortNo());
                    row.setStatus("ENABLED");
                    row.setUpdatedBy(operator());
                    mapper.insertItem(row);
                    audit.record(
                            new AuditEvent(
                                    "DICT_ITEM_CREATE",
                                    row.getUpdatedBy(),
                                    "CREATE",
                                    "plt_dict_item:" + dictCode + ":" + itemCode,
                                    "平台字典项已新增："
                                            + dictCode
                                            + "/"
                                            + itemCode
                                            + "（"
                                            + label
                                            + "；即时下发全部租户）"));
                    cache.evict();
                    return items(dictCode);
                });
    }

    /** 维护字典项：文字 / 排序 / 停用启用（停用不删除）；项编码不可改（路径定位，携带不同值 40001）。 */
    @Transactional
    public List<DictItemView> updateItem(String dictCode, String itemCode, ItemUpdate update) {
        if (update == null) {
            throw bad("请求体不能为空");
        }
        return TenantContext.callAsPlatform(
                () -> {
                    PlatformConfigMapper.DictRow dict = requireDict(dictCode);
                    PlatformConfigMapper.ItemRow item = mapper.findItem(dict.getId(), itemCode);
                    if (item == null) {
                        throw new ApiException(404, 40400, "字典项不存在");
                    }
                    String label = requireName(update.label(), "显示文字");
                    if (label.length() > LABEL_MAX) {
                        throw bad("显示文字不能超过 " + LABEL_MAX + " 个字符");
                    }
                    String status = statusOf(update.status());
                    item.setLabel(label);
                    item.setSortNo(update.sortNo() == null ? 0 : update.sortNo());
                    item.setStatus(status);
                    item.setUpdatedBy(operator());
                    mapper.updateItem(item);
                    audit.record(
                            new AuditEvent(
                                    "DICT_ITEM_UPDATE",
                                    item.getUpdatedBy(),
                                    "UPDATE",
                                    "plt_dict_item:" + dictCode + ":" + itemCode,
                                    "平台字典项已更新："
                                            + dictCode
                                            + "/"
                                            + itemCode
                                            + "，状态="
                                            + status
                                            + "（即时下发全部租户）"));
                    cache.evict();
                    return items(dictCode);
                });
    }

    // ---------------------------------------------------------------- 私有

    private PlatformConfigMapper.DictRow requireDict(String code) {
        PlatformConfigMapper.DictRow dict = mapper.findDict(code);
        if (dict == null) {
            throw new ApiException(404, 40400, "字典不存在");
        }
        return dict;
    }

    private DictTypeView viewOf(String code) {
        return list().stream()
                .filter(view -> view.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ApiException(404, 40400, "字典不存在"));
    }

    private static Long operator() {
        return CurrentSession.current().map(principal -> principal.userId()).orElse(null);
    }

    private static String requireName(String raw, String field) {
        String name = trim(raw);
        if (name == null) {
            throw bad(field + "不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw bad(field + "不能超过 " + NAME_MAX + " 个字符");
        }
        return name;
    }

    private static String optionalLength(String raw, int max, String field) {
        String value = trim(raw);
        if (value != null && value.length() > max) {
            throw bad(field + "不能超过 " + max + " 个字符");
        }
        return value;
    }

    private static String statusOf(String status) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw bad("状态只能是 ENABLED 或 DISABLED");
        }
        return status;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, 40001, message);
    }

    static String formatTime(java.time.LocalDateTime time) {
        return time == null
                ? null
                : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
    }
}
