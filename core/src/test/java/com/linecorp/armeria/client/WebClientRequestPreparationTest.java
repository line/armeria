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

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

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
            sb.serviceUnder("/", (ctx, req) -> HttpResponse.of(
                    ctx.decodedPath() + (ctx.query() != null ? '?' + ctx.query() : "")));
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
            assertThat(res.join().contentUtf8()).isEqualTo("/ping");
        }
    }

    @ParameterizedTest
    @CsvSource({
            ",      /",
            "/,     /",
            "/foo,  /foo",
            "/foo/, /foo/",
            "/:,    /:",
            "/:/,   /:/",
            "/:/:,  /:/:",
            "/:/:/, /:/:/",
            // Note: We don't test '{}' because absolute URIs don't accept '{' or '}'.
    })
    void absoluteUriWithoutPathParams(@Nullable String path, String expectedPath) {
        path = firstNonNull(path, "");
        final AggregatedHttpResponse res =
                WebClient.of()
                         .prepare()
                         .get(server.httpUri() + path)
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @CsvSource({
            ",            /",
            "/,           /",
            "/foo,        /foo",
            "/foo/,       /foo/",
            "/{},         /{}",
            "/:,          /:",
            "/{}/,        /{}/",
            "/:/,         /:/",
            "/{}/{},      /{}/{}",
            "/{}/:,       /{}/:",
            "/:/:,        /:/:",
            "/:/{},       /:/{}",
            "/{}/{}/,     /{}/{}/",
            "/{}/:/,      /{}/:/",
            "/:/:/,       /:/:/",
            "/:/{}/,      /:/{}/",
            "/{unmatched, /{unmatched"
    })
    void pathWithoutPathParams(@Nullable String path, String expectedPath) {
        path = firstNonNull(path, "");
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get(path)
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @CsvSource({
            "/{path},        /ping",
            "/:path,         /ping",
            "/{path}/,       /ping/",
            "/:path/,        /ping/",
            "/{path}/:,      /ping/:",
            "/:path/:,       /ping/:",
            "/{path}/:/,     /ping/:/",
            "/:path/:/,      /ping/:/",
            "/{path}/:/pong, /ping/:/pong",
            "/:path/:/pong,  /ping/:/pong",
            // Note: We don't test '{}' because absolute URIs don't accept '{' or '}'.
    })
    void absoluteUriWithPathParam(String path, String expectedPath) {
        final AggregatedHttpResponse res =
                WebClient.of()
                         .prepare()
                         .get(server.httpUri() + path)
                         .pathParam("path", "ping")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @CsvSource({
            "/{path},         /ping",
            "/:path,          /ping",
            "/{path}/,        /ping/",
            "/:path/,         /ping/",
            "/{path}/{},      /ping/{}",
            "/:path/{},       /ping/{}",
            "/{path}/:,       /ping/:",
            "/:path/:,        /ping/:",
            "/{path}/{}/,     /ping/{}/",
            "/:path/{}/,      /ping/{}/",
            "/{path}/:/,      /ping/:/",
            "/:path/:/,       /ping/:/",
            "/{path}/{}/pong, /ping/{}/pong",
            "/:path/{}/pong,  /ping/{}/pong",
            "/{path}/:/pong,  /ping/:/pong",
            "/:path/:/pong,   /ping/:/pong",
    })
    void pathWithPathParam(String path, String expectedPath) {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get(path)
                         .pathParam("path", "ping")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(expectedPath);
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
        assertThat(res.contentUtf8()).isEqualTo("/query?foo=bar");
    }

    @Test
    void queryParamsInPath() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/query?alice=bob")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("/query?alice=bob");
    }

    @Test
    void queryParamsMixed() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/query?foo=bar")
                         .queryParam("alice", "bob")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("/query?foo=bar&alice=bob");
    }

    @ParameterizedTest
    @CsvSource({
            // No query string in path
            "/{path},         /ping?alice=bob",
            "/:path,          /ping?alice=bob",
            "/{path}/,        /ping/?alice=bob",
            "/:path/,         /ping/?alice=bob",
            "/{path}/{},      /ping/{}?alice=bob",
            "/:path/{},       /ping/{}?alice=bob",
            "/{path}/:,       /ping/:?alice=bob",
            "/:path/:,        /ping/:?alice=bob",
            "/{path}/{}/,     /ping/{}/?alice=bob",
            "/:path/{}/,      /ping/{}/?alice=bob",
            "/{path}/:/,      /ping/:/?alice=bob",
            "/:path/:/,       /ping/:/?alice=bob",
            "/{path}/{}/pong, /ping/{}/pong?alice=bob",
            "/:path/{}/pong,  /ping/{}/pong?alice=bob",
            "/{path}/:/pong,  /ping/:/pong?alice=bob",
            "/:path/:/pong,   /ping/:/pong?alice=bob",
            // A query string in path
            "/{path}?foo=bar,         /ping?foo=bar&alice=bob",
            "/:path?foo=bar,          /ping?foo=bar&alice=bob",
            "/{path}/?foo=bar,        /ping/?foo=bar&alice=bob",
            "/:path/?foo=bar,         /ping/?foo=bar&alice=bob",
            "/{path}/{}?foo=bar,      /ping/{}?foo=bar&alice=bob",
            "/:path/{}?foo=bar,       /ping/{}?foo=bar&alice=bob",
            "/{path}/:?foo=bar,       /ping/:?foo=bar&alice=bob",
            "/:path/:?foo=bar,        /ping/:?foo=bar&alice=bob",
            "/{path}/{}/?foo=bar,     /ping/{}/?foo=bar&alice=bob",
            "/:path/{}/?foo=bar,      /ping/{}/?foo=bar&alice=bob",
            "/{path}/:/?foo=bar,      /ping/:/?foo=bar&alice=bob",
            "/:path/:/?foo=bar,       /ping/:/?foo=bar&alice=bob",
            "/{path}/{}/pong?foo=bar, /ping/{}/pong?foo=bar&alice=bob",
            "/:path/{}/pong?foo=bar,  /ping/{}/pong?foo=bar&alice=bob",
            "/{path}/:/pong?foo=bar,  /ping/:/pong?foo=bar&alice=bob",
            "/:path/:/pong?foo=bar,   /ping/:/pong?foo=bar&alice=bob",
    })
    void pathParamAndQueryParams(String path, String expectedPath) {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get(path)
                         .pathParam("path", "ping")
                         .queryParam("alice", "bob")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(expectedPath);
    }
}
