package com.crdp.framework.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.MissingTenantContextException;
import com.crdp.framework.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * E10-T02 / C-NFR-005：本地文件系统替身适配器单元测试（本机 / CI 无云凭证时的 crdp.storage.type=local）。
 *
 * <p>目录结构与 OBS 一致（t/{租户}/…），前缀隔离语义与生产相同：重点覆盖任务验收「按租户前缀隔离」的 双租户用例（AGENTS §6：租户隔离改动必须有双租户用例）——demoa 与
 * demob 同相对键互不可见、物理路径分居。
 */
class LocalObjectStorageTest {

    @TempDir Path root;

    private LocalObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalObjectStorage(root);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** put 返回完整键并落盘；get 原样读回；delete 幂等。 */
    @Test
    void putGetDeleteRoundTrip() throws IOException {
        TenantContext.useTenant(1L, "demoa");
        byte[] body = "%PDF-1.4（示例）".getBytes();

        String key = storage.put("files/202611/uuid.pdf", body, "application/pdf");
        assertEquals("t/demoa/files/202611/uuid.pdf", key);

        Path file = root.resolve("t/demoa/files/202611/uuid.pdf");
        assertTrue(Files.isRegularFile(file), "对象按租户前缀落盘：" + file);
        assertArrayEquals(body, Files.readAllBytes(file));
        assertArrayEquals(body, storage.get("files/202611/uuid.pdf"));

        storage.delete("files/202611/uuid.pdf");
        assertNull(storage.get("files/202611/uuid.pdf"));
        storage.delete("files/202611/uuid.pdf"); // 再删不抛（幂等）
        assertFalse(Files.exists(file));
    }

    /** 双租户隔离：同相对键各写各的，互读不到、互删不到（验收口径）。 */
    @Test
    void tenantsAreIsolatedByPrefix() throws IOException {
        TenantContext.useTenant(1L, "demoa");
        String keyA = storage.put("files/202611/shared.pdf", "A（示例）".getBytes(), "application/pdf");
        storage.put("files/202611/only-a.pdf", "仅甲（示例）".getBytes(), "application/pdf");
        TenantContext.useTenant(2L, "demob");
        String keyB = storage.put("files/202611/shared.pdf", "B（示例）".getBytes(), "application/pdf");

        assertEquals("t/demoa/files/202611/shared.pdf", keyA);
        assertEquals("t/demob/files/202611/shared.pdf", keyB);

        // demob 视角：同相对键只看到自己的内容；demoa 独有的对象对它不存在
        assertArrayEquals("B（示例）".getBytes(), storage.get("files/202611/shared.pdf"));
        assertNull(storage.get("files/202611/only-a.pdf"), "另一租户的对象不可见");

        TenantContext.useTenant(1L, "demoa");
        assertArrayEquals("A（示例）".getBytes(), storage.get("files/202611/shared.pdf"));

        // 物理路径分居两个前缀目录
        assertTrue(Files.exists(root.resolve("t/demoa/files/202611/shared.pdf")));
        assertTrue(Files.exists(root.resolve("t/demob/files/202611/shared.pdf")));
    }

    /** demob 删除同相对键不会碰到 demoa 的对象。 */
    @Test
    void tenantDeleteDoesNotTouchOtherTenant() throws IOException {
        TenantContext.useTenant(1L, "demoa");
        storage.put("files/x.pdf", "A（示例）".getBytes(), "application/pdf");

        TenantContext.useTenant(2L, "demob");
        storage.delete("files/x.pdf");

        TenantContext.useTenant(1L, "demoa");
        assertArrayEquals("A（示例）".getBytes(), storage.get("files/x.pdf"), "另一租户删除不影响本租户对象");
    }

    /** get 不存在的对象返回 null（下载侧按缺失处理，不抛异常）。 */
    @Test
    void missingObjectReturnsNull() {
        TenantContext.useTenant(1L, "demoa");
        assertNull(storage.get("files/never.pdf"));
    }

    /** 无租户上下文：与数据库路由同一口径，MissingTenantContextException。 */
    @Test
    void missingContextRejected() {
        assertThrows(
                MissingTenantContextException.class,
                () -> storage.put("files/a.pdf", new byte[1], "application/pdf"));
        assertThrows(MissingTenantContextException.class, () -> storage.get("files/a.pdf"));
        assertThrows(MissingTenantContextException.class, () -> storage.delete("files/a.pdf"));
    }

    /** 相对键校验兜底（StorageKeys 已拒，适配器不再放行穿越路径）。 */
    @Test
    void traversalKeyRejected() {
        TenantContext.useTenant(1L, "demoa");
        assertThrows(
                IllegalArgumentException.class,
                () -> storage.put("../../outside.pdf", new byte[1], "application/pdf"));
        assertThrows(IllegalArgumentException.class, () -> storage.get("/abs/path.pdf"));
    }
}
