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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.EmptyHttp2Headers;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;

class Http2ClientWithPushPromiseTest {

    @RegisterExtension
    static NettyServerExtension h2cServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new PushPromisedH2CHandlerBuilder().build());
        }
    };

    private static int promisedStreamId;
    private static List<Integer> rstStreamIds;

    @BeforeEach
    void setUp() {
        promisedStreamId = 0;
        rstStreamIds = new ArrayList<>();
    }

    @Test
    void resetStreamOnPushPromise() {
        final WebClient client = WebClient.of(SessionProtocol.H2C, h2cServer.endpoint());
        final AggregatedHttpResponse response = client.get("/").aggregate().join();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(promisedStreamId).isNotZero();
        assertThat(rstStreamIds).contains(promisedStreamId);
    }

    private static final class PushPromisedH2CHandler extends SimpleH2CServerHandler {

        PushPromisedH2CHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                               Http2Settings initialSettings) {
            super(decoder, encoder, initialSettings);
        }

        @Override
        public void onHeadersRead(ChannelHandlerContext ctx, int streamId, Http2Headers headers, int padding,
                                  boolean endOfStream) {
            if (endOfStream) {
                promisedStreamId = streamId + 1;
                encoder().writePushPromise(ctx, streamId, promisedStreamId, EmptyHttp2Headers.INSTANCE, 0,
                                           ctx.newPromise());
            }
            super.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
        }

        @Override
        public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
            rstStreamIds.add(streamId);
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
