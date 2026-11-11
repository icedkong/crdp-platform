package com.crdp.system.dict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.web.ApiException;
import com.crdp.system.config.PlatformConfigPort;
import com.crdp.system.infra.mapper.ConfigMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * E06-T10 / PLT-CFG-002 单元（MySQL 行为由 DictParamOverrideIT 覆盖）： 继承合并（平台项 − 本院停用 + 本院自有项）、平台项只能停用 /
 * 恢复（改文字 40001）、自有字典与项的唯一性（40902）、平台字典类型不可改。
 */
class TenantDictServiceTest {

    private final PlatformConfigPort port = mock(PlatformConfigPort.class);
    private final ConfigMapper mapper = mock(ConfigMapper.class);
    private final AuditLogger audit = mock(AuditLogger.class);
    private final TenantDictService service = new TenantDictService(port, mapper, audit);

    private static final PlatformConfigPort.PlatformDictDef GENDER =
            new PlatformConfigPort.PlatformDictDef(
                    "gender",
                    "性别",
                    null,
                    "ENABLED",
                    List.of(
                            new PlatformConfigPort.PlatformDictItem("1", "男", 10, "ENABLED"),
                            new PlatformConfigPort.PlatformDictItem("2", "女", 20, "ENABLED")));

    @Test
    void itemsMergePlatformTenantAndDisableMarkers() {
        when(port.dict("gender")).thenReturn(Optional.of(GENDER));
        when(mapper.findOwnDict("gender")).thenReturn(null);
        ConfigMapper.ItemRow disabledMale = new ConfigMapper.ItemRow();
        disabledMale.setDictCode("gender");
        disabledMale.setItemCode("1");
        disabledMale.setSource("PLATFORM");
        disabledMale.setStatus("DISABLED");
        disabledMale.setSortNo(10);
        ConfigMapper.ItemRow own = new ConfigMapper.ItemRow();
        own.setDictCode("gender");
        own.setItemCode("X_OTHER");
        own.setLabel("其他（本院）");
        own.setSource("TENANT");
        own.setStatus("ENABLED");
        own.setSortNo(30);
        when(mapper.listItems("gender")).thenReturn(List.of(disabledMale, own));

        List<TenantDictService.DictItemView> items = service.items("gender");

        // 管理视角保留停用行（可恢复）：男（本院停用）/ 女 / 本院自有项，按 sortNo 升序
        assertEquals(3, items.size());
        assertEquals("1", items.get(0).itemCode());
        assertEquals("DISABLED", items.get(0).status(), "本院停用的平台项");
        assertEquals("男", items.get(0).label(), "平台项文字仍取平台定义");
        assertFalse(items.get(0).editable(), "平台项不可改文字");
        assertEquals("2", items.get(1).itemCode());
        assertEquals("ENABLED", items.get(1).status());
        assertEquals("X_OTHER", items.get(2).itemCode());
        assertTrue(items.get(2).editable());
        assertEquals("其他（本院）", items.get(2).label());
    }

    @Test
    void platformItemCanOnlyBeDisabledOrRestored() {
        when(port.dict("gender")).thenReturn(Optional.of(GENDER));
        when(mapper.findItem("gender", "1")).thenReturn(null);

        // 改文字 / 排序 → 40001（PLT-CFG-002：租户不能修改平台字典项，只能停用）
        ApiException edit =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateItem(
                                        "gender",
                                        "1",
                                        new TenantDictService.ItemUpdate(
                                                "男生", null, null, "DISABLED")));
        assertEquals(40001, edit.code());
        verify(mapper, never()).insertItem(any());

