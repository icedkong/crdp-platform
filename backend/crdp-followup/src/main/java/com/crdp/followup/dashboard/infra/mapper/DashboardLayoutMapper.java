package com.crdp.followup.dashboard.infra.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 首页卡片目录与布局取数（E19-T06 / A-DASH-013、A-DASH-014；docs/api/system.md §5、stats.md §2 /
 * §3）。三张配置表（followup V77）都是租户内普通配置，不在 {@code ControlledTables}——卡片配置只决定 显示与顺序，不放宽数据权限（A 规格 §9
 * 业务规则：每张卡片照常受数据权限与病区隔离限制），各卡片取数仍由 自己的 Mapper 语句 {@code @DataScope} 出行。
 *
 * <p>即时生效（tasks.csv E19-T06 验收「按角色配置后即生效」）：读取路径每次实时查库，服务层不做缓存；本 Mapper 全部为直查直写。
 */
@Mapper
public interface DashboardLayoutMapper {

    /** 目录卡片行（status=ENABLED，default_order 升序；固定清单由迁移落库，无运行期增删改）。 */
    record CardRow(
            String cardCode,
            String title,
            String width,
            boolean defaultVisible,
            int defaultOrder,
            String permissionCode,
            String featureCode,
            boolean allowUserHide) {}

    /** 角色布局行（role_id + card_code 唯一；sort_order 角色内从 1 起）。 */
    record RoleLayoutRow(
            long roleId, String cardCode, boolean visible, int sortOrder, boolean allowUserHide) {}

    /** 角色候选行（配置页下拉；内置 / 模板 / 自定义都可配置——生效只看用户是否持有）。 */
    record RoleOptionRow(long id, String code, String name) {}

    /** 用户隐藏行（表内只存隐藏行；hidden 恒 1）。 */
    record UserPrefRow(long userId, String cardCode) {}

    // ---------------------------------------------------------------- 目录与角色布局

    /** 全部启用卡片（default_order 升序）。 */
    @Select(
            """
            SELECT card_code, title, width, default_visible, default_order,
                   permission_code, feature_code, allow_user_hide
            FROM dash_card
            WHERE status = 'ENABLED'
            ORDER BY default_order
            """)
    List<CardRow> findCards();

    /** 单个角色（配置页保存时校验存在性，40400 口径）。 */
    @Select("SELECT id FROM sys_role WHERE id = #{roleId}")
    Long findRoleId(@Param("roleId") long roleId);

    /** 会话角色编码 → 角色 id（只取启用角色，id 升序）：多角色合并的「按角色排序」取创建序（sys_role 无排序列， 暂行口径，OQ-111）。 */
    @Select(
            """
            <script>
            SELECT id FROM sys_role
            WHERE status = 'ENABLED' AND code IN
              <foreach collection="codes" item="c" open="(" separator="," close=")">#{c}</foreach>
            ORDER BY id
            </script>
            """)
    List<Long> findRoleIdsByCodes(@Param("codes") List<String> codes);

    /** 角色候选（配置页选择角色；id 升序 = 创建序，多角色合并时「第一个已配置布局的角色」同序）。 */
    @Select("SELECT id, code, name FROM sys_role WHERE status = 'ENABLED' ORDER BY id")
    List<RoleOptionRow> findRoleOptions();

    /** 某角色全部布局行（sort_order 升序）。 */
    @Select(
            """
            SELECT role_id, card_code, visible, sort_order, allow_user_hide
            FROM dash_role_layout
            WHERE role_id = #{roleId}
            ORDER BY sort_order
            """)
    List<RoleLayoutRow> findRoleLayout(@Param("roleId") long roleId);

    /** 多个角色的布局行（当前用户多角色合并：并集可见 + 第一个已配置角色定序，A 规格 §9）。 */
    @Select(
            """
            <script>
            SELECT role_id, card_code, visible, sort_order, allow_user_hide
            FROM dash_role_layout
            WHERE role_id IN
              <foreach collection="roleIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            ORDER BY role_id, sort_order
            </script>
            """)
    List<RoleLayoutRow> findRoleLayouts(@Param("roleIds") List<Long> roleIds);

    /** 保存前清空该角色旧行（PUT 全量覆盖）。 */
    @Delete("DELETE FROM dash_role_layout WHERE role_id = #{roleId}")
    int deleteRoleLayout(@Param("roleId") long roleId);

    /** 写入一行（服务层逐行插入）。 */
    @Insert(
            """
            INSERT INTO dash_role_layout
              (role_id, card_code, visible, sort_order, allow_user_hide, updated_by)
            VALUES
              (#{roleId}, #{cardCode}, #{visible}, #{sortOrder}, #{allowUserHide}, #{updatedBy})
            """)
    int insertRoleLayoutRow(
            @Param("roleId") long roleId,
            @Param("cardCode") String cardCode,
            @Param("visible") boolean visible,
            @Param("sortOrder") int sortOrder,
            @Param("allowUserHide") boolean allowUserHide,
            @Param("updatedBy") long updatedBy);

    // ---------------------------------------------------------------- 用户隐藏偏好（A-DASH-014，P1）

    /** 某用户全部隐藏行。 */
    @Select("SELECT user_id, card_code FROM dash_user_pref WHERE user_id = #{userId}")
    List<UserPrefRow> findUserPrefs(@Param("userId") long userId);

    /** 保存前清空该用户全部隐藏行（PUT 全量覆盖；空清单 = 恢复角色默认）。 */
    @Delete("DELETE FROM dash_user_pref WHERE user_id = #{userId}")
    int deleteUserPrefs(@Param("userId") long userId);

    /** 写入一行隐藏偏好。 */
    @Insert(
            """
            INSERT INTO dash_user_pref (user_id, card_code, hidden)
            VALUES (#{userId}, #{cardCode}, 1)
            """)
    int insertUserPrefRow(@Param("userId") long userId, @Param("cardCode") String cardCode);
}
