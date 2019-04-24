/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class ConnectionLimitingHandlerIntegrationTest {

    private static final String LOOPBACK = null;

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.maxNumConnections(2);
            sb.serviceUnder("/", new AbstractHttpService() {});
        }
    };

    @Test
    public void testExceedMaxNumConnections() throws Exception {
        // Known to fail on WSL (Windows Subsystem for Linux)
        assumeTrue(System.getenv("WSLENV") == null);

        try (Socket s1 = newSocketAndTest()) {
            assertThat(server.server().numConnections()).isEqualTo(1);

            try (Socket s2 = newSocketAndTest()) {
                assertThat(server.server().numConnections()).isEqualTo(2);

                assertThatThrownBy(ConnectionLimitingHandlerIntegrationTest::newSocketAndTest)
                        .isInstanceOf(SocketException.class);

                assertThat(server.server().numConnections()).isEqualTo(2);
            }

            await().until(() -> server.server().numConnections() == 1);

            try (Socket s2 = newSocketAndTest()) {
                assertThat(server.server().numConnections()).isEqualTo(2);
            }
        }
    }

    private static Socket newSocketAndTest() throws IOException {
        final Socket socket = new Socket(LOOPBACK, server.httpPort());

        // Test this socket is opened or not.
        final OutputStream os = socket.getOutputStream();
        os.write("GET / HTTP/1.1\r\n\r\n".getBytes());
        os.flush();

        // Read the next byte and ignore it.
        socket.getInputStream().read();

        return socket;
    }
}
