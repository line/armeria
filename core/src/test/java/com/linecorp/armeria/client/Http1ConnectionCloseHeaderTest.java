/*
 * Copyright 2019 LINE Corporation
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
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

/**
 * Makes sure an HTTP/1 client closes the connection when it receives a response with
 * a {@code "connection: close"} header.
 */
public class Http1ConnectionCloseHeaderTest {

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void connectionCloseHeaderInResponse() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);) {
            final int port = ss.getLocalPort();

            final WebClient client = WebClient.of("h1c://127.0.0.1:" + port);
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
                           "Connection: close\r\n" +
                           "Content-Length: 0\r\n" +
                           "\r\n").getBytes(StandardCharsets.US_ASCII));

                assertThat(in.readLine()).isNull();
            }
        }
    }
}
