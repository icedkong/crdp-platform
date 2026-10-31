package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.crypto.CryptoTypeHandler;
import com.crdp.framework.crypto.EnvelopeCryptoService;
import com.crdp.framework.crypto.LocalEnvelopeKeyProvider;
import com.crdp.framework.crypto.TenantDataKeyService;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * E03-T03 对真 MySQL 的端到端验证：经 MyBatis 写入的敏感字段在库里是密文（v1.AES-...）， 经 Mapper
 * 读回是明文。CRDP_IT_MYSQL_PORT=13306 时运行；自 E02-T09 起 CI 注入该端口；自建自清。
 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class CryptoTypeHandlerMySqlIT {

    /** 演示用具体 handler：生产子类（姓名、病案号、电话）随 E13 患者实体提供。必须 public，MyBatis 反射取公共构造器。 */
    public static class FieldNameHandler extends CryptoTypeHandler {
        @Override
        protected String purpose() {
            return "it-field-name";
        }
    }

    public interface DemoRowMapper {
        @org.apache.ibatis.annotations.Insert(
                "INSERT INTO it_crypto_demo (display_name) VALUES (#{name, typeHandler="
                        + "com.crdp.server.config.CryptoTypeHandlerMySqlIT$FieldNameHandler})")
        @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
        int insert(DemoRow row);

        @org.apache.ibatis.annotations.Select(
                "SELECT id, display_name FROM it_crypto_demo WHERE id = #{id}")
        @org.apache.ibatis.annotations.Results(
                @org.apache.ibatis.annotations.Result(
                        column = "display_name",
                        property = "name",
                        typeHandler = FieldNameHandler.class))
        DemoRow select(long id);
    }

    public static class DemoRow {
        private long id;
        private String name;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static JdbcTemplate demoa;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        String url =
                "jdbc:mysql://127.0.0.1:"
                        + port
                        + "/t_demoa?useSSL=false&allowPublicKeyRetrieval=true";
        DataSource dataSource = new DriverManagerDataSource(url, "crdp_app", "DevOnly#app2026");
        demoa = new JdbcTemplate(dataSource);
        demoa.execute(
                "CREATE TABLE IF NOT EXISTS it_crypto_demo ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                        + "display_name VARCHAR(512) NOT NULL) ENGINE=InnoDB");
        CryptoService crypto =
                new EnvelopeCryptoService(
                        new TenantDataKeyService(
                                new LocalEnvelopeKeyProvider("dev-only-not-a-production-key"),
                                demoa));
        CryptoTypeHandler.configure(crypto);

        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.addMapper(DemoRowMapper.class);
        configuration.setEnvironment(
                new Environment("it", new JdbcTransactionFactory(), dataSource));
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void tearDown() {
        if (demoa != null) {
            demoa.execute("DROP TABLE IF EXISTS it_crypto_demo");
            demoa.update("DELETE FROM crypto_data_key WHERE purpose LIKE 'it-%'");
        }
        CryptoTypeHandler.configure(null);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void storesCiphertextAndReadsPlaintextBackThroughMyBatis() {
        TenantContext.useTenant(201L, "demoa-it");
        DemoRow row = new DemoRow();
        row.setName("张医生（示例）");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            session.getMapper(DemoRowMapper.class).insert(row);
            String stored =
                    demoa.queryForObject(
                            "SELECT display_name FROM it_crypto_demo WHERE id = ?",
                            String.class,
                            row.getId());
            assertTrue(stored.startsWith("v1.AES-256-GCM."), "库里必须是密文，实际：" + stored);

            DemoRow loaded = session.getMapper(DemoRowMapper.class).select(row.getId());
            assertEquals("张医生（示例）", loaded.getName(), "经 Mapper 读回应透明解密为明文");
        }
    }

    @Test
    void sameNameTwiceYieldsDifferentCiphertexts() {
        TenantContext.useTenant(201L, "demoa-it");
        DemoRow first = new DemoRow();
        first.setName("李护士（示例）");
        DemoRow second = new DemoRow();
        second.setName("李护士（示例）");

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            DemoRowMapper mapper = session.getMapper(DemoRowMapper.class);
            mapper.insert(first);
            mapper.insert(second);
        }

        List<String> stored =
                demoa.queryForList(
                        "SELECT display_name FROM it_crypto_demo WHERE id IN (?, ?)",
                        String.class,
                        first.getId(),
                        second.getId());
        assertTrue(!stored.get(0).equals(stored.get(1)), "随机 IV 下相同明文两次入库的密文应不同");
    }
}
