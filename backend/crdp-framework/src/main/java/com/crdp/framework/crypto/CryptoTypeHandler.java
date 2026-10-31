package com.crdp.framework.crypto;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * 敏感字段的透明加解密（技术方案第 4 章：MyBatis TypeHandler 负责透明加解密）。写入时加密、读取时 解密，业务实体与 SQL
 * 只接触明文。用途（决定使用哪把数据密钥）由具体子类返回——患者姓名、病案号、 电话等生产子类随 E13 患者实体提供，用法示例：{@code @TableField(typeHandler =
 * PatientNameHandler.class)} 加 {@code @TableName(autoResultMap = true)}}。子类必须是 public 的（MyBatis
 * 反射只取公共无参构造器）。 MyBatis 以反射实例化 TypeHandler、不走 Spring 容器，因此 {@link CryptoService} 经静态 {@link
 * #configure} 注入（由 CryptoConfiguration 完成）。
 */
public abstract class CryptoTypeHandler extends BaseTypeHandler<String> {

    private static volatile CryptoService cryptoService;

    /** 由 Spring 配置调用；测试收尾时传 null 复位。 */
    public static void configure(CryptoService service) {
        cryptoService = service;
    }

    /** 该字段使用的数据密钥用途，如 field-name。 */
    protected abstract String purpose();

    @Override
    public void setNonNullParameter(
            PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, require().encrypt(purpose(), parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    private String decrypt(String stored) {
        return stored == null ? null : require().decrypt(stored);
    }

    private static CryptoService require() {
        CryptoService service = cryptoService;
        if (service == null) {
            throw new IllegalStateException("CryptoTypeHandler 尚未配置 CryptoService");
        }
        return service;
    }
}
