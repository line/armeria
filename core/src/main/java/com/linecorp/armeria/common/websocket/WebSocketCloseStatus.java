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
/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.websocket;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * WebSocket <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.1">Defined Status Codes</a>.
 */
@UnstableApi
public final class WebSocketCloseStatus {

    // Forked from Netty 4.1.92 https://github.com/netty/netty/blob/05093de0d6fe5787447439836e2ca159e79c88bf/codec-http/src/main/java/io/netty/handler/codec/http/websocketx/WebSocketCloseStatus.java

    /**
     * {@code 1000} indicates a normal closure.
     */
    public static final WebSocketCloseStatus NORMAL_CLOSURE =
            new WebSocketCloseStatus(1000, "Bye");

    /**
     * {@code 1001} indicates that an endpoint is "going away", such as a server
     * going down or a browser having navigated away from a page.
     */
    public static final WebSocketCloseStatus ENDPOINT_UNAVAILABLE =
            new WebSocketCloseStatus(1001, "Endpoint unavailable");

    /**
     * {@code 1002} indicates that an endpoint is terminating the connection due to a protocol error.
     */
    public static final WebSocketCloseStatus PROTOCOL_ERROR =
            new WebSocketCloseStatus(1002, "Protocol error");

    /**
     * {@code 1003} indicates that an endpoint is terminating the connection because it has received
     * a type of data it cannot accept (e.g., an endpoint that understands only text data MAY send this if it
     * receives a binary message).
     */
    public static final WebSocketCloseStatus INVALID_MESSAGE_TYPE =
            new WebSocketCloseStatus(1003, "Invalid message type");

    /**
     * {@code 1007} indicates that an endpoint is terminating the connection
     * because it has received data within a message that was not
     * consistent with the type of the message (e.g., non-UTF-8 [RFC3629] data within a text message).
     */
    public static final WebSocketCloseStatus INVALID_PAYLOAD_DATA =
            new WebSocketCloseStatus(1007, "Invalid payload data");

    /**
     * {@code 1008} indicates that an endpoint is terminating the connection
     * because it has received a message that violates its policy. This
     * is a generic status code that can be returned when there is no
     * other more suitable status code (e.g., {@code 1003} or {@code 1009}) or if there
     * is a need to hide specific details about the policy.
     */
    public static final WebSocketCloseStatus POLICY_VIOLATION =
            new WebSocketCloseStatus(1008, "Policy violation");

    /**
     * {@code 1009} indicates that an endpoint is terminating the connection
     * because it has received a message that is too big for it to process.
     */
    public static final WebSocketCloseStatus MESSAGE_TOO_BIG =
            new WebSocketCloseStatus(1009, "Message too big");

    /**
     * {@code 1010} indicates that an endpoint (client) is terminating the connection
     * because it has expected the server to negotiate one or more extension,
     * but the server didn't return them in the response message of the WebSocket handshake.
     * The list of extensions that are needed SHOULD appear in the {@code reason} part of the Close frame.
     * Note that this status code is not used by the server,
     * because it can fail the WebSocket handshake instead.
     */
    public static final WebSocketCloseStatus MANDATORY_EXTENSION =
            new WebSocketCloseStatus(1010, "Mandatory extension");

    /**
     * {@code 1011} indicates that a server is terminating the connection
     * because it encountered an unexpected condition that prevented it from fulfilling the request.
     */
    public static final WebSocketCloseStatus INTERNAL_SERVER_ERROR =
            new WebSocketCloseStatus(1011, "Internal server error");

    /**
     * {@code 1012} (IANA Registry, Non RFC 6455) indicates that the service is restarted.
     * A client may reconnect, and if it chooses to do, should reconnect using a randomized delay
     * of 5 - 30 seconds.
     */
    public static final WebSocketCloseStatus SERVICE_RESTART =
            new WebSocketCloseStatus(1012, "Service Restart");

    /**
     * {@code 1013} (IANA Registry, Non RFC 6455) indicates that the service is experiencing overload.
     * A client should only connect to a different IP (when there are multiple for the target)
     * or reconnect to the same IP upon user action.
     */
    public static final WebSocketCloseStatus TRY_AGAIN_LATER =
            new WebSocketCloseStatus(1013, "Try Again Later");

    /**
     * {@code 1014} (IANA Registry, Non RFC 6455) indicates that the server was acting as a gateway or
     * proxy and received an invalid response from the upstream server.
     * This is similar to 502 HTTP Status Code.
     */
    public static final WebSocketCloseStatus BAD_GATEWAY =
            new WebSocketCloseStatus(1014, "Bad Gateway");

