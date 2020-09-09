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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.EmptyHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Settings;

class Http2ClientWithPushPromiseTest {

    @RegisterExtension
    static NettyServerExtension h2cServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new PushPromisedH2CHandlerBuilder().build());
        }
    };

    private static final AtomicReference<Throwable> errorCaptor = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        errorCaptor.set(null);
    }

    @Test
    void disablePushPromise() {
        final WebClient client = WebClient.of(SessionProtocol.H2C, h2cServer.endpoint());
        assertThatThrownBy(() ->  client.get("/push").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClosedStreamException.class);

        await().untilAsserted(() -> {
            assertThat(errorCaptor.get())
                    .isInstanceOf(Http2Exception.class)
                    .hasMessageContaining("Server push not allowed");
        });
    }

    private static final class PushPromisedH2CHandler extends SimpleH2CServerHandler {

        PushPromisedH2CHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                               Http2Settings initialSettings) {
            super(decoder, encoder, initialSettings);
        }

        @Override
        public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
            assertThat(settings.pushEnabled()).isFalse();
        }

        @Override
        protected void sendResponse(ChannelHandlerContext ctx, int streamId, HttpResponseStatus status,
                                    ByteBuf payload) {
            // PUSH_PROMISE is disable by Armeria client
            encoder().writePushPromise(ctx, streamId, streamId + 1, EmptyHttp2Headers.INSTANCE, 0,
                                       ctx.newPromise())
                     .addListener(f -> errorCaptor.set(f.cause()));
            super.sendResponse(ctx, streamId, status, payload);
        }
    }

    private static final class PushPromisedH2CHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<
            PushPromisedH2CHandler, PushPromisedH2CHandlerBuilder> {

        @Override
        public PushPromisedH2CHandler build() {
            return super.build();
        }

        @Override
        public PushPromisedH2CHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                            Http2Settings initialSettings) {
            final PushPromisedH2CHandler handler =
                    new PushPromisedH2CHandler(decoder, encoder, initialSettings);
            frameListener(handler);
            return handler;
        }
    }
}
