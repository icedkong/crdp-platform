package com.crdp.framework.crypto;

import java.util.regex.Pattern;

/**
 * 一个需要重加密作业巡检的加密列（E03-T05）。表与列名只来自代码内注册的 {@link EncryptedColumnProvider}，不接受外部输入；标识符校验是纵深防御。
 * 盲索引列与密文列成对：重写密文时同步重算盲索引（NFR-CRY-002 批量重加密、重算盲索引）。
 *
 * @param tableName 业务表名
 * @param idColumn 主键列名（作业按它升序分批推进游标）
 * @param valueColumn 密文列名（{@link Ciphertext} 自描述格式）
 * @param cipherPurpose 密文列的数据密钥用途
 * @param blindIndexColumn 盲索引列名，无则 null
 * @param blindIndexPurpose 盲索引的数据密钥用途，无盲索引时为 null
 */
public record EncryptedColumn(
        String tableName,
        String idColumn,
        String valueColumn,
        String cipherPurpose,
        String blindIndexColumn,
        String blindIndexPurpose) {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    public EncryptedColumn {
        requireIdentifier("tableName", tableName);
        requireIdentifier("idColumn", idColumn);
        requireIdentifier("valueColumn", valueColumn);
        requireIdentifier("blindIndexColumn", blindIndexColumn);
        if ((blindIndexColumn == null) != (blindIndexPurpose == null)) {
            throw new IllegalArgumentException("盲索引列与用途必须成对出现");
        }
    }

    public boolean hasBlindIndex() {
        return blindIndexColumn != null;
    }

    private static void requireIdentifier(String field, String value) {
        if (value != null && !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("加密列定义的 " + field + " 不是合法标识符: " + value);
        }
    }
}
