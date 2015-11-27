/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.http;

import java.lang.reflect.Method;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Promise;

/**
 * A HTTP {@link ClientCodec} codec for {@link SimpleHttpRequest} and {@link SimpleHttpResponse}.
 */
public class SimpleHttpClientCodec implements ClientCodec {

    private static final byte[] EMPTY = new byte[0];

    private final String host;

    /**
     * Creates a new codec with the specified {@link Scheme} and {@code host}.
     */
    public SimpleHttpClientCodec(String host) {
        this.host = host;
    }

    @Override
    public <T> void prepareRequest(Method method, Object[] args, Promise<T> resultPromise) {
        // Nothing to do.
    }

    @Override
    public EncodeResult encodeRequest(
            Channel channel, SessionProtocol sessionProtocol, Method method, Object[] args) {
        @SuppressWarnings("unchecked")  // Guaranteed by SimpleHttpClient interface.
        SimpleHttpRequest request = (SimpleHttpRequest) args[0];
        FullHttpRequest fullHttpRequest = convertToFullHttpRequest(request, channel);
        Scheme scheme = Scheme.of(SerializationFormat.NONE, sessionProtocol);
        return new SimpleHttpInvocation(channel, scheme, host, request.uri().getPath(),
                                        fullHttpRequest, request);
    }

    @Override
    public <T> T decodeResponse(ServiceInvocationContext ctx, ByteBuf content, Object originalResponse)
            throws Exception {
        if (!(originalResponse instanceof FullHttpResponse)) {
            throw new IllegalStateException("HTTP client can only be used when session protocol is HTTP: " +
                                            ctx.scheme().uriText());
        }
        FullHttpResponse httpResponse = (FullHttpResponse) originalResponse;
        byte[] body = content.readableBytes() == 0 ? EMPTY : ByteBufUtil.getBytes(content);
        @SuppressWarnings("unchecked") // Guaranteed by SimpleHttpClient interface.
        T response = (T) new SimpleHttpResponse(httpResponse.status(), httpResponse.headers(), body);
        return response;
    }

    @Override
    public boolean isAsyncClient() {
        return true;
    }

    private static FullHttpRequest convertToFullHttpRequest(SimpleHttpRequest request, Channel channel) {
        FullHttpRequest fullHttpRequest;
        if (request.content().length > 0) {
            ByteBuf content = channel.alloc().ioBuffer().writeBytes(request.content());
            fullHttpRequest = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, request.method(), request.uri().getPath(), content);
        } else {
            fullHttpRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, request.method(),
                                                         request.uri().getPath());
        }
        fullHttpRequest.headers().set(request.headers());
        return fullHttpRequest;
    }
}
