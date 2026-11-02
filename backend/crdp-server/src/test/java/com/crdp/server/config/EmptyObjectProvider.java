package com.crdp.server.config;

import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 测试助手：手工装配 {@link TenantOnboardingService} 时用的空 {@link ObjectProvider}（getIfAvailable 返回
 * null，对应生产容器里没有该 bean 的情形）。E06-T12 起构造器带 local/dev 演示占位替换器。
 */
public final class EmptyObjectProvider {

    private EmptyObjectProvider() {}

    public static <T> ObjectProvider<T> get() {
        return new ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }
        };
    }
}
