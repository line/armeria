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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class RequestOptionsTest {

    final AttributeKey<String> foo = AttributeKey.valueOf("foo");
    final AttributeKey<String> bar = AttributeKey.valueOf("bar");
    private static final AtomicInteger retryAttempts = new AtomicInteger();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/ping", (ctx, req) -> HttpResponse.of("pong"));
            sb.service("/retryName", (ctx, req) -> HttpResponse.of(
                    retryAttempts.getAndIncrement() == 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK));
            sb.service("/redirectName", (ctx, req) -> HttpResponse.of(
                    ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT, HttpHeaderNames.LOCATION, "/ping")));
        }
    };

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
        retryAttempts.set(0);
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setAttributes(boolean usePreparation) {
        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final CompletableFuture<AggregatedHttpResponse> res;
            if (usePreparation) {
                res = client.prepare()
                            .get("/ping")
                            .attr(foo, "bar")
                            .execute()
                            .aggregate();
            } else {
                final HttpRequest req = HttpRequest.builder().get("/ping").build();
                final RequestOptions options = RequestOptions.builder().attr(foo, "bar").build();
                res = client.execute(req, options).aggregate();
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.ownAttr(foo)).isEqualTo("bar");
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setLogName(boolean usePreparation) {
        final WebClient client = WebClient.builder(server.httpUri())
                .decorator((delegate, ctx, req) -> {
                    final RequestLogAccess log = ctx.log();
                    assertThat(log.isAvailable(RequestLogProperty.NAME)).isTrue();
                    assertThat(log.partial().serviceName()).isEqualTo("example.Service");
                    assertThat(log.partial().name()).isEqualTo("ping");
                    return delegate.execute(ctx, req);
                }).build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            if (usePreparation) {
                client.prepare().get("/ping").name("example.Service", "ping").execute().aggregate().join();
            } else {
                client.execute(HttpRequest.of(HttpMethod.GET, "/ping"),
                               RequestOptions.builder().name("example.Service", "ping").build())
                      .aggregate().join();
            }
            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.serviceName()).isEqualTo("example.Service");
            assertThat(log.name()).isEqualTo("ping");
        }
    }

    @Test
    void logNameOptions() {
        assertThat(RequestOptions.of().serviceName()).isNull();
        assertThat(RequestOptions.of().name()).isNull();
        assertThat(RequestOptions.builder().build()).isSameAs(RequestOptions.of());
        final RequestOptions options = RequestOptions.builder().name("example.Service", "ping").build();
        assertThat(options.serviceName()).isEqualTo("example.Service");
        assertThat(options.name()).isEqualTo("ping");
        assertThat(options.toBuilder().build()).isEqualTo(options).hasSameHashCodeAs(options);
        assertThat(options.toString()).contains("serviceName=example.Service", "name=ping");
        assertThat(RequestOptions.builder().name("other.Service", "ping").build()).isNotEqualTo(options);
        assertThat(RequestOptions.builder().name("example.Service", "other").build()).isNotEqualTo(options);
        final RequestOptions methodOnly = options.toBuilder().name("other").build();
        assertThat(methodOnly.serviceName()).isNull();
        assertThat(methodOnly.name()).isEqualTo("other");
    }

    @Test
    void invalidLogNames() {
        assertThatNullPointerException().isThrownBy(() -> RequestOptions.builder().name(null));
        assertThatNullPointerException().isThrownBy(() -> RequestOptions.builder().name(null, "ping"));
        assertThatNullPointerException().isThrownBy(() -> RequestOptions.builder().name("service", null));
        assertThatThrownBy(() -> RequestOptions.builder().name(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOptions.builder().name("", "ping"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RequestOptions.builder().name("service", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copyLogNameIntoPreparation() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.prepare().get("/ping").name("old.Service", "old")
                  .requestOptions(RequestOptions.builder().name("ping").build())
                  .execute().aggregate().join();
            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.serviceName()).isNull();
            assertThat(log.name()).isEqualTo("ping");
        }
    }

    @Test
    void transformedAndBlockingLogNames() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.prepare().get("/ping").asString().name("future").execute().join();
            client.blocking().prepare().get("/ping").name("blocking").execute();
            client.blocking().prepare().get("/ping").asString().name("transformed").execute();
            RestClient.of(client).get("/ping").name("rest").execute(ResponseAs.string()).join();
            assertThat(captor.getAll()).extracting(ctx -> ctx.log().whenComplete().join().name())
                                      .containsExactly("future", "blocking", "transformed", "rest");
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void logNamePropagatesToAttempts(boolean retry) {
        final WebClientBuilder builder = WebClient.builder(server.httpUri());
        if (retry) {
            builder.decorator(RetryingClient.newDecorator(
                    RetryRule.onServerErrorStatus(Backoff.withoutDelay())));
        } else {
            builder.followRedirects();
        }
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            builder.build().prepare().get(retry ? "/retryName" : "/redirectName")
                   .name("example.Service", "ping").attr(foo, "bar").execute().aggregate().join();
            final RequestLog log = captor.get().log().whenComplete().join();
            assertThat(log.serviceName()).isEqualTo("example.Service");
            assertThat(log.name()).isEqualTo("ping");
            assertThat(log.children()).hasSize(2);
            for (RequestLogAccess child : log.children()) {
                final RequestLog childLog = child.whenComplete().join();
                assertThat(childLog.serviceName()).isEqualTo("example.Service");
                assertThat(childLog.name()).isEqualTo("ping");
                assertThat(childLog.context().attr(foo)).isEqualTo("bar");
            }
        }
    }

    @Test
    void explicitNameTakesPrecedenceOverContextCustomizer() {
        final WebClient client = WebClient.builder(server.httpUri())
                .contextCustomizer(ctx -> ctx.logBuilder().name("customizer"))
                .build();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.prepare().get("/ping").name("explicit").execute().aggregate().join();
            assertThat(captor.get().log().whenComplete().join().name()).isEqualTo("explicit");
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setResponseTimeout(boolean usePreparation) {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final Duration timeout = Duration.ofSeconds(42);
            final CompletableFuture<AggregatedHttpResponse> res;
            if (usePreparation) {
                res = client.prepare()
                            .get("/ping")
                            .responseTimeout(timeout)
                            .execute()
                            .aggregate();
            } else {
                final HttpRequest req = HttpRequest.builder().get("/ping").build();
                final RequestOptions options = RequestOptions.builder().responseTimeout(timeout).build();
                res = client.execute(req, options).aggregate();
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(timeout.toMillis());
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setWriteTimeout(boolean usePreparation) {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final Duration timeout = Duration.ofSeconds(21);
            final CompletableFuture<AggregatedHttpResponse> res;
            if (usePreparation) {
                res = client.prepare()
                            .get("/ping")
                            .writeTimeout(timeout)
                            .execute()
                            .aggregate();
            } else {
                final HttpRequest req = HttpRequest.builder().get("/ping").build();
                final RequestOptions options = RequestOptions.builder().writeTimeout(timeout).build();
                res = client.execute(req, options).aggregate();
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.writeTimeoutMillis()).isEqualTo(timeout.toMillis());
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setMaxResponseLength(boolean usePreparation) {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final int maxResponseLength = 4242;
            final CompletableFuture<AggregatedHttpResponse> res;
            if (usePreparation) {
                res = client.prepare()
                            .get("/ping")
                            .maxResponseLength(maxResponseLength)
                            .execute()
                            .aggregate();
            } else {
                final HttpRequest req = HttpRequest.builder().get("/ping").build();
                final RequestOptions options = RequestOptions.builder()
                                                             .maxResponseLength(maxResponseLength)
                                                             .build();
                res = client.execute(req, options).aggregate();
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.maxResponseLength()).isEqualTo(maxResponseLength);
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setRequestAutoAbortDelayMillis(boolean usePreparation) {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final long requestAutoAbortDelayMillis = 2000;
            final StreamWriter<HttpData> streaming = StreamMessage.streaming();
            final CompletableFuture<AggregatedHttpResponse> res;
            if (usePreparation) {
                res = client.prepare()
                            .get("/ping")
                            .content(MediaType.ANY_TYPE, streaming)
                            .requestAutoAbortDelayMillis(requestAutoAbortDelayMillis)
                            .execute()
                            .aggregate();
            } else {
                final HttpRequest req = HttpRequest.builder()
                                                   .get("/ping")
                                                   .content(MediaType.ANY_TYPE, streaming)
                                                   .build();
                final RequestOptions options =
                        RequestOptions.builder()
                                      .requestAutoAbortDelayMillis(requestAutoAbortDelayMillis)
                                      .build();
                res = client.execute(req, options).aggregate();
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.requestAutoAbortDelayMillis()).isEqualTo(requestAutoAbortDelayMillis);
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
            // Buffer 1 second
            await().atLeast(1, TimeUnit.SECONDS).until(() -> ctx.log().isRequestComplete());
        }
    }

    @Test
    void overwriteTest() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final RequestOptions requestOptions = RequestOptions.builder()
                                                                .responseTimeoutMillis(2000)
                                                                .writeTimeoutMillis(1000)
                                                                .maxResponseLength(1028)
                                                                .requestAutoAbortDelayMillis(1000)
                                                                .attr(foo, "hello")
                                                                .attr(bar, "options")
                                                                .build();
            final CompletableFuture<AggregatedHttpResponse> res;
            res = client.prepare()
                        .get("/ping")
                        .maxResponseLength(10)
                        .responseTimeoutMillis(500)
                        .writeTimeoutMillis(300)
                        .requestAutoAbortDelayMillis(300)
                        .attr(foo, "world")
                        .requestOptions(requestOptions)
                        .execute()
                        .aggregate();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(requestOptions.responseTimeoutMillis());
            assertThat(ctx.writeTimeoutMillis()).isEqualTo(requestOptions.writeTimeoutMillis());
            assertThat(ctx.maxResponseLength()).isEqualTo(requestOptions.maxResponseLength());
            assertThat(ctx.requestAutoAbortDelayMillis()).isEqualTo(
                    requestOptions.requestAutoAbortDelayMillis());
            final Iterator<Entry<AttributeKey<?>, Object>> attrs = ctx.attrs();
            while (attrs.hasNext()) {
                final Entry<AttributeKey<?>, Object> next = attrs.next();
                assertThat(requestOptions.attrs()).contains(next);
            }
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @Test
    void copyFromOtherOptions() {
        final AttributeKey<String> quz = AttributeKey.valueOf("quz");
        final ClientTlsSpec spec = ClientTlsSpec.builder().build();
        final RequestOptions requestOptions1 = RequestOptions.builder()
                                                             .responseTimeoutMillis(2000)
                                                             .writeTimeoutMillis(1000)
                                                             .maxResponseLength(1028)
                                                             .requestAutoAbortDelayMillis(1000)
                                                             .attr(foo, "hello")
                                                             .attr(bar, "options")
                                                             .clientTlsSpec(spec)
                                                             .build();

        final RequestOptions requestOptions2 = RequestOptions.builder(requestOptions1)
                                                             .maxResponseLength(3000)
                                                             .attr(foo, "world")
                                                             .attr(quz, "Armeria")
                                                             .build();

        assertThat(requestOptions2.responseTimeoutMillis()).isEqualTo(requestOptions1.responseTimeoutMillis());
        assertThat(requestOptions2.writeTimeoutMillis()).isEqualTo(requestOptions1.writeTimeoutMillis());
        assertThat(requestOptions2.requestAutoAbortDelayMillis()).isEqualTo(
                requestOptions1.requestAutoAbortDelayMillis());
        assertThat(requestOptions2.maxResponseLength()).isEqualTo(3000);
        assertThat(requestOptions1.clientTlsSpec()).isSameAs(requestOptions2.clientTlsSpec());
        final Map<AttributeKey<?>, Object> attrs = requestOptions2.attrs();
        assertThat(attrs.get(foo)).isEqualTo("world");
        assertThat(attrs.get(bar)).isEqualTo("options");
        assertThat(attrs.get(quz)).isEqualTo("Armeria");
    }

    @Test
    void localAddressInRequestOptions() throws Exception {
        final InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        final RequestOptions options = RequestOptions.builder()
                                                     .localBindAddress(addr)
                                                     .build();
        assertThat(options.localBindAddress()).isEqualTo(addr);
    }

    @Test
    void localAddressNullInEmptyOptions() {
        assertThat(RequestOptions.of().localBindAddress()).isNull();
    }

    @Test
    void localAddressCopiedFromOtherOptions() throws Exception {
        final InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        final RequestOptions original = RequestOptions.builder()
                                                      .localBindAddress(addr)
                                                      .build();
        final RequestOptions copied = RequestOptions.builder(original).build();
        assertThat(copied.localBindAddress()).isEqualTo(addr);
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void setLocalAddress(boolean usePreparation) throws Exception {
        final InetSocketAddress addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final CompletableFuture<AggregatedHttpResponse> res;
            if (usePreparation) {
                res = client.prepare()
                            .get("/ping")
                            .localBindAddress(addr)
                            .execute()
                            .aggregate();
            } else {
                final HttpRequest req = HttpRequest.builder().get("/ping").build();
                final RequestOptions options = RequestOptions.builder().localBindAddress(addr).build();
                res = client.execute(req, options).aggregate();
            }
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.localBindAddress()).isEqualTo(addr);
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }
}
