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

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

class PendingAcquisitionTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @CsvSource({ "H2C,HTTP", "H2,HTTPS" })
    @ParameterizedTest
    void shouldUsePendingAcquisitionFromExplicitProtocol(SessionProtocol explicitProtocol,
                                                         SessionProtocol nonExplicitProtocol) {
        final CountingConnectionPoolListener connectionPoolListener = new CountingConnectionPoolListener();

        final PendingAcquisitionHandler pendingAcquisitionHandler = new PendingAcquisitionHandler();
        ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER.doNewValue(pipeline -> {
            pipeline.addLast(pendingAcquisitionHandler);
        });
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionPoolListener(connectionPoolListener)
                                                  .tlsNoVerify()
                                                  .build()) {
            final WebClient client = WebClient.builder()
                                              .factory(factory)
                                              .build();
            final CompletableFuture<AggregatedHttpResponse> responseH2 =
                    client.get(server.uri(explicitProtocol).resolve("/").toString())
                          .aggregate();
            final CompletableFuture<AggregatedHttpResponse> responseHttp =
                    client.get(server.uri(nonExplicitProtocol).resolve("/").toString())
                          .aggregate();
            pendingAcquisitionHandler.latch.complete(null);
            assertThat(responseH2.join().status()).isEqualTo(HttpStatus.OK);
            assertThat(responseHttp.join().status()).isEqualTo(HttpStatus.OK);
            assertThat(connectionPoolListener.opened()).isEqualTo(1);
        }
    }

    static class PendingAcquisitionHandler extends ChannelOutboundHandlerAdapter {
        private final CompletableFuture<Void> latch = new CompletableFuture<>();

        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) throws Exception {
            latch.thenRunAsync(() -> {
                try {
                    super.connect(ctx, remoteAddress, localAddress, promise);
                } catch (Exception e) {
                    Exceptions.throwUnsafely(e);
                }
            }, ctx.executor());
        }
    }
}
