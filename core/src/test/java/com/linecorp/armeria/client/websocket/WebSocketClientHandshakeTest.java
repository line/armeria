/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.websocket.WebSocketClientTest.WebSocketServiceEchoHandler;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.websocket.WebSocket;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.websocket.WebSocketService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class WebSocketClientHandshakeTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/chat", WebSocketService.builder(new WebSocketServiceEchoHandler())
                                                .subprotocols("foo", "foo1", "foo2")
                                                .build());
        }
    };

    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private final Logger requestHandlerLogger =
            (Logger) LoggerFactory.getLogger("com.linecorp.armeria.client.AbstractHttpRequestHandler");

    @BeforeEach
    void attachAppender() {
        logAppender.start();
        requestHandlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachAppender() {
        requestHandlerLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @CsvSource({
            "H1C, foo2, foo1, foo2",
            "H1C, bar1, bar2, ",
            "H2C, foo2, foo1, foo2",
            "H2C, bar1, bar2, "
    })
    @ParameterizedTest
    void subprotocol(SessionProtocol sessionProtocol,
                     String subprotocol1, String subprotocol2, @Nullable String selected) {
        final WebSocketClient client =
                WebSocketClient.builder(server.uri(sessionProtocol, SerializationFormat.WS))
                               .subprotocols(subprotocol1, subprotocol2)
                               .build();
        final WebSocketSession session = client.connect("/chat").join();
        if (selected == null) {
            assertThat(session.responseHeaders().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)).isNull();
        } else {
            assertThat(session.responseHeaders().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL))
                    .isEqualTo(selected);
        }
        // Abort the session to close the connection.
        final WebSocketWriter outbound = WebSocket.streaming();
        outbound.abort();
        session.setOutbound(outbound);
        session.inbound().abort();
    }

    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H2C" })
    @ParameterizedTest
    void invalidResponseShouldNotLogUnexpectedException(SessionProtocol sessionProtocol) throws Exception {
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();
        final WebSocketClient client =
                WebSocketClient.builder(server.uri(sessionProtocol, SerializationFormat.WS))
                               .decorator((delegate, ctx, req) -> {
                                   ctxRef.set(ctx);
                                   return delegate.execute(ctx, req);
                               })
                               .build();
        final Throwable cause = catchThrowable(() -> client.connect("/not-found").join());
        assertThat(cause).isInstanceOf(CompletionException.class)
                         .hasCauseInstanceOf(WebSocketClientHandshakeException.class);

        final ClientRequestContext ctx = ctxRef.get();
        assertThat(ctx).isNotNull();
        final RequestLog log = ctx.log().whenComplete().join();
        // Make sure the tasks scheduled in the event loop, such as logging, are done.
        ctx.eventLoop().submit(() -> null).get();

        assertThat(logAppender.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
        assertThat(log.requestCause()).isSameAs(cause.getCause());
        assertThat(log.responseCause()).isSameAs(cause.getCause());
    }
}
