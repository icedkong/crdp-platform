package com.crdp.framework.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** E03-T03：敏感字段经 TypeHandler 入库为密文、读出为明文，业务代码无感。 */
class CryptoTypeHandlerTest {

    /** 测试用具体子类：用途由子类决定。 */
    static class FieldNameHandler extends CryptoTypeHandler {
        @Override
        protected String purpose() {
            return "field-name";
        }
    }

    private final CryptoService cryptoService = Mockito.mock(CryptoService.class);
    private final FieldNameHandler handler = new FieldNameHandler();

    @AfterEach
    void reset() {
        CryptoTypeHandler.configure(null);
    }

    @Test
    void writesTheEncryptedValueOnInsert() throws Exception {
        CryptoTypeHandler.configure(cryptoService);
        PreparedStatement statement = Mockito.mock(PreparedStatement.class);
        when(cryptoService.encrypt("field-name", "张医生（示例）"))
                .thenReturn("v1.AES-256-GCM.1.field-name.iv.data");

        handler.setNonNullParameter(statement, 3, "张医生（示例）", JdbcType.VARCHAR);

        verify(cryptoService).encrypt("field-name", "张医生（示例）");
        verify(statement).setString(3, "v1.AES-256-GCM.1.field-name.iv.data");
    }

    @Test
    void readsThePlaintextBackFromTheCiphertextColumn() throws Exception {
        CryptoTypeHandler.configure(cryptoService);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        when(resultSet.getString("display_name")).thenReturn("v1.AES-256-GCM.1.field-name.iv.data");
        when(cryptoService.decrypt("v1.AES-256-GCM.1.field-name.iv.data")).thenReturn("张医生（示例）");

        assertEquals("张医生（示例）", handler.getNullableResult(resultSet, "display_name"));
    }

    @Test
    void nullColumnStaysNullWithoutTouchingTheCryptoService() throws Exception {
        CryptoTypeHandler.configure(cryptoService);
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        when(resultSet.getString("display_name")).thenReturn(null);

        assertEquals(null, handler.getNullableResult(resultSet, "display_name"));
        Mockito.verifyNoInteractions(cryptoService);
    }

    @Test
    void failsFastWhenTheServiceIsNotConfigured() throws Exception {
        ResultSet resultSet = Mockito.mock(ResultSet.class);
        when(resultSet.getString("display_name")).thenReturn("v1.AES-256-GCM.1.field-name.iv.data");

        assertThrows(
                IllegalStateException.class,
                () -> handler.getNullableResult(resultSet, "display_name"),
                "未配置 CryptoService 时应明确报错，而不是空指针");
    }
}