    // 1004, 1005, 1006, 1015 are reserved and should never be used by user

    /**
     * {@code 1005} is a reserved value and MUST NOT be set as a status code in a Close control frame
     * by an endpoint. It is designated for use in applications expecting a status code to indicate
     * that no status code was actually present.
     */
    public static final WebSocketCloseStatus EMPTY =
            new WebSocketCloseStatus(1005, "Empty");

    /**
     * {@code 1006} is a reserved value and MUST NOT be set as a status code in a Close control frame
     * by an endpoint. It is designated for use in applications expecting a status code to indicate that the
     * connection was closed abnormally, e.g., without sending or receiving a Close control frame.
     */
    public static final WebSocketCloseStatus ABNORMAL_CLOSURE =
            new WebSocketCloseStatus(1006, "Abnormal closure");

    /**
     * {@code 1015} is a reserved value and MUST NOT be set as a status code in a Close control frame
     * by an endpoint. It is designated for use in applications expecting a status code to indicate that the
     * connection was closed due to a failure to perform a TLS handshake
     * (e.g., the server certificate can't be verified).
     */
    public static final WebSocketCloseStatus TLS_HANDSHAKE_FAILED =
            new WebSocketCloseStatus(1015, "TLS handshake failed");

    /**
     * Creates a new {@link WebSocketCloseStatus} whose status is in the range 4000-4999 for private use.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4.2">rfc6455</a>.
     *
     * <p>Note that {@code reasonPhrase} must be 125 bytes or less.
     */
    public static WebSocketCloseStatus ofPrivateUse(int code, String reasonPhrase) {
        checkArgument(code >= 4000 && code < 5000, "code: %s (expected: 4000 <= code < 5000)", code);
        checkArgument(reasonPhrase.getBytes().length <= 125, "reasonPhrase: %s (expected: <= 125 bytes)",
                      reasonPhrase);
        return new WebSocketCloseStatus(code, requireNonNull(reasonPhrase, "reasonPhrase"));
    }

    /**
     * Tells whether the {@code code} is valid.
     */
    public static boolean isValidStatusCode(int code) {
        return 1000 <= code && code <= 1003 ||
               1007 <= code && code <= 1014 ||
               3000 <= code;
    }

    /**
     * Returns a {@link WebSocketCloseStatus} with the {@code code}. If the {@code code} is not predefined,
     * a new instance is created.
     */
    public static WebSocketCloseStatus valueOf(int code) {
        switch (code) {
            case 1000:
                return NORMAL_CLOSURE;
            case 1001:
                return ENDPOINT_UNAVAILABLE;
            case 1002:
                return PROTOCOL_ERROR;
            case 1003:
                return INVALID_MESSAGE_TYPE;
            case 1005:
                return EMPTY;
            case 1006:
                return ABNORMAL_CLOSURE;
            case 1007:
                return INVALID_PAYLOAD_DATA;
            case 1008:
                return POLICY_VIOLATION;
            case 1009:
                return MESSAGE_TOO_BIG;
            case 1010:
                return MANDATORY_EXTENSION;
            case 1011:
                return INTERNAL_SERVER_ERROR;
            case 1012:
                return SERVICE_RESTART;
            case 1013:
                return TRY_AGAIN_LATER;
            case 1014:
                return BAD_GATEWAY;
            case 1015:
                return TLS_HANDSHAKE_FAILED;
            default:
                if (!isValidStatusCode(code)) {
                    throw new IllegalArgumentException(
                            "WebSocket close status code does NOT comply with RFC 6455: " + code);
                }
                return new WebSocketCloseStatus(code, "Close status #" + code);
        }
    }

    private final int statusCode;
    private final String reasonPhrase;
    @Nullable
    private String text;

    private WebSocketCloseStatus(int statusCode, String reasonPhrase) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    /**
     * Returns the status code.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    public int code() {
        return statusCode;
    }

    /**
     * Returns the text that indicates the reason for closure.
     *
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc6455#section-7.4">Status Codes</a>
     */
    public String reasonPhrase() {
        return reasonPhrase;
    }

    /**
     * Equality of {@link WebSocketCloseStatus} only depends on {@link #code()}.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WebSocketCloseStatus)) {
            return false;
        }
        if (this == o) {
            return true;
        }
        final WebSocketCloseStatus that = (WebSocketCloseStatus) o;

        return statusCode == that.statusCode;
    }

    @Override
    public int hashCode() {
        return statusCode;
    }

    @Override
    public String toString() {
        if (text != null) {
            return text;
        }
        // For example: "1000 Bye", "1009 Message too big"
        text = code() + " " + reasonPhrase();
        return text;
    }
}
