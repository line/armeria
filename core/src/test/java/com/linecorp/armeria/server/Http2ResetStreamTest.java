/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.netty.SimpleHttp2Connection;
import com.linecorp.armeria.internal.testing.netty.SimpleHttp2Connection.Http2Stream;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Frame;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.logging.LogLevel;
import io.netty.util.ReferenceCountUtil;

class Http2ResetStreamTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    @Test
    void rstStreamShouldNotBeRecv() throws Exception {
        final Deque<Integer> rstStreamFrames = new ArrayDeque<>();
        final Http2FrameLogger frameLogger = new Http2FrameLogger(LogLevel.DEBUG, Http2ResetStreamTest.class) {
            @Override
            public void logRstStream(Direction direction, ChannelHandlerContext ctx, int streamId,
                                     long errorCode) {
                rstStreamFrames.offer(streamId);
                super.logRstStream(direction, ctx, streamId, errorCode);
            }
        };
        try (final SimpleHttp2Connection conn = SimpleHttp2Connection.of(server.httpUri(), frameLogger);
             final Http2Stream stream = conn.createStream()) {
            final DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("GET");
            headers.path("/");
            final Http2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, true);
            stream.sendFrame(headersFrame).syncUninterruptibly();

            await().untilAsserted(() -> assertThat(stream.isOpen()).isFalse());
            final Http2Frame responseHeaderFrame = stream.take();
            assertThat(responseHeaderFrame).isInstanceOf(Http2HeadersFrame.class);
            assertThat(((Http2HeadersFrame) responseHeaderFrame).headers().status())
                    .asString().isEqualTo("200");

            Http2Frame dataFrame = stream.take();
            assertThat(dataFrame).isInstanceOf(Http2DataFrame.class);
            assertThat(((Http2DataFrame) dataFrame).content().toString(StandardCharsets.UTF_8))
                    .asString().isEqualTo("hello");
            ReferenceCountUtil.release(dataFrame);

            dataFrame = stream.take();
            assertThat(dataFrame).isInstanceOf(Http2DataFrame.class);
            assertThat(((Http2DataFrame) dataFrame).isEndStream()).isTrue();
            ReferenceCountUtil.release(dataFrame);

            await().atLeast(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(rstStreamFrames).isEmpty());
        }
    }
}
