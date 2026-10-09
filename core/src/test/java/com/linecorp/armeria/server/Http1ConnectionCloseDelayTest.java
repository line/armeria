/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class Http1ConnectionCloseDelayTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final HttpService service = (ctx, req) -> HttpResponse.builder()
                                                                  .ok()
                                                                  .content("OK\n")
                                                                  .header(HttpHeaderNames.CONNECTION, "close")
                                                                  .build();
            sb.idleTimeoutMillis(0)
              .http1ConnectionCloseDelay(Duration.ZERO)
              .service("/close", service)
              .virtualHost("foo.com")
              .http1ConnectionCloseDelay(Duration.ofSeconds(2))
              .service("/close", service);
        }
    };

    @Test
    void serverLevel() throws IOException {
        assertThat(server.server().config().defaultVirtualHost().http1ConnectionCloseDelayMillis())
                .isZero();
        assertThat(closeDurationMillis("127.0.0.1")).isLessThan(1000);
    }

    @Test
    void virtualHostLevel() throws IOException {
        assertThat(closeDurationMillis("foo.com")).isGreaterThanOrEqualTo(1500);
    }

    @Test
    void negativeDelay() {
        assertThatThrownBy(() -> Server.builder().http1ConnectionCloseDelayMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Server.builder().virtualHost("foo.com").http1ConnectionCloseDelayMillis(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Server.builder().http1ConnectionCloseDelay(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Server.builder().virtualHost("foo.com")
                                       .http1ConnectionCloseDelay(Duration.ofNanos(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static long closeDurationMillis(String host) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(10000);
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /close HTTP/1.1\r\n");
            writer.print("Host: " + host + "\r\n");
            writer.print("\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");
            while (!in.readLine().isEmpty()) {
                // Skip headers.
            }
            assertThat(in.readLine()).isEqualTo("OK");
            final long startNanos = System.nanoTime();
            // -1 means that the server closed the connection.
            assertThat(in.read()).isEqualTo(-1);
            return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        }
    }
}
