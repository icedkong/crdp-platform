package com.crdp.framework.datascope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * E07-T01 默认拒绝扫描：访问受控表的 SELECT 未声明 @DataScope / @IgnoreScope 时，启动即失败
 * （验收标准“未声明范围的患者类查询启动即报错”）。规格来源：技术方案 §拦截器实现（“默认拒绝： 查询受控表但没有范围上下文的 SQL 直接抛错”）、AGENTS.md 规则 2。
 */
class DataScopeStartupScannerTest {

    @Test
    void reportsControlledSelectWithoutDeclaration() {
        List<String> violations = scan(BadPatientMapper.class);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("selectPatients"), "违规清单应包含语句 id，实际：" + violations);
        assertTrue(violations.get(0).contains("@DataScope"), "违规信息应给出修复指引，实际：" + violations);
    }

    @Test
    void acceptsDeclaredScopedIgnoredAndUncontrolledStatements() {
        List<String> violations =
                scan(ScopedPatientMapper.class, IgnoredPatientMapper.class, FreeUserMapper.class);
        assertTrue(violations.isEmpty(), "不应有违规，实际：" + violations);
    }

    @Test
    void typeLevelDataScopeCoversEveryMethodOfTheMapper() {
        List<String> violations = scan(TypeScopedPatientMapper.class);
        assertTrue(violations.isEmpty(), "接口级 @DataScope 应覆盖全部方法，实际：" + violations);
    }

    @Test
    void inheritedStatementIsCoveredByTypeLevelDeclaration() {
        // 模拟 MyBatis-Plus 注入语句：id 指向子接口，方法声明在父接口（BaseMapper.selectById 场景）。
        Configuration configuration = new Configuration();
        configuration.addMapper(InheritedIgnoredMapper.class);
        addSelectStatement(
                configuration,
                InheritedIgnoredMapper.class.getName() + ".selectById",
                "SELECT id FROM pt_patient WHERE id = ?");
        List<String> violations = new DataScopeStartupScanner().scan(configuration);
        assertTrue(violations.isEmpty(), "父接口 @IgnoreScope 应覆盖注入语句，实际：" + violations);
    }

    @Test
    void unresolvableMapperMethodIsAViolation() {
        // 语句 id 指不到 Mapper 方法时无法核对声明，按默认拒绝记违规。
        Configuration configuration = new Configuration();
        configuration.addMapper(FreeUserMapper.class);
        addSelectStatement(
                configuration,
                FreeUserMapper.class.getName() + ".ghostMethod",
                "SELECT id FROM pt_patient");
        List<String> violations = new DataScopeStartupScanner().scan(configuration);
        assertEquals(1, violations.size());
    }

    @Test
    void writesAreNotQueriesAndAreLeftToLaterRewriteTasks() {
        // 验收口径是“患者类查询”；INSERT 不在启动扫描范围（写路径的范围过滤随 E07-T02+ 落地）。
        Configuration configuration = new Configuration();
        configuration.addMapper(InsertOnlyPatientMapper.class);
        List<String> violations = new DataScopeStartupScanner().scan(configuration);
        assertTrue(violations.isEmpty(), "非 SELECT 不扫描，实际：" + violations);
    }

    @Test
    void shortNameCollisionsDoNotBreakTheScan() {
        // 两个 Mapper 同名方法会让 StrictMap 在短名键上放 Ambiguity 占位对象（类型擦除成 Object），
        // 扫描必须跳过占位对象而不是抛 ClassCastException（真实应用曾踩中，见 MybatisStrictMap）。
        List<String> violations = scan(CollidingPatientMapper.class, CollidingUserMapper.class);
        assertEquals(1, violations.size());
        assertTrue(
                violations.get(0).contains("CollidingPatientMapper"), "只应报患者表那一条，实际：" + violations);
    }

    @Test
    void startingTheContextFailsWhenAnyStatementIsUndeclared() {
        RuntimeException failure =
                assertThrows(
                        RuntimeException.class,
                        () -> new AnnotationConfigApplicationContext(BadContext.class),
                        "上下文刷新应因未声明范围的受控查询而失败");
        assertTrue(
                hasCause(failure, DataScopeUndeclaredException.class),
                "启动失败应源自默认拒绝扫描，实际：" + failure);
        assertTrue(
                failure.getMessage() != null && failure.getMessage().contains("selectPatients"),
                "失败信息应指出违规语句，实际：" + failure.getMessage());
    }

    @Test
    void startingTheContextSucceedsWhenEverythingIsDeclared() {
        try (AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext(GoodContext.class)) {
            assertTrue(context.isRunning());
        }
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        Throwable current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static List<String> scan(Class<?>... mappers) {
        Configuration configuration = new Configuration();
        for (Class<?> mapper : mappers) {
            configuration.addMapper(mapper);
        }
        return new DataScopeStartupScanner().scan(configuration);
    }

    private static void addSelectStatement(Configuration configuration, String id, String sql) {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(configuration, "crdp/datascope-scan-test");
        assistant.setCurrentNamespace(id.substring(0, id.lastIndexOf('.')));
        assistant.addMappedStatement(
                id.substring(id.lastIndexOf('.') + 1),
                new org.apache.ibatis.builder.StaticSqlSource(configuration, sql),
                org.apache.ibatis.mapping.StatementType.PREPARED,
                SqlCommandType.SELECT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                org.apache.ibatis.executor.keygen.NoKeyGenerator.INSTANCE,
                null,
                null,
                configuration.getDatabaseId(),
                configuration.getDefaultScriptingLanguageInstance(),
                null);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class BadContext {

        @Bean
        DataScopeStartupScanner scanner(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory
                        beanFactory,
                SqlSessionFactory badFactory) {
            return new DataScopeStartupScanner(beanFactory, List.of(badFactory));
        }

        @Bean
        SqlSessionFactory badFactory() {
            return factoryOf();
        }

        @Bean
        org.mybatis.spring.mapper.MapperFactoryBean<?> badMapper(SqlSessionFactory badFactory) {
            return mapperFactoryBeanOf(BadPatientMapper.class, badFactory);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class GoodContext {

        @Bean
        DataScopeStartupScanner scanner(
                org.springframework.beans.factory.config.ConfigurableListableBeanFactory
                        beanFactory,
                SqlSessionFactory goodFactory) {
            return new DataScopeStartupScanner(beanFactory, List.of(goodFactory));
        }

        @Bean
        SqlSessionFactory goodFactory() {
            return factoryOf();
        }

        @Bean
        org.mybatis.spring.mapper.MapperFactoryBean<?> scopedMapper(SqlSessionFactory goodFactory) {
            return mapperFactoryBeanOf(ScopedPatientMapper.class, goodFactory);
        }

        @Bean
        org.mybatis.spring.mapper.MapperFactoryBean<?> freeMapper(SqlSessionFactory goodFactory) {
            return mapperFactoryBeanOf(FreeUserMapper.class, goodFactory);
        }
    }

    private static org.mybatis.spring.mapper.MapperFactoryBean<?> mapperFactoryBeanOf(
            Class<?> mapper, SqlSessionFactory factory) {
        org.mybatis.spring.mapper.MapperFactoryBean<?> bean =
                new org.mybatis.spring.mapper.MapperFactoryBean<>(mapper);
        bean.setSqlSessionFactory(factory);
        return bean;
    }

    private static SqlSessionFactory factoryOf(Class<?>... mappers) {
        Configuration configuration = new Configuration();
        // MapperFactoryBean → SqlSessionTemplate 构造时只取 DataSource 引用，不真连库。
        configuration.setEnvironment(
                new org.apache.ibatis.mapping.Environment(
                        "datascope-scan-test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                        mock(javax.sql.DataSource.class)));
        for (Class<?> mapper : mappers) {
            configuration.addMapper(mapper);
        }
        SqlSessionFactory factory = mock(SqlSessionFactory.class);
        when(factory.getConfiguration()).thenReturn(configuration);
        return factory;
    }

    @Mapper
    interface BadPatientMapper {

        @Select("SELECT id, name FROM pt_patient WHERE ward_id = #{wardId}")
        List<String> selectPatients(Long wardId);
    }

    @Mapper
    interface ScopedPatientMapper {

        @DataScope(resource = "patient")
        @Select("SELECT id, name FROM pt_patient WHERE ward_id = #{wardId}")
        List<String> selectPatients(Long wardId);
    }

    @Mapper
    interface IgnoredPatientMapper {

        @IgnoreScope(reason = "夜间对账作业，需全院扫描（示例）")
        @Select("SELECT COUNT(*) FROM pt_patient")
        int countAll();
    }

    @Mapper
    interface FreeUserMapper {

        @Select("SELECT id, username FROM sys_user WHERE status = #{status}")
        List<String> selectUsers(String status);
    }

    @Mapper
    interface CollidingPatientMapper {

        @Select("SELECT id FROM pt_patient WHERE id = #{id}")
        String find(Long id);
    }

    @Mapper
    interface CollidingUserMapper {

        @Select("SELECT id FROM sys_user WHERE id = #{id}")
        String find(Long id);
    }

    @Mapper
    @DataScope(resource = "patient")
    interface TypeScopedPatientMapper {

        @Select("SELECT id FROM pt_patient WHERE id = #{id}")
        String selectById(Long id);

        @Select("SELECT id FROM fu_task WHERE patient_id = #{patientId}")
        List<String> selectTasks(Long patientId);
    }

    @Mapper
    interface InsertOnlyPatientMapper {

        @Insert("INSERT INTO pt_patient(id, name) VALUES (#{id}, #{name})")
        int insert(Long id, String name);
    }

    interface PatientBaseMapper {

        String selectById(Long id);
    }

    @Mapper
    @IgnoreScope(reason = "数据修正作业，需全量患者（示例）")
    interface InheritedIgnoredMapper extends PatientBaseMapper {}
}
