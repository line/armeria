/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;

class Http2ClientRefusedStreamTest {

    @RegisterExtension
    static NettyServerExtension h2cServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) {
            ch.pipeline().addLast(new RefusedStreamH2CHandlerBuilder().build());
        }
    };

    @Test
    void shouldThrowUnprocessedRequestExceptionOn_RST_STREAM() {
        assertThatThrownBy(() -> {
            WebClient.of(h2cServer.endpoint().toUri(SessionProtocol.H2C))
                     .get("/").aggregate().join();
        }).isInstanceOf(CompletionException.class)
          .hasCauseInstanceOf(UnprocessedRequestException.class)
          .hasRootCauseInstanceOf(ClosedStreamException.class);
    }

    private static final class RefusedStreamH2CHandler extends SimpleH2CServerHandler {

        RefusedStreamH2CHandler(Http2ConnectionDecoder decoder,
                                Http2ConnectionEncoder encoder,
                                Http2Settings initialSettings) {
            super(decoder, encoder, initialSettings);
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                                  boolean endOfStream) {
            encoder().writeRstStream(ctx, streamId, Http2Error.REFUSED_STREAM.code(), ctx.newPromise());
        }
    }

    private static final class RefusedStreamH2CHandlerBuilder extends AbstractHttp2ConnectionHandlerBuilder<
            RefusedStreamH2CHandler, RefusedStreamH2CHandlerBuilder> {

        @Override
        public RefusedStreamH2CHandler build() {
            return super.build();
        }

        @Override
        public RefusedStreamH2CHandler build(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                             Http2Settings initialSettings) {
            final RefusedStreamH2CHandler handler =
                    new RefusedStreamH2CHandler(decoder, encoder, initialSettings);
            frameListener(handler);
            return handler;
        }
    }
}
