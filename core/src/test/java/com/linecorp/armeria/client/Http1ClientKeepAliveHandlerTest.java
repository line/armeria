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
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

class Http1ClientKeepAliveHandlerTest {

    @CsvSource({ "20000", "0" })
    @ParameterizedTest
    void shouldCloseConnectionWhenNoPingAck(long idleTimeoutMillis) throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(idleTimeoutMillis)
                                                  .pingIntervalMillis(10000)
                                                  .useHttp1Pipelining(true)
                                                  .build()) {

            final WebClient client = WebClient.builder("h1c://127.0.0.1:" + ss.getLocalPort())
                                              .factory(factory)
                                              .decorator(MetricCollectingClient.newDecorator(
                                                      MeterIdPrefixFunction.ofDefault("client")))
                                              .build();
            client.get("/").aggregate();

            try (Socket s = ss.accept()) {
                final BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                final OutputStream out = s.getOutputStream();

                assertThat(in.readLine()).isEqualTo("GET / HTTP/1.1");
                assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                assertThat(in.readLine()).startsWith("user-agent: armeria/");
                assertThat(in.readLine()).isEmpty();
                out.write(("HTTP/1.1 200 OK\r\n" +
                           "Content-Length: 0\r\n" +
                           "\r\n").getBytes(StandardCharsets.US_ASCII));

                // No response for OPTIONS *
                assertThat(in.readLine()).isEqualTo("OPTIONS * HTTP/1.1");
                assertThat(in.readLine()).startsWith("user-agent: armeria/");
                assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                assertThat(in.readLine()).isEmpty();

                // Send another request before the PING timeout
                Thread.sleep(5000);
                client.get("/").aggregate();

                String line;
                while ((line = in.readLine()) != null) {
                    assertThat(line).doesNotContain("OPTIONS * HTTP/1.1");
                }
            }
        }
    }
}
