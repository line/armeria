/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.internal.client.ClientUtil.UNDEFINED_URI;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.ImmediateEventLoop;

import io.netty.channel.EventLoop;

class DefaultWebClientTest {

    @Test
    void testConcatenateRequestPath() {
        final String clientUriPath = "http://127.0.0.1/hello";
        final String requestPath = "world/test?q1=foo";
        final WebClient client = WebClient.of(clientUriPath);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, requestPath))).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/hello/world/test?q1=foo");
        }
    }

    @Test
    void testRequestParamsUndefinedEndPoint() {
        final String path = "http://127.0.0.1/helloWorld/test?q1=foo";
        final WebClient client = WebClient.of(UNDEFINED_URI);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path))).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test?q1=foo");
        }
    }

    @Test
    void testWithoutRequestParamsUndefinedEndPoint() {
        final String path = "http://127.0.0.1/helloWorld/test";
        final WebClient client = WebClient.of(UNDEFINED_URI);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path))).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test");
        }
    }

    @Test
    void endpointRemapper() {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                     Endpoint.of("127.0.0.1", 1));
        final WebClient client = WebClient.builder("http://group")
                                          .endpointRemapper(endpoint -> {
                                              if ("group".equals(endpoint.host())) {
                                                  return group;
                                              } else {
                                                  return endpoint;
                                              }
                                          })
                                          .build();

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.get("/").aggregate();
            final ClientRequestContext cctx = ctxCaptor.get();
            await().untilAsserted(() -> {
                assertThat(cctx.endpointGroup()).isSameAs(group);
                assertThat(cctx.endpoint()).isEqualTo(Endpoint.of("127.0.0.1", 1));
                assertThat(cctx.authority()).isEqualTo("127.0.0.1:1");
            });
        }
    }

    @Test
    void endpointRemapperForUnspecifiedUri() {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                     Endpoint.of("127.0.0.1", 1));
        final WebClient client = WebClient.builder()
                                          .endpointRemapper(endpoint -> {
                                              if ("group".equals(endpoint.host())) {
                                                  return group;
                                              } else {
                                                  return endpoint;
                                              }
                                          })
                                          .build();

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.get("http://group").aggregate();
            final ClientRequestContext cctx = ctxCaptor.get();
            await().untilAsserted(() -> {
                assertThat(cctx.endpointGroup()).isSameAs(group);
                assertThat(cctx.endpoint()).isEqualTo(Endpoint.of("127.0.0.1", 1));
                assertThat(cctx.authority()).isEqualTo("127.0.0.1:1");
            });
        }
    }

    @Test
    void testWithQueryParams() {
        final String path = "http://127.0.0.1/helloWorld/test";
        final QueryParams queryParams = QueryParams.builder()
                                                   .add("q1", "foo")
                                                   .build();
        final WebClient client = WebClient.of(UNDEFINED_URI);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.get(path, queryParams).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test?q1=foo");
        }

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.post(path, queryParams, "").aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test?q1=foo");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "/, HTTP, false",
            "/, UNDEFINED, false",
            "/prefix, HTTP, false",
            "/prefix, UNDEFINED, false",
            "/, HTTP, true",
    })
    void preprocessorBuilder(String prefix, SessionProtocol protocol, boolean isDefault) {
        final Endpoint endpoint = Endpoint.of("127.0.0.1");
        final EventLoop eventLoop = ImmediateEventLoop.INSTANCE;
        final WebClientBuilder builder;
        final HttpPreprocessor preprocessor = HttpPreprocessor.of(protocol, endpoint, eventLoop);
        if (isDefault) {
            builder = WebClient.builder().preprocessor(preprocessor);
        } else if ("/".equals(prefix)) {
            builder = WebClient.builder(preprocessor);
        } else {
            builder = WebClient.builder(preprocessor, prefix);
        }

        final WebClient client =
                builder.decorator((delegate, ctx, req) -> {
                           if ("/".equals(prefix)) {
                               assertThat(req.path()).isEqualTo("/hello");
                           } else {
                               assertThat(req.path()).isEqualTo("/prefix/hello");
                           }
                           assertThat(ctx.sessionProtocol()).isEqualTo(protocol);
                           assertThat(ctx.endpointGroup()).isEqualTo(endpoint);
                           assertThat(ctx.eventLoop().withoutContext()).isEqualTo(eventLoop);
                           return HttpResponse.of(200);
                       })
                       .build();
        final CompletableFuture<AggregatedHttpResponse> cf = client.get("/hello").aggregate();
        if (SessionProtocol.httpAndHttpsValues().contains(protocol)) {
            final AggregatedHttpResponse res = cf.join();
            assertThat(res.status().code()).isEqualTo(200);
        } else {
            assertThatThrownBy(cf::join)
                    .isInstanceOf(CompletionException.class)
                    .cause()
                    .isInstanceOf(UnprocessedRequestException.class)
                    .cause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ctx.sessionProtocol() cannot be 'undefined");
        }
    }

    @Test
    void ctorPreprocessorDerivation() {
        final HttpPreprocessor http1 = HttpPreprocessor.of(HTTP, Endpoint.of("127.0.0.1", 8080));
        final HttpPreprocessor http2 = HttpPreprocessor.of(HTTP, Endpoint.of("127.0.0.1", 8081));

        ClientPreprocessors clientPreprocessors = WebClient.of().options().clientPreprocessors();
        assertThat(clientPreprocessors.preprocessors()).isEmpty();
        assertThat(clientPreprocessors.rpcPreprocessors()).isEmpty();

        clientPreprocessors = WebClient.builder().preprocessor(http1).build().options().clientPreprocessors();
        assertThat(clientPreprocessors.preprocessors()).containsExactly(http1);
        assertThat(clientPreprocessors.rpcPreprocessors()).isEmpty();

        clientPreprocessors = WebClient.of(http1).options().clientPreprocessors();
        assertThat(clientPreprocessors.preprocessors()).containsExactly(http1);
        assertThat(clientPreprocessors.rpcPreprocessors()).isEmpty();

        clientPreprocessors = WebClient.builder(http1).preprocessor(http2).build()
                                       .options().clientPreprocessors();
        assertThat(clientPreprocessors.preprocessors()).containsExactly(http1, http2);
        assertThat(clientPreprocessors.rpcPreprocessors()).isEmpty();
    }

    @Test
    void rpcPreprocessorNotAllowed() {
        assertThatThrownBy(() -> WebClient.builder().rpcPreprocessor(
                RpcPreprocessor.of(HTTP, Endpoint.of("127.0.0.1"))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("RPC preprocessor cannot be added");
    }

    @Test
    void exceptionsAreHandled() {
        final RuntimeException exception = new RuntimeException();
        final WebClient webClient = WebClient.of((delegate, ctx, req) -> {
            throw exception;
        });
        final CompletableFuture<AggregatedHttpResponse> cf = webClient.get("/hello").aggregate();
        assertThatThrownBy(cf::join).isInstanceOf(CompletionException.class)
                                    .cause()
                                    .isSameAs(exception);
    }

    @Test
    void undefinedUriWithPath() {
        final ClientBuilderParams params = WebClient.of().paramsBuilder()
                                                    .absolutePathRef("/echo-path")
                                                    .build();
        assertThatThrownBy(() -> ClientFactory.ofDefault().newClient(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot set a prefix path for clients created by 'WebClient.of().'");
    }
}
