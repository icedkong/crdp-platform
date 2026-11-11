package com.crdp.system.dict;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.web.ApiException;
import com.crdp.system.config.PlatformConfigPort;
import com.crdp.system.infra.mapper.ConfigMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E06-T10 / PLT-CFG-002：租户字典（租户库 cfg_dict / cfg_dict_item，权限 tenant:dict:edit）。 E11-T08 /
 * A-FLD-012、C-FLD-002：业务字典（患者类型、术者与 C 的起搏器类型等）走同一模型——租户管理员 维护取值（新增、停用、排序、<b>别名</b>，aliases
 * 供历史写法映射与导入匹配）。
 *
 * <p>继承平台字典、增量覆盖（platform-spec §10「租户字典：继承平台字典，可新增租户自有项或停用平台项」）：
 *
 * <ul>
 *   <li>生效字典类型 = 平台启用字典 ∪ 本院自有字典（cfg_dict）；
 *   <li>生效项 =（平台项 − 本院停用的平台项）∪ 本院自有项；平台新增项自动出现（无需同步）；
 *   <li>租户不能删除或修改平台字典项，只能停用 / 恢复（规则同 system.md §4「条目停用不删除」）；
 *   <li>租户不能修改平台字典类型（名称 / 状态），只能维护自有字典。
 * </ul>
 *
 * <p>写操作写租户审计（DICT_CREATE / DICT_UPDATE / DICT_ITEM_ADD / DICT_ITEM_UPDATE， PLT-CFG-011），并写
 * cfg_dict_log 变更记录（A-FLD-009、C-FLD-009「记录每次字典调整的前后值、操作人、时间」； 与 cfg_param_log
 * 同款双写，停用不删除——停用项不再出现在下拉、历史值保留照常显示）。
 */
@Service
public class TenantDictService {

    /** 字典编码：小写字母开头，小写字母 / 数字 / 下划线（与平台字典编码同口径）。 */
    private static final Pattern DICT_CODE = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");

    /** 项编码：字母 / 数字 / 下划线 / 中横线（平台种子含 ID_CARD 这类大写编码）。 */
    private static final Pattern ITEM_CODE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private static final int NAME_MAX = 128;
    private static final int LABEL_MAX = 128;
    private static final int DESC_MAX = 255;
    private static final int ALIASES_MAX = 255;

    /** 变更记录查询上限（与 TenantParamService.LOG_LIMIT 同口径：倒序最近 100 条）。 */
    private static final int LOG_LIMIT = 100;

    private final PlatformConfigPort platformConfig;
    private final ConfigMapper mapper;
    private final AuditLogger audit;
    private final ObjectMapper json = new ObjectMapper();

