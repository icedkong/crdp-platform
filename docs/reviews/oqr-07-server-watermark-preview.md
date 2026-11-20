# OQR-07：服务器水印预览实施记录

日期：2026-11-19。分支：`codex/oqr-07-server-watermark-preview`。关联 OQ-127、E10-T04、E16-T06、A-ATT-005、C-SCAN-018。

## 已实现

图片重绘成带身份水印的 PNG，PDF 栅格化并重建为图像页 PDF，DOCX 在本机无网络隔离进程转换后按 PDF 同样处理。水印烧录进像素，前端页面叠加层继续保留；服务器副本不携带原 PDF 的文本层、脚本、附件或原元数据。转换不可用、超时、损坏 / 加密 / 超预算时返回 50301，绝不回退原件。

预览授权 v2 绑定租户、用户、登录会话、文件对象键 / SHA-256 / 大小 / 格式和水印身份，最长 300 秒，Redis 仅以随机令牌摘要为键，旧授权拒绝。取流必须登录，前后两次重载当前会话、最新权限 / 范围及文件状态；撤权、注销、跨会话 / 账号 / 租户、删除 / 改版后不交付。已知 INFECTED / HASH_MISMATCH 不能预览；生产扫描默认值、补扫和下载异常状态收紧由 OQR-08 接续。

前端 API 统一携带令牌取 Blob；缩略图、对照面板、全屏查看器与音频使用临时内存地址。到期、退出 / 切换账号、收到权限版本变化及关闭组件清理，迟到响应不恢复旧内容；DOCX 原件不再交给浏览器解析。PDF 渲染 DOM 与 Vue 状态节点分离，防止清理画布破坏错误提示。

原件下载保留已有独立 `followup:scan:download` 权限和签名链路；音频鉴权播放并审计，不承诺不可复制。无数据库变更、无新权限码、无 framework 接口变更。

## 安全与审计

- 数据范围：沿用报告单 / 沟通录音的患者范围守卫；未归档仅本人上传且有上传权限可看。越范围 / 身份不匹配 / 不存在均 40400，已在双租户真实 HTTP 用例覆盖。
- 审计：保留 `aud_log.FILE_PREVIEW` / `FILE_PREVIEW_DENIED`；实际取流另写 `aud_data_access_log.FILE_PREVIEW_CONTENT` / `FILE_PREVIEW_CONTENT_DENIED`，正常重试分别记录。对象仅文件 ID、描述固定，不写文件名、令牌、水印正文或患者明文。
- 隔离：固定本机启动器、私有临时目录、无网络、只读运行时、两并发及页数 / 像素 / 内存 / 时间 / 文件预算；不向外部转换服务发送资料，无持久预览缓存。生产 Linux 用 bubblewrap，本地 / CI 用无主机挂载 Docker。
- 本地审查修正：捕获大于 127 的租户 ID 装箱比较问题；转换后再次复核授权；补充关闭 / 切换中的响应保护和缩略图清屏；通过数据库断言保留签发审计并新增两次取流审计。

## 验证

| 验证 | 本地结果 |
|---|---|
| 文件专项：Service / Controller / Grant / Access / Renderer / RasterWorker / Download 兼容 + HTTP MySQL / Redis | 74 项通过；其中 ServerWatermarkPreviewIT 7 项、FileServiceAcceptanceIT 5 项。新增取流审计断言后后者再次 5 项通过 |
| PreviewSandboxIT：真实无网络容器转换 PNG / PDF / DOCX 和坏 DOCX 失败关闭 | 3 项通过；中文示例输出已目视核对，DOCX PDF 另用 pdftoppm 渲染核对 |
| 后端 `spotless:check test` 全量（未设置 MySQL / sandbox 门控变量） | 3573 项：2705 执行通过，868 门控跳过，0 失败 / 错误 |
| 前端 `pnpm test` | 236 文件 / 1893 项通过，无未处理错误 |
| Flyway 版本校验、shell 语法、`git diff --check` | 通过；没有新增或修改迁移 |
| 前端格式、lint 与三端 build | 本地通过，构建仍有既有大包提示 |

CI 新增中文字体和隔离镜像构建，真实 worker 测试通过环境变量纳入 CI；尚未推送，不能声称 CI 已绿。临时验证日志位于本机 `/tmp/crdp-oqr07-*.log`，不含作为交付内容的真实患者资料；示例产物在忽略的 target 目录，不提交二进制。

## 部署边界与下一项

[ADR 0008](../adr/0008-server-watermark-preview.md) 和 [隔离部署手册](../ops/preview-renderer.md) 已同步。未执行真实云操作，生产 bubblewrap / namespace / 字体 / Office 版式 / cgroup 容量仍须持账号运维实测留证（OQR-10）。默认生产启动器不存在时预览失败关闭；须前后端与转换环境一同发布。

已交付内容不能远程回收，水印不是 DRM；PDF 栅格化会丢失可搜索文本并带来资源开销。音频仍为鉴权原格式播放。下一项为 **OQR-08：生产扫描与异常文件访问**；OQR-09 自动入组开关及 OQR-10 部署验收证据继续待办。
