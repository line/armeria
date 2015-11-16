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

package com.linecorp.armeria.client.thrift;

import static java.util.Objects.requireNonNull;

import java.net.URI;

import org.apache.thrift.transport.TTransportException;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;

class ByteBufOverHttpCodec extends ChannelDuplexHandler {
    private static final String APPLICATION_X_THRIFT = "application/x-thrift";
    
    private final URI uri;

    ByteBufOverHttpCodec(URI uri) {
        this.uri = requireNonNull(uri, "uri");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpResponse) {
            FullHttpResponse response = (FullHttpResponse) msg;
            ByteBuf content = ((FullHttpResponse) msg).content();

            if (HttpStatusClass.SUCCESS != response.status().codeClass()) {
                ctx.fireExceptionCaught(new TTransportException("HTTP Response code: " + response.status()));
            }

            if (content.isReadable()) {
                ctx.fireChannelRead(content);
            }
        } else {
            ctx.fireChannelRead(msg);
        }

    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf) {

            ByteBuf content = (ByteBuf) msg;
            DefaultFullHttpRequest request =
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getPath(), content);

            HttpHeaders httpHeaders = request.headers();
            httpHeaders.set(HttpHeaderNames.HOST, uri.getHost());
            httpHeaders.set(ExtensionHeaderNames.SCHEME.text(), uri.getScheme());
            httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
            httpHeaders.set(HttpHeaderNames.CONTENT_TYPE, APPLICATION_X_THRIFT);
            httpHeaders.set(HttpHeaderNames.ACCEPT, APPLICATION_X_THRIFT);

            //TODO check custom header exists on config
            ctx.write(request, promise);
        } else {
            ctx.write(msg, promise);
        }
    }

}
