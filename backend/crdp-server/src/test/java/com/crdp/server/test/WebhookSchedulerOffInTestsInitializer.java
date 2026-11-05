package com.crdp.server.test;

import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * IT 套件的 Webhook 投递轮询默认关闭（经 src/test/resources/META-INF/spring.factories 注册到每个测试上下文）。
 *
 * <p>动机（E09-T06 CI 实测）：surefire 单 JVM 里多个缓存的 Spring 上下文共享同一套租户库， 任一未开联调开关的上下文（生产口径 SSRF 防护）每 15
 * 秒轮询都会把别的上下文刚入队、指向 http://127.0.0.1 的投递行按「内网拒绝」直接定格 FAILED—— {@code
 * WebhookDeliveryIT.exhaustedRetriesGoFailedAndCanBeRedelivered} 因此间歇性红。
 *
 * <p>做法：属性未显式设置时以<b>最低优先级</b>注入 {@code crdp.webhook.delivery.scheduler-enabled=false}； 需要真实轮询的用例在
 * {@code @SpringBootTest(properties = ...)} 显式置 true（inline 属性优先级更高，不受影响）。 生产 / 本地开发跑 jar 时 test
 * classpath 不存在，缺省仍为开（{@code WebhookDeliveryScheduler} 的 matchIfMissing = true）。
 */
public class WebhookSchedulerOffInTestsInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String SCHEDULER_ENABLED_KEY = "crdp.webhook.delivery.scheduler-enabled";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (context.getEnvironment().getProperty(SCHEDULER_ENABLED_KEY) == null) {
            context.getEnvironment()
                    .getPropertySources()
                    .addLast(
                            new MapPropertySource(
                                    "webhookSchedulerOffInTests",
                                    Map.of(SCHEDULER_ENABLED_KEY, "false")));
        }
    }
}
