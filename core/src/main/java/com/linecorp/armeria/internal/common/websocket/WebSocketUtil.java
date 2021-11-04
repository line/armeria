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
package com.linecorp.armeria.internal.common.websocket;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.StreamWriter;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

public final class WebSocketUtil {

    private static final AttributeKey<StreamWriter<HttpObject>> WEB_SOCKET_STREAM =
            AttributeKey.valueOf(WebSocketUtil.class, "WEB_SOCKET_STREAM");

    public static void setWebSocketInboundStream(AttributeMap attributeMap, StreamWriter<HttpObject> writer) {
        requireNonNull(attributeMap, "attributeMap");
        requireNonNull(writer, "writer");
        attributeMap.attr(WEB_SOCKET_STREAM).set(writer);
    }

    public static void closeWebSocketInboundStream(AttributeMap attributeMap) {
        requireNonNull(attributeMap, "attributeMap");
        if (attributeMap.hasAttr(WEB_SOCKET_STREAM)) {
            attributeMap.attr(WEB_SOCKET_STREAM).get().close();
        }
    }

    public static void closeWebSocketInboundStream(AttributeMap attributeMap, Throwable cause) {
        requireNonNull(attributeMap, "attributeMap");
        requireNonNull(cause, "cause");
        if (attributeMap.hasAttr(WEB_SOCKET_STREAM)) {
            attributeMap.attr(WEB_SOCKET_STREAM).get().close(cause);
        }
    }

    public static boolean isHttp1WebSocketUpgradeRequest(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        // GET /chat HTTP/1.1
        // Upgrade: websocket
        // Connection: Upgrade
        // ...
        return headers.method() == HttpMethod.GET &&
               HttpHeaderValues.UPGRADE.contentEqualsIgnoreCase(headers.get(HttpHeaderNames.CONNECTION)) &&
               HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(headers.get(HttpHeaderNames.UPGRADE));
    }

    public static boolean isHttp1WebSocketUpgradeResponse(ResponseHeaders headers) {
        requireNonNull(headers, "headers");
        // HTTP/1.1 101 Switching Protocols
        // Upgrade: websocket
        // Connection: Upgrade
        // ...
        return headers.status() == HttpStatus.SWITCHING_PROTOCOLS &&
               HttpHeaderValues.UPGRADE.contentEqualsIgnoreCase(headers.get(HttpHeaderNames.CONNECTION)) &&
               HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(headers.get(HttpHeaderNames.UPGRADE));
    }

    public static boolean isHttp2WebSocketUpgradeRequest(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        // HEADERS + END_HEADERS
        // :method = CONNECT
        // :protocol = websocket
        // ...
        return headers.method() == HttpMethod.CONNECT &&
               HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(headers.get(HttpHeaderNames.PROTOCOL));
    }

    static int intMask(byte[] mask) {
        // Remark: & 0xFF is necessary because Java will do signed expansion from
        // byte to int which we don't want.
        return ((mask[0] & 0xFF) << 24) |
               ((mask[1] & 0xFF) << 16) |
               ((mask[2] & 0xFF) << 8) |
               (mask[3] & 0xFF);
    }

    private WebSocketUtil() {}
}
