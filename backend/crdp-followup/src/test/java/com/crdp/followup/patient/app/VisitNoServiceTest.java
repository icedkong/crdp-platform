package com.crdp.followup.patient.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.followup.patient.infra.mapper.VisitNoMapper;
import com.crdp.framework.web.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

/**
 * E21-T10 / C-PAT-003 单元测试（tasks.csv 验收「编号规则可配置」）。
 *
 * <p>锁定口径：规则＝「前缀-建档年份-年内 3 位流水号」（Q10 / OQ-131①，出厂默认前缀 105）； 生效前缀＝本院 cfg_param 覆盖 → 平台库 plt_param 默认
 * → 代码内置缺省（非法配置回落缺省不停摆）； 年度流水按 LAST_INSERT_ID 原子自增，年度首例建行、并发首插由主键兜底重试； 流水号 3 位补零、超过 999
 * 自然进位不截断。全部虚构数据。
 */
class VisitNoServiceTest {

    private final VisitNoMapper mapper = mock(VisitNoMapper.class);
    private final VisitNoParamDefaults defaults = mock(VisitNoParamDefaults.class);

    private final VisitNoService service = new VisitNoService(defaults, mapper);

    // ---------------------------------------------------------------- 规则可配置（验收原文）

    @Test
    void ruleFallsBackPlatformDefaultThenBuiltIn() {
        // 本院未覆盖、平台未定义 → 代码内置缺省 105（OQ-131①，绝不因缺配置停摆）
        when(mapper.findPrefixOverride()).thenReturn(null);
        when(defaults.param(VisitNoService.PARAM_PREFIX)).thenReturn(Optional.empty());
        assertEquals("105", service.rule().prefix(), "出厂默认前缀 105");

        // 平台库定义默认 → 平台值生效
        when(defaults.param(VisitNoService.PARAM_PREFIX))
                .thenReturn(
                        Optional.of(
                                new VisitNoParamDefaults.ParamDefault(
                                        VisitNoService.PARAM_PREFIX, "C21", "TEXT")));
        assertEquals("C21", service.rule().prefix(), "平台默认值生效");
    }

    @Test
    void tenantOverrideWinsOverPlatformDefault() {
        when(mapper.findPrefixOverride()).thenReturn("88");
        when(defaults.param(VisitNoService.PARAM_PREFIX))
                .thenReturn(
                        Optional.of(
                                new VisitNoParamDefaults.ParamDefault(
                                        VisitNoService.PARAM_PREFIX, "105", "TEXT")));
        assertEquals("88", service.rule().prefix(), "租户覆盖优先（规则可配置验收）");

        // 生效规则带示例编号（登记页提示用）
        assertEquals("88-2026-001", service.rule().sample(2026));
    }

    @Test
    void invalidPrefixFallsBackToDefault() {
        // 含连字符 / 空白 / 超长的覆盖值都非法（连字符是规则分隔符），回落出厂默认
        when(defaults.param(VisitNoService.PARAM_PREFIX)).thenReturn(Optional.empty());
        for (String invalid : new String[] {"10-5", "  ", "A B", "x".repeat(17)}) {
            when(mapper.findPrefixOverride()).thenReturn(invalid);
            assertEquals("105", service.rule().prefix(), "非法前缀回落默认：" + invalid);
        }
        // 平台默认值非法同样回落
        when(mapper.findPrefixOverride()).thenReturn(null);
        when(defaults.param(VisitNoService.PARAM_PREFIX))
                .thenReturn(
                        Optional.of(
                                new VisitNoParamDefaults.ParamDefault(
                                        VisitNoService.PARAM_PREFIX, "  ", "TEXT")));
        assertEquals("105", service.rule().prefix(), "平台默认非法回落内置缺省");
    }

    // ---------------------------------------------------------------- 生成格式与年度流水

    @Test
    void nextVisitNoFormatsPrefixYearAndPaddedSerial() {
        when(mapper.findPrefixOverride()).thenReturn("105");
        when(defaults.param(VisitNoService.PARAM_PREFIX)).thenReturn(Optional.empty());
        when(mapper.bumpSerial(anyString(), anyInt())).thenReturn(1);
        when(mapper.lastInsertId()).thenReturn(1L);

        String visitNo = service.nextVisitNo();
        // 建档年份按业务时区（Asia/Shanghai，A-NFR-006）；本机与 CI 都按同一时区断言
        int year =
                java.time.LocalDate.now(
                                com.crdp.followup.plan.domain.PlanDateCalculator.BUSINESS_ZONE)
                        .getYear();
        assertEquals("105-" + year + "-001", visitNo, "默认规则 105-2026-001 形态（Q10 示例）");

        when(mapper.lastInsertId()).thenReturn(42L);
        assertEquals("105-" + year + "-042", service.nextVisitNo(), "年内 3 位流水号补零");

        when(mapper.lastInsertId()).thenReturn(1000L);
        assertEquals("105-" + year + "-1000", service.nextVisitNo(), "超过 999 自然进位不截断");
    }

    @Test
    void firstOfYearSeedsRowAndConcurrentSeedRetriesBump() {
        when(mapper.findPrefixOverride()).thenReturn("105");
        when(defaults.param(VisitNoService.PARAM_PREFIX)).thenReturn(Optional.empty());
        int year =
                java.time.LocalDate.now(
                                com.crdp.followup.plan.domain.PlanDateCalculator.BUSINESS_ZONE)
                        .getYear();

        // 年度首例：bump 0 行 → 建行（LAST_INSERT_ID(1)）→ 1
        when(mapper.bumpSerial(anyString(), anyInt())).thenReturn(0);
        when(mapper.lastInsertId()).thenReturn(1L);
        assertEquals("105-" + year + "-001", service.nextVisitNo(), "年度首例建行取 001");
        verify(mapper).insertSeed("105", year);

        // 并发首插：建行撞主键 → 回到 bump 成功
        when(mapper.bumpSerial(anyString(), anyInt())).thenReturn(0, 1);
        when(mapper.insertSeed(anyString(), anyInt())).thenThrow(new DuplicateKeyException("并发首插"));
        when(mapper.lastInsertId()).thenReturn(2L);
        assertEquals("105-" + year + "-002", service.nextVisitNo(), "并发首插由主键兜底重试 bump");
        // 首段 1 次 + 并发段 2 次（撞主键回到自增）= 3 次
        verify(mapper, times(3)).bumpSerial("105", year);
    }

    @Test
    void exhaustedSeedRetriesSurfacesError() {
        when(mapper.findPrefixOverride()).thenReturn("105");
        when(defaults.param(VisitNoService.PARAM_PREFIX)).thenReturn(Optional.empty());
        when(mapper.bumpSerial(anyString(), anyInt())).thenReturn(0);
        when(mapper.insertSeed(anyString(), anyInt())).thenThrow(new DuplicateKeyException("持续并发"));

        ApiException error = assertThrows(ApiException.class, () -> service.nextVisitNo());
        assertEquals(50000, error.code(), "重试耗尽返回系统繁忙（50000）");
        assertTrue(error.getMessage().contains("随访编号"));
    }
}
