package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.Test;

/** E05-T01（PLT-TEN-001）：列表检索条件的纯函数构造——按名称/编码关键词、状态、到期日、模块筛选， 排序白名单。SQL 只允许白名单片段拼接，值全部走参数。 */
class TenantDirectoryFilterTest {

    @Test
    void emptyQueryYieldsNoWhereAndDefaultOrder() {
        TenantDirectoryService.PreparedFilter filter =
                TenantDirectoryService.prepareFilter(
                        new TenantDirectoryService.ListQuery(null, null, null, null));

        assertEquals("", filter.whereSql());
        assertTrue(filter.args().isEmpty());
        assertEquals(
                " ORDER BY t.created_at DESC, t.id DESC", TenantDirectoryService.orderClause(null));
    }

    @Test
    void keywordMatchesNameOrCodeWithLike() {
        TenantDirectoryService.PreparedFilter filter =
                TenantDirectoryService.prepareFilter(
                        new TenantDirectoryService.ListQuery("示例医院", null, null, null));

        assertTrue(
                filter.whereSql().contains("(t.name LIKE ? OR t.code LIKE ?)"), filter.whereSql());
        assertEquals(2, filter.args().size());
        assertEquals("%示例医院%", filter.args().get(0));
        assertEquals("%示例医院%", filter.args().get(1));
    }

    @Test
    void keywordEscapesLikeWildcards() {
        TenantDirectoryService.PreparedFilter filter =
                TenantDirectoryService.prepareFilter(
                        new TenantDirectoryService.ListQuery("100%_x\\y", null, null, null));

        assertEquals("%100\\%\\_x\\\\y%", filter.args().get(0));
    }

    @Test
    void allFiltersCombineWithAnd() {
        TenantDirectoryService.PreparedFilter filter =
                TenantDirectoryService.prepareFilter(
                        new TenantDirectoryService.ListQuery(
                                "demo", "ENABLED", "2027-06-30", "followup"));

        assertTrue(filter.whereSql().contains("(t.name LIKE ? OR t.code LIKE ?)"));
        assertTrue(filter.whereSql().contains("t.status = ?"));
        assertTrue(
                filter.whereSql().contains("t.expire_type = 'FIXED' AND t.expire_at <= ?"),
                filter.whereSql());
        assertTrue(filter.whereSql().contains("EXISTS"), filter.whereSql());
        assertTrue(
                filter.whereSql().startsWith(" WHERE ")
                        && !filter.whereSql().contains(" WHERE AND"));
        assertEquals("ENABLED", filter.args().get(2));
        assertEquals(java.time.LocalDate.of(2027, 6, 30), filter.args().get(3));
        assertEquals("followup", filter.args().get(4));
    }

    @Test
    void unknownStatusIsRejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantDirectoryService.prepareFilter(
                                        new TenantDirectoryService.ListQuery(
                                                null, "REMOVED", null, null)));
        assertEquals(40001, ex.code());
    }

    @Test
    void invalidExpireBeforeIsRejected() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                TenantDirectoryService.prepareFilter(
                                        new TenantDirectoryService.ListQuery(
                                                null, null, "2027/06/30", null)));
        assertEquals(40001, ex.code());
    }

    @Test
    void sortWhitelistSupportsDescPrefixAndRejectsOthers() {
        assertEquals(
                " ORDER BY t.created_at DESC, t.id DESC",
                TenantDirectoryService.orderClause("-createdAt"));
        assertEquals(
                " ORDER BY t.created_at ASC, t.id ASC",
                TenantDirectoryService.orderClause("createdAt"));
        assertEquals(" ORDER BY t.name ASC, t.id ASC", TenantDirectoryService.orderClause("name"));
        assertEquals(
                " ORDER BY t.code DESC, t.id DESC", TenantDirectoryService.orderClause("-code"));
        // 长期有效（expire_at 为空）排在有到期日的租户之后
        assertEquals(
                " ORDER BY t.expire_at IS NULL, t.expire_at ASC, t.id ASC",
                TenantDirectoryService.orderClause("expireAt"));
        assertThrows(
                ApiException.class, () -> TenantDirectoryService.orderClause("status; DROP TABLE"));
        assertThrows(ApiException.class, () -> TenantDirectoryService.orderClause("-updated_at"));
    }
}
