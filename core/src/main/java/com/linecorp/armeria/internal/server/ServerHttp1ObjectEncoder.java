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

package com.linecorp.armeria.internal.server;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.util.HttpTimestampSupplier;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Exception;

public final class ServerHttp1ObjectEncoder extends Http1ObjectEncoder {
    private final boolean enableServerHeader;
    private final boolean enableDateHeader;

    public ServerHttp1ObjectEncoder(Channel ch, SessionProtocol protocol,
                                    boolean enableServerHeader, boolean enableDateHeader) {
        super(ch, protocol);
        this.enableServerHeader = enableServerHeader;
        this.enableDateHeader = enableDateHeader;
    }

    @Override
    protected ChannelFuture doWriteHeaders(int id, int streamId, HttpHeaders headers, boolean endStream,
                                           HttpHeaders additionalHeaders, HttpHeaders additionalTrailers) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        try {
            final HttpObject converted;
            final String status = headers.get(HttpHeaderNames.STATUS);
            if (status == null) {
                // Trailers
                converted = convertTrailers(streamId, headers, endStream, additionalTrailers);
                final ChannelFuture f = write(id, converted, endStream);
                channel().flush();
                return f;
            }

            converted = convertHeaders(streamId, headers, endStream, additionalHeaders, additionalTrailers);

            if (!status.isEmpty() && status.charAt(0) == '1') {
                // Informational status headers.
                final ChannelFuture f = write(id, converted, false);
                if (endStream) {
                    // Can't end a stream with informational status in HTTP/1.
                    f.addListener(ChannelFutureListener.CLOSE);
                }
                channel().flush();
                return f;
            }

            // Non-informational status headers.
            return writeNonInformationalHeaders(id, converted, endStream);
        } catch (Throwable t) {
            return newFailedFuture(t);
        }
    }

    private static LastHttpContent convertTrailers(int streamId, HttpHeaders inHeaders, boolean endStream,
                                                   HttpHeaders additionalTrailers) throws Http2Exception {
        if (inHeaders.isEmpty()) {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }

        final LastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, false);

        ArmeriaHttpUtil.toNettyHttp1ServerTrailer(streamId, inHeaders, additionalTrailers,
                                                  lastContent.trailingHeaders(), endStream);

        removeHttpExtensionHeaders(lastContent.trailingHeaders());
        return lastContent;
    }

    private HttpObject convertHeaders(
            int streamId, HttpHeaders headers, boolean endStream,
            HttpHeaders additionalHeaders, HttpHeaders additionalTrailers) throws Http2Exception {
        final String status = headers.get(HttpHeaderNames.STATUS);
        final HttpResponse res;
        final int statusCode = Integer.parseInt(status);
        final boolean informational = HttpStatusClass.INFORMATIONAL.contains(statusCode);
        final HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        if (endStream || informational) {
            res = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, nettyStatus,
                    Unpooled.EMPTY_BUFFER, false);

            final io.netty.handler.codec.http.HttpHeaders outHeaders = res.headers();
            convertHeaders0(streamId, headers, outHeaders, endStream,
                            additionalHeaders, additionalTrailers);

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
            // Perform conversion.
            convertHeaders0(streamId, headers, res.headers(), endStream,
                            additionalHeaders, additionalTrailers);
            setTransferEncoding(res);
        }
        return res;
    }

    private static void setTransferEncoding(HttpMessage out) {
        final io.netty.handler.codec.http.HttpHeaders outHeaders = out.headers();
        final long contentLength = HttpUtil.getContentLength(out, -1L);
        if (contentLength < 0) {
            // Use chunked encoding.
            outHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    private void convertHeaders0(
            int streamId, HttpHeaders inHeaders, io.netty.handler.codec.http.HttpHeaders outHeaders,
            boolean endStream, HttpHeaders additionalHeaders, HttpHeaders additionalTrailers)
            throws Http2Exception {
        ArmeriaHttpUtil.toNettyHttp1ServerHeader(streamId, inHeaders, additionalHeaders, additionalTrailers,
                                                 outHeaders, HttpVersion.HTTP_1_1, endStream);
        removeHttpExtensionHeaders(outHeaders);

        if (!additionalTrailers.isEmpty() &&
            outHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
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
}
