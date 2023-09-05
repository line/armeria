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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Spy;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.handler.codec.http2.DefaultHttp2Connection;

class HttpClientUpgradeTest {

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Spy
    final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    final Logger http2ConnectionLogger = (Logger) LoggerFactory.getLogger(DefaultHttp2Connection.class);

    @BeforeEach
    public void attachAppender() {
        logAppender.start();
        http2ConnectionLogger.addAppender(logAppender);
    }

    @AfterEach
    public void detachAppender() {
        http2ConnectionLogger.detachAppender(logAppender);
        logAppender.list.clear();
    }

    @Test
    void upgradeSuccess() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp2Preface(false)
                                                  .build()) {
            final WebClient client = WebClient.builder(server.httpUri()).factory(factory).build();
            // Before https://github.com/line/armeria/pull/5162 is applied,
            // the following exception was raised and caught by DefaultHttp2Connection:
            //
            // ERROR i.n.h.c.http2.DefaultHttp2Connection - Caught Throwable from listener onStreamClosed.
            // java.lang.AssertionError: null
            //  at com.linecorp.armeria.client.HttpSessionHandler.isAcquirable(HttpSessionHandler.java:290)
            //  at com.linecorp.armeria.client.AbstractHttpResponseDecoder.needsToDisconnectNow(Abstract...)
            //  at com.linecorp.armeria.client.Http2ResponseDecoder.shouldSendGoAway(Http2ResponseDecoder...)
            //  ..
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThat(client.get("/").aggregate().join().status()).isEqualTo(HttpStatus.OK);
                assertThat(captor.get().log().whenComplete().join().sessionProtocol())
                        .isEqualTo(SessionProtocol.H2C);
            }
            // "Caught Throwable from listener onStreamClosed." isn't logged.
            assertThat(logAppender.list.size()).isZero();
        }
    }
}