    public TenantDictService(
            PlatformConfigPort platformConfig, ConfigMapper mapper, AuditLogger audit) {
        this.platformConfig = platformConfig;
        this.mapper = mapper;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- 视图

    /** 字典类型视图：source 区分平台 / 本院；itemCount 为生效项数（平台项 − 本院停用 + 本院自有）。 */
    public record DictTypeView(
            String code,
            String name,
            String description,
            String source,
            String status,
            int itemCount,
            int tenantItemCount,
            int disabledPlatformCount,
            String updatedAt) {}

    /** 字典项视图：平台项 editable=false（只能停用 / 恢复），本院自有项可维护文字、别名与排序。 */
    public record DictItemView(
            String itemCode,
            String label,
            String aliases,
            int sortNo,
            String status,
            String source,
            boolean editable,
            String updatedAt) {}

    /** 新建本院字典。 */
    public record DictSave(String code, String name, String description) {}

    /** 维护本院字典（名称 / 说明 / 停用）。 */
    public record DictUpdate(String name, String description, String status) {}

    /** 新增字典项（本院自有项，加进平台字典或本院字典）；别名为 CSV（历史写法映射，A-FLD-012）。 */
    public record ItemSave(String itemCode, String label, String aliases, Integer sortNo) {}

    /** 维护字典项：本院自有项可改文字 / 别名 / 排序；平台项只认 status（停用 / 恢复）。 */
    public record ItemUpdate(String label, String aliases, Integer sortNo, String status) {}

    /** 字典变更记录行（A-FLD-009：前后值、操作人、时间；倒序）。 */
    public record DictLogView(
            long id,
            String dictCode,
            String itemCode,
            String action,
            String beforeJson,
            String afterJson,
            Long operatorId,
            String operatorName,
            String createdAt) {}

    // ---------------------------------------------------------------- 读取

    /** 字典类型列表：平台启用字典 + 本院自有字典，按编码排序。 */
    public List<DictTypeView> list() {
        List<ConfigMapper.ItemRow> markers = allMarkerAndOwnItems();
        List<DictTypeView> views = new ArrayList<>();
        for (PlatformConfigPort.PlatformDictDef def : platformConfig.dicts()) {
            long disabled =
                    markers.stream()
                            .filter(
                                    row ->
                                            row.getDictCode().equals(def.code())
                                                    && "PLATFORM".equals(row.getSource())
                                                    && "DISABLED".equals(row.getStatus()))
                            .count();
            long own =
                    markers.stream()
                            .filter(
                                    row ->
                                            row.getDictCode().equals(def.code())
                                                    && "TENANT".equals(row.getSource())
                                                    && "ENABLED".equals(row.getStatus()))
                            .count();
            long platformEnabled =
                    def.items().stream().filter(item -> "ENABLED".equals(item.status())).count();
            views.add(
                    new DictTypeView(
                            def.code(),
                            def.name(),
                            def.description(),
                            "PLATFORM",
                            def.status(),
                            (int) (platformEnabled - disabled + own),
                            (int) own,
                            (int) disabled,
                            null));
        }
        for (ConfigMapper.DictRow own1 : mapper.listOwnDicts()) {
            long ownItems =
                    markers.stream()
                            .filter(
                                    row ->
                                            row.getDictCode().equals(own1.getCode())
                                                    && "TENANT".equals(row.getSource())
                                                    && "ENABLED".equals(row.getStatus()))
                            .count();
            views.add(
                    new DictTypeView(
                            own1.getCode(),
                            own1.getName(),
                            own1.getDescription(),
                            "TENANT",
                            own1.getStatus(),
                            (int) ownItems,
                            (int) ownItems,
                            0,
                            TenantTime.format(own1.getUpdatedAt())));
        }
        views.sort(java.util.Comparator.comparing(DictTypeView::code));
        return views;
    }

    /** 生效项列表（平台项 − 本院停用 + 本院自有项），按 sortNo、itemCode 升序；未知字典 40400。 */
    public List<DictItemView> items(String code) {
        List<DictItemView> merged = mergedItemsOf(code);
        if (merged == null) {
            throw new ApiException(404, 40400, "字典不存在");
        }
        return merged;
    }

    /**
     * 生效项列表（含停用项，供调用方区分「进下拉」与「历史值照常显示」，C-FLD-009）； 字典不存在（未预置或平台停用）返回空列表——业务读取端口用，不抛 40400（E21-T04
     * 植入记录选项）。
     */
    public List<DictItemView> itemsOrEmpty(String code) {
        List<DictItemView> merged = mergedItemsOf(code);
        return merged == null ? List.of() : merged;
    }

    /**
     * 字典变更记录（A-FLD-009、C-FLD-009）：倒序最近 {@value LOG_LIMIT} 条，含前后值快照、操作人（联查 sys_user 姓名）与时间；未知字典
     * 40400。
     */
    public List<DictLogView> logs(String code) {
        requireDict(code);
        List<DictLogView> views = new ArrayList<>();
        for (ConfigMapper.DictLogRow row : mapper.listDictLogs(code, LOG_LIMIT)) {
            views.add(
                    new DictLogView(
                            row.getId(),
                            row.getDictCode(),
                            row.getItemCode(),
                            row.getAction(),
                            row.getBeforeJson(),
                            row.getAfterJson(),
                            row.getOperatorId(),
                            row.getOperatorName(),
                            TenantTime.format(row.getCreatedAt())));
        }
        return views;
    }

    // ---------------------------------------------------------------- 字典类型维护

    /** 新建本院字典：编码本院唯一（含与平台字典冲突，40902），创建后不可改。 */
    @Transactional
    public DictTypeView createDict(DictSave save) {
        if (save == null) {
            throw bad("请求体不能为空");
        }
        String code = trim(save.code());
        if (code == null || !DICT_CODE.matcher(code).matches()) {
            throw bad("字典编码必须是小写字母开头的 2–64 位小写字母、数字或下划线");
        }
        if (platformConfig.dict(code).isPresent() || mapper.findOwnDict(code) != null) {
            throw new ApiException(409, 40902, "字典编码已存在");
        }
        String name = requireName(save.name());
        String description = optionalLength(save.description(), DESC_MAX, "说明");
        ConfigMapper.DictRow row = new ConfigMapper.DictRow();
        row.setCode(code);
        row.setName(name);
        row.setDescription(description);
        row.setStatus("ENABLED");
        mapper.insertOwnDict(row);
        Long operator = operator();
        audit.record(
                new AuditEvent(
                        "DICT_CREATE",
                        operator,
                        "CREATE",
                        "cfg_dict:" + code,
                        "租户字典已创建：" + code + "（" + name + "）"));
        appendLog(code, null, "DICT_CREATE", null, dictSnapshot(row));
        return viewOf(code);
    }

    /** 维护本院字典（名称 / 说明 / 停用启用）；平台字典不可修改（40001）。 */
    @Transactional
    public DictTypeView updateDict(String code, DictUpdate update) {
        if (update == null) {
            throw bad("请求体不能为空");
        }
        if (platformConfig.dict(code).isPresent()) {
            throw bad("平台字典不能修改，只能停用其中的项");
        }
        ConfigMapper.DictRow row = mapper.findOwnDict(code);
        if (row == null) {
            throw new ApiException(404, 40400, "字典不存在");
        }
        String beforeJson = dictSnapshot(row);
        row.setName(requireName(update.name()));
        row.setDescription(optionalLength(update.description(), DESC_MAX, "说明"));
        String status = statusOf(update.status());
        row.setStatus(status);
        mapper.updateOwnDict(row);
        audit.record(
                new AuditEvent(
                        "DICT_UPDATE",
                        operator(),
                        "UPDATE",
                        "cfg_dict:" + code,
                        "租户字典已更新：" + code + "，状态=" + status));
        appendLog(code, null, "DICT_UPDATE", beforeJson, dictSnapshot(row));
        return viewOf(code);
    }

    // ---------------------------------------------------------------- 字典项维护

    /** 新增本院自有项（加进平台字典或本院字典）：项编码在生效项内唯一（40902）。 */
    @Transactional
    public List<DictItemView> addItem(String dictCode, ItemSave save) {
        if (save == null) {
            throw bad("请求体不能为空");
        }
        requireDict(dictCode);
        String itemCode = trim(save.itemCode());
        if (itemCode == null || !ITEM_CODE.matcher(itemCode).matches()) {
            throw bad("项编码必须是 1–64 位字母、数字、下划线或中横线");
        }
        if (effectiveItem(dictCode, itemCode) != null) {
            throw new ApiException(409, 40902, "项编码已存在");
        }
        String label = trim(save.label());
        if (label == null) {
            throw bad("显示文字不能为空");
        }
        if (label.length() > LABEL_MAX) {
            throw bad("显示文字不能超过 " + LABEL_MAX + " 个字符");
        }
        String aliases = aliasesOf(save.aliases());
        int sortNo = sortNo(save.sortNo());
        ConfigMapper.ItemRow row = new ConfigMapper.ItemRow();
        row.setDictCode(dictCode);
        row.setItemCode(itemCode);
        row.setLabel(label);
        row.setAliases(aliases);
        row.setSortNo(sortNo);
        row.setSource("TENANT");
        row.setStatus("ENABLED");
        row.setUpdatedBy(operator());
        mapper.insertItem(row);
        audit.record(
                new AuditEvent(
                        "DICT_ITEM_ADD",
                        row.getUpdatedBy(),
                        "CREATE",
                        "cfg_dict_item:" + dictCode + ":" + itemCode,
                        "租户字典项已新增：" + dictCode + "/" + itemCode + "（" + label + "）"));
        appendLog(dictCode, itemCode, "ITEM_ADD", null, itemSnapshot(dictCode, row));
        return items(dictCode);
    }

    /**
     * 维护字典项：本院自有项可改文字 / 别名 / 排序 / 停用； 平台项只能停用 / 恢复（改文字与排序 40001， PLT-CFG-002「租户不能删除或修改平台字典项，只能停用」）。
     */
    @Transactional
    public List<DictItemView> updateItem(String dictCode, String itemCode, ItemUpdate update) {
        if (update == null) {
            throw bad("请求体不能为空");
        }
        PlatformConfigPort.PlatformDictDef platformDict =
                platformConfig.dict(dictCode).orElse(null);
        ConfigMapper.DictRow ownDict = mapper.findOwnDict(dictCode);
        if (platformDict == null && ownDict == null) {
            throw new ApiException(404, 40400, "字典不存在");
        }
        String status = statusOf(update.status());
        ConfigMapper.ItemRow row = mapper.findItem(dictCode, itemCode);

        if (platformDict != null
                && platformDict.item(itemCode).isPresent()
                && (row == null || "PLATFORM".equals(row.getSource()))) {
            // 平台项：只允许停用 / 恢复（落 cfg_dict_item 标记行，label 不落库；别名同文字不可改）
            if (update.label() != null || update.sortNo() != null || update.aliases() != null) {
                throw bad("平台字典项不能修改文字、排序或别名，只能停用 / 恢复");
            }
            PlatformConfigPort.PlatformDictItem def = platformDict.item(itemCode).orElseThrow();
            String beforeJson = platformItemSnapshot(def, markerStatusOf(row, def));
            if (row == null) {
                row = new ConfigMapper.ItemRow();
                row.setDictCode(dictCode);
                row.setItemCode(itemCode);
                row.setLabel(null);
                row.setSortNo(def.sortNo());
                row.setSource("PLATFORM");
                row.setStatus(status);
                row.setUpdatedBy(operator());
                mapper.insertItem(row);
            } else {
                row.setStatus(status);
                row.setUpdatedBy(operator());
                mapper.updateItem(row);
            }
            audit.record(
                    new AuditEvent(
                            "DICT_ITEM_UPDATE",
                            row.getUpdatedBy(),
                            "UPDATE",
                            "cfg_dict_item:" + dictCode + ":" + itemCode,
                            "平台字典项在本院已" + ("DISABLED".equals(status) ? "停用" : "恢复")));
            appendLog(
                    dictCode,
                    itemCode,
                    "ITEM_UPDATE",
                    beforeJson,
                    platformItemSnapshot(def, status));
            return items(dictCode);
        }

        if (row != null && "TENANT".equals(row.getSource())) {
            String label = trim(update.label());
            if (label == null) {
                throw bad("显示文字不能为空");
            }
            if (label.length() > LABEL_MAX) {
                throw bad("显示文字不能超过 " + LABEL_MAX + " 个字符");
            }
            String beforeJson = itemSnapshot(dictCode, row);
            row.setLabel(label);
            row.setAliases(aliasesOf(update.aliases()));
            row.setSortNo(sortNo(update.sortNo()));
            row.setStatus(status);
            row.setUpdatedBy(operator());
            mapper.updateItem(row);
            audit.record(
                    new AuditEvent(
                            "DICT_ITEM_UPDATE",
                            row.getUpdatedBy(),
                            "UPDATE",
                            "cfg_dict_item:" + dictCode + ":" + itemCode,
                            "租户字典项已更新：" + dictCode + "/" + itemCode + "，状态=" + status));
            appendLog(dictCode, itemCode, "ITEM_UPDATE", beforeJson, itemSnapshot(dictCode, row));
            return items(dictCode);
        }
        throw new ApiException(404, 40400, "字典项不存在");
    }

    // ---------------------------------------------------------------- 变更记录（A-FLD-009、C-FLD-009）

    /** 字典行快照（变更记录用）。 */
    private String dictSnapshot(ConfigMapper.DictRow row) {
        return writeJson(
                java.util.Map.of(
                        "code",
                        row.getCode() == null ? "" : row.getCode(),
                        "name",
                        row.getName() == null ? "" : row.getName(),
                        "description",
                        row.getDescription() == null ? "" : row.getDescription(),
                        "status",
                        row.getStatus() == null ? "" : row.getStatus()));
    }

    /** 本院自有项快照（变更记录用；label / 别名 / 排序 / 状态都在）。 */
    private String itemSnapshot(String dictCode, ConfigMapper.ItemRow row) {
        return writeJson(
                java.util.Map.of(
                        "dictCode",
                        dictCode,
                        "itemCode",
                        row.getItemCode(),
                        "label",
                        row.getLabel() == null ? "" : row.getLabel(),
                        "aliases",
                        row.getAliases() == null ? "" : row.getAliases(),
                        "sortNo",
                        row.getSortNo(),
                        "status",
                        row.getStatus() == null ? "" : row.getStatus(),
                        "source",
                        "TENANT"));
    }

    /** 平台项快照（本院视角状态 = 平台状态叠加本院停用标记；文字与排序取平台定义）。 */
    private String platformItemSnapshot(
            PlatformConfigPort.PlatformDictItem item, String effectiveStatus) {
        return writeJson(
                java.util.Map.of(
                        "itemCode", item.itemCode(),
                        "label", item.label(),
                        "sortNo", item.sortNo(),
                        "status", effectiveStatus,
                        "source", "PLATFORM"));
    }

    /** 平台项在本院的当前生效状态（有停用标记行取标记，否则取平台状态）。 */
    private String markerStatusOf(
            ConfigMapper.ItemRow marker, PlatformConfigPort.PlatformDictItem def) {
        if (marker != null && "DISABLED".equals(marker.getStatus())) {
            return "DISABLED";
        }
        return def.status();
    }

    /** 追加一条变更记录（前后值 + 操作人；A-FLD-009）。 */
    private void appendLog(
            String dictCode, String itemCode, String action, String before, String after) {
        ConfigMapper.DictLogRow row = new ConfigMapper.DictLogRow();
        row.setDictCode(dictCode);
        row.setItemCode(itemCode);
        row.setAction(action);
        row.setBeforeJson(before);
        row.setAfterJson(after);
        row.setOperatorId(operator());
        mapper.insertDictLog(row);
    }

    /** 别名：去空白、超长 40001；空 → null（A-FLD-012）。 */
    private static String aliasesOf(String raw) {
        String value = trim(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > ALIASES_MAX) {
            throw bad("别名不能超过 " + ALIASES_MAX + " 个字符");
        }
        return value;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字典变更记录序列化失败", e);
        }
    }

