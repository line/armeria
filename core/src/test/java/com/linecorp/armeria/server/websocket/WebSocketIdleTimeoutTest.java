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
package com.linecorp.armeria.server.websocket;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.websocket.WebSocketServiceTest.AbstractWebSocketHandler;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class WebSocketIdleTimeoutTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final WebSocketService service =
                    WebSocketService.builder(new AbstractWebSocketHandler()).build();
            sb.service("/idle", service);
            sb.idleTimeout(Duration.ofMillis(1));
        }
    };

    @Test
    void shouldThrowWhenWritingToIdleWebSocket() throws Exception {
        final Throwable ex = assertThrows(RuntimeException.class, () -> {
            final WebSocketClient client = WebSocketClient.of(SessionProtocol.H1C, server.httpEndpoint());
            final WebSocketSession session = client.connect("/idle").join();
            final WebSocketWriter writer = session.outbound();
            writer.write("test");
            writer.defaultSubscriberExecutor().schedule(
                    () -> writer.write("text"),
                    1, TimeUnit.SECONDS
            );
            Thread.sleep(Duration.ofSeconds(10).toMillis());
        });

        assertSame(ClosedSessionException.class, ex.getCause().getClass());
    }
}
