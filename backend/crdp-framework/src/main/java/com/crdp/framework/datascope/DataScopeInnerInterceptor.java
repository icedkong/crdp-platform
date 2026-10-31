package com.crdp.framework.datascope;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.sql.SQLException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/** 查询受控表且没有范围上下文时直接抛错，不把 SQL 放行给数据库。 */
public class DataScopeInnerInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(
            Executor executor,
            MappedStatement ms,
            Object parameter,
            RowBounds rowBounds,
            ResultHandler resultHandler,
            BoundSql boundSql)
            throws SQLException {
        String sql = boundSql.getSql();
        String rewritten =
                DataScopeGuard.enforce(sql, DataScopeContext.get(), DataScopeContext.ignored());
        if (!rewritten.equals(sql)) {
            MetaObject metaObject = SystemMetaObject.forObject(boundSql);
            metaObject.setValue("sql", rewritten);
        }
    }
}
