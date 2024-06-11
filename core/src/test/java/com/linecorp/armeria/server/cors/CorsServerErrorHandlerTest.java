/*
 * Copyright 2024 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.server.cors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class CorsServerErrorHandlerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final HttpService myService = (ctx, req) -> HttpResponse.of(HttpStatus.OK);

            addCorsServiceWithException(sb, myService, "/cors_status_exception",
                                        HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR), false);
            addCorsServiceWithException(sb, myService, "/cors_response_exception",
                                        HttpResponseException.of(
                                                HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)), false);

            addCorsServiceWithException(sb, myService, "/cors_status_exception-route",
                                        HttpStatusException.of(HttpStatus.INTERNAL_SERVER_ERROR), true);
            addCorsServiceWithException(sb, myService, "/cors_response_exception-route",
                                        HttpResponseException.of(
                                                HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)), true);
        }
    };

    private static void addCorsServiceWithException(ServerBuilder sb, HttpService myService, String pathPattern,
                                                    Exception exception, boolean useRouteDecorator) {
        final Function<? super HttpService, CorsService> corsService =
                CorsService.builder("http://example.com")
                           .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                           .allowRequestHeaders("allow_request_header")
                           .exposeHeaders("expose_header_1", "expose_header_2")
                           .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                           .newDecorator();
        if (useRouteDecorator) {
            sb.decorator(pathPattern, corsService);
            sb.decorator(pathPattern, (delegate, ctx, req) -> {
                throw exception;
            });
        } else {
            myService = myService.decorate(corsService)
                                 .decorate((delegate, ctx, req) -> {
                                     throw exception;
                                 });
        }
        sb.service(pathPattern, myService);
    }

    private static AggregatedHttpResponse request(WebClient client, HttpMethod method, String path,
                                                  String origin, String requestMethod) {
        return client.execute(RequestHeaders.of(
                method, path, HttpHeaderNames.ACCEPT, "utf-8", HttpHeaderNames.ORIGIN, origin,
                HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD, requestMethod)).aggregate().join();
    }

    private static AggregatedHttpResponse preflightRequest(WebClient client, String path, String origin,
                                                           String requestMethod) {
        return request(client, HttpMethod.OPTIONS, path, origin, requestMethod);
    }

    @ParameterizedTest
    @CsvSource({
            "/cors_status_exception",
            "/cors_status_exception-route",
            "/cors_response_exception",
            "/cors_response_exception-route"
    })
    void testCorsHeaderWithException(String path) {
        final WebClient client = server.webClient();
        final AggregatedHttpResponse response = preflightRequest(client, path,
                                                                 "http://example.com", "GET");
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS)).isEqualTo(
                "allow_request_header");
        assertThat(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(
                "http://example.com");
    }
}
