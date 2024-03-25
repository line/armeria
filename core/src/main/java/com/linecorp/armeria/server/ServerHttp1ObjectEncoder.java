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

import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.internal.common.NoopKeepAliveHandler;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.util.ReferenceCountUtil;

final class ServerHttp1ObjectEncoder extends Http1ObjectEncoder implements ServerHttpObjectEncoder {
    private final KeepAliveHandler keepAliveHandler;
    private final Http1HeaderNaming http1HeaderNaming;

    /**
     * The ID of the last request whose response headers is written.
     */
    private int lastResponseHeadersId;
    private boolean webSocketUpgrading;
    private boolean webSocketUpgraded;

    ServerHttp1ObjectEncoder(Channel ch, SessionProtocol protocol, KeepAliveHandler keepAliveHandler,
                             Http1HeaderNaming http1HeaderNaming) {
        super(ch, protocol);
        assert keepAliveHandler instanceof Http1ServerKeepAliveHandler ||
               keepAliveHandler instanceof NoopKeepAliveHandler;
        this.keepAliveHandler = keepAliveHandler;
        this.http1HeaderNaming = http1HeaderNaming;
    }

    @Override
    public KeepAliveHandler keepAliveHandler() {
        return keepAliveHandler;
    }

    @Override
    public ChannelFuture doWriteHeaders(int id, int streamId, ResponseHeaders headers, boolean endStream,
                                        boolean isTrailersEmpty, HttpMethod method) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        if (webSocketUpgrading && headers.status() == HttpStatus.SWITCHING_PROTOCOLS) {
            webSocketUpgraded = true;
            final HttpResponse res = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS, false);
            // Call toNettyHttp1Server directly not to remove "Connection: Upgrade" header.
            ArmeriaHttpUtil.toNettyHttp1Server(headers, res.headers(), http1HeaderNaming, false);
            return write(id, res, false);
        }

        final HttpResponse converted = convertHeaders(headers, endStream, isTrailersEmpty, method);
        if (headers.status().isInformational()) {
            return write(id, converted, false);
        }

        return writeNonInformationalHeaders(id, converted, endStream, channel().newPromise());
    }

    private HttpResponse convertHeaders(ResponseHeaders headers, boolean endStream, boolean isTrailersEmpty,
                                        HttpMethod method) {
        final int statusCode = headers.status().code();
        final HttpResponseStatus nettyStatus = HttpResponseStatus.valueOf(statusCode);

        final HttpResponse res;
        if (headers.status().isInformational()) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            ArmeriaHttpUtil.toNettyHttp1ServerHeaders(headers, res.headers(), http1HeaderNaming, true);
        } else if (endStream) {
            res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, Unpooled.EMPTY_BUFFER, false);
            final io.netty.handler.codec.http.HttpHeaders outHeaders = res.headers();
            convertHeaders(headers, outHeaders, isTrailersEmpty);

            if (HttpStatus.isContentAlwaysEmpty(statusCode)) {
                maybeRemoveContentLength(statusCode, outHeaders);
            } else if (method == HttpMethod.HEAD) {
                // Preserve the 'content-length' header set by a user.
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
            maybeSetTransferEncoding(res, statusCode);
        }

        return res;
    }

    private void convertHeaders(ResponseHeaders inHeaders, io.netty.handler.codec.http.HttpHeaders outHeaders,
                                boolean isTrailersEmpty) {
        ArmeriaHttpUtil.toNettyHttp1ServerHeaders(inHeaders, outHeaders, http1HeaderNaming,
                                                  !keepAliveHandler.needsDisconnection());

        if (!isTrailersEmpty && outHeaders.contains(HttpHeaderNames.CONTENT_LENGTH)) {
            // We don't apply chunked encoding when the content-length header is set, which would
            // prevent the trailers from being sent so we go ahead and remove content-length to
            // force chunked encoding.
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    private static void maybeRemoveContentLength(int statusCode,
                                                 io.netty.handler.codec.http.HttpHeaders outHeaders) {
        if (statusCode == 304) {
            // 304 response can have the "content-length" header when it is a response to a conditional
            // GET request. See https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2
        } else {
            outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
        }
    }

    private static void maybeSetTransferEncoding(HttpMessage out, int statusCode) {
        final io.netty.handler.codec.http.HttpHeaders outHeaders = out.headers();
        if (HttpStatus.isContentAlwaysEmpty(statusCode)) {
            maybeRemoveContentLength(statusCode, outHeaders);
        } else {
            final long contentLength = HttpUtil.getContentLength(out, -1L);
            if (contentLength < 0) {
                // Use chunked encoding.
                outHeaders.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                outHeaders.remove(HttpHeaderNames.CONTENT_LENGTH);
            }
        }
    }

    @Override
    protected ChannelFuture write(HttpObject obj, ChannelPromise promise) {
        // Use FQCN for Netty HttpResponse to avoid confusion with Armeria HttpResponse
        // We check if obj is an HttpResponse here because server-side writes both headers
        // and errors as an HttpResponse.
        //noinspection UnnecessaryFullyQualifiedName
        if (obj instanceof io.netty.handler.codec.http.HttpResponse) {
            final int currentId = currentId();
            if (lastResponseHeadersId >= currentId) {
                // Response headers were written already. This may occur Http1RequestDecoder sends an error
                // response while HttpResponseSubscriber writes a response headers and then waits for bodies.
                ReferenceCountUtil.release(obj);
                return writeReset(currentId, 1, Http2Error.PROTOCOL_ERROR, false);
            }
            if (webSocketUpgraded ||
                ((HttpResponse) obj).status().codeClass() != HttpStatusClass.INFORMATIONAL) {
                lastResponseHeadersId = currentId;
            }
        }

        return channel().write(obj, promise);
    }

    @Override
    protected void convertTrailers(HttpHeaders inputHeaders,
                                   io.netty.handler.codec.http.HttpHeaders outputHeaders) {
        ArmeriaHttpUtil.toNettyHttp1ServerTrailers(inputHeaders, outputHeaders, http1HeaderNaming);
    }

    @Override
    protected boolean isPing(int id) {
        return false;
    }

    @Override
    public boolean isResponseHeadersSent(int id, int streamId) {
        return id <= lastResponseHeadersId;
    }

    @Override
    public ChannelFuture writeErrorResponse(int id, int streamId,
                                            ServiceConfig serviceConfig,
                                            @Nullable RequestHeaders headers,
                                            HttpStatus status,
                                            @Nullable String message,
                                            @Nullable Throwable cause) {

        // Mark keepAlive handler as disconnected before writing headers so that ServerHttp1ObjectEncoder sets
        // "Connection: close" to the response headers
        keepAliveHandler().disconnectWhenFinished();

        final ChannelFuture future = ServerHttpObjectEncoder.super.writeErrorResponse(
                id, streamId, serviceConfig, headers, status, message, cause);
        // Update the closed ID to prevent the HttpResponseSubscriber from
        // writing additional headers or messages.
        updateClosedId(id);

        return future.addListener(ChannelFutureListener.CLOSE);
    }

    void webSocketUpgrading() {
        webSocketUpgrading = true;
    }
}
