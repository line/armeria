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

package com.linecorp.armeria.server;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

final class ServerHttp1ObjectEncoder extends Http1ObjectEncoder implements ServerHttpObjectEncoder {
    @Nullable
    private final KeepAliveHandler keepAliveHandler;
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;

    private boolean sentConnectionCloseHeader;

    ServerHttp1ObjectEncoder(Channel ch, SessionProtocol protocol,
                             @Nullable KeepAliveHandler keepAliveHandler,
                             boolean enableDateHeader, boolean enableServerHeader) {
        super(ch, protocol);
        this.keepAliveHandler = keepAliveHandler;
        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                        boolean isTrailersEmpty) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        final HttpResponse converted = convertHeaders(headers, endStream, isTrailersEmpty);
        if (headers.status().isInformational()) {
            return write(id, converted, false);
        }

        if (keepAliveHandler != null && keepAliveHandler.needToCloseConnection()) {
            converted.headers().set(HttpHeaderNames.CONNECTION, "close");
            sentConnectionCloseHeader = true;
        }
        return writeNonInformationalHeaders(id, converted, endStream);
    }

    private HttpResponse convertHeaders(ResponseHeaders headers, boolean endStream, boolean isTrailersEmpty) {
        final int statusCode = headers.status().code();
        final HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        final HttpResponse res;
        if (headers.status().isInformational()) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            ArmeriaHttpUtil.toNettyHttp1ServerHeader(headers, res.headers());
        } else if (endStream) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            final io.netty.handler.codec.http.HttpHeaders outHeaders = res.headers();
            convertHeaders(headers, outHeaders, isTrailersEmpty);

            if (HttpStatus.isContentAlwaysEmpty(statusCode)) {
                if (statusCode == 304) {
                    // 304 response can have the "content-length" header when it is a response to a conditional
                    // GET request. See https://tools.ietf.org/html/rfc7230#section-3.3.2
                } else {
                    outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
                }
            } else if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                // NB: Set the 'content-length' only when not set rather than always setting to 0.
                //     It's because a response to a HEAD request can have empty content while having
                //     non-zero 'content-length' header.
                //     However, this also opens the possibility of sending a non-zero 'content-length'
                //     header even when it really has to be zero. e.g. a response to a non-HEAD request
                outHeaders.setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            }
        } else {
            res = new DefaultHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, false);
            convertHeaders(headers, res.headers(), isTrailersEmpty);
            maybeSetTransferEncoding(res);
        }

        return res;
    }

    private void convertHeaders(HttpHeaders inHeaders, io.netty.handler.codec.http.HttpHeaders outHeaders,
                                boolean isTrailersEmpty) {
        ArmeriaHttpUtil.toNettyHttp1ServerHeader(inHeaders, outHeaders);

        if (!isTrailersEmpty && outHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // We don't apply chunked encoding when the content-length header is set, which would
            // prevent the trailers from being sent so we go ahead and remove content-length to
            // force chunked encoding.
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }

        if (enableServerHeader && !outHeaders.contains(HttpHeaderNames.SERVER)) {
            outHeaders.add(HttpHeaderNames.SERVER, ArmeriaHttpUtil.SERVER_HEADER);
        }

        if (enableDateHeader && !outHeaders.contains(HttpHeaderNames.DATE)) {
            outHeaders.add(HttpHeaderNames.DATE, HttpTimestampSupplier.currentTime());
        }
    }

    private static void maybeSetTransferEncoding(HttpMessage out) {
        final io.netty.handler.codec.http.HttpHeaders outHeaders = out.headers();
        final long contentLength = HttpUtil.getContentLength(out, -1L);
        if (contentLength < 0) {
            // Use chunked encoding.
            outHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    @Override
    protected void convertTrailers(HttpHeaders inputHeaders,
                                   io.netty.handler.codec.http.HttpHeaders outputHeaders) {
        ArmeriaHttpUtil.toNettyHttp1ServerTrailer(inputHeaders, outputHeaders);
    }

    @Nullable
    @Override
    public KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    @Override
    protected boolean isPing(int id) {
        return false;
    }

    boolean isSentConnectionCloseHeader() {
        return sentConnectionCloseHeader;
    }
}
