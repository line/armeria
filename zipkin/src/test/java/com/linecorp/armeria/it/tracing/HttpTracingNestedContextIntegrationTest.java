/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.it.tracing;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.common.HttpStatus.INTERNAL_SERVER_ERROR;
import static com.linecorp.armeria.common.HttpStatus.OK;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.with;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.awaitility.core.ConditionTimeoutException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.tracing.HttpTracingService;
import com.linecorp.armeria.testing.server.ServerRule;

import brave.Tracing;
import brave.propagation.CurrentTraceContext;
import brave.sampler.Sampler;
import zipkin2.Span;

public class HttpTracingNestedContextIntegrationTest {

    private static final ReporterImpl spanReporter = new ReporterImpl();

    private HttpClient poolHttpClient;

    private final CountDownLatch waitCreateCache = new CountDownLatch(1);

    @Rule
    public final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            final CountDownLatch countDownLatch = new CountDownLatch(2);
            final AtomicReference<CompletableFuture<HttpStatus>> cache = new AtomicReference<>();

            sb.service("/non-trace", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    if (Tracing.currentTracer().currentSpan() != null) {
                        return HttpResponse.of(INTERNAL_SERVER_ERROR);
                    }
                    return HttpResponse.of(OK);
                }
            });

            sb.service("/create-cache", decorate("service/create-cache", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    final CompletableFuture<HttpStatus> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            countDownLatch.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return OK;
                    }, RequestContext.current().contextAwareEventLoop());
                    cache.set(future);
                    waitCreateCache.countDown();
                    return HttpResponse.from(future.thenApply(status -> {
                        if (Tracing.currentTracer().currentSpan() == null) {
                            return HttpResponse.of(INTERNAL_SERVER_ERROR);
                        }
                        return HttpResponse.of(status);
                    }));
                }
            }));

            sb.service("/read-cache", decorate("service/read-cache", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    try {
                        final RequestContext requestContext = RequestContext.current();
                        return HttpResponse.from(
                                cache.get().thenApply(status -> {
                                    try (SafeCloseable ignored = RequestContext.push(requestContext)) {
                                        if (Tracing.currentTracer().currentSpan() == null) {
                                            return HttpResponse.of(INTERNAL_SERVER_ERROR);
                                        }
                                        return HttpResponse.of(status);
                                    }
                                }));
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            }));
        }
    };

    @Before
    public void setupClients() {
        poolHttpClient = HttpClient.of(server.uri("/"));
    }

    @After
    public void tearDown() {
        Tracing.current().close();
    }

    @After
    public void shouldHaveNoExtraSpans() {
        assertThat(spanReporter.getSpans()).isEmpty();
    }

    private static HttpTracingService decorate(String name, Service<HttpRequest, HttpResponse> service) {
        return HttpTracingService.newDecorator(newTracing(name)).apply(service);
    }

    private static Tracing newTracing(String name) {
        return Tracing.newBuilder()
                      .currentTraceContext(CurrentTraceContext.Default.create())
                      .localServiceName(name)
                      .spanReporter(spanReporter)
                      .sampler(Sampler.ALWAYS_SAMPLE)
                      .build();
    }

    @Test(timeout = 20000)
    public void testNestedRequestContext() throws Exception {
        final CompletableFuture<AggregatedHttpMessage> create = poolHttpClient.get("/create-cache").aggregate();
        waitCreateCache.await(3, SECONDS);
        final CompletableFuture<AggregatedHttpMessage> read1 = poolHttpClient.get("/read-cache").aggregate();
        final CompletableFuture<AggregatedHttpMessage> read2 = poolHttpClient.get("/read-cache").aggregate();

        assertThat(create.get().status()).isEqualTo(OK);
        assertThat(read1.get().status()).isEqualTo(OK);
        assertThat(read2.get().status()).isEqualTo(OK);

        final Span[] spans = spanReporter.take(3);
        assertThat(Arrays.stream(spans).map(Span::traceId).collect(toImmutableSet())).hasSize(3);

        try {
            with().pollInterval(10, MILLISECONDS)
                  .then()
                  .atMost(10, SECONDS)
                  .untilAsserted(
                          () -> assertThat(poolHttpClient.get("/non-trace").aggregate().get().status())
                                  .isEqualTo(INTERNAL_SERVER_ERROR));
            fail("There is a leaked context.");
        } catch (ConditionTimeoutException ignored) {
        }
    }
}
