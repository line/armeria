package com.linecorp.armeria.server.websocket;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketInboundTestHandler;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
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
    void shouldClosedConnection() throws Exception {
        final WebSocketClient client = WebSocketClient.of(server.httpUri());
        final WebSocketSession session = client.connect("/idle").join();

        Thread.sleep(2000);
        assertThat(session.outbound().isOpen()).isFalse();
    }
}
