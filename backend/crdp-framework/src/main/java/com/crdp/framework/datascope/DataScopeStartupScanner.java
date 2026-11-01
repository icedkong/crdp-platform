package com.crdp.framework.datascope;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

/**
 * 默认拒绝扫描（E07-T01，验收：“未声明范围的患者类查询启动即报错”）。
 *
 * <p>技术方案 §拦截器实现要求“Mapper 方法标注 @DataScope(resource = ...)”，默认拒绝不能只靠运行时
 * 40302——那要等第一条查询执行才知道漏了声明。本扫描在容器就绪后：①先物化全部 MapperFactoryBean （{@code @MapperScan} 注册的
 * beanClassName 含 MapperFactoryBean），确保每条 Mapper 语句都已登记进 SqlSessionFactory 的 Configuration——语句注册时机由
 * Spring 装配顺序决定，等它自然发生会让扫描出现 空窗；②遍历每个 {@link MappedStatement}：SQL 引用受控表（{@link ControlledTables}）的
 * SELECT，其 Mapper 方法或接口层级必须能找到 {@code @DataScope} 或 {@code @IgnoreScope}，否则抛 {@link
 * DataScopeUndeclaredException} 使启动失败。
 *
 * <p>只扫 SELECT：验收口径是“患者类查询”，E07-T02 的条件改写也只作用于查询（{@code InnerInterceptor#beforeQuery}
 * 不挂写路径）；INSERT/UPDATE/DELETE 的范围校验随患者域写用例（M2）另行落地。MyBatis-Plus 注入语句（selectById
 * 等继承方法）无法在方法级标注，靠接口级注解覆盖。 动态 SQL（&lt;script&gt; / &lt;foreach&gt;）无参数时求不出
 * BoundSql，回退用方法注解的原始文本做受控表 匹配（更保守：任意分支的表名都会命中）；原始文本也拿不到（如 XML、@SelectProvider）时按默认拒绝记 违规，不静默放行。
 */
