/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.ContextAwareBlockingTaskExecutor;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

class DefaultBlockingTaskExecutorTest {

    private interface UnwrappableScheduledExecutorService extends ScheduledExecutorService, Unwrappable {}

    @Test
    void unwrapDefaultBlockingTaskExecutor() {
        final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.of(pool);
        try {
            assertThat(executor.unwrap()).isSameAs(executor);
            assertThat(executor.unwrapAll()).isSameAs(pool);
            assertThat(executor.as(BlockingTaskExecutor.class)).isSameAs(executor);
            assertThat(executor.as(ScheduledThreadPoolExecutor.class)).isSameAs(pool);
            assertThat(executor.as(ThreadPoolExecutor.class)).isSameAs(pool);
            assertThat(executor.as(String.class)).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unwrapNonThreadPoolExecutor() {
        final ScheduledExecutorService singleThread = Executors.newSingleThreadScheduledExecutor();
        final BlockingTaskExecutor executor = BlockingTaskExecutor.of(singleThread);
        try {
            assertThat(executor.unwrap()).isSameAs(executor);
            assertThat(executor.unwrapAll()).isSameAs(singleThread);
            assertThat(executor.as(ThreadPoolExecutor.class)).isNull();
            assertThat(executor.as(ScheduledExecutorService.class)).isSameAs(executor);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unwrapUnwrappableDelegate() {
        final ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1);
        final UnwrappableScheduledExecutorService unwrappableDelegate =
                (UnwrappableScheduledExecutorService) Proxy.newProxyInstance(
                        DefaultBlockingTaskExecutorTest.class.getClassLoader(),
                        new Class<?>[] { UnwrappableScheduledExecutorService.class },
                        (proxy, method, args) -> {
                            final String name = method.getName();
                            if ("as".equals(name)) {
                                final Class<?> type = (Class<?>) args[0];
                                return type.isInstance(pool) ? type.cast(pool) : null;
                            }
                            if ("unwrapAll".equals(name)) {
                                return pool;
                            }
                            return method.invoke(pool, args);
                        });
        final BlockingTaskExecutor executor = BlockingTaskExecutor.of(unwrappableDelegate);
        try {
            assertThat(executor.as(ThreadPoolExecutor.class)).isSameAs(pool);
            assertThat(executor.unwrapAll()).isSameAs(pool);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unwrapContextAwareBlockingTaskExecutor() {
        final BlockingTaskExecutor base = BlockingTaskExecutor.builder().numThreads(1).build();
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        try {
            final ContextAwareBlockingTaskExecutor contextAware =
                    ContextAwareBlockingTaskExecutor.of(ctx, base);
            assertThat(contextAware.unwrap()).isSameAs(base);
            assertThat(contextAware.unwrapAll()).isInstanceOf(ScheduledThreadPoolExecutor.class);
            assertThat(contextAware.as(ContextAwareBlockingTaskExecutor.class)).isSameAs(contextAware);
            assertThat(contextAware.as(ThreadPoolExecutor.class))
                    .isSameAs(base.as(ThreadPoolExecutor.class));
            assertThat(ctx.blockingTaskExecutor().unwrap())
                    .isSameAs(CommonPools.blockingTaskExecutor());
        } finally {
            base.shutdownNow();
        }
    }
}
