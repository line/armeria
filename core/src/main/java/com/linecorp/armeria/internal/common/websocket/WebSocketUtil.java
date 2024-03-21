/*
 * Copyright 2022 LINE Corporation
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

import static com.google.common.base.Strings.isNullOrEmpty;
import static io.netty.util.AsciiString.contentEquals;
import static io.netty.util.AsciiString.contentEqualsIgnoreCase;
import static io.netty.util.AsciiString.trim;
import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Iterator;

import com.google.common.hash.Hashing;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.websocket.CloseWebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketCloseStatus;
import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.server.websocket.WebSocketProtocolViolationException;

import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.AsciiString;

public final class WebSocketUtil {

    private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final long DEFAULT_REQUEST_RESPONSE_TIMEOUT_MILLIS = 0;
    public static final long DEFAULT_MAX_REQUEST_RESPONSE_LENGTH = 0;
    public static final long DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS = 5000;

    public static boolean isHttp1WebSocketUpgradeRequest(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        // GET /chat HTTP/1.1
        // Upgrade: websocket
        // Connection: Upgrade
        // ...
        return headers.method() == HttpMethod.GET &&
               containsValue(headers, HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true) &&
               containsValue(headers, HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
    }

    // Forked from https://github.com/netty/netty/blob/4.1/codec-http/src/main/java/io/netty/handler/codec/http/HttpHeaders.java#L1597-L1651
    private static boolean containsValue(RequestHeaders headers, CharSequence name,
                                         CharSequence value, boolean ignoreCase) {
        final Iterator<? extends CharSequence> itr = headers.valueIterator(name);
        while (itr.hasNext()) {
            if (containsCommaSeparatedTrimmed(itr.next(), value, ignoreCase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCommaSeparatedTrimmed(CharSequence rawNext, CharSequence expected,
                                                         boolean ignoreCase) {
        int begin = 0;
        int end;
        if (ignoreCase) {
            if ((end = AsciiString.indexOf(rawNext, ',', begin)) == -1) {
                if (contentEqualsIgnoreCase(trim(rawNext), expected)) {
                    return true;
                }
            } else {
                do {
                    if (contentEqualsIgnoreCase(trim(rawNext.subSequence(begin, end)), expected)) {
                        return true;
                    }
                    begin = end + 1;
                } while ((end = AsciiString.indexOf(rawNext, ',', begin)) != -1);

                if (begin < rawNext.length()) {
                    if (contentEqualsIgnoreCase(trim(rawNext.subSequence(begin, rawNext.length())), expected)) {
                        return true;
                    }
                }
            }
        } else {
            if ((end = AsciiString.indexOf(rawNext, ',', begin)) == -1) {
                if (contentEquals(trim(rawNext), expected)) {
                    return true;
                }
            } else {
                do {
                    if (contentEquals(trim(rawNext.subSequence(begin, end)), expected)) {
                        return true;
                    }
                    begin = end + 1;
                } while ((end = AsciiString.indexOf(rawNext, ',', begin)) != -1);

                if (begin < rawNext.length()) {
                    if (contentEquals(trim(rawNext.subSequence(begin, rawNext.length())), expected)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean isHttp2WebSocketUpgradeRequest(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        // HEADERS + END_HEADERS
        // :method = CONNECT
        // :protocol = websocket
        // ...
        return headers.method() == HttpMethod.CONNECT &&
               containsValue(headers, HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET, true);
    }

    /**
     * Generates Sec-WebSocket-Accept using Sec-WebSocket-Key.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-1.3">Opening Handshake</a>
     */
    public static String generateSecWebSocketAccept(String webSocketKey) {
        final String acceptSeed = webSocketKey + MAGIC_GUID;
        final byte[] sha1 = Hashing.sha1().hashBytes(acceptSeed.getBytes(StandardCharsets.US_ASCII)).asBytes();
        return Base64.getEncoder().encodeToString(sha1);
    }

    static int byteAtIndex(int mask, int index) {
        return (mask >> 8 * (3 - index)) & 0xFF;
    }

    //TODO(minwoox): provide an exception handler that converts a cause into a CloseWebSocketFrame.
    public static CloseWebSocketFrame newCloseWebSocketFrame(Throwable cause) {
        final WebSocketCloseStatus closeStatus;
        if (cause instanceof WebSocketProtocolViolationException) {
            closeStatus = ((WebSocketProtocolViolationException) cause).closeStatus();
        } else {
            closeStatus = WebSocketCloseStatus.INTERNAL_SERVER_ERROR;
        }
        // If the length of the phrase exceeds 125 characters, it is truncated to satisfy the
        // <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-5.5">specification</a>.
        String reasonPhrase = maybeTruncate(cause.getMessage());
        if (reasonPhrase == null) {
            reasonPhrase = closeStatus.reasonPhrase();
        }
        return WebSocketFrame.ofClose(closeStatus, reasonPhrase);
    }

    @Nullable
    public static String maybeTruncate(@Nullable String message) {
        if (isNullOrEmpty(message)) {
            return null;
        }
        if (message.length() <= 125) {
            return message;
        }
        return message.substring(0, 111) + "...(truncated)"; // + 14 characters
    }

    private WebSocketUtil() {}
}
