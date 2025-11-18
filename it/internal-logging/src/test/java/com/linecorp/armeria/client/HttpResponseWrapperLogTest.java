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
package com.linecorp.armeria.client;

import static com.linecorp.armeria.internal.testing.Http2ByteUtil.handleInitialExchange;
import static com.linecorp.armeria.internal.testing.Http2ByteUtil.newClientFactory;
import static com.linecorp.armeria.internal.testing.Http2ByteUtil.readFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.handler.codec.http2.Http2FrameTypes;

class HttpResponseWrapperLogTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    private static final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private static final Logger logger =
            (Logger) LoggerFactory.getLogger(HttpResponseWrapper.class);
    private static final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void beforeEach() {
        appender.setContext(context);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void afterEach() {
        appender.stop();
        logger.detachAppender(appender);
    }

    @Test
    void goAwayNotLogged() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory clientFactory = newClientFactory(eventLoop.get())) {

            final int port = ss.getLocalPort();

            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            final HttpRequest req = HttpRequest.streaming(HttpMethod.GET, "/");
            final CompletableFuture<AggregatedHttpResponse> resFuture = client.execute(req).aggregate();
            try (Socket s = ss.accept()) {

                final InputStream in = s.getInputStream();
                final BufferedOutputStream bos = new BufferedOutputStream(s.getOutputStream());
                handleInitialExchange(in, bos);

                // Read a HEADERS frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.HEADERS);

                // Send a GOAWAY frame.
                bos.write(new byte[] {
                        0x00, 0x00, 0x08, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x03, // lastStreamId = 3
                        0x00, 0x00, 0x00, 0x00  // errorCode = 0
                });
                bos.flush();

                // The second request should fail with UnprocessedRequestException
                // which has a cause of GoAwayReceivedException.
                await().untilAsserted(resFuture::isCompletedExceptionally);
                assertThatThrownBy(resFuture::join).isInstanceOf(CompletionException.class)
                                                   .hasCauseInstanceOf(ClosedSessionException.class);

                // Read a GOAWAY frame.
                assertThat(readFrame(in).getByte(3)).isEqualTo(Http2FrameTypes.GO_AWAY);

                assertThat(in.read()).isEqualTo(-1);
            }
        }
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getMessage())
                    .doesNotContain(HttpResponseWrapper.UNEXPECTED_EXCEPTION_MSG);
        });
    }
}
