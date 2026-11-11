package com.crdp.platform.infra.mapper;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * E05-T11 / PLT-CFG-009：系统公告（平台库 plt_announcement / plt_announcement_tenant）。
 *
 * <p>scope=ALL 面向全部租户（不落关联行）；scope=TENANTS 只面向 {@code plt_announcement_tenant} 登记的租户。状态只有
 * PUBLISHED（发布即生效）与 REVOKED（撤下，医院端即刻不可见）。 调用前必须已在平台上下文（TenantContext.callAsPlatform）。
 */
@Mapper
public interface PlatformAnnouncementMapper {

    // --------------------------------------------------------------- 平台端维护

    /** 单条公告。 */
    @Select(
            """
            SELECT id, title, content, scope, status, published_at AS publishedAt,
                   published_by AS publishedBy, published_by_name AS publishedByName,
                   revoked_at AS revokedAt
            FROM plt_announcement WHERE id = #{id}
            """)
    AnnouncementRow find(@Param("id") long id);

    /** 分页计数（keyword 按标题模糊，null = 不过滤）。 */
    @Select(
            """
            <script>
            SELECT COUNT(*) FROM plt_announcement
            <where>
              <if test="keyword != null">title LIKE CONCAT('%', #{keyword}, '%')</if>
            </where>
            </script>
            """)
    long count(@Param("keyword") String keyword);

    /** 公告分页（新→旧；keyword 按标题模糊）。 */
    @Select(
            """
            <script>
            SELECT id, title, content, scope, status, published_at AS publishedAt,
                   published_by AS publishedBy, published_by_name AS publishedByName,
                   revoked_at AS revokedAt
            FROM plt_announcement
            <where>
              <if test="keyword != null">title LIKE CONCAT('%', #{keyword}, '%')</if>
            </where>
            ORDER BY published_at DESC, id DESC
            LIMIT #{offset}, #{size}
            </script>
            """)
    List<AnnouncementRow> page(
            @Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);

    /** 发布（status=PUBLISHED，发布即生效）。 */
    @Insert(
            """
            INSERT INTO plt_announcement
              (title, content, scope, status, published_at, published_by, published_by_name)
            VALUES
              (#{title}, #{content}, #{scope}, #{status}, #{publishedAt}, #{publishedBy},
               #{publishedByName})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnnouncementRow row);

    /** 撤下（条件更新：仅 PUBLISHED 行命中；命中 0 行说明已撤下，由服务层判定冲突）。 */
    @Update(
            """
            UPDATE plt_announcement
            SET status = 'REVOKED', revoked_at = #{revokedAt}, revoked_by = #{revokedBy}
            WHERE id = #{id} AND status = 'PUBLISHED'
            """)
    int revoke(
            @Param("id") long id,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("revokedBy") Long revokedBy);

    /** scope=TENANTS 的定向关联（发布时一次性写入）。 */
    @Insert(
            """
            <script>
            INSERT INTO plt_announcement_tenant (announcement_id, tenant_id) VALUES
            <foreach collection='tenantIds' item='tenantId' separator=','>
              (#{announcementId}, #{tenantId})
            </foreach>
            </script>
            """)
    int insertTenants(
            @Param("announcementId") long announcementId,
            @Param("tenantIds") Collection<Long> tenantIds);

    /** 公告 → 租户关联（带租户编码与名称，平台端视图拼「指定租户」名单用）。 */
    @Select(
            """
            <script>
            SELECT t.announcement_id AS announcementId, t.tenant_id AS tenantId,
                   te.code AS tenantCode, te.name AS tenantName
            FROM plt_announcement_tenant t
            JOIN plt_tenant te ON te.id = t.tenant_id
            WHERE t.announcement_id IN
            (<foreach collection='announcementIds' item='announcementId' separator=','>
              #{announcementId}
            </foreach>)
            ORDER BY t.announcement_id, te.code
            </script>
            """)
    List<TenantLinkRow> findTenantsOf(@Param("announcementIds") Collection<Long> announcementIds);

    /** 校验指定租户存在（发布定向公告用；不限状态——停用租户恢复后即可见）。 */
    @Select(
            """
            <script>
            SELECT id, code, name FROM plt_tenant WHERE id IN
            (<foreach collection='ids' item='id' separator=','>#{id}</foreach>)
            </script>
            """)
    List<TenantRow> findTenants(@Param("ids") Collection<Long> ids);

    // --------------------------------------------------------------- 医院端读取

    /** 当前租户可见的生效公告（PUBLISHED 且 scope=ALL 或定向命中；新→旧，上限 50 条）。 */
    @Select(
            """
            SELECT a.id, a.title, a.content, a.published_at AS publishedAt
            FROM plt_announcement a
            WHERE a.status = 'PUBLISHED'
              AND (a.scope = 'ALL'
                   OR EXISTS (SELECT 1 FROM plt_announcement_tenant t
                              WHERE t.announcement_id = a.id AND t.tenant_id = #{tenantId}))
            ORDER BY a.published_at DESC, a.id DESC
            LIMIT 50
            """)
    List<TenantAnnouncementRow> listForTenant(@Param("tenantId") long tenantId);

    // ---------------------------------------------------------------- 行对象

    /** plt_announcement 行。 */
    class AnnouncementRow {
        private Long id;
        private String title;
        private String content;
        private String scope;
        private String status;
        private LocalDateTime publishedAt;
        private Long publishedBy;
        private String publishedByName;
        private LocalDateTime revokedAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public LocalDateTime getPublishedAt() {
            return publishedAt;
        }

        public void setPublishedAt(LocalDateTime publishedAt) {
            this.publishedAt = publishedAt;
        }

        public Long getPublishedBy() {
            return publishedBy;
        }

        public void setPublishedBy(Long publishedBy) {
            this.publishedBy = publishedBy;
        }

        public String getPublishedByName() {
            return publishedByName;
        }

        public void setPublishedByName(String publishedByName) {
            this.publishedByName = publishedByName;
        }

        public LocalDateTime getRevokedAt() {
            return revokedAt;
        }

        public void setRevokedAt(LocalDateTime revokedAt) {
            this.revokedAt = revokedAt;
        }
    }

    /** 公告 → 租户关联行（带名称）。 */
    record TenantLinkRow(
            long announcementId, long tenantId, String tenantCode, String tenantName) {}

    /** plt_tenant 精简行（定向校验与名单用）。 */
    record TenantRow(long id, String code, String name) {}

    /** 医院端公告行。 */
    record TenantAnnouncementRow(
            long id, String title, String content, LocalDateTime publishedAt) {}
}
