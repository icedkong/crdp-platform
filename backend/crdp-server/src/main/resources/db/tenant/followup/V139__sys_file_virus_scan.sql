-- E22-T15 / 等保自查 D-01 整改（恶意代码防范，tech-plan §10 后续方案）：上传文件病毒扫描台账。
--   ClamAV（clamd INSTREAM）同步扫描落地：
--   - 上传链路：扫描启用（crdp.file.virus-scan.mode=clamd）时通过才写对象与台账，台账行 virus_scan_status=CLEAN；
--     检出病毒 40001 拒绝（FILE_VIRUS_REJECTED 审计）；扫描不可用 50300 不放行（fail closed）。
--   - 禁用态（off，本机 / CI / 未部署 clamd）：不扫描，台账行 UNSCANNED。
--   - 存量补扫（VirusScanRetroService，每日 03:20）：按本表已记录的 sha256 逐个读回对象核对完整性并补扫，
--     干净 → CLEAN、检出 → INFECTED、对象与台账哈希不一致 → HASH_MISMATCH（完整性事件，人工处置）。
--   状态机：UNSCANNED →（上传扫描 / 补扫）CLEAN | INFECTED | HASH_MISMATCH；只前进不回退（补扫守卫
--   WHERE virus_scan_status = 'UNSCANNED'，重复执行幂等）。本表不带 tenant_id（AGENTS §5.1）。

ALTER TABLE sys_file
  ADD COLUMN virus_scan_status VARCHAR(16) NOT NULL DEFAULT 'UNSCANNED'
    COMMENT '病毒扫描状态：UNSCANNED 未扫描（扫描未启用期间上传，待补扫）/ CLEAN 干净 / INFECTED 检出病毒（E22-T15 D-01）'
    AFTER status,
  ADD COLUMN virus_scanned_at DATETIME(3) NULL
    COMMENT '病毒扫描时间（上传同步扫描或补扫作业回填）'
    AFTER virus_scan_status,
  ADD KEY idx_sys_file_virus_scan (virus_scan_status, id);
