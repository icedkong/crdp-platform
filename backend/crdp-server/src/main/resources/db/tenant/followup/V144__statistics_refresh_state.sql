-- OQR-05B：记录真正的汇总提交时刻；旧汇总没有可靠时刻时不伪造回填。
CREATE TABLE st_refresh_state (
  code VARCHAR(64) NOT NULL PRIMARY KEY,
  logical_date DATE NOT NULL,
  refreshed_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户统计汇总最近成功刷新（与事实重建同事务）';
