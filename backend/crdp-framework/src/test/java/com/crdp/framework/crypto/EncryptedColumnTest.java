package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** E03-T05/E03-T06：加密列登记的成对与标识符约束（表名列名只来自代码内注册，校验是纵深防御）。 */
class EncryptedColumnTest {

    @Test
    void cipherOnlyColumnIsAllowed() {
        EncryptedColumn column =
                new EncryptedColumn("it_patient", "id", "name_cipher", "field-name", null, null);

        assertFalse(column.hasBlindIndex());
    }

    @Test
    void cipherWithBlindIndexPairIsAllowed() {
        EncryptedColumn column =
                new EncryptedColumn(
                        "it_patient", "id", "name_cipher", "field-name", "name_bi", "idx-name");

        assertTrue(column.hasBlindIndex());
        assertEquals("idx-name", column.blindIndexPurpose());
    }

    @Test
    void blindIndexColumnAndPurposeMustAppearTogether() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EncryptedColumn(
                                "it_patient", "id", "name_cipher", "field-name", "name_bi", null),
                "有盲索引列却没有用途，无法选钥");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EncryptedColumn(
                                "it_patient", "id", "name_cipher", "field-name", null, "idx-name"),
                "有用途却没有盲索引列，语义不完整");
    }

    @Test
    void everyIdentifierIsValidated() {
        assertRejects("it patient", "id", "name_cipher");
        assertRejects("it_patient", "id; DROP TABLE it_patient", "name_cipher");
        assertRejects("it_patient", "id", "name-cipher");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EncryptedColumn(
                                "it_patient",
                                "id",
                                "name_cipher",
                                "field-name",
                                "name bi",
                                "idx-name"),
                "盲索引列同样必须是合法标识符");
    }

    private static void assertRejects(String tableName, String idColumn, String valueColumn) {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EncryptedColumn(
                                tableName, idColumn, valueColumn, "field-name", null, null));
    }
}
