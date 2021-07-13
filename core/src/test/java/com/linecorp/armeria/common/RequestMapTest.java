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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RequestMapTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> {
                return HttpResponse.of(req.headers().get("foo"));
            });
        }
    };

    @Test
    void mapHeaders() {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                               HttpData.ofUtf8("foo"));
        assertThat(req.headers().get(HttpHeaderNames.USER_AGENT)).isNull();

        final HttpRequest transformed = req.mapHeaders(headers -> {
            return headers.withMutations(builder -> builder.add(HttpHeaderNames.USER_AGENT, "Armeria"));
        });

        assertThat(transformed.aggregate().join().contentUtf8()).isEqualTo("foo");
        assertThat(transformed.headers().method()).isEqualTo(HttpMethod.GET);
        assertThat(transformed.headers().path()).isEqualTo("/test");
        assertThat(transformed.headers().get(HttpHeaderNames.USER_AGENT)).isEqualTo("Armeria");
    }

    @Test
    void mapData() {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                               HttpData.ofUtf8("foo"));
        final HttpRequest transformed = req.mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + " bar"));

        assertThat(transformed.aggregate().join().contentUtf8()).isEqualTo("foo bar");
        assertThat(transformed.headers()).isEqualTo(req.headers());
    }

    @Test
    void mapTrailers() {
        final HttpRequest noTrailers = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                                      HttpData.ofUtf8("foo"));
        final AtomicBoolean invoked = new AtomicBoolean();
        final HttpRequest transformed1 = noTrailers.mapTrailers(trailers -> {
            invoked.set(true);
            return trailers;
        });

        final AggregatedHttpRequest aggregated1 = transformed1.aggregate().join();
        assertThat(invoked).isFalse();
        assertThat(transformed1.headers()).isEqualTo(noTrailers.headers());
        assertThat(aggregated1.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated1.trailers().isEmpty()).isTrue();

        final HttpRequest withTrailers = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                                        HttpData.ofUtf8("foo"),
                                                        HttpHeaders.of("trailer1", "1"));

        final HttpRequest transformed2 = withTrailers.mapTrailers(trailers -> {
            invoked.set(true);
            if ("1".equals(trailers.get("trailer1"))) {
                return trailers.toBuilder().add("trailer2", "2").build();
            }
            return trailers;
        });

        final AggregatedHttpRequest aggregated2 = transformed2.aggregate().join();
        assertThat(invoked).isTrue();
        assertThat(aggregated2.contentUtf8()).isEqualTo("foo");
        assertThat(aggregated2.trailers().size()).isEqualTo(2);
        assertThat(aggregated2.trailers().get("trailer1")).isEqualTo("1");
        assertThat(aggregated2.trailers().get("trailer2")).isEqualTo("2");
    }

    @Test
    void mapChain() {
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test"),
                                               HttpData.ofUtf8("foo"),
                                               HttpHeaders.of("status", "0"));
        final AggregatedHttpRequest response =
                req.mapHeaders(headers -> headers.toBuilder().add("header1", "1").build())
                   .mapHeaders(headers -> headers.toBuilder().add("header2", "2").build())
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .mapData(data -> HttpData.ofUtf8(data.toStringUtf8() + '!'))
                   .mapTrailers(trailers -> trailers.toBuilder().add("trailer1", "1").build())
                   .mapTrailers(trailers -> trailers.toBuilder().add("trailer2", "2").build())
                   .aggregate().join();

        assertThat(response.headers().get("header1")).isEqualTo("1");
        assertThat(response.headers().get("header2")).isEqualTo("2");
        assertThat(response.contentUtf8()).isEqualTo("foo!!");
        assertThat(response.trailers().get("status")).isEqualTo("0");
        assertThat(response.trailers().get("trailer1")).isEqualTo("1");
        assertThat(response.trailers().get("trailer2")).isEqualTo("2");
    }

    @Test
    void mapError() {
        final IllegalStateException first = new IllegalStateException("1");
        final IllegalStateException second = new IllegalStateException("2");
        final HttpRequestWriter requestWriter = HttpRequest.streaming(HttpMethod.GET, "/foo");
        requestWriter.write(HttpData.ofUtf8("body"));
        requestWriter.close(first);
        final HttpRequest transformed = requestWriter.mapError(error -> {
            assertThat(error).isSameAs(first);
            return second;
        });
        assertThatThrownBy(() -> transformed.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCause(second);
    }

    @Test
    void withDecorator() {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             final HttpRequest transformed =
                                     req.mapHeaders(headers -> {
                                         return headers.withMutations(builder -> builder.add("foo", "bar"));
                                     });
                             ctx.updateRequest(transformed);
                             return delegate.execute(ctx, transformed);
                         })
                         .build();

        assertThat(client.get("foo").aggregate().join().contentUtf8()).isEqualTo("bar");
    }
}
