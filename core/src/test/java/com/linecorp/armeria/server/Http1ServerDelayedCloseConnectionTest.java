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
 * under the License.
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.BindException;
import java.net.Socket;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

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
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(100000);
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /close" + " HTTP/1.1\r\n");
            writer.print("\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");
            in.readLine(); // content-type
            in.readLine(); // content-length
            in.readLine(); // server
            in.readLine(); // date
            assertThat(in.readLine()).isEqualToIgnoringCase("connection: close");
            assertThat(in.readLine()).isEmpty();
            assertThat(in.readLine()).isEqualToIgnoringCase("OK");
            final long readStartTimestamp = System.nanoTime();
            final int readResult = in.read();
            final long readDurationMillis = Duration.ofNanos(System.nanoTime() - readStartTimestamp).toMillis();

            // -1 means that the server closed the connection
            // after defaultHttp1ConnectionCloseDelayMillis elapsed.
            assertThat(readResult).isEqualTo(-1);
            final long defaultHttp1ConnectionCloseDelayMillis = Flags.defaultHttp1ConnectionCloseDelayMillis();
            assertThat(readDurationMillis).isBetween(
                    defaultHttp1ConnectionCloseDelayMillis - 2000,
                    defaultHttp1ConnectionCloseDelayMillis + 2000
            );
        }
    }

    @Test
    void shouldWaitForDisconnectByClientSideFirst() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(100000);
            final int socketPort = socket.getLocalPort();
            final PrintWriter writer = new PrintWriter(socket.getOutputStream());
            writer.print("GET /close" + " HTTP/1.1\r\n");
            writer.print("\r\n");
            writer.flush();

            final BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            assertThat(in.readLine()).isEqualTo("HTTP/1.1 200 OK");
            in.readLine(); // content-type
            in.readLine(); // content-length
            in.readLine(); // server
            in.readLine(); // date
            assertThat(in.readLine()).isEqualToIgnoringCase("connection: close");
            assertThat(in.readLine()).isEmpty();
            assertThat(in.readLine()).isEqualToIgnoringCase("OK");

            assertThat(server.server().numConnections()).isEqualTo(1);

            socket.close();
            assertThatThrownBy(
                    () -> {
                        final Socket reuseSock = new Socket("127.0.0.1", server.httpPort(), null, socketPort);
                        // close the socket in case initializing the socket doesn't throw an exception
                        reuseSock.close();
                    })
                    .isInstanceOf(BindException.class)
                    .hasMessageContaining("Address already in use");

            await().untilAsserted(() -> assertThat(server.server().numConnections()).isZero());
        }
    }
}
