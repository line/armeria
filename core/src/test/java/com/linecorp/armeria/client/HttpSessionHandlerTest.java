/*
 * Copyright 2020 LINE Corporation
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

import static com.linecorp.armeria.client.Http2ClientSettingsTest.readBytes;
import static io.netty.handler.codec.http2.Http2CodecUtil.connectionPrefaceBuf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;

class HttpSessionHandlerTest {

    @Test
    void connectionTimeoutBeforeSettingsFrameIsSent() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);
             ClientFactory clientFactory =
                     ClientFactory.builder()
                                  .idleTimeoutMillis(1000)
                                  .useHttp2Preface(true)
                                  .build()) {
            final int port = ss.getLocalPort();
            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + port)
                                              .factory(clientFactory)
                                              .build();
            final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();

            try (Socket s = ss.accept()) {
                final InputStream in = s.getInputStream();
                // Read the connection preface and discard it.
                readBytes(in, connectionPrefaceBuf().readableBytes());
                // Read a SETTINGS frame.
                readBytes(in, 21);
                // Do not send back the SETTINGS frame.
                TimeUnit.SECONDS.sleep(3);
                assertThat(future.isCompletedExceptionally()).isTrue();
                assertThatThrownBy(future::join).hasRootCauseExactlyInstanceOf(ClosedSessionException.class);
            }
        }
    }
}
