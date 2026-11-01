package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.datascope.DataScopeStartupScanner;
import java.util.List;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * E07-T01 默认拒绝扫描在真实应用里的接线：扫描器必须是容器里的单例（{@code com.crdp} 组件扫描 扫到 framework 包），并对真实
 * SqlSessionFactory（平台库 + 租户路由库的全部 Mapper 语句）扫描结果为零违规 ——即“应用能启动”本身就意味着所有受控表查询都已声明范围（未声明的会让启动直接失败）。
 * 门控 CRDP_IT_MYSQL_PORT：与仓库其他 *IT 一致，无 MySQL 时跳过、CI 中执行。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class DataScopeStartupScanIT {

    @Autowired private DataScopeStartupScanner scanner;
    @Autowired private List<SqlSessionFactory> sqlSessionFactories;

    @Test
    void scannerBeanIsWiredAndTheRealMappersDeclareTheirScope() {
        assertNotNull(scanner, "扫描器应被组件扫描注册为单例，否则默认拒绝只停留在类库层");
        assertTrue(!sqlSessionFactories.isEmpty(), "真实应用应至少有一个 SqlSessionFactory 可供扫描");
        for (SqlSessionFactory factory : sqlSessionFactories) {
            List<String> violations = scanner.scan(factory.getConfiguration());
            assertTrue(
                    violations.isEmpty(),
                    "真实 Mapper 语句存在未声明范围的受控表查询：\n" + String.join("\n", violations));
        }
    }
}
