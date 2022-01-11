/*
 * Copyright 2020 LINE Corporation
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;

class Http1HeaderNamingTest {

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void clientTraditionalHeaderNaming(boolean useHeaderNaming) throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            final int port = ss.getLocalPort();
            final ClientFactory clientFactory;
            if (useHeaderNaming) {
                clientFactory = ClientFactory.builder()
                                             .http1HeaderNaming(Http1HeaderNaming.traditional())
                                             .build();
            } else {
                clientFactory = ClientFactory.ofDefault();
            }

            final WebClient client = WebClient.builder("h1c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();

            final CompletableFuture<AggregatedHttpResponse> response =
                    client.prepare()
                          .get("/")
                          .header(HttpHeaderNames.AUTHORIZATION, "Bearer foo")
                          .header(HttpHeaderNames.X_FORWARDED_FOR, "bar")
                          .execute().aggregate();

            try (Socket socket = ss.accept()) {
                final InputStream is = socket.getInputStream();
                final BufferedReader reader = new BufferedReader(new InputStreamReader(is));

                boolean hasAuthorization = false;
                boolean hasXForwardedFor = false;
                for (;;) {
                    final String line = reader.readLine();
                    if (Strings.isNullOrEmpty(line)) {
                        break;
                    }
                    if (useHeaderNaming) {
                        if ("Authorization: Bearer foo".equals(line)) {
                            hasAuthorization = true;
                        }
                        if ("X-Forwarded-For: bar".equals(line)) {
                            hasXForwardedFor = true;
                        }
                    } else {
                        if ("authorization: Bearer foo".equals(line)) {
                            hasAuthorization = true;
                        }
                        if ("x-forwarded-for: bar".equals(line)) {
                            hasXForwardedFor = true;
                        }
                    }
                }

                assertThat(hasAuthorization).isTrue();
                assertThat(hasXForwardedFor).isTrue();
                final OutputStream os = socket.getOutputStream();
                os.write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
            }

            final HttpStatus status = response.join().status();
            assertThat(status).isEqualTo(HttpStatus.OK);
            clientFactory.close();
        }
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void serverTraditionalHeaderNaming(boolean useHeaderNaming) throws IOException {
        final ServerBuilder serverBuilder = Server
                .builder()
                .service("/", (ctx, req) -> HttpResponse
                        .of(ResponseHeaders.of(HttpStatus.OK,
                                               HttpHeaderNames.AUTHORIZATION, "Bearer foo",
                                               HttpHeaderNames.X_FORWARDED_FOR, "bar")));
        if (useHeaderNaming) {
            serverBuilder.http1HeaderNaming(Http1HeaderNaming.traditional());
        }
        final Server server = serverBuilder.build();
        server.start().join();

        try (Socket socket = new Socket()) {
            socket.connect(server.activePort().localAddress());

            final PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);
            outWriter.print("GET / HTTP/1.1\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            final InputStream is = socket.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            boolean hasAuthorization = false;
            boolean hasXForwardedFor = false;
            for (;;) {
                final String line = reader.readLine();
                System.out.println(line);
                if (Strings.isNullOrEmpty(line)) {
                    break;
                }
                if (useHeaderNaming) {
                    if ("Authorization: Bearer foo".equals(line)) {
                        hasAuthorization = true;
                    }
                    if ("X-Forwarded-For: bar".equals(line)) {
                        hasXForwardedFor = true;
                    }
                } else {
                    if ("authorization: Bearer foo".equals(line)) {
                        hasAuthorization = true;
                    }
                    if ("x-forwarded-for: bar".equals(line)) {
                        hasXForwardedFor = true;
                    }
                }
            }

            assertThat(hasAuthorization).isTrue();
            assertThat(hasXForwardedFor).isTrue();
        }
    }
}