    // ---------------------------------------------------------------- 私有

    /** 平台项（叠加本院停用标记）+ 本院自有项，按 sortNo、itemCode 升序；字典不存在返回 null。 */
    private List<DictItemView> mergedItemsOf(String code) {
        PlatformConfigPort.PlatformDictDef platformDict = platformConfig.dict(code).orElse(null);
        ConfigMapper.DictRow ownDict = mapper.findOwnDict(code);
        if (platformDict == null && ownDict == null) {
            return null;
        }
        List<ConfigMapper.ItemRow> rows = mapper.listItems(code);
        List<DictItemView> merged = new ArrayList<>();
        if (platformDict != null) {
            for (PlatformConfigPort.PlatformDictItem item : platformDict.items()) {
                ConfigMapper.ItemRow marker =
                        rows.stream()
                                .filter(
                                        row ->
                                                row.getItemCode().equals(item.itemCode())
                                                        && "PLATFORM".equals(row.getSource()))
                                .findFirst()
                                .orElse(null);
                boolean disabledByTenant = marker != null && "DISABLED".equals(marker.getStatus());
                String status = disabledByTenant ? "DISABLED" : item.status();
                merged.add(
                        new DictItemView(
                                item.itemCode(),
                                item.label(),
                                null,
                                item.sortNo(),
                                status,
                                "PLATFORM",
                                false,
                                marker == null ? null : TenantTime.format(marker.getUpdatedAt())));
            }
        }
        for (ConfigMapper.ItemRow row : rows) {
            if (!"TENANT".equals(row.getSource())) {
                continue;
            }
            merged.add(
                    new DictItemView(
                            row.getItemCode(),
                            row.getLabel(),
                            row.getAliases(),
                            row.getSortNo(),
                            row.getStatus(),
                            "TENANT",
                            true,
                            TenantTime.format(row.getUpdatedAt())));
        }
        merged.sort(
                java.util.Comparator.comparingInt(DictItemView::sortNo)
                        .thenComparing(DictItemView::itemCode));
        return merged;
    }

