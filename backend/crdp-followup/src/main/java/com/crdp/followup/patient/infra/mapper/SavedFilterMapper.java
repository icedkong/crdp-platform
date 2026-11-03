package com.crdp.followup.patient.infra.mapper;

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
 * 个人保存的筛选（E13-T06 / A-PAT-015；表 fu_saved_filter，followup V42）。
 *
 * <p>仅本人可见（A-PAT-015）：全部语句都带 {@code user_id} 条件，本人的筛选对其他用户不可见也不可改。 个人配置不含患者数据（payload
 * 只有筛选面与条件树，检索词不落库），不在受控表清单，无 @DataScope。
 */
@Mapper
public interface SavedFilterMapper {

    /** 行（payload 为原始 JSON 文本，由服务层解析后下发）。 */
    record SavedFilterRow(
            Long id,
            Long userId,
            String name,
            String payload,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    /** 插入参数（id 回填）。 */
    class SavedFilterInsert {
        private Long id;
        private final long userId;
        private final String name;
        private final String payload;

        public SavedFilterInsert(long userId, String name, String payload) {
            this.userId = userId;
            this.name = name;
            this.payload = payload;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getUserId() {
            return userId;
        }

        public String getName() {
            return name;
        }

        public String getPayload() {
            return payload;
        }
    }

    /** 本人的筛选（新在前）。 */
    @Select(
            """
            SELECT id, user_id AS userId, name, payload, created_at AS createdAt,
                   updated_at AS updatedAt
            FROM fu_saved_filter WHERE user_id = #{userId} ORDER BY id DESC
            """)
    List<SavedFilterRow> findByUser(@Param("userId") long userId);

    /** 按 id 取本人的筛选（他人 / 不存在返回 null，调用方按 40400 处理）。 */
    @Select(
            """
            SELECT id, user_id AS userId, name, payload, created_at AS createdAt,
                   updated_at AS updatedAt
            FROM fu_saved_filter WHERE id = #{id} AND user_id = #{userId}
            """)
    SavedFilterRow findOwn(@Param("id") long id, @Param("userId") long userId);

    /** 新建（id 回填到 insert.id）。 */
    @Insert(
            """
            INSERT INTO fu_saved_filter (user_id, name, payload, created_by, updated_by)
            VALUES (#{userId}, #{name}, #{payload}, #{userId}, #{userId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SavedFilterInsert insert);

    /** 修改（重命名 / 覆盖条件；只动本人的行）。 */
    @Update(
            """
            UPDATE fu_saved_filter SET name = #{name}, payload = #{payload}, updated_by = #{userId}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateOwn(
            @Param("id") long id,
            @Param("userId") long userId,
            @Param("name") String name,
            @Param("payload") String payload);

    /** 删除（只删本人的行）。 */
    @Delete("DELETE FROM fu_saved_filter WHERE id = #{id} AND user_id = #{userId}")
    int deleteOwn(@Param("id") long id, @Param("userId") long userId);

    /** 本人筛选数（上限校验）。 */
    @Select("SELECT COUNT(*) FROM fu_saved_filter WHERE user_id = #{userId}")
    long countByUser(@Param("userId") long userId);
}
