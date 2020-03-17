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

package com.linecorp.armeria.internal.client;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.common.Http1ObjectEncoder;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2Exception;

public final class ClientHttp1ObjectEncoder extends Http1ObjectEncoder {
    public ClientHttp1ObjectEncoder(Channel ch, SessionProtocol protocol) {
        super(ch, protocol);
    }

    @Override
    protected ChannelFuture doWriteHeaders(int id, int streamId, HttpHeaders headers, boolean endStream,
                                           HttpHeaders additionalHeaders, HttpHeaders additionalTrailers) {
        if (!isWritable(id)) {
            return newClosedSessionFuture();
        }

        try {
            final HttpObject converted;
            final String method = headers.get(HttpHeaderNames.METHOD);
            if (method == null) {
                converted = convertTrailers(streamId, headers);
            } else {
                converted = convertHeaders(streamId, headers, endStream, additionalHeaders);
            }
            return writeNonInformationalHeaders(id, converted, endStream);
        } catch (Throwable t) {
            return newFailedFuture(t);
        }
    }

    private static LastHttpContent convertTrailers(int streamId, HttpHeaders inHeaders) throws Http2Exception {
        if (inHeaders.isEmpty()) {
            return LastHttpContent.EMPTY_LAST_CONTENT;
        }
        final LastHttpContent lastContent = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER, false);

        ArmeriaHttpUtil.toNettyHttp1ClientTrailer(streamId, inHeaders, lastContent.trailingHeaders());

        removeHttpExtensionHeaders(lastContent.trailingHeaders());
        return  lastContent;
    }

    private HttpObject convertHeaders(int streamId, HttpHeaders headers, boolean endStream,
                                      HttpHeaders additionalHeaders) throws Http2Exception {
        final String method = headers.get(HttpHeaderNames.METHOD);
        final String path = headers.get(HttpHeaderNames.PATH);
        assert path != null;
        final HttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), path,
                                                       false);
        ArmeriaHttpUtil.toNettyHttp1ClientHeader(streamId, headers, additionalHeaders, req.headers(),
                                                 HttpVersion.HTTP_1_1);

        removeHttpExtensionHeaders(req.headers());

        if (!req.headers().contains(HttpHeaderNames.USER_AGENT)) {
            req.headers().add(HttpHeaderNames.USER_AGENT, HttpHeaderUtil.USER_AGENT.toString());
        }

        if (!req.headers().contains(HttpHeaderNames.HOST)) {
            final InetSocketAddress remoteAddress = (InetSocketAddress) channel().remoteAddress();
            req.headers().add(HttpHeaderNames.HOST, ArmeriaHttpUtil.authorityHeader(remoteAddress.getHostName(),
                                                                                    remoteAddress.getPort(),
                                                                                    protocol().defaultPort()));
        }

        if (endStream) {
            req.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);

            // Set or remove the 'content-length' header depending on request method.
            // See: https://tools.ietf.org/html/rfc7230#section-3.3.2
            //
            // > A user agent SHOULD send a Content-Length in a request message when
            // > no Transfer-Encoding is sent and the request method defines a meaning
            // > for an enclosed payload body.  For example, a Content-Length header
            // > field is normally sent in a POST request even when the value is 0
            // > (indicating an empty payload body).  A user agent SHOULD NOT send a
            // > Content-Length header field when the request message does not contain
            // > a payload body and the method semantics do not anticipate such a
            // > body.
            switch (method) {
                case "POST":
                case "PUT":
                case "PATCH":
                    req.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
                    break;
                default:
                    req.headers().remove(HttpHeaderNames.CONTENT_LENGTH);
            }
        } else if (HttpUtil.getContentLength(req, -1L) >= 0) {
            // Avoid the case where both 'content-length' and 'transfer-encoding' are set.
            req.headers().remove(HttpHeaderNames.TRANSFER_ENCODING);
        } else {
            req.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        }
        return req;
    }
}