    /** 生效项里按编码取项（平台项 + 本院自有项，含停用行；不存在返回 null）。 */
    private DictItemView effectiveItem(String dictCode, String itemCode) {
        List<DictItemView> merged = mergedItemsOf(dictCode);
        if (merged == null) {
            return null;
        }
        return merged.stream()
                .filter(item -> item.itemCode().equals(itemCode))
                .findFirst()
                .orElse(null);
    }

    private void requireDict(String dictCode) {
        if (platformConfig.dict(dictCode).isEmpty() && mapper.findOwnDict(dictCode) == null) {
            throw new ApiException(404, 40400, "字典不存在");
        }
    }

    private List<ConfigMapper.ItemRow> allMarkerAndOwnItems() {
        // 停用标记与自有项都在 cfg_dict_item；字典量级小，全量读后内存过滤
        List<ConfigMapper.ItemRow> rows = new ArrayList<>();
        for (ConfigMapper.DictRow dict : mapper.listOwnDicts()) {
            rows.addAll(mapper.listItems(dict.getCode()));
        }
        for (PlatformConfigPort.PlatformDictDef def : platformConfig.dicts()) {
            rows.addAll(mapper.listItems(def.code()));
        }
        return rows;
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

    private static String requireName(String raw) {
        String name = trim(raw);
        if (name == null) {
            throw bad("名称不能为空");
        }
        if (name.length() > NAME_MAX) {
            throw bad("名称不能超过 " + NAME_MAX + " 个字符");
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

    private static int sortNo(Integer sortNo) {
        return sortNo == null ? 0 : sortNo;
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

    /** 时间展示口径（北京时间 ISO-8601 带时区）。 */
    static final class TenantTime {
        private TenantTime() {}

        static String format(java.time.LocalDateTime time) {
            return time == null
                    ? null
                    : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(time.atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
    }
}
