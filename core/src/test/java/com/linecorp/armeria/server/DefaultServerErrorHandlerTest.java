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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DefaultServerErrorHandlerTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, false);
        }
    };

    @RegisterExtension
    static ServerExtension verboseServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            configureServer(sb, true);
        }
    };

    private static void configureServer(ServerBuilder sb, boolean verboseResponses) {
        sb.verboseResponses(verboseResponses);
        sb.maxRequestLength(1);

        sb.service("/", (ctx, req) -> {
            // Consume the request to trigger 413 Request Entity Too Large.
            return HttpResponse.from(req.aggregate().thenApply(unused -> HttpResponse.of(200)));
        });

        sb.annotatedService(new Object() {
            @Get("/400")
            public void get() {
                throw new IllegalArgumentException();
            }
        });

        sb.service("/500", (ctx, req) -> {
            throw new Exception();
        });

        sb.service("/503", (ctx, req) -> {
            ctx.timeoutNow();
            return HttpResponse.of(200);
        });

        sb.service("/status/{code}", (ctx, req) -> {
            throw HttpStatusException.of(Integer.parseInt(ctx.pathParam("code")));
        });

        sb.service("/response/{code}", (ctx, req) -> {
            final int code = Integer.parseInt(ctx.pathParam("code"));
            throw HttpResponseException.of(HttpResponse.of(
                    HttpStatus.valueOf(code), MediaType.PLAIN_TEXT_UTF_8, "custom_status=%d", code));
        });
    }

    @ParameterizedTest
    @CsvSource({
            "/400, 400, Bad Request",
            "/500, 500, Internal Server Error",
            "/503, 503, Service Unavailable",
            "/status/501, 501, Not Implemented",
    })
    void serviceErrorsWithoutStackTrace(String path, int expectedCode, String expectedDescription) {
        final HttpStatus expectedStatus = HttpStatus.valueOf(expectedCode);
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get(path);
        assertThat(res.status()).isSameAs(expectedStatus);
        assertThat(res.contentUtf8()).isEqualTo("Status: " + expectedCode + '\n' +
                                                "Description: " + expectedDescription + '\n');
        assertThat(res.trailers()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "/400, 400, Bad Request, java.lang.IllegalArgumentException",
            "/500, 500, Internal Server Error, java.lang.Exception",
            "/503, 503, Service Unavailable, com.linecorp.armeria.server.RequestTimeoutException",
            "/status/501, 501, Not Implemented, com.linecorp.armeria.server.HttpStatusException"
    })
    void serviceErrorsWithStackTrace(String path, int expectedCode,
                                     String expectedDescription, String expectedException) {
        final BlockingWebClient client = BlockingWebClient.of(verboseServer.httpUri());
        final AggregatedHttpResponse res = client.get(path);
        assertThat(res.status().code()).isEqualTo(expectedCode);
        final String content = res.contentUtf8();
        assertThat(content).startsWith("Status: " + expectedCode + '\n' +
                                       "Description: " + expectedDescription + '\n')
                           .contains("\nStack trace:\n" + expectedException);
        assertThat(res.trailers()).isEmpty();
    }

    @Test
    void responseException() {
        final BlockingWebClient client = BlockingWebClient.of(verboseServer.httpUri());
        final AggregatedHttpResponse res = client.get("/response/504");
        assertThat(res.status()).isSameAs(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(res.contentUtf8()).isEqualTo("custom_status=504");
        assertThat(res.trailers()).isEmpty();
    }

    /**
     * An {@link HttpStatusException} with the 'SUCCESS' class should not contain any stack trace,
     * even if {@link ServiceConfig#verboseResponses()} is enabled.
     */
    @Test
    void test200() {
        final BlockingWebClient client = BlockingWebClient.of(verboseServer.httpUri());
        final AggregatedHttpResponse res = client.get("/status/200");
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("Status: 200\nDescription: OK\n");
        assertThat(res.trailers()).isEmpty();
    }

    @Test
    void test204() {
        final BlockingWebClient client = BlockingWebClient.of(verboseServer.httpUri());
        final AggregatedHttpResponse res = client.get("/status/204");
        assertThat(res.status()).isSameAs(HttpStatus.NO_CONTENT);
        assertThat(res.contentUtf8()).isEmpty();
        assertThat(res.trailers()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({ "H1C", "H2C" })
    void protocolErrorsWithoutException(SessionProtocol protocol) {
        final WebClient client = WebClient.of(server.uri(protocol));
        final AggregatedHttpResponse res = client
                .execute(HttpRequest.of(HttpMethod.CONNECT, "/"))
                .aggregate()
                .join();
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.content().toStringUtf8())
                .isEqualTo("Status: 405\nDescription: Unsupported method\n");
        assertThat(res.trailers()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({ "H1C, false", "H1C, true", "H2C, false", "H2C, true" })
    void protocolErrorsWithException(SessionProtocol protocol, boolean verboseResponses) {
        final WebClient client = WebClient.of((verboseResponses ? verboseServer : server).uri(protocol));
        final AggregatedHttpResponse res = client
                .execute(HttpRequest.of(HttpMethod.POST, "/", MediaType.PLAIN_TEXT_UTF_8, "very_large"))
                .aggregate()
                .join();
        assertThat(res.status()).isSameAs(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        if (verboseResponses) {
            assertThat(res.content().toStringUtf8())
                    .startsWith("Status: 413\nDescription: Request Entity Too Large\nStack trace:\n" +
                                ContentTooLargeException.class.getName() + ": ");
        } else {
            assertThat(res.content().toStringUtf8())
                    .isEqualTo("Status: 413\nDescription: Request Entity Too Large\n");
        }
        assertThat(res.trailers()).isEmpty();
    }
}
