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

package com.linecorp.armeria.server.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.websocket.WebSocketClient;
import com.linecorp.armeria.client.websocket.WebSocketSession;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceOptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class WebSocketServiceOptionsTest {
    private static final ServiceOptions webSocketServiceOptions =
            ServiceOptions.builder()
                          .requestTimeoutMillis(100001)
                          .maxRequestLength(10002)
                          .requestAutoAbortDelayMillis(10003)
                          .build();

    private static final ServiceOptions serviceOptions =
            ServiceOptions.builder()
                          .requestTimeoutMillis(50001)
                          .build();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final WebSocketService webSocketService =
                    WebSocketService
                            .builder((ctx, in) -> in) // echo back
                            .serviceOptions(webSocketServiceOptions)
                            .fallbackService(new HttpService() {
                                @Override
                                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req)
                                        throws Exception {
                                    return HttpResponse.of("fallback");
                                }

                                @Override
                                public ServiceOptions options() {
                                    return serviceOptions;
                                }
                            })
                            .build();
            sb.service("/ws-or-rest", webSocketService);
        }
    };

    @Test
    void overrideServiceOptions() throws InterruptedException {
        final WebSocketClient webSocketClient = WebSocketClient.of(server.httpUri());
        final WebSocketSession session = webSocketClient.connect("/ws-or-rest").join();
        final WebSocketWriter out = session.outbound();
        out.write("hello");
        out.write("world");
        out.close();
        assertThat(session.inbound().collect().join().stream().map(WebSocketFrame::text))
                .contains("hello", "world");

        final ServiceRequestContext wsCtx = server.requestContextCaptor().take();
        assertThat(wsCtx.requestTimeoutMillis()).isEqualTo(webSocketServiceOptions.requestTimeoutMillis());
        assertThat(wsCtx.maxRequestLength()).isEqualTo(webSocketServiceOptions.maxRequestLength());
        assertThat(wsCtx.requestAutoAbortDelayMillis()).isEqualTo(
                webSocketServiceOptions.requestAutoAbortDelayMillis());

        final BlockingWebClient restClient = server.blockingWebClient();
        assertThat(restClient.get("/ws-or-rest").contentUtf8()).isEqualTo("fallback");
        final ServiceRequestContext restCtx = server.requestContextCaptor().take();
        assertThat(restCtx.requestTimeoutMillis()).isEqualTo(serviceOptions.requestTimeoutMillis());
        // Respect the virtual host's configurations if no value is set in the ServiceOptions of the
        // fallback service.
        assertThat(restCtx.maxRequestLength()).isEqualTo(restCtx.config().virtualHost().maxRequestLength());
        assertThat(restCtx.requestAutoAbortDelayMillis())
                .isEqualTo(restCtx.config().virtualHost().requestAutoAbortDelayMillis());
    }
}
