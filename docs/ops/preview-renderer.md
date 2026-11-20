# 隔离预览渲染部署手册（OQR-07）

本手册是部署草稿。编码代理只完成本地示例验证，生产操作和验收由持账号运维执行；本轮未变更云资源。设计见 [ADR 0008](../adr/0008-server-watermark-preview.md)，接口见 [report.md](../api/report.md)。

## 构建与运行边界

在 JDK 21 / Maven 环境运行 `scripts/preview/build-worker.sh`，输出 `backend/crdp-followup/target/preview-image/worker/`（仅 worker 类与 PDFBox 依赖，无应用配置）。CI 镜像构建：

```bash
docker build -t crdp-preview-renderer:oqr07 backend/crdp-followup/target/preview-image
```

构建阶段允许官方软件源联网；转换阶段必须禁网。依赖 LibreOffice Writer、Noto CJK、fontconfig、JRE 21。正式发布须固定镜像摘要和软件包版本并纳入漏洞更新，不把本地标签当生产凭证。

macOS / CI 的 `scripts/preview/docker-launcher.sh` 只将 input.bin 与 watermark.txt 用 tar 标准输入交给容器，不挂载主机目录。容器非 root、只读根、无网络、无 capabilities、禁提权、512 MiB / 1 CPU / 128 进程，512 MiB 临时 tmpfs。后端本地配置 `crdp.file.preview.renderer-launcher` 为该脚本的绝对路径。该启动器要求 Docker 权限，只用于可信开发 / CI，不能授予生产 Web 进程 Docker socket。

## 生产 Linux 安装

运维把 worker/classes 与 worker/lib 分别装入 `/opt/crdp-preview/classes` 和 `/opt/crdp-preview/lib`，将 `scripts/preview/preview-sandbox` 安装为 `/opt/crdp-preview/bin/preview-sandbox`。目录和启动器由 root 拥有、应用不可写，启动器 0555；不在该目录放账号、密钥或应用配置。配置默认已指向该路径。

Linux 必须提供 `/usr/bin/bwrap`、`/usr/bin/java`、LibreOffice、字体及脚本列出的只读系统运行目录。启用受限的非特权 user namespace，并验证 AppArmor / seccomp / CCE 策略允许本启动器创建隔离域。脚本显式要求 user namespace；不支持时直接失败，不使用特权模式、host network 或原件兜底。应用以非 root 运行，worker 只可见只读系统运行时和本次固定输入，输出仅能写本次 output 目录。

建议应用私有临时目录使用有容量限制的 tmpfs，非共享可读目录，禁止把临时数据收集到日志 / 崩溃上传 / 备份。父进程创建 0700 工作目录，结束清理；主机重启和进程强杀后的残留由运维按目录前缀及存活进程核验清理，清理不输出文件内容。

## 资源和失败行为

每应用实例两并发；输入 / 输出各 64 MiB，每页最多 1200 万像素、总计一亿像素、50 页、144 DPI。子 JVM 堆 256 MiB、40 秒超时，生产启动器另加 CPU 35 秒、文件 64 MiB、fd / 进程上限 128。应用默认等待 45 秒（配置限制在 1–60 秒），超时杀进程并清理。部署还应使用 cgroup / 容器对应用及全部转换子进程设总内存、CPU 和 pids 上限；bubblewrap 本身不提供总内存配额。

启动器、隔离能力、字体或转换器缺失、并发占满、格式异常、加密文档及超预算均返回 50301。不得配置裸 Java / LibreOffice 作为降级启动器，不得改回返回原件。对外错误不包含路径、命令、原文或患者信息；容量与失败率监控只记录汇总值。授权最长 300 秒，不使用公开对象存储链接；前端必须与后端同步发布，以支持鉴权 Blob 获取。历史 v1 预览授权自动拒绝，重新打开即可签发 v2，无数据库迁移。

## 放行证据

运维在目标 Linux / CCE 环境逐项留环境、版本、镜像摘要、时间及结果，不纳入真实患者文件：

1. 中文示例 JPG / PNG / PDF / DOCX 都能预览，实际下载预览响应确认水印烧入像素；DOCX 对照版式、分页、中文字体与医生账号 / 医院 / 分钟时间完整。
2. 匿名、跨账号 / 会话 / 租户均不能取流；撤权 / 收窄范围 / 注销、过期、文件替换 / 删除后拒绝；转换期间撤权不得返回副本。
3. 隔离进程看不到外网、应用配置和其他请求文件；namespace 不可用、转换损坏 / 超时、并发满时失败关闭，无原件回退。
4. 核验 cgroup 总资源上限，使用带“示例”标记的临界页数 / 大图压测；检查清理、重启残留、响应耗时与 50301 比例。
5. 无下载权限仍能按范围看水印副本，但拿不到原件；签发及取流审计可查询，预览响应 no-store，浏览器换账号 / 到期后清屏。音频仅为鉴权播放，已交付内容不可远程回收。

本地真实容器回归入口为设置 `CRDP_IT_PREVIEW_LAUNCHER` 后运行 `PreviewSandboxIT`；HTTP / Redis / 双租户 MySQL 回归为设置 `CRDP_IT_MYSQL_PORT` 后运行 `ServerWatermarkPreviewIT` 与 `FileServiceAcceptanceIT`。生产验收记录归 OQR-10，本机通过不代替生产放行。
