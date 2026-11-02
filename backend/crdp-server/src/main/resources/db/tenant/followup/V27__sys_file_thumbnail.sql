-- E10-T03 图片缩略图（任务验收：上传图片后生成缩略图，无转码；tech-plan §10「派生文件：只生成图片缩略图；
--   录音不转码、不另存副本」）。
--   sys_file 补缩略图对象键列：图片（jpg / png）上传通过校验后同步生成缩略图，落同一业务桶的
--   thumbnails/{年月}/{uuid}.{扩展名} 对象（租户前缀 t/{租户编码}/ 由存储端口强制，ADR 0006），
--   完整键记入本列；缩略图保持原格式、不放大（小图直接复用原字节，即「无转码」）。非图片 NULL
--   （录音不转码不另存副本、PDF / DOCX / XLSX 无缩略图，列表按文件图标显示）。
--   pages 口径收敛（V25 注释原写「随 E10-T03 回填」）：图片恒为 1（本任务起回填）；PDF 页数需要
--   解析器依赖（E10-T01 只引 tika-core 的取舍，OQ-61），随 E10-T04 预览能力落地再回填，注释一并修正。
--   损坏 / 超尺寸（单边 > 12000 px 或 > 4000 万像素，防解压炸弹）图片在上传时 40001 拒绝，不产生本表行。

ALTER TABLE sys_file
  ADD COLUMN thumbnail_key VARCHAR(255) NULL COMMENT '缩略图对象键（thumbnails/{年月}/{uuid}.{扩展名}，含租户前缀完整键；仅图片有值，E10-T03）' AFTER sha256,
  MODIFY COLUMN pages INT NULL COMMENT '页数（图片恒为 1；PDF 随 E10-T04 预览解析回填，OQ-61）';
