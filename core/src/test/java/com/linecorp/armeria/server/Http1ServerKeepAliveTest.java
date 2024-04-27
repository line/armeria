/*
 * Copyright 2022 LINE Corporation
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.HttpHeadersUtil;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class Http1ServerKeepAliveTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());

            sb.service("/", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.valueOf(routingContext.params().get("exchangeType"));
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    // Wrap `delayedResponse` with an `HttpResponseException` so that
                    // the HttpResponseException completes first and `delayedResponse` is written later.
                    final HttpResponse delayedResponse =
                            HttpResponse.delayed(HttpResponse.of("A late response"),
                                                 Duration.ofSeconds(1));
                    throw HttpResponseException.of(delayedResponse);
                }
            });

            sb.service("/close", new HttpService() {
                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.valueOf(routingContext.params().get("exchangeType"));
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    final ResponseHeaders headers = ResponseHeaders.of(HttpStatus.OK,
                                                                       HttpHeaderNames.CONNECTION,
                                                                       HttpHeadersUtil.CLOSE_STRING);
                    return HttpResponse.of(headers, HttpData.ofUtf8("The last response"));
                }
            });
        }
    };

    @EnumSource(ExchangeType.class)
    @ParameterizedTest
    void noKeepAliveByClient(ExchangeType exchangeType) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(10000);
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /?exchangeType=" + exchangeType.name() + " HTTP/1.1\r\n");
            writer.print("Connection: close\r\n\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");

            String line;
            boolean hasConnectionClose = false;
            while ((line = in.readLine()) != null) {
                if ("connection: close".equalsIgnoreCase(line)) {
                    // If "Connection: close" was sent by the client,
                    // the server should return "Connection: close" as well.
                    hasConnectionClose = true;
                }
                if (line.isEmpty() || line.contains(":")) {
                    // Skip headers.
                    continue;
                }
                assertThat(line).isEqualTo("A late response");
            }
            assertThat(hasConnectionClose).isTrue();
        }
    }

    @EnumSource(ExchangeType.class)
    @ParameterizedTest
    void noKeepAliveByServer(ExchangeType exchangeType) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(10000);
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /close?exchangeType=" + exchangeType.name() + " HTTP/1.1\r\n");
            writer.print("\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");

            String line;
            boolean hasConnectionClose = false;
            while ((line = in.readLine()) != null) {
                if ("connection: close".equalsIgnoreCase(line)) {
                    // If "Connection: close" was sent by the client,
                    // the server should return "Connection: close" as well.
                    hasConnectionClose = true;
                }
                if (line.isEmpty() || line.contains(":")) {
                    // Skip headers.
                    continue;
                }
                assertThat(line).isEqualTo("The last response");
            }
            assertThat(hasConnectionClose).isTrue();
        }
    }
}
