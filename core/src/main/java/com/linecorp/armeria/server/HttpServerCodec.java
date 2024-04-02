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

import static io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE;
import static io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE;
import static io.netty.handler.codec.http.HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.CombinedChannelDuplexHandler;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;

/**
 * Forked from {@link HttpClientCodec} to override {@code HttpServerRequestDecoder#createMessage(String[])}.
 * A combination of {@link HttpRequestDecoder} and {@link HttpResponseEncoder}
 * which enables easier server side HTTP implementation.
 */
final class HttpServerCodec extends CombinedChannelDuplexHandler<HttpRequestDecoder, HttpResponseEncoder>
        implements HttpServerUpgradeHandler.SourceCodec {

    // Forked from https://github.com/netty/netty/blob/cf624c93c5f97097f1b13fe926ed50c32c8b1430/codec-http/src/main/java/io/netty/handler/codec/http/HttpServerCodec.java

    /** A queue that is used for correlating a request and a response. */
    private final Queue<HttpMethod> queue = new ArrayDeque<HttpMethod>();

    /**
     * Creates a new instance with the default decoder options
     * ({@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}).
     */
    HttpServerCodec() {
        this(DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE, DEFAULT_MAX_CHUNK_SIZE);
    }

    /**
     * Creates a new instance with the specified decoder options.
     */
    HttpServerCodec(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
        init(new HttpServerRequestDecoder(maxInitialLineLength, maxHeaderSize, maxChunkSize),
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

        HttpServerRequestDecoder(int maxInitialLineLength, int maxHeaderSize, int maxChunkSize) {
            super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
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

        @Override
        protected HttpMessage createMessage(String[] initialLine) throws Exception {
            return new NettyHttp1Request(
                    HttpVersion.valueOf(initialLine[2]),
                    HttpMethod.valueOf(initialLine[0]), initialLine[1]);
        }
    }

    private final class HttpServerResponseEncoder extends HttpResponseEncoder {

        private HttpMethod method;

        @Override
        protected void sanitizeHeadersBeforeEncode(HttpResponse msg, boolean isAlwaysEmpty) {
            if (!isAlwaysEmpty && HttpMethod.CONNECT.equals(method) &&
                msg.status().codeClass() == HttpStatusClass.SUCCESS) {
                // Stripping Transfer-Encoding:
                // See section 3.3.1 of https://datatracker.ietf.org/doc/rfc7230
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
