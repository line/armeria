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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

class Http2ClientPrefaceTest {

    @RegisterExtension
    static NettyServerExtension h2cServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new H2CHandlerBuilder().build());
        }
    };

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void shouldAlwaysUseConnectionPrefaceForH2C(boolean useHttp2Preface) {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp2Preface(useHttp2Preface)
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder(h2cServer.endpoint().toUri(SessionProtocol.H2C))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void shouldUseConnectionPrefaceOrUpgradeForHttp(boolean useHttp2Preface) {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp2Preface(useHttp2Preface)
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder(h2cServer.endpoint().toUri(SessionProtocol.HTTP))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            if (useHttp2Preface) {
                final AggregatedHttpResponse response = client.get("/");
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
            } else {
                // Upgrade HTTP/1.1 to HTTP/2 fails because the server only supports H2C.
                assertThatThrownBy(() -> client.get("/"))
                        .isInstanceOf(UnprocessedRequestException.class)
                        .hasCauseInstanceOf(ClosedSessionException.class);
            }
        }
    }

    private static final class H2CHandler extends SimpleH2CServerHandler {

        H2CHandler(Http2ConnectionDecoder decoder,
                   Http2ConnectionEncoder encoder,
                   Http2Settings initialSettings) {
            super(decoder, encoder, initialSettings);
        }
    }

    private static final class H2CHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<
            H2CHandler, H2CHandlerBuilder> {

        @Override
        public H2CHandler build() {
            return super.build();
        }

        @Override
        public H2CHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                Http2Settings initialSettings) {
            final H2CHandler handler =
                    new H2CHandler(decoder, encoder, initialSettings);
            frameListener(handler);
            return handler;
        }
    }
}
