/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.metric.MicrometerUtil;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.observation.ObservationService;
import com.linecorp.armeria.server.observation.ServiceObservationContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;

class TransientServiceRouteDecoratorTest {

    private static final Set<String> accessLoggedPaths = ConcurrentHashMap.newKeySet();
    private static final Set<String> serviceLoggedPaths = ConcurrentHashMap.newKeySet();
    private static final Set<String> observedPaths = ConcurrentHashMap.newKeySet();
    private static final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final ObservationRegistry observationRegistry = ObservationRegistry.create();

    static {
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler<ServiceObservationContext>() {
                    @Override
                    public void onStart(ServiceObservationContext context) {
                        observedPaths.add(context.requestContext().path());
                    }

                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return context instanceof ServiceObservationContext;
                    }
                });
    }

    private static final LogWriter serviceLogWriter = new LogWriter() {
        @Override
        public void logRequest(RequestOnlyLog log) {}

        @Override
        public void logResponse(RequestLog log) {
            serviceLoggedPaths.add(log.context().path());
        }
    };

    private static final HttpService fooService = (ctx, req) -> HttpResponse.of("foo");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.meterRegistry(meterRegistry);
            sb.accessLogWriter(log -> accessLoggedPaths.add(log.context().path()), false);
            sb.decorator(MetricCollectingService.newDecorator(MeterIdPrefixFunction.of(
                    (registry, log) -> new MeterIdPrefix("transient.test", "path",
                                                         log.context().path()))));
            sb.decorator(LoggingService.builder()
                                       .logWriter(serviceLogWriter)
                                       .newDecorator());
            sb.decorator(ObservationService.newDecorator(observationRegistry));

            sb.service("/non-transient", fooService);
            sb.service("/binding-transient", fooService.decorate(TransientHttpService.newDecorator()));

            sb.service("/route-transient", fooService);
            sb.decorator("/route-transient", TransientHttpService.newDecorator());

            sb.service("/route-transient-with-access-logging", fooService);
            sb.decorator("/route-transient-with-access-logging",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));

            sb.service("/route-transient-with-tracing", fooService);
            sb.decorator("/route-transient-with-tracing",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_TRACING,
                                                           TransientServiceOption.WITH_METRIC_COLLECTION));

            // The route decorator takes precedence over the decorator bound to the service.
            sb.service("/binding-with-access-logging-route-none",
                       fooService.decorate(TransientHttpService.newDecorator(
                               TransientServiceOption.WITH_ACCESS_LOGGING)));
            sb.decorator("/binding-with-access-logging-route-none", TransientHttpService.newDecorator());
            sb.service("/binding-none-route-with-access-logging",
                       fooService.decorate(TransientHttpService.newDecorator()));
            sb.decorator("/binding-none-route-with-access-logging",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));

            // The outermost route decorator takes precedence. With the default route decorator ordering
            // (Flags.useLegacyRouteDecoratorOrdering() is false), the last added one is the outermost.
            sb.service("/outer-none/a", fooService);
            sb.decorator("/outer-none/a",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));
            sb.decoratorUnder("/outer-none/", TransientHttpService.newDecorator());
            sb.service("/outer-with-access-logging/a", fooService);
            sb.decoratorUnder("/outer-with-access-logging/", TransientHttpService.newDecorator());
            sb.decorator("/outer-with-access-logging/a",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));

            sb.service("/route-transient-non-matching", fooService);
            sb.decorator("/non-matching", TransientHttpService.newDecorator());

            // HealthCheckService is a TransientService without any TransientServiceOptions by default.
            sb.service("/health", HealthCheckService.of());
            sb.service("/health-with-access-logging", HealthCheckService.of());
            sb.decorator("/health-with-access-logging",
                         TransientHttpService.newDecorator(TransientServiceOption.WITH_ACCESS_LOGGING));
        }
    };

    @BeforeEach
    void setUp() {
        accessLoggedPaths.clear();
        serviceLoggedPaths.clear();
        observedPaths.clear();
        meterRegistry.clear();
        MicrometerUtil.clear();
    }

    @CsvSource({
            "/non-transient,                          true,  true,  true,  true",
            "/binding-transient,                      false, false, false, false",
            "/route-transient,                        false, false, false, false",
            "/route-transient-with-access-logging,    true,  false, false, false",
            "/route-transient-with-tracing,           false, true,  false, true",
            "/binding-with-access-logging-route-none, false, false, false, false",
            "/binding-none-route-with-access-logging, true,  false, false, false",
            "/outer-none/a,                           false, false, false, false",
            "/outer-with-access-logging/a,            true,  false, false, false",
            "/route-transient-non-matching,           true,  true,  true,  true",
            "/health,                                 false, false, false, false",
            "/health-with-access-logging,             true,  false, false, false",
    })
    @ParameterizedTest
    void routeDecorator(String path, boolean accessLogging, boolean metricCollection,
                        boolean serviceLogging, boolean observation) throws Exception {
        assertThat(server.blockingWebClient().get(path).status().code()).isEqualTo(200);
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        ctx.log().whenComplete().join();

        // Wait until all expected side effects happen.
        await().untilAsserted(() -> {
            if (accessLogging) {
                assertThat(accessLoggedPaths).as("access logging").contains(path);
            }
            if (metricCollection) {
                assertThat(hasMetrics(path)).as("metric collection").isTrue();
            }
            if (serviceLogging) {
                assertThat(serviceLoggedPaths).as("service logging").contains(path);
            }
            if (observation) {
                assertThat(observedPaths).as("observation").contains(path);
            }
        });
        // Make sure that the unexpected side effects never happen.
        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(accessLoggedPaths.contains(path)).as("access logging").isEqualTo(accessLogging);
            assertThat(hasMetrics(path)).as("metric collection").isEqualTo(metricCollection);
            assertThat(serviceLoggedPaths.contains(path)).as("service logging").isEqualTo(serviceLogging);
            assertThat(observedPaths.contains(path)).as("observation").isEqualTo(observation);
        });
    }

    private static boolean hasMetrics(String path) {
        return meterRegistry.find("transient.test.requests").tag("path", path).counter() != null;
    }

    @Test
    void routeDecoratorShouldExcludeRequestFromPendingResponses() throws Exception {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final AtomicBoolean transientServiceCalled = new AtomicBoolean();
        final HttpResponseWriter transientResponse = HttpResponse.streaming();
        final HttpResponseWriter nonTransientResponse = HttpResponse.streaming();
        final Server gracefulServer = Server.builder()
                                            .meterRegistry(registry)
                                            .requestTimeoutMillis(0)
                                            .gracefulShutdownTimeoutMillis(1000, 2000)
                                            .service("/transient", (ctx, req) -> {
                                                transientServiceCalled.set(true);
                                                return transientResponse;
                                            })
                                            .decorator("/transient", TransientHttpService.newDecorator())
                                            .service("/non-transient", (ctx, req) -> nonTransientResponse)
                                            .build();
        gracefulServer.start().join();
        try {
            final WebClient client = WebClient.of("http://127.0.0.1:" + gracefulServer.activeLocalPort());
            final CompletableFuture<AggregatedHttpResponse> res1 = client.get("/transient").aggregate();
            await().untilTrue(transientServiceCalled);
            final CompletableFuture<AggregatedHttpResponse> res2 = client.get("/non-transient").aggregate();
            // Only the request to the non-transient service should be counted as a pending response.
            // 'armeria.server.pending.responses' reports the number of the responses that
            // the graceful shutdown waits for.
            await().untilAsserted(() -> assertThat(pendingResponses(registry)).isPositive());
            await().during(Duration.ofMillis(500))
                   .atMost(Duration.ofSeconds(3))
                   .untilAsserted(() -> assertThat(pendingResponses(registry)).isOne());

            transientResponse.write(ResponseHeaders.of(200));
            transientResponse.close();
            nonTransientResponse.write(ResponseHeaders.of(200));
            nonTransientResponse.close();
            res1.join();
            res2.join();
            await().untilAsserted(() -> assertThat(pendingResponses(registry)).isZero());
        } finally {
            gracefulServer.stop().join();
        }
    }

    @Test
    void routeDecoratorWithNullOptionsShouldFailOnBuild() {
        final ServerBuilder sb = Server.builder()
                                       .service("/", fooService)
                                       .decorator("/", delegate -> new NullOptionsTransientService(delegate));
        assertThatThrownBy(sb::build).isInstanceOf(NullPointerException.class)
                                     .hasMessageContaining("transientServiceOptions() returned null");
    }

    private static double pendingResponses(MeterRegistry registry) {
        return registry.get("armeria.server.pending.responses").gauge().value();
    }

    private static final class NullOptionsTransientService extends SimpleDecoratingHttpService
            implements TransientHttpService {

        NullOptionsTransientService(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req);
        }

        @Override
        @SuppressWarnings("NullAway")
        public Set<TransientServiceOption> transientServiceOptions() {
            return null;
        }
    }
}
