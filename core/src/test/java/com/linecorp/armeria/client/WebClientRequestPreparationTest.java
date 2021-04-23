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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class WebClientRequestPreparationTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("root"));
            sb.service("/ping", (ctx, req) -> HttpResponse.of("pong"));
            sb.service("exact:/%7B%7D", (ctx, req) -> HttpResponse.of("{}"));
            sb.service("exact:/:", (ctx, req) -> HttpResponse.of(":"));
            sb.service("exact:/%7B%7D/%7B%7D", (ctx, req) -> HttpResponse.of("{}/{}"));
            sb.service("exact:/%7B%7D/:", (ctx, req) -> HttpResponse.of("{}/:"));
            sb.service("exact:/:/%7B%7D", (ctx, req) -> HttpResponse.of(":/{}"));
            sb.service("exact:/:/:", (ctx, req) -> HttpResponse.of(":/:"));
            sb.service("exact:/%7Bunmatched", (ctx, req) -> HttpResponse.of("unmatched"));
            sb.service("/query", (ctx, req) -> HttpResponse.of(String.valueOf(ctx.query())));
        }
    };

    @Test
    void shouldRejectEmptyPathParams() {
        assertThatThrownBy(() -> WebClient.of().prepare().pathParam("", "foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");

        assertThatThrownBy(() -> WebClient.of().prepare().pathParams(ImmutableMap.of("", "foo")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void shouldFailOnMissingPathParam(boolean curly) {
        assertThatThrownBy(() -> WebClient.of(server.httpUri())
                                          .prepare()
                                          .get(curly ? "/{foo}" : "/:foo")
                                          .execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("param 'foo'");
    }

    @Test
    void setAttributes() {
        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final CompletableFuture<AggregatedHttpResponse> res =
                    WebClient.of(server.httpUri())
                             .prepare()
                             .get("/ping")
                             .attr(foo, "bar")
                             .execute()
                             .aggregate();
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.ownAttr(foo)).isEqualTo("bar");
            assertThat(res.join().contentUtf8()).isEqualTo("pong");
        }
    }

    @Test
    void absoluteUri() {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .prepare()
                         .get(server.httpUri().resolve("/ping").toASCIIString())
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("pong");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void absoluteUriWithPathParam(boolean curly) {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .prepare()
                         .get(server.httpUri() + (curly ? "/{path}": "/:path"))
                         .pathParam("path", "ping")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("pong");
    }

    @Test
    void absoluteUriWithoutPath() {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .prepare()
                         .get(server.httpUri().toASCIIString())
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("root");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void shouldIgnoreEmptyPathParam(boolean curly) {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get(curly ? "/{}" : "/:")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(curly ? "{}" : ":");
    }

    @ParameterizedTest
    @CsvSource({ "true, true", "true, false", "false, true", "false, false" })
    void shouldIgnoreMultipleEmptyPathParams(boolean firstCurly, boolean secondCurly) {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get((firstCurly ? "/{}" : "/:") + (secondCurly ? "/{}" : "/:"))
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo((firstCurly ? "{}" : ":") + '/' + (secondCurly ? "{}" : ":"));
    }

    @Test
    void shouldIgnoreUnmatchedCurlyPathParam() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/{unmatched")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("unmatched");
    }

    @Test
    void queryParams() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/query")
                         .queryParam("foo", "bar")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("foo=bar");
    }

    @Test
    void queryParamsInPath() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/query?baz=qux")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("baz=qux");
    }

    @Test
    void queryParamsMixed() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/query?foo=bar")
                         .queryParam("baz", "qux")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("foo=bar&baz=qux");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void pathParamAndQueryParam(boolean curly) {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get(curly ? "/{path}" : "/:path")
                         .pathParam("path", "query")
                         .queryParam("foo", "bar")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("foo=bar");
    }

    @ParameterizedTest
    @CsvSource({ "true", "false" })
    void pathParamAndQueryParamsMixed(boolean curly) {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get((curly ? "/{path}" : "/:path") + "?foo=bar")
                         .pathParam("path", "query")
                         .queryParam("baz", "qux")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("foo=bar&baz=qux");
    }
}
