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

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class RequestOptionsTest {

    final AttributeKey<String> foo = AttributeKey.valueOf("foo");
    final AttributeKey<String> bar = AttributeKey.valueOf("bar");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/ping", (ctx, req) -> HttpResponse.of("pong"));
        }
    };

    private WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
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

    @Test
    void overwriteTest() {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final RequestOptions requestOptions = RequestOptions.builder()
                                                                .responseTimeoutMillis(2000)
                                                                .writeTimeoutMillis(1000)
                                                                .maxResponseLength(1028)
                                                                .attr(foo, "hello")
                                                                .attr(bar, "options")
                                                                .build();
            final CompletableFuture<AggregatedHttpResponse> res;
            res = client.prepare()
                        .get("/ping")
                        .maxResponseLength(10)
                        .responseTimeoutMillis(500)
                        .writeTimeoutMillis(300)
                        .attr(foo, "world")
                        .requestOptions(requestOptions)
                        .execute()
                        .aggregate();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(requestOptions.responseTimeoutMillis());
            assertThat(ctx.writeTimeoutMillis()).isEqualTo(requestOptions.writeTimeoutMillis());
            assertThat(ctx.maxResponseLength()).isEqualTo(requestOptions.maxResponseLength());
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
        final RequestOptions requestOptions1 = RequestOptions.builder()
                                                             .responseTimeoutMillis(2000)
                                                             .writeTimeoutMillis(1000)
                                                             .maxResponseLength(1028)
                                                             .attr(foo, "hello")
                                                             .attr(bar, "options")
                                                             .build();

        final RequestOptions requestOptions2 = RequestOptions.builder(requestOptions1)
                                                             .maxResponseLength(3000)
                                                             .attr(foo, "world")
                                                             .attr(quz, "Armeria")
                                                             .build();

        assertThat(requestOptions2.responseTimeoutMillis()).isEqualTo(requestOptions1.responseTimeoutMillis());
        assertThat(requestOptions2.writeTimeoutMillis()).isEqualTo(requestOptions1.writeTimeoutMillis());
        assertThat(requestOptions2.maxResponseLength()).isEqualTo(3000);
        final Map<AttributeKey<?>, Object> attrs = requestOptions2.attrs();
        assertThat(attrs.get(foo)).isEqualTo("world");
        assertThat(attrs.get(bar)).isEqualTo("options");
        assertThat(attrs.get(quz)).isEqualTo("Armeria");
    }
}
