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
package com.linecorp.armeria.server;

import static com.google.common.base.MoreObjects.firstNonNull;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.Maps;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class CustomServerErrorHandlerTest {

    private static final int MAX_REQUEST_LENGTH = 10;
    private static final String TEST_HOST = "foo.com";

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.maxRequestLength(MAX_REQUEST_LENGTH);
            sb.service("/timeout", (ctx, req) -> {
                ctx.timeoutNow();
                return HttpResponse.of(200);
            });
            sb.service("/throw-exception", (ctx, req) -> {
                ctx.logBuilder().defer(RequestLogProperty.RESPONSE_CONTENT);
                throw new IllegalArgumentException("Illegal Argument!");
            });
            sb.service("/responseSubscriber", (ctx, req) -> {
                final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                ctx.eventLoop().schedule(() -> future.completeExceptionally(
                        new UnsupportedOperationException("Unsupported!")), 100, TimeUnit.MILLISECONDS);
                return HttpResponse.of(future);
            });
            sb.service("/post", (ctx, req) -> HttpResponse.of(
                    req.aggregate().thenApply(aggregated -> HttpResponse.of(HttpStatus.OK))));

            sb.virtualHost(TEST_HOST)
              .service("/bar", (ctx, req) -> {
                  throw new AnticipatedException();
              })
              .errorHandler((ctx, cause) -> {
                  if (cause instanceof AnticipatedException) {
                      return HttpResponse.of(HttpStatus.BAD_REQUEST);
                  }
                  return null;
              });

            sb.errorHandler(new CustomServerErrorHandler());
        }
    };

    @Test
    void exceptionTranslated() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        AggregatedHttpResponse response = client.get("/timeout");
        assertThat(response.headers().status()).isSameAs(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(response.contentUtf8()).isEqualTo("timeout!");
        assertThat(response.trailers().get("trailer-exists")).isEqualTo("true");

        response = client.get("/throw-exception");
        assertThat(response.headers().status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(response.contentUtf8()).isEqualTo("Illegal Argument!");
        assertThat(response.trailers().get("trailer-exists")).isEqualTo("true");

        response = client.get("/responseSubscriber");
        assertThat(response.headers().status()).isSameAs(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.contentUtf8()).isEqualTo("Unsupported!");
        assertThat(response.trailers().get("trailer-exists")).isEqualTo("true");
    }

    @Test
    void logIsCompleteEvenIfResponseContentIsDeferred() throws InterruptedException {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        client.get("/throw-exception");
        final ServiceRequestContext ctx = server.requestContextCaptor().poll();
        assertThat(ctx).isNotNull();
        await().until(() -> ctx.log().whenComplete().isDone());
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void unsupportedMethods(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse res1 = client
                .execute(RequestHeaders.of(HttpMethod.CONNECT, "/", "user-id", "42"))
                .aggregate()
                .join();
        assertThat(res1.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(res1.headers()).contains(Maps.immutableEntry(HttpHeaderNames.of("alice"), "bob"));
        assertThatJson(res1.content().toStringUtf8()).isEqualTo(
                "{ " +
                "  \"code\": 405," +
                "  \"message\": \"Unsupported method\"," +
                "  \"has-ctx\": false," +
                "  \"user-id\": \"42\"" +
                '}');
        assertThat(res1.trailers()).contains(Maps.immutableEntry(HttpHeaderNames.of("charlie"), "daniel"));
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void tooLargeContent(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse res1 = client
                .execute(RequestHeaders.of(HttpMethod.POST, "/post", "user-id", "24"),
                         HttpData.wrap(new byte[MAX_REQUEST_LENGTH + 1]))
                .aggregate()
                .join();
        assertThat(res1.status()).isSameAs(HttpStatus.BAD_REQUEST);
        assertThat(res1.headers()).contains(Maps.immutableEntry(HttpHeaderNames.of("alice"), "bob"));
        assertThatJson(res1.content().toStringUtf8()).isEqualTo(
                "{ \"code\": 413, \"message\": \"<null>\", \"has-ctx\": true, \"user-id\": \"24\" }");
        assertThat(res1.trailers()).contains(Maps.immutableEntry(HttpHeaderNames.of("charlie"), "daniel"));
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void defaultVirtualHost(SessionProtocol protocol) {
        final Endpoint endpoint = Endpoint.of(TEST_HOST, server.httpPort()).withIpAddr("127.0.0.1");
        final BlockingWebClient webClientTest = WebClient.of(protocol, endpoint).blocking();
        final AggregatedHttpResponse response = webClientTest.get("/bar");

        assertThat(response.status()).isSameAs(HttpStatus.BAD_REQUEST);
    }

    private static class CustomServerErrorHandler implements ServerErrorHandler {
        @Override
        public @Nullable HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
            if (cause instanceof RequestTimeoutException) {
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.GATEWAY_TIMEOUT),
                                       HttpData.ofUtf8("timeout!"),
                                       HttpHeaders.of("trailer-exists", true));
            }
            if (cause instanceof IllegalArgumentException) {
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.BAD_REQUEST),
                                       HttpData.ofUtf8(cause.getMessage()),
                                       HttpHeaders.of("trailer-exists", true));
            }
            if (cause instanceof UnsupportedOperationException) {
                return HttpResponse.of(ResponseHeaders.of(HttpStatus.NOT_IMPLEMENTED),
                                       HttpData.ofUtf8(cause.getMessage()),
                                       HttpHeaders.of("trailer-exists", true));
            }
            return null;
        }

        @Override
        public AggregatedHttpResponse renderStatus(@Nullable ServiceRequestContext ctx,
                                                   ServiceConfig config,
                                                   @Nullable RequestHeaders headers,
                                                   HttpStatus status,
                                                   @Nullable String description,
                                                   @Nullable Throwable cause) {
            assertThat(config).isNotNull();
            return AggregatedHttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.BAD_REQUEST) // Always emit 400.
                                   .contentType(MediaType.JSON)
                                   .set("alice", "bob")
                                   .build(),
                    HttpData.ofUtf8("{\n" +
                                    "  \"code\": %d,\n" +
                                    "  \"message\": \"%s\",\n" +
                                    "  \"has-ctx\": %s,\n" +
                                    "  \"user-id\": \"%s\"\n" +
                                    '}',
                                    status.code(), firstNonNull(description, "<null>"),
                                    ctx != null,
                                    headers != null ? headers.get("user-id", "<null>") : "<no headers>"),
                    HttpHeaders.of("charlie", "daniel"));
        }
    }
}
