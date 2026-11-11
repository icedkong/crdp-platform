package com.crdp.server.platform;

import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.platform.infra.mapper.PlatformMapper;
import com.crdp.platform.resource.ResourceChangeRow;
import org.springframework.stereotype.Service;

/**
 * 资源变更记录写入（E05-T10 / PLT-PERM-012，P2）：菜单、接口资源发生变更时向 plt_resource_change_log 追加一行，
 * 供平台端「资源变更记录」查询与导出。与审计（安全追溯、哈希链）互补：本表是资源维度的可查变更流水。
 *
 * <p>动作口径：CREATE 新建 / UPDATE 维护或登记变化 / DELETE 删除或清除 / OFFLINE 清单下线（软停用）/ REGISTER 接口首次登记。渠道：PAGE
 * 平台端页面操作；SYNC 清单同步或接口扫描（含页面手动触发的同步——变更都来自代码清单）。 操作者取当前会话（启动扫描无会话时为空，页面显示「系统」）。
 */
@Service
public class ResourceChangeRecorder {

    /** 资源类型：菜单（plt_menu）。 */
    public static final String TYPE_MENU = "MENU";

    /** 资源类型：接口（plt_api_resource）。 */
    public static final String TYPE_API = "API";

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_OFFLINE = "OFFLINE";
    public static final String ACTION_REGISTER = "REGISTER";

    public static final String CHANNEL_PAGE = "PAGE";
    public static final String CHANNEL_SYNC = "SYNC";

    private final PlatformMapper platformMapper;

    public ResourceChangeRecorder(PlatformMapper platformMapper) {
        this.platformMapper = platformMapper;
    }

    /**
     * 追加一条变更记录（只追加，不抛业务语义外的影响——写入失败随所在数据操作一起失败回滚）。
     *
     * @param resourceType MENU / API（FIELD 随字段资源管理接口落地时接入）
     * @param resourceId 资源行 id（菜单 / 接口资源主键；下线或清除后仍保留原值供追溯）
     * @param resourceName 展示名：菜单标题；接口「方法 路径模式」
     * @param resourceIdent 稳定标识：菜单路由名或按钮权限码；接口路径模式
     * @param action CREATE / UPDATE / DELETE / OFFLINE / REGISTER
     * @param channel PAGE / SYNC
     * @param detail 变更摘要（≤512 字）
     */
    public void record(
            String resourceType,
            Long resourceId,
            String resourceName,
            String resourceIdent,
            String action,
            String channel,
            String detail) {
        ResourceChangeRow row = new ResourceChangeRow();
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setResourceName(truncate(resourceName, 128));
        row.setResourceIdent(truncate(resourceIdent, 128));
        row.setAction(action);
        row.setChannel(channel);
        row.setDetail(truncate(detail, 512));
        CurrentSession.current()
                .ifPresent(
                        principal -> {
                            row.setOperatorId(principal.userId());
                            row.setOperatorName(operatorText(principal));
                        });
        TenantContext.callAsPlatform(() -> platformMapper.insertResourceChange(row));
    }

    /** 操作人快照：姓名（账号）。 */
    private static String operatorText(SessionPrincipal principal) {
        String name = principal.displayName();
        String username = principal.username();
        if (name == null || name.isBlank()) {
            return username;
        }
        return name + "（" + username + "）";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
