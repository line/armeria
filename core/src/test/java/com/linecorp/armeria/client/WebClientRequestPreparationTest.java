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
            sb.service("exact:/%7Bunmatched", (ctx, req) -> HttpResponse.of("unmatched"));
            sb.service("exact:/:", (ctx, req) -> HttpResponse.of(":"));
            sb.service("exact:/:/:", (ctx, req) -> HttpResponse.of(":/:"));
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


    @Test
    void shouldFailOnMissingCurlyPathParam() {
        assertThatThrownBy(() -> WebClient.of(server.httpUri())
                                          .prepare()
                                          .get("/{foo}")
                                          .execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("param 'foo'");
    }

    @Test
    void shouldFailOnMissingColonPathParam() {
        assertThatThrownBy(() -> WebClient.of(server.httpUri())
                                          .prepare()
                                          .get("/:bar")
                                          .execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("param 'bar'");
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

    @Test
    void shouldIgnoreEmptyCurlyPathParam() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/{}")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("{}");
    }

    @Test
    void shouldIgnoreEmptyColonPathParam() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/:")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(":");
    }

    @Test
    void shouldIgnoreMultipleEmptyColonPathParams() {
        final AggregatedHttpResponse res =
                WebClient.of(server.httpUri())
                         .prepare()
                         .get("/:/:")
                         .execute()
                         .aggregate()
                         .join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(":/:");
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
}
