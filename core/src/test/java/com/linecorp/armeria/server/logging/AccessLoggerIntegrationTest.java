/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientHttpService;
import com.linecorp.armeria.server.TransientServiceOption;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AccessLoggerIntegrationTest {

    private static final AtomicReference<RequestContext> REQUEST_CONTEXT_REFERENCE = new AtomicReference<>();
    private static final AtomicInteger CONTEXT_HOOK_COUNTER = new AtomicInteger(0);

    private static final AccessLogWriter ACCESS_LOG_WRITER = log ->
            REQUEST_CONTEXT_REFERENCE.set(RequestContext.currentOrNull());

    private static final Supplier<? extends AutoCloseable> CONTENT_HOOK = () -> {
        CONTEXT_HOOK_COUNTER.incrementAndGet();
        return () -> {};
    };

    private static final HttpService BASE_SERVICE = ((HttpService) (ctx, req) -> HttpResponse.of(200))
            .decorate((delegate, ctx, req) -> {
                ctx.hook(CONTENT_HOOK);
                return delegate.serve(ctx, req);
            });

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route().path("/default-service")
                    .build(BASE_SERVICE);
            sb.route().path("/default-service-with-access-log-writer")
                    .accessLogWriter(ACCESS_LOG_WRITER, false)
                    .build(BASE_SERVICE);
            sb.route().path("/transit-service")
                    .build(BASE_SERVICE.decorate(TransientHttpService.newDecorator()));
            sb.route().path("/transit-service-with-access-logger")
                    .accessLogWriter(ACCESS_LOG_WRITER, false)
                    .build(BASE_SERVICE.decorate(TransientHttpService.newDecorator()));
            sb.route().path("/transit-service-with-access-log-option")
                    .build(BASE_SERVICE.decorate(
                            TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING))
                    );
            sb.route().path("/transit-service-with-access-log-option-and-access-logger")
                    .accessLogWriter(ACCESS_LOG_WRITER, false)
                    .build(BASE_SERVICE.decorate(
                            TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING))
                    );
        }
    };

    @BeforeEach
    void resetState() {
        REQUEST_CONTEXT_REFERENCE.set(null);
        CONTEXT_HOOK_COUNTER.set(0);
    }

    @CsvSource({
            "/default-service, false",
            "/default-service-with-access-log-writer, true",
            "/transit-service, false",
            "/transit-service-with-access-logger, false",
            "/transit-service-with-access-log-option, false",
            "/transit-service-with-access-log-option-and-access-logger, true"
    })
    @ParameterizedTest
    void testAccessLogger(String path, boolean shouldWriteAccessLog) throws Exception {
        assertThat(server.blockingWebClient().get(path).status().code())
                .as("Response status for path: %s", path)
                .isEqualTo(200);

        assertThat(server.requestContextCaptor().size())
                .as("Expected exactly one captured context for path: %s", path)
                .isEqualTo(1);

        final ServiceRequestContext ctx = server.requestContextCaptor().poll();
        assertThat(ctx)
                .as("ServiceRequestContext should not be null for path: %s", path)
                .isNotNull();

        if (shouldWriteAccessLog) {
            await().untilAsserted(() ->
                    assertThat(REQUEST_CONTEXT_REFERENCE)
                            .as("Expected request context to be set for path: %s", path)
                            .hasValue(ctx)
            );
        }

        final int expectedHookCounter = shouldWriteAccessLog ? 1 : 0;
        assertThat(CONTEXT_HOOK_COUNTER)
                .as("Context hook counter mismatch for path: %s", path)
                .hasValue(expectedHookCounter);
    }
}
