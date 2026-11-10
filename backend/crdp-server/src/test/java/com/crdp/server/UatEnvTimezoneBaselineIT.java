package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * E23-T10 UAT 自查缺陷 UAT-SELF-02 核对（环境基线，门控 CRDP_IT_MYSQL_PORT）：本地 / CI 的 docker compose MySQL
 * 时区必须与生产 RDS 基线一致（`iac/rds-prod/params-baseline.json`：`time_zone = UTC+08:00`）。
 *
 * <p>背景：UAT 手册 §5 M-1「抽验当日登录 / 明文查看 / 新建 / 上传」在本地自查时当日窗口检索恒空。根因是 compose MySQL 未设时区（容器系统时区
 * UTC），而审计等表的 `created_at` 依赖数据库默认值 `CURRENT_TIMESTAMP(3)`（`JdbcAuditLogger` 的 INSERT 不带该列）——落库为
 * UTC 墙钟时间，比合同口径 「数据库 DATETIME 一律北京时间」（`AuditQuerySupport.ZONE`）慢 8
 * 小时；`AuditQuerySupport.parseTime` 把 ISO-8601 带时区入参折算成北京时间墙钟再比较，窗口内命中不了行。生产 RDS 基线是 `UTC+08:00`，所以
 * 该缺陷只在本地 / CI 复现——**环境与生产基线漂移**，UAT 预演 / 回归的结论会失真。
 *
 * <p>修复（本任务）：`docker-compose.yml` 的 mysql 增加 `--default-time-zone=+08:00`（与生产 RDS 参数 基线对齐，CI 起的是同一份
 * compose）。本测试锁定该基线，防再次漂移：
 *
 * <ol>
 *   <li>`@@global.time_zone` = `+08:00`；
 *   <li>`NOW()` 与北京墙钟时间相差 ≤ 10 分钟（容差吸收 CI 时钟漂移）。
 * </ol>
 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class UatEnvTimezoneBaselineIT {

    /** 北京时间（业务口径，AGENTS 第 6 节；与 AuditQuerySupport.ZONE 同一约定）。 */
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    /** 比对容差：吸收 CI runner 与容器之间的时钟漂移。 */
    private static final Duration TOLERANCE = Duration.ofMinutes(10);

    @Test
    void mysqlGlobalTimezoneMatchesProductionRdsBaseline() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT @@global.time_zone")) {
            assertTrue(resultSet.next());
            assertEquals(
                    "+08:00",
                    resultSet.getString(1),
                    "compose MySQL 必须与生产 RDS 基线一致（iac/rds-prod/params-baseline.json："
                            + " time_zone = UTC+08:00；docker-compose.yml --default-time-zone=+08:00）");
        }
    }

    @Test
    void databaseNowTracksBeijingWallClock() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT NOW(3)")) {
            assertTrue(resultSet.next());
            LocalDateTime databaseNow = resultSet.getTimestamp(1).toLocalDateTime();
            Duration drift = Duration.between(databaseNow, LocalDateTime.now(BEIJING)).abs();
            assertTrue(
                    drift.compareTo(TOLERANCE) <= 0,
                    "数据库 NOW() 应为北京墙钟时间（审计 created_at 等默认时间戳列的合同口径），"
                            + "实际漂移 "
                            + drift
                            + "（数据库 "
                            + databaseNow
                            + "，北京 "
                            + LocalDateTime.now(BEIJING)
                            + "）");
        }
    }

    private Connection connection() throws Exception {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        return java.sql.DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:"
                        + port
                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                "crdp_app",
                "DevOnly#app2026");
    }
}
