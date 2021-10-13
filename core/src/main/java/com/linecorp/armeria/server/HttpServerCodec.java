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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpServerPipelineConfigurator.WebSocketUpgradeContext;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpStatusClass;

final class HttpServerCodec extends CombinedChannelDuplexHandler<HttpRequestDecoder, HttpResponseEncoder>
        implements HttpServerUpgradeHandler.SourceCodec {

    // Forked from Netty 4.1.69 at 34a31522f0145e2d434aaea2ef8ac5ed8d1a91a0
    // - Added a logic that does not decode HTTP palyoad when WebSocketSession is established.

    /** A queue that is used for correlating a request and a response. */
    private final Queue<HttpMethod> queue = new ArrayDeque<HttpMethod>();

    private boolean webSocketEstablished;

    /**
     * Creates a new instance with the specified decoder options.
     */
    HttpServerCodec(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
                    WebSocketUpgradeContext webSocketUpgradeContext) {
        init(new HttpServerRequestDecoder(maxInitialLineLength, maxHeaderSize,
                                          maxChunkSize, webSocketUpgradeContext),
             new HttpServerResponseEncoder());
    }

    /**
     * Upgrades to another protocol from HTTP. Removes the {@link HttpRequestDecoder} and
     * {@link HttpResponseEncoder} from the pipeline.
     */
    @Override
    public void upgradeFrom(ChannelHandlerContext ctx) {
        ctx.pipeline().remove(this);
    }

    private final class HttpServerRequestDecoder extends HttpRequestDecoder {

        private final int maxChunkSize;
        private final WebSocketUpgradeContext webSocketUpgradeContext;

        HttpServerRequestDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize,
                                 WebSocketUpgradeContext webSocketUpgradeContext) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
            this.maxChunkSize = maxChunkSize;
            this.webSocketUpgradeContext = webSocketUpgradeContext;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
            if (webSocketUpgradeContext.webSocketEstablished()) {
                final int toRead = Math.min(buffer.readableBytes(), maxChunkSize);
                if (toRead > 0) {
                    final ByteBuf content = buffer.readRetainedSlice(toRead);
                    out.add(new DefaultHttpContent(content));
                }
                return;
            }
            final int oldSize = out.size();
            super.decode(ctx, buffer, out);
            final int size = out.size();
            for (int i = oldSize; i < size; i++) {
                final Object obj = out.get(i);
                if (obj instanceof HttpRequest) {
                    queue.add(((HttpRequest) obj).method());
                }
            }
        }
    }

    private final class HttpServerResponseEncoder extends HttpResponseEncoder {

        @Nullable
        private HttpMethod method;

        @Override
        protected void sanitizeHeadersBeforeEncode(HttpResponse msg, boolean isAlwaysEmpty) {
            if (!isAlwaysEmpty && HttpMethod.CONNECT.equals(method) &&
                msg.status().codeClass() == HttpStatusClass.SUCCESS) {
                // Stripping Transfer-Encoding:
                // See https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.1
                msg.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
                return;
            }

            super.sanitizeHeadersBeforeEncode(msg, isAlwaysEmpty);
        }

        @Override
        protected boolean isContentAlwaysEmpty(@SuppressWarnings("unused") HttpResponse msg) {
            method = queue.poll();
            return HttpMethod.HEAD.equals(method) || super.isContentAlwaysEmpty(msg);
        }
    }
}
