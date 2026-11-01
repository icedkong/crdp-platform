package com.crdp.system.infra.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** E06-T11 / PLT-CFG-008：租户个性化（cfg_brand，单行 id=1）。调用前必须已在租户上下文。 */
@Mapper
public interface BrandMapper {

    /** 读当前配置行；未配置返回 null（各字段的生效值由服务层回落平台默认）。 */
    @Select(
            """
            SELECT display_name AS displayName, logo_url AS logoUrl,
                   login_bg_url AS loginBgUrl, theme_color AS themeColor,
                   copyright, updated_by AS updatedBy, updated_at AS updatedAt
            FROM cfg_brand
            WHERE id = 1
            """)
    BrandRow find();

    /** 首次保存插入配置行（应用层固定 id=1，单行表）。 */
    @Insert(
            """
            INSERT INTO cfg_brand
              (id, display_name, logo_url, login_bg_url, theme_color, copyright, updated_by)
            VALUES (1, #{displayName}, #{logoUrl}, #{loginBgUrl}, #{themeColor}, #{copyright}, #{updatedBy})
            """)
    int insert(BrandRow row);

    /** 后续保存覆盖各字段（素材清空 = 传 NULL）。 */
    @Update(
            """
            UPDATE cfg_brand
            SET display_name = #{displayName}, logo_url = #{logoUrl},
                login_bg_url = #{loginBgUrl}, theme_color = #{themeColor},
                copyright = #{copyright}, updated_by = #{updatedBy}
            WHERE id = 1
            """)
    int update(BrandRow row);

    /** 登录页取显示名称（未配置返回 null，调用方回落平台租户名称）。 */
    @Select("SELECT display_name FROM cfg_brand WHERE id = 1")
    String displayName();
}
