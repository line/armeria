/*
 * Copyright 2023 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HeadersUpdatingClientTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/echo-test-header", (ctx, req) -> {
                  final String testHeader = req.headers().get("test");
                  assertThat(testHeader).isNotNull();
                  return HttpResponse.of(testHeader);
              })
              .service("/test-response-header",
                       (ctx, req) -> HttpResponse.of(ResponseHeaders.of(HttpStatus.OK, "test", "qux")));
        }
    };

    @Test
    void testAddObjectToRequestHeader() {
        final String path = "echo-test-header";
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .requestHeaders()
                                .add("test", "foo")
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).contentUtf8()).isEqualTo("foo");
    }

    @Test
    void testAddFunctionToRequestHeader() {
        final String path = "echo-test-header";
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .requestHeaders()
                                .add("test", header -> {
                                    if (header == null) {
                                        return "foo";
                                    }
                                    return "bar";
                                })
                                .add("test", header -> header + "baz")
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).contentUtf8()).isEqualTo("foobaz");
    }

    @Test
    void testAddAsyncFunctionToRequestHeader() {
        final String path = "echo-test-header";
        final AtomicInteger counter = new AtomicInteger(0);
        final Function<String, CompletableFuture<String>> headerFunction = header -> {
            final CompletableFuture<String> future = new CompletableFuture<>();
            future.complete(Integer.toString(counter.getAndIncrement()));
            return future;
        };
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .requestHeaders()
                                .addAsync("test", headerFunction)
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).contentUtf8()).isEqualTo("0");
        assertThat(client.get(path).contentUtf8()).isEqualTo("1");
        assertThat(client.get(path).contentUtf8()).isEqualTo("2");
    }

    @Test
    void testDecoratorComposition() {
        final String path = "echo-test-header";
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .requestHeaders()
                                .add("test", "0")
                                .add("test",
                                     header -> header + "1")
                                .addAsync("test",
                                          header -> UnmodifiableFuture.completedFuture(
                                                  header + "2"))
                                .add("test",
                                     header -> header + "3")
                                .addAsync("test",
                                          header -> UnmodifiableFuture.completedFuture(
                                                  header + "4"))
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).contentUtf8()).isEqualTo("01234");
    }

    /**
     * Order of precedence for applied headers.
     * <ol>
     * <li>Headers set at the moment of sending a request using {@link WebClient#execute(RequestHeaders)}.</li>
     * <li>Headers set using the decorator created with {@link HeadersUpdatingClient}.</li>
     * <li>Default static headers set using {@link WebClientBuilder#addHeader(CharSequence, Object)}.</li>
     * </ol>
     */
    @Test
    void testRequestHeaderPriority() {
        final String path = "echo-test-header";
        // HeadersUpdatingClient > ClientRequestContest#defaultRequestHeaders
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .addHeader("test", "foo1")
                .addHeader("test", "foo2")
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .requestHeaders()
                                .add("test", header -> header + "bar")
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).contentUtf8()).isEqualTo("foo2bar");
        // RequestHeaders > HeadersUpdatingClient
        final AggregatedHttpRequest request = AggregatedHttpRequest
                .of(RequestHeaders
                            .builder()
                            .path(path)
                            .method(HttpMethod.GET)
                            .add("test", "42")
                            .build());
        assertThat(client.execute(request).contentUtf8()).isEqualTo("42");
    }

    @Test
    void testAddObjectToResponseHeader() {
        final String path = "test-response-header";
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .responseHeaders()
                                .add("test", "foo")
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).headers().get("test")).isEqualTo("foo");
    }

    @Test
    void testAddFunctionToResponseHeader() {
        final String path = "test-response-header";
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .responseHeaders()
                                .add("test", header -> header + "foo")
                                .add("new-header", header -> "bar")
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).headers().get("test")).isEqualTo("quxfoo");
        assertThat(client.get(path).headers().get("new-header")).isEqualTo("bar");
    }

    @Test
    void testAddAsyncFunctionToResponseHeader() {
        final String path = "test-response-header";
        final AtomicInteger counter = new AtomicInteger(0);
        final Function<String, CompletableFuture<String>> headerFunction = header -> {
            final CompletableFuture<String> future = new CompletableFuture<>();
            future.complete(header + counter.getAndIncrement());
            return future;
        };
        final BlockingWebClient client = WebClient
                .builder(server.httpUri())
                .decorator(
                        HeadersUpdatingClient
                                .builder()
                                .responseHeaders()
                                .addAsync("test", headerFunction)
                                .and()
                                .newDecorator())
                .build()
                .blocking();
        assertThat(client.get(path).headers().get("test")).isEqualTo("qux0");
        assertThat(client.get(path).headers().get("test")).isEqualTo("qux1");
        assertThat(client.get(path).headers().get("test")).isEqualTo("qux2");
    }
}
