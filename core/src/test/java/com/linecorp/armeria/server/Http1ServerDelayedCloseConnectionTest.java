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

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;

import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Http1ServerDelayedCloseConnectionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.idleTimeoutMillis(0);
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/close", (ctx, req) -> {
                return HttpResponse.builder()
                        .ok()
                        .content("OK\n")
                        .header(HttpHeaderNames.CONNECTION, "close")
                        .build();
            });
        }
    };


    @Test
    void shouldDelayDisconnectByServerSideIfClientDoesNotHandleConnectionClose() throws IOException {
        Random random = new Random();
        short localPort = (short) random.nextInt(Short.MAX_VALUE + 1);
        try (Socket socket = new Socket("127.0.0.1", server.httpPort(),  null, localPort)) {
            socket.setReuseAddress(true);
            socket.setSoTimeout(100000);
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /close" + " HTTP/1.1\r\n");
            writer.print("\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");

            String line;
            boolean hasConnectionClose = false;
            while ((line = in.readLine()) != null) {
                if ("connection: close".equalsIgnoreCase(line)) {
                    hasConnectionClose = true;
                }
                if (line.isEmpty() || line.contains(":")) {
                    continue;
                }
                if (line.startsWith("OK")) {
                    break;
                }
            }
            long readStartTimestamp = System.nanoTime();
            int readResult = in.read();
            long readDurationMillis = Duration.ofNanos(System.nanoTime() - readStartTimestamp).toMillis();

            assertThat(hasConnectionClose).isTrue();
            assertThat(readResult).isEqualTo(-1);

            long defaultHttp1ConnectionCloseDelayMillis = Flags.defaultHttp1ConnectionCloseDelayMillis();
            assertThat(readDurationMillis).isBetween(
                    defaultHttp1ConnectionCloseDelayMillis - 100,
                    defaultHttp1ConnectionCloseDelayMillis + 1000
            );

            socket.close();
            Socket reuseSock = new Socket();
            reuseSock.bind(new InetSocketAddress((InetAddress) null, localPort));
            reuseSock.close();
        }
    }

    @Test
    void shouldWaitForDisconnectByClientSideFirst() throws IOException {
        Random random = new Random();
        short localPort = (short) random.nextInt(Short.MAX_VALUE + 1);
        try (Socket socket = new Socket("127.0.0.1", server.httpPort(),  null, localPort)) {
            socket.setReuseAddress(true);
            socket.setSoTimeout(1000);
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /close" + " HTTP/1.1\r\n");
            writer.print("\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");

            String line;
            boolean hasConnectionClose = false;
            while ((line = in.readLine()) != null) {
                if ("connection: close".equalsIgnoreCase(line)) {
                    hasConnectionClose = true;
                }
                if (line.isEmpty() || line.contains(":")) {
                    continue;
                }
                if (line.startsWith("OK")) {
                    break;
                }
            }
            assertThat(hasConnectionClose).isTrue();
            assertThat(server.server().numConnections()).isEqualTo(1);

            socket.close();
            Socket reuseSock = new Socket();
            assertThatThrownBy(() -> reuseSock.bind(new InetSocketAddress((InetAddress) null, localPort)))
                    .isInstanceOf(BindException.class)
                    .hasMessageContaining("Address already in use");
            reuseSock.close();
            assertThat(server.server().numConnections()).isZero();
        }
    }
}