        // 只停用 → 落 PLATFORM 标记行（不存 label）
        service.updateItem(
                "gender", "1", new TenantDictService.ItemUpdate(null, null, null, "DISABLED"));
        verify(mapper)
                .insertItem(
                        argThat(
                                row ->
                                        row != null
                                                && "PLATFORM".equals(row.getSource())
                                                && "DISABLED".equals(row.getStatus())
                                                && row.getLabel() == null));
        verify(audit)
                .record(
                        argThat(
                                (AuditEvent event) ->
                                        event != null
                                                && "DICT_ITEM_UPDATE".equals(event.eventType())
                                                && event.detail().contains("停用")));
    }

    @Test
    void updatePlatformDictTypeIsRejected() {
        when(port.dict("gender")).thenReturn(Optional.of(GENDER));
        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.updateDict(
                                        "gender",
                                        new TenantDictService.DictUpdate("本院性别", null, "ENABLED")));
        assertEquals(40001, error.code());
        assertEquals("平台字典不能修改，只能停用其中的项", error.getMessage());
    }

    @Test
    void createOwnDictValidatesCodeAndConflicts() {
        // 与平台字典冲突 40902；编码格式 40001
        when(port.dict("gender")).thenReturn(Optional.of(GENDER));
        assertEquals(
                40902,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.createDict(
                                                new TenantDictService.DictSave(
                                                        "gender", "本院性别", null)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.createDict(
                                                new TenantDictService.DictSave(
                                                        "Bad-Code", "本院字典", null)))
                        .code());
        assertEquals(
                40001,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.createDict(
                                                new TenantDictService.DictSave(
                                                        "own_dict", " ", null)))
                        .code(),
                "名称必填");
        verify(mapper, never()).insertOwnDict(any());
    }

    @Test
    void addItemRejectsDuplicateAgainstPlatformAndOwnItems() {
        when(port.dict("gender")).thenReturn(Optional.of(GENDER));
        when(mapper.findItem("gender", "1")).thenReturn(null);
        when(mapper.listItems("gender")).thenReturn(List.of());

        // 与平台项编码冲突
        assertEquals(
                40902,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.addItem(
                                                "gender",
                                                new TenantDictService.ItemSave(
                                                        "1", "男生", null, null)))
                        .code());

        // 与本院已有自有项冲突
        ConfigMapper.ItemRow own = new ConfigMapper.ItemRow();
        own.setDictCode("gender");
        own.setItemCode("X_OWN");
        own.setSource("TENANT");
        own.setStatus("ENABLED");
        own.setSortNo(10);
        when(mapper.listItems("gender")).thenReturn(List.of(own));
        assertEquals(
                40902,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.addItem(
                                                "gender",
                                                new TenantDictService.ItemSave(
                                                        "X_OWN", "重复项", null, null)))
                        .code());
        verify(mapper, never()).insertItem(any());
    }

    @Test
    void unknownDictOrItemIs40400() {
        when(port.dict(anyString())).thenReturn(Optional.empty());
        when(mapper.findOwnDict(anyString())).thenReturn(null);
        assertEquals(40400, assertThrows(ApiException.class, () -> service.items("nosuch")).code());
        assertEquals(
                40400,
                assertThrows(
                                ApiException.class,
                                () ->
                                        service.addItem(
                                                "nosuch",
                                                new TenantDictService.ItemSave(
                                                        "A", "项", null, null)))
                        .code());
        assertEquals(40400, assertThrows(ApiException.class, () -> service.logs("nosuch")).code());
    }

    @Test
    void itemsOrEmptyKeepsDisabledRowsAndReturnsEmptyForUnknownDict() {
        // E21-T04：业务读取端口（植入记录选项）用——含停用行（历史值照常显示，C-FLD-009），未知字典不抛 40400
        when(port.dict("pacing_mode")).thenReturn(Optional.of(GENDER));
        when(mapper.findOwnDict("pacing_mode")).thenReturn(null);
        ConfigMapper.ItemRow disabled = new ConfigMapper.ItemRow();
        disabled.setDictCode("pacing_mode");
        disabled.setItemCode("1");
        disabled.setSource("PLATFORM");
        disabled.setStatus("DISABLED");
        disabled.setSortNo(10);
        when(mapper.listItems("pacing_mode")).thenReturn(List.of(disabled));

        List<TenantDictService.DictItemView> items = service.itemsOrEmpty("pacing_mode");
        assertEquals(2, items.size(), "停用行保留（调用方按 status 区分下拉与历史显示）");
        assertEquals("DISABLED", items.get(0).status());

        when(port.dict(anyString())).thenReturn(Optional.empty());
        when(mapper.findOwnDict(anyString())).thenReturn(null);
        assertTrue(service.itemsOrEmpty("nosuch").isEmpty(), "未知字典返回空列表而非 40400");
    }

    // --------------------------------------------------------------- E11-T08：别名与变更记录

    private static final PlatformConfigPort.PlatformDictDef PATIENT_TYPE =
            new PlatformConfigPort.PlatformDictDef(
                    "patient_type",
                    "患者类型",
                    null,
                    "ENABLED",
                    List.of(
                            new PlatformConfigPort.PlatformDictItem(
                                    "ELECTIVE", "择期手术（示例）", 10, "ENABLED"),
                            new PlatformConfigPort.PlatformDictItem(
                                    "URGENT", "限期手术（示例）", 20, "ENABLED")));

    private ConfigMapper.ItemRow tenantRow(String itemCode, String label, String aliases) {
        ConfigMapper.ItemRow row = new ConfigMapper.ItemRow();
        row.setDictCode("patient_type");
        row.setItemCode(itemCode);
        row.setLabel(label);
        row.setAliases(aliases);
        row.setSortNo(30);
        row.setSource("TENANT");
        row.setStatus("ENABLED");
        return row;
    }

    @Test
    void addItemWithAliasesWritesChangeLog() {
        when(port.dict("patient_type")).thenReturn(Optional.of(PATIENT_TYPE));
        when(mapper.findOwnDict("patient_type")).thenReturn(null);
        when(mapper.listItems("patient_type")).thenReturn(List.of());

        service.addItem(
                "patient_type",
                new TenantDictService.ItemSave("X_LOCAL", "本院分型（示例）", "本地分型、老分型", 40));

        // A-FLD-012：取值维护含别名；A-FLD-009：新增留痕（前值为空、后值含别名与操作人）
        verify(mapper)
                .insertItem(
                        argThat(
                                row ->
                                        row != null
                                                && "X_LOCAL".equals(row.getItemCode())
                                                && "本院分型（示例）".equals(row.getLabel())
                                                && "本地分型、老分型".equals(row.getAliases())));
        verify(mapper)
                .insertDictLog(
                        argThat(
                                log ->
                                        log != null
                                                && "patient_type".equals(log.getDictCode())
                                                && "X_LOCAL".equals(log.getItemCode())
                                                && "ITEM_ADD".equals(log.getAction())
                                                && log.getBeforeJson() == null
                                                && log.getAfterJson().contains("本院分型（示例）")
                                                && log.getAfterJson().contains("本地分型、老分型")));
    }

    @Test
    void addItemValidatesAliasLength() {
        when(port.dict("patient_type")).thenReturn(Optional.of(PATIENT_TYPE));
        when(mapper.listItems("patient_type")).thenReturn(List.of());

        ApiException tooLong =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.addItem(
                                        "patient_type",
                                        new TenantDictService.ItemSave(
                                                "X_LONG", "超长别名（示例）", "别".repeat(256), null)));
        assertEquals(40001, tooLong.code());
        verify(mapper, never()).insertItem(any());
    }

    @Test
    void updateTenantItemLogsBeforeAndAfter() {
        when(port.dict("patient_type")).thenReturn(Optional.empty());
        ConfigMapper.DictRow own = new ConfigMapper.DictRow();
        own.setCode("patient_type");
        own.setName("患者类型");
        own.setStatus("ENABLED");
        when(mapper.findOwnDict("patient_type")).thenReturn(own);
        when(mapper.findItem("patient_type", "X_LOCAL"))
                .thenReturn(tenantRow("X_LOCAL", "本院分型（示例）", null));

        service.updateItem(
                "patient_type",
                "X_LOCAL",
                new TenantDictService.ItemUpdate("本院分型·改（示例）", "旧写法", 50, "DISABLED"));

        verify(mapper)
                .updateItem(
                        argThat(
                                row ->
                                        row != null
                                                && "本院分型·改（示例）".equals(row.getLabel())
                                                && "旧写法".equals(row.getAliases())
                                                && "DISABLED".equals(row.getStatus())
                                                && row.getSortNo() == 50));
        // 前后值都在：调整前无别名启用，调整后带别名停用（C-FLD-009 停用留痕）
        verify(mapper)
                .insertDictLog(
                        argThat(
                                log ->
                                        log != null
                                                && "ITEM_UPDATE".equals(log.getAction())
                                                && log.getBeforeJson() != null
                                                && log.getBeforeJson().contains("本院分型（示例）")
                                                && log.getBeforeJson().contains("ENABLED")
                                                && log.getAfterJson() != null
                                                && log.getAfterJson().contains("DISABLED")
                                                && log.getAfterJson().contains("旧写法")));
    }

    @Test
    void platformItemDisableAndRestoreAreLogged() {
        when(port.dict("patient_type")).thenReturn(Optional.of(PATIENT_TYPE));
        when(mapper.findItem("patient_type", "ELECTIVE")).thenReturn(null);

        service.updateItem(
                "patient_type",
                "ELECTIVE",
                new TenantDictService.ItemUpdate(null, null, null, "DISABLED"));

        verify(mapper)
                .insertDictLog(
                        argThat(
                                log ->
                                        log != null
                                                && "ELECTIVE".equals(log.getItemCode())
                                                && "ITEM_UPDATE".equals(log.getAction())
                                                && log.getBeforeJson().contains("ENABLED")
                                                && log.getAfterJson().contains("DISABLED")));
    }

    @Test
    void createAndUpdateDictWriteLogs() {
        when(port.dict("own_dict")).thenReturn(Optional.empty());
        ConfigMapper.DictRow row = new ConfigMapper.DictRow();
        row.setCode("own_dict");
        row.setName("本院字典（示例）");
        row.setStatus("ENABLED");
        // createDict 的冲突检查返回 null（未存在），updateDict 按编码查到本院字典行
        when(mapper.findOwnDict("own_dict")).thenReturn(null, row);
        when(mapper.listOwnDicts()).thenReturn(List.of(row));

        service.createDict(new TenantDictService.DictSave("own_dict", "本院字典（示例）", null));
        verify(mapper)
                .insertDictLog(
                        argThat(
                                log ->
                                        log != null
                                                && "DICT_CREATE".equals(log.getAction())
                                                && log.getBeforeJson() == null
                                                && log.getAfterJson().contains("本院字典（示例）")));

        service.updateDict(
                "own_dict", new TenantDictService.DictUpdate("本院字典·改（示例）", null, "ENABLED"));
        verify(mapper)
                .insertDictLog(
                        argThat(
                                log ->
                                        log != null
                                                && "DICT_UPDATE".equals(log.getAction())
                                                && log.getBeforeJson().contains("本院字典（示例）")
                                                && log.getAfterJson().contains("本院字典·改（示例）")));
    }

    @Test
    void logsReturnsRecordsWithOperatorName() {
        when(port.dict("patient_type")).thenReturn(Optional.of(PATIENT_TYPE));
        ConfigMapper.DictLogRow row = new ConfigMapper.DictLogRow();
        row.setId(9L);
        row.setDictCode("patient_type");
        row.setItemCode("X_LOCAL");
        row.setAction("ITEM_UPDATE");
        row.setBeforeJson("{\"status\":\"ENABLED\"}");
        row.setAfterJson("{\"status\":\"DISABLED\"}");
        row.setOperatorId(7L);
        row.setOperatorName("张医生（示例）");
        row.setCreatedAt(java.time.LocalDateTime.of(2027, 3, 1, 9, 0));
        when(mapper.listDictLogs("patient_type", 100)).thenReturn(List.of(row));

        List<TenantDictService.DictLogView> logs = service.logs("patient_type");

        assertEquals(1, logs.size());
        TenantDictService.DictLogView view = logs.get(0);
        assertEquals("ITEM_UPDATE", view.action());
        assertEquals("X_LOCAL", view.itemCode());
        assertEquals("张医生（示例）", view.operatorName(), "操作人姓名（sys_user 联查）");
        assertNotNull(view.createdAt());
        assertTrue(view.afterJson().contains("DISABLED"));
    }
}
