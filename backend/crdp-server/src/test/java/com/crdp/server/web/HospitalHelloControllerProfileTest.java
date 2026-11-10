package com.crdp.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A3（M3 审查）：/api/hospital/hello 用 SELECT DATABASE() 回显当前数据库名，只允许 demo / local / dev 演示剖面注册；prod 剖面该
 * bean 必须不存在（不注册即 404，不泄露环境信息）。权限链占位 ping 不受剖面限制。
 *
 * <p>用 {@link ApplicationContextRunner} 只装配被测控制器（{@code @Profile} 在 bean 注册期求值），不起完整 应用上下文——prod
 * 剖面的启动安全检查（KMS、审计归档等）不属于本用例范围；租户路由行为由 {@code TenantIsolationAcceptanceIT} 在 local 剖面覆盖。
 */
class HospitalHelloControllerProfileTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withBean(JdbcTemplate.class, () -> Mockito.mock(JdbcTemplate.class))
                    .withUserConfiguration(HospitalHelloController.class);

    @Test
    void prodProfileDoesNotRegisterHelloController() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).doesNotHaveBean(HospitalHelloController.class));
    }

    @Test
    void demoLocalDevProfilesRegisterHelloController() {
        for (String profile : new String[] {"demo", "local", "dev"}) {
            runner.withPropertyValues("spring.profiles.active=" + profile)
                    .run(
                            context ->
                                    assertThat(context)
                                            .as("剖面 %s 应注册 hello 接口", profile)
                                            .hasSingleBean(HospitalHelloController.class));
        }
    }

    @Test
    void pingControllerStaysRegisteredUnderProdProfile() {
        new ApplicationContextRunner()
                .withUserConfiguration(FollowupPingController.class)
                .withPropertyValues("spring.profiles.active=prod")
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(FollowupPingController.class);
                            var response = context.getBean(FollowupPingController.class).ping();
                            assertThat(response.code()).isZero();
                            assertThat(response.data().module()).isEqualTo("followup");
                            assertThat(response.data().status()).isEqualTo("placeholder");
                        });
    }
}
