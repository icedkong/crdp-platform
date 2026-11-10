package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalKeyMaterialTest {

    @TempDir Path dir;

    @Test
    void configuredKeyWinsAndDoesNotTouchTheFile() {
        Path file = dir.resolve("demo-crypto.key");
        String resolved = LocalKeyMaterial.resolve("  from-env  ", file);
        assertEquals("from-env", resolved);
        assertFalse(Files.exists(file));
    }

    @Test
    void blankKeyIsGeneratedOnceAndReused() throws Exception {
        Path file = dir.resolve("nested/demo-crypto.key");
        String first = LocalKeyMaterial.resolve("  ", file);
        String second = LocalKeyMaterial.resolve(null, file);
        assertFalse(first.isBlank());
        assertEquals(first, second);
        assertTrue(Files.readString(file).contains(first));
    }
}
