-- E10-T02 / C-NFR-003：OBS 对象存储适配与服务端加密（ADR 0006）。
--   V25 预告的「迁桶并清理 body 列」在此落地：文件体只在对象存储
--   （生产 OBS SSE-KMS 服务端加密；本机 crdp.storage.type=local 文件系统替身），
--   对象键由存储端口强制带租户前缀 t/{租户编码}/files/{年月}/{uuid}.{扩展名}（验收：按租户前缀隔离）。
--   V25 上线期间暂存在 body 列的开发文件体随本列丢弃（当时无下载路径，无业务引用）。

ALTER TABLE sys_file DROP COLUMN body;

ALTER TABLE sys_file
  MODIFY object_key VARCHAR(255) NOT NULL
  COMMENT '完整对象键（t/{租户编码}/files/{年月}/{uuid}.{扩展名}；租户前缀由存储端口强制，E10-T02）';
