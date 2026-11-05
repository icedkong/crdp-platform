package com.crdp.system.infra.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 租户 Webhook 配置 sys_webhook（E09-T06 / A-NTF-008、C-NTF-008）。调用前必须已在租户上下文（租户库表）。 secret 只存密文； 订阅中的
 * Webhook 由 {@code enabled + FIND_IN_SET(event, events)} 圈定。
 */
@Mapper
public interface WebhookMapper {

    String COLUMNS =
            "id, name, url, secret_cipher AS secretCipher, events, enabled,"
                    + " last_delivery_at AS lastDeliveryAt, created_by AS createdBy,"
                    + " updated_by AS updatedBy, updated_at AS updatedAt, created_at AS createdAt";

    @Select("SELECT " + COLUMNS + " FROM sys_webhook ORDER BY id")
    List<WebhookRow> listAll();

    @Select("SELECT " + COLUMNS + " FROM sys_webhook WHERE id = #{id}")
    WebhookRow find(@Param("id") long id);

    /** 订阅某事件且启用的 Webhook（outbox 订阅方入口）。 */
    @Select(
            """
            SELECT id, name, url, secret_cipher AS secretCipher, events, enabled,
                   last_delivery_at AS lastDeliveryAt, created_by AS createdBy,
                   updated_by AS updatedBy, updated_at AS updatedAt, created_at AS createdAt
            FROM sys_webhook
            WHERE enabled = 1 AND FIND_IN_SET(#{event}, events) > 0
            ORDER BY id
            """)
    List<WebhookRow> listEnabledSubscribed(@Param("event") String event);

    @Insert(
            """
            INSERT INTO sys_webhook (name, url, secret_cipher, events, enabled, created_by, updated_by)
            VALUES (#{name}, #{url}, #{secretCipher}, #{events}, #{enabled}, #{createdBy}, #{updatedBy})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(WebhookInsert row);

    @Update(
            """
            UPDATE sys_webhook
            SET name = #{name}, url = #{url}, secret_cipher = #{secretCipher}, events = #{events},
                enabled = #{enabled}, updated_by = #{updatedBy}
            WHERE id = #{id}
            """)
    int update(WebhookRow row);

    @Delete("DELETE FROM sys_webhook WHERE id = #{id}")
    int delete(@Param("id") long id);

    @Update("UPDATE sys_webhook SET last_delivery_at = #{at} WHERE id = #{id}")
    int touchDelivery(@Param("id") long id, @Param("at") LocalDateTime at);
}