@Component
public class DataScopeStartupScanner implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DataScopeStartupScanner.class);

    /** MyBatis 语句注解（取原始 SQL 文本的回退来源）。 */
    private static final Set<String> SQL_ANNOTATIONS =
            Set.of(
                    "Select",
                    "SelectProvider",
                    "Insert",
                    "InsertProvider",
                    "Update",
                    "UpdateProvider",
                    "Delete",
                    "DeleteProvider");

    private final ConfigurableListableBeanFactory beanFactory;
    private final List<SqlSessionFactory> sqlSessionFactories;

    /**
     * Spring 注入：扫描容器里全部 SqlSessionFactory（平台库 + 租户路由库）。 必须显式
     * {@code @Autowired}——类上还有一个单测用的无参构造器，Spring 多构造器且无注解时会退回无参构造。
     */
    @Autowired
    public DataScopeStartupScanner(
            ConfigurableListableBeanFactory beanFactory,
            List<SqlSessionFactory> sqlSessionFactories) {
        this.beanFactory = beanFactory;
        this.sqlSessionFactories = List.copyOf(sqlSessionFactories);
    }

    /** 直接扫描单个 Configuration 的单测入口，不物化任何 bean。 */
    public DataScopeStartupScanner() {
        this.beanFactory = null;
        this.sqlSessionFactories = List.of();
    }

    @Override
    public void afterSingletonsInstantiated() {
        materializeMapperBeans();
        List<String> violations = new ArrayList<>();
        for (SqlSessionFactory factory : sqlSessionFactories) {
            violations.addAll(scan(factory.getConfiguration()));
        }
        if (!violations.isEmpty()) {
            throw new DataScopeUndeclaredException(violations);
        }
        log.info(
                "数据范围默认拒绝扫描通过：共核对 {} 条 Mapper 语句，未发现未声明范围的受控表查询（E07-T01）",
                statementsIn(sqlSessionFactories));
    }

    /**
     * 把 {@code @MapperScan} 注册的 MapperFactoryBean 全部物化，等价于这些 Mapper 提前完成初始化： 语句由此登记进
     * Configuration。beanClassName 判定同时覆盖 MyBatis 与 MyBatis-Plus 的实现。
     */
    private void materializeMapperBeans() {
        if (beanFactory == null) {
            return;
        }
        int materialized = 0;
        for (String name : beanFactory.getBeanDefinitionNames()) {
            String beanClassName =
                    String.valueOf(beanFactory.getBeanDefinition(name).getBeanClassName());
            if (beanClassName.contains("MapperFactoryBean")) {
                beanFactory.getBean(name);
                materialized++;
            }
        }
        log.info("数据范围默认拒绝扫描前置：已登记 {} 个 Mapper 接口（E07-T01）", materialized);
    }

    /**
     * 扫描一个 MyBatis 配置，返回违规清单；清单为空表示全部声明合规。
     *
     * <p>MyBatis 的 StrictMap（MyBatis-Plus 的 MybatisStrictMap 同理）把同一语句按“全限定 id + 短名”放两份，短名冲突时还会塞
     * Ambiguity 占位对象（类型擦除成 Object），因此按 Object 迭代、 instanceof 过滤、再按全限定 id 去重。
     */
    public List<String> scan(Configuration configuration) {
        List<String> violations = new ArrayList<>();
        Map<String, MappedStatement> statements = new LinkedHashMap<>();
        for (Object candidate : configuration.getMappedStatements()) {
            if (candidate instanceof MappedStatement statement) {
                statements.putIfAbsent(statement.getId(), statement);
            }
        }
        for (MappedStatement statement : statements.values()) {
            String violation = violationOf(statement);
            if (violation != null) {
                violations.add(violation);
            }
        }
        return violations;
    }

    private String violationOf(MappedStatement statement) {
        if (statement.getSqlCommandType() != SqlCommandType.SELECT) {
            return null;
        }
        String sql = sqlOf(statement);
        if (sql == null) {
            return statement.getId()
                    + " SQL 在启动期无法求值，也无法回退到注解原文，无法核对数据范围声明：需显式标注"
                    + " @DataScope 或 @IgnoreScope";
        }
        if (!ControlledTables.referencedBy(sql)) {
            return null;
        }
        if (declaresScope(statement.getId())) {
            return null;
        }
        return statement.getId()
                + " 查询受控表但未声明数据范围（SQL："
                + summarize(sql)
                + "）：加 @DataScope(resource = \"patient\") 或 @IgnoreScope(reason = \"...\")";
    }

    /**
     * 先求 BoundSql；动态 SQL 无参数时抛错，回退用 Mapper 方法注解的原始 SQL 文本（表名匹配比求值后 更保守）。两样都拿不到返回 null，由调用方按默认拒绝记违规。
     */
    private String sqlOf(MappedStatement statement) {
        try {
            return statement.getBoundSql(null).getSql();
        } catch (RuntimeException failure) {
            String raw = rawAnnotationSql(statement.getId());
            if (raw != null) {
                return raw;
            }
            log.warn(
                    "语句 {} 的 SQL 无法在启动期求值，也没有可回退的注解原文：{}", statement.getId(), failure.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String rawAnnotationSql(String statementId) {
        Method method = mapperMethodOf(statementId);
        if (method == null) {
            return null;
        }
        StringBuilder raw = new StringBuilder();
        for (Annotation annotation : method.getAnnotations()) {
            String simpleName = annotation.annotationType().getSimpleName();
            if (!SQL_ANNOTATIONS.contains(simpleName)) {
                continue;
            }
            try {
                for (String fragment :
                        (String[])
                                annotation.annotationType().getMethod("value").invoke(annotation)) {
                    raw.append(fragment).append(' ');
                }
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return raw.isEmpty() ? null : raw.toString();
    }

    private Method mapperMethodOf(String statementId) {
        int lastDot = statementId.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        Class<?> mapper = mapperClassOf(statementId.substring(0, lastDot));
        if (mapper == null) {
            return null;
        }
        String methodName = statementId.substring(lastDot + 1);
        for (Method method : mapper.getMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        return null;
    }

    private String summarize(String sql) {
        String compact = sql == null ? "" : sql.strip().replaceAll("\\s+", " ");
        return compact.length() <= 120 ? compact : compact.substring(0, 120) + "…";
    }

    /**
     * 语句 id 形如 {@code com.crdp…PatientMapper.selectByWard}。方法级注解优先；没有则沿 Mapper 接口层级找类型级注解（覆盖
     * MyBatis-Plus 注入的继承方法）。
     */
    private boolean declaresScope(String statementId) {
        Method method = mapperMethodOf(statementId);
        if (method != null
                && (method.isAnnotationPresent(DataScope.class)
                        || method.isAnnotationPresent(IgnoreScope.class))) {
            return true;
        }
        int lastDot = statementId.lastIndexOf('.');
        if (lastDot <= 0) {
            return false;
        }
        Class<?> mapper = mapperClassOf(statementId.substring(0, lastDot));
        if (mapper == null) {
            return false;
        }
        return typeHierarchyOf(mapper).stream()
                .anyMatch(
                        type ->
                                type.isAnnotationPresent(DataScope.class)
                                        || type.isAnnotationPresent(IgnoreScope.class));
    }

    private Class<?> mapperClassOf(String className) {
        try {
            return Class.forName(className, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException failure) {
            try {
                return Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }
    }

    private Set<Class<?>> typeHierarchyOf(Class<?> mapper) {
        Set<Class<?>> hierarchy = new LinkedHashSet<>();
        collectInterfaces(mapper, hierarchy);
        return hierarchy;
    }

    private void collectInterfaces(Class<?> type, Set<Class<?>> collected) {
        if (type == null || !type.isInterface() || !collected.add(type)) {
            return;
        }
        for (Class<?> parent : type.getInterfaces()) {
            collectInterfaces(parent, collected);
        }
    }

    private int statementsIn(List<SqlSessionFactory> factories) {
        Set<String> ids = new LinkedHashSet<>();
        for (SqlSessionFactory factory : factories) {
            for (Object candidate : factory.getConfiguration().getMappedStatements()) {
                if (candidate instanceof MappedStatement statement) {
                    ids.add(statement.getId());
                }
            }
        }
        return ids.size();
    }
}
