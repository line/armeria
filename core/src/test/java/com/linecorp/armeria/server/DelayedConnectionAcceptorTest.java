/*
 * Copyright 2025 LINE Corporation
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Tests that requests succeed when the {@link ConnectionAcceptor} future completes
 * after a delay, ensuring that inbound bytes are not dropped while the accept is pending.
 */
class DelayedConnectionAcceptorTest {

    @RegisterExtension
    private static final EventLoopExtension eventLoop = new EventLoopExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0)
              .https(0)
              .tls(TlsKeyPair.ofSelfSigned())
              .connectionAcceptor(ctx -> {
                  final CompletableFuture<Boolean> future = new CompletableFuture<>();
                  eventLoop.get().schedule(() -> future.complete(true), 1, TimeUnit.SECONDS);
                  return future;
              })
              .service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @RegisterExtension
    static final ServerExtension stalledServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0)
              .https(0)
              .tls(TlsKeyPair.ofSelfSigned())
              .idleTimeoutMillis(1000)
              // Never completes — should be closed by the accept timeout.
              .connectionAcceptor(ctx -> new CompletableFuture<>())
              .service("/", (ctx, req) -> HttpResponse.of("should not reach"));
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"HTTP", "HTTPS"})
    void delayedAcceptShouldNotDropRequest(SessionProtocol protocol) {
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(protocol))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = {"HTTP", "HTTPS"})
    void stalledAcceptShouldTimeout(SessionProtocol protocol) {
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(stalledServer.uri(protocol))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }
}
