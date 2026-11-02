package com.crdp.framework.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.crdp.framework.tenant.MissingTenantContextException;
import com.crdp.framework.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * E10-T02 / C-NFR-003、C-NFR-005：对象键租户前缀与安全校验单元测试。
 *
 * <p>锁定任务验收「按租户前缀隔离」的构造性保证：前缀只来自 TenantContext（登录令牌）， 相对键与租户编码里的路径穿越成分一律拒绝； 没有租户上下文直接
 * MissingTenantContextException（与数据库路由同一口径，AGENTS §5.1）。
 */
class StorageKeysTest {

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 医院租户：完整键 = t/{租户编码}/{相对键}（tech-plan §2 修订：一桶、每院一前缀）。 */
    @Test
    void tenantContextGetsTenantPrefix() {
        TenantContext.useTenant(1L, "demoa");
        assertEquals(
                "t/demoa/files/202611/uuid.pdf", StorageKeys.scopedKey("files/202611/uuid.pdf"));
    }

    /** 平台上下文：platform/ 前缀（E08 导出迁桶随 E10-T05，此处预留）。 */
    @Test
    void platformContextGetsPlatformPrefix() {
        TenantContext.usePlatform();
        assertEquals("platform/export/x.csv", StorageKeys.scopedKey("export/x.csv"));
    }

    /** 无租户上下文：拒绝（不落到公共前缀，避免对象归属不明）。 */
    @Test
    void missingContextRejected() {
        assertThrows(
                MissingTenantContextException.class, () -> StorageKeys.scopedKey("files/a.pdf"));
    }

    /** 相对键校验：空、绝对路径、反斜杠、.. 段、控制字符、超长全部拒绝。 */
    @Test
    void relativeKeyValidated() {
        TenantContext.useTenant(1L, "demoa");
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("  "));
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("a\\b.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("a/../b.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("./a.pdf"));
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("a\nb.pdf"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StorageKeys.scopedKey("f/" + "x".repeat(1024) + ".pdf"));
        // 合法键放行：大小写、中文、点号在段内、多级目录
        assertEquals("t/demoa/Files/报告单（示例）.PDF", StorageKeys.scopedKey("Files/报告单（示例）.PDF"));
    }

    /** 租户编码来自服务端会话，同样做防御：分隔符与 .. 拒绝（不给前缀注入留口）。 */
    @Test
    void tenantCodeValidated() {
        TenantContext.useTenant(1L, "../evil");
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("a.pdf"));

        TenantContext.useTenant(1L, "a/b");
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("a.pdf"));

        TenantContext.useTenant(1L, "  ");
        assertThrows(IllegalArgumentException.class, () -> StorageKeys.scopedKey("a.pdf"));
    }
}
