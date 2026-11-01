package com.crdp.system.dict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                                        new TenantDictService.ItemUpdate("男生", null, "DISABLED")));
        assertEquals(40001, edit.code());
        verify(mapper, never()).insertItem(any());

        // 只停用 → 落 PLATFORM 标记行（不存 label）
        service.updateItem("gender", "1", new TenantDictService.ItemUpdate(null, null, "DISABLED"));
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
                                                new TenantDictService.ItemSave("1", "男生", null)))
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
                                                        "X_OWN", "重复项", null)))
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
                                                new TenantDictService.ItemSave("A", "项", null)))
                        .code());
    }
}
