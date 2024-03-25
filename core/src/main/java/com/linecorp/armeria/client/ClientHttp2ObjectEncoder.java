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

import static com.linecorp.armeria.internal.client.ClosedStreamExceptionUtil.newClosedStreamException;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http2ObjectEncoder;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Connection.Endpoint;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2LocalFlowController;

final class ClientHttp2ObjectEncoder extends Http2ObjectEncoder implements ClientHttpObjectEncoder {
    private final SessionProtocol protocol;

    ClientHttp2ObjectEncoder(ChannelHandlerContext connectionHandlerCtx,
                             Http2ClientConnectionHandler connectionHandler,
                             SessionProtocol protocol) {
        super(connectionHandlerCtx, connectionHandler);
        this.protocol = requireNonNull(protocol, "protocol");
        assert keepAliveHandler() instanceof Http2ClientKeepAliveHandler ||
               keepAliveHandler() instanceof NoopKeepAliveHandler;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, RequestHeaders headers, boolean endStream,
                                        ChannelPromise promise) {
        final Http2Connection conn = encoder().connection();
        if (isStreamPresentAndWritable(streamId)) {
            keepAliveHandler().onReadOrWrite();
            return encoder().writeHeaders(ctx(), streamId, convertHeaders(headers), 0,
                                          endStream, promise);
        }

        final Endpoint<Http2LocalFlowController> local = conn.local();
        if (local.mayHaveCreatedStream(streamId)) {
            final ClosedStreamException closedStreamException =
                    new ClosedStreamException("Cannot create a new stream. streamId: " + streamId +
                                              ", lastStreamCreated: " + local.lastStreamCreated());
            return newFailedFuture(UnprocessedRequestException.of(closedStreamException));
        }

        // Client starts a new stream.
        return encoder().writeHeaders(ctx(), streamId, convertHeaders(headers), 0, endStream, promise);
    }

    private Http2Headers convertHeaders(HttpHeaders inputHeaders) {
        final Http2Headers outputHeaders = ArmeriaHttpUtil.toNettyHttp2ClientHeaders(inputHeaders);

        if (!outputHeaders.contains(HttpHeaderNames.SCHEME)) {
            outputHeaders.add(HttpHeaderNames.SCHEME, protocol.isTls() ? SessionProtocol.HTTPS.uriText()
                                                                       : SessionProtocol.HTTP.uriText());
        }

        if (!outputHeaders.contains(HttpHeaderNames.AUTHORITY) &&
            !outputHeaders.contains(HttpHeaderNames.HOST)) {
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
            keepAliveHandler().onReadOrWrite();
            return encoder().writeHeaders(ctx(), streamId, ArmeriaHttpUtil.toNettyHttp2ClientTrailers(headers),
                                          0, true, ctx().newPromise());
        }

        return newFailedFuture(newClosedStreamException(channel()));
    }
}
