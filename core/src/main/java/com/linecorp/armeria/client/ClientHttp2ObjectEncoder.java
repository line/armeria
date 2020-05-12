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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.client.HttpHeaderUtil;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http2KeepAliveHandler;
import com.linecorp.armeria.internal.common.Http2ObjectEncoder;
import com.linecorp.armeria.internal.common.KeepAliveHandler;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Connection.Endpoint;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;

final class ClientHttp2ObjectEncoder extends Http2ObjectEncoder implements ClientHttpObjectEncoder {
    private final SessionProtocol protocol;
    @Nullable
    private final Http2KeepAliveHandler keepAliveHandler;

    ClientHttp2ObjectEncoder(ChannelHandlerContext ctx, Http2ConnectionEncoder encoder,
                             SessionProtocol protocol, @Nullable Http2KeepAliveHandler keepAliveHandler) {
        super(ctx, encoder);
        this.protocol = requireNonNull(protocol, "protocol");
        this.keepAliveHandler = keepAliveHandler;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, RequestHeaders headers, boolean endStream) {
        final Http2Connection conn = encoder().connection();
        if (isStreamPresentAndWritable(streamId)) {
            if (keepAliveHandler != null) {
                keepAliveHandler.onReadOrWrite();
            }
            return encoder().writeHeaders(ctx(), streamId, convertHeaders(headers), 0,
                                          endStream, ctx().newPromise());
        }

        final Endpoint<Http2LocalFlowController> local = conn.local();
        if (local.mayHaveCreatedStream(streamId)) {
            final ClosedStreamException closedStreamException =
                    new ClosedStreamException("Cannot create a new stream. streamId: " + streamId +
                                              ", lastStreamCreated: " + local.lastStreamCreated());
            return newFailedFuture(new UnprocessedRequestException(closedStreamException));
        }

        // Client starts a new stream.
        return encoder().writeHeaders(ctx(), streamId, convertHeaders(headers), 0, endStream,
                                      ctx().newPromise());
    }

    private Http2Headers convertHeaders(HttpHeaders inputHeaders) {
        final Http2Headers outputHeaders = ArmeriaHttpUtil.toNettyHttp2ClientHeader(inputHeaders);

        if (!outputHeaders.contains(HttpHeaderNames.USER_AGENT)) {
            outputHeaders.add(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }

        if (!outputHeaders.contains(HttpHeaderNames.SCHEME)) {
            outputHeaders.add(HttpHeaderNames.SCHEME, protocol.isTls() ? SessionProtocol.HTTPS.uriText()
                                                                       : SessionProtocol.HTTP.uriText());
        }

        if (!outputHeaders.contains(HttpHeaderNames.AUTHORITY)) {
            final InetSocketAddress remoteAddress = (InetSocketAddress) channel().remoteAddress();
            outputHeaders.add(HttpHeaderNames.AUTHORITY,
                              ArmeriaHttpUtil.authorityHeader(remoteAddress.getHostName(),
                                                              remoteAddress.getPort(), protocol.defaultPort()));
        }
        return outputHeaders;
    }

    @Override
    public ChannelFuture doWriteTrailers(int id, int streamId, HttpHeaders headers) {
        if (isStreamPresentAndWritable(streamId)) {
            if (keepAliveHandler != null) {
                keepAliveHandler.onReadOrWrite();
            }
            return encoder().writeHeaders(ctx(), streamId, ArmeriaHttpUtil.toNettyHttp2ClientTrailer(headers),
                                          0, true, ctx().newPromise());
        }

        return newFailedFuture(ClosedStreamException.get());
    }

    @Nullable
    @Override
    public KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }
}
