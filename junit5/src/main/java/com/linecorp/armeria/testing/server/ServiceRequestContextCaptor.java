/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.testing.server;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Captures the {@code ServiceRequestContext}s.
 *
 * <p>Example:
 * <pre>{@code
 * class ServiceRequestContextCaptorTest {
 *     @RegisterExtension
 *     static final ServerExtension server = new ServerExtension() {
 *         @Override
 *         protected void configure(ServerBuilder sb) throws Exception {
 *             sb.service("/hello", (ctx, req) -> HttpResponse.of(200));
 *         }
 *     };
 *
 *     @Test
 *     void test() {
 *         final ServiceRequestContextCaptor captor = server.requestContextCaptor();
 *         client.get("/hello").aggregate().join();
 *         assertThat(captor.size()).isEqualTo(1);
 *     }
 * }
 * }</pre>
 *
 * <p>Example: use {@code ServiceRequestContextCaptor} manually
 * <pre>{@code
 * class ServiceRequestContextCaptorTest {
 *     static final ServiceRequestContextCaptor captor = new ServiceRequestContextCaptor();
 *     static final Server server = Server.builder()
 *                                        .decorator(captor.newDecorator())
 *                                        .service("/hello", (ctx, req) -> HttpResponse.of(200))
 *                                        .build();
 *
 *     @BeforeAll
 *     static void beforeClass() {
 *         server.start().join();
 *     }
 *
 *     @AfterAll
 *     static void afterClass() {
 *         server.stop().join();
 *     }
 *
 *     @Test
 *     void test() {
 *         final WebClient client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort()).build();
 *         client.get("/hello").aggregate().join();
 *         assertThat(captor.size()).isEqualTo(1);
 *     }
 *
 *     @AfterEach
 *     void tearDown() {
 *         captor.clear();
 *     }
 * }
 * }</pre>
 */
@UnstableApi
public final class ServiceRequestContextCaptor {
    private static final int DEFAULT_TIMEOUT_IN_SECONDS = 15;

    private final BlockingQueue<ServiceRequestContext> serviceContexts =
            new LinkedBlockingDeque<>();

    /**
     * Creates a new decorator to capture the {@link ServiceRequestContext}s.
     */
    public Function<? super HttpService, ? extends HttpService> newDecorator() {
        return delegate -> new SimpleDecoratingHttpService(delegate) {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                serviceContexts.add(ctx);
                return delegate.serve(ctx, req);
            }
        };
    }

    /**
     * Creates a new decorator to capture the {@link ServiceRequestContext}s
     * satisfying the given predicate {@code filter}.
     */
    public Function<? super HttpService, ? extends HttpService>
    newDecorator(Predicate<? super ServiceRequestContext> filter) {
        requireNonNull(filter, "filter");
        return delegate -> new SimpleDecoratingHttpService(delegate) {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                if (filter.test(ctx)) {
                    serviceContexts.add(ctx);
                }
                return delegate.serve(ctx, req);
            }
        };
    }

    /**
     * Clears the captured {@link ServiceRequestContext}s.
     */
    public void clear() {
        serviceContexts.clear();
    }

    /**
     * Returns the number of captured {@link ServiceRequestContext}s.
     */
    public int size() {
        return serviceContexts.size();
    }

    /**
     * Retrieves and removes the first captured {@link ServiceRequestContext}, waiting if necessary until
     * an element becomes available.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public ServiceRequestContext take() throws InterruptedException {
        return serviceContexts.take();
    }

    /**
     * Retrieves and removes the first captured {@link ServiceRequestContext}, waiting up to
     * {@value DEFAULT_TIMEOUT_IN_SECONDS} seconds if necessary until an element becomes available.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    @Nullable
    public ServiceRequestContext poll() throws InterruptedException {
        return poll(DEFAULT_TIMEOUT_IN_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Retrieves and removes the first captured {@link ServiceRequestContext}, waiting up to
     * the specified wait time if necessary until an element becomes available.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    @Nullable
    public ServiceRequestContext poll(long timeout, TimeUnit unit) throws InterruptedException {
        return serviceContexts.poll(timeout, unit);
    }
}
